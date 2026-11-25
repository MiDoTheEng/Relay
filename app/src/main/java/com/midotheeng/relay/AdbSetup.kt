package com.midotheeng.relay

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** An adb client (like a computer would be), used to unlock the OTHER device over Wi-Fi. */
class RelayAdb private constructor(ctx: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        // The device we talk to always has Wireless debugging, so Android 11+ (TLS).
        setApi(maxOf(Build.VERSION.SDK_INT, 30))
        val kf = File(ctx.filesDir, "adb.key")
        val cf = File(ctx.filesDir, "adb.crt")
        val loaded: Pair<PrivateKey, Certificate>? = if (kf.exists() && cf.exists()) {
            try {
                val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                val c = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                k to c
            } catch (_: Exception) { null }
        } else null
        if (loaded != null) {
            key = loaded.first
            cert = loaded.second
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val c = makeCert(kp)
            kf.writeBytes(kp.private.encoded)
            cf.writeBytes(c.encoded)
            key = kp.private
            cert = c
        }
    }

    private fun makeCert(kp: KeyPair): Certificate {
        val now = Date()
        val until = Date(now.time + 20L * 365 * 24 * 3600 * 1000)
        val name = X500Name("CN=Relay")
        val b = JcaX509v3CertificateBuilder(name, BigInteger(64, SecureRandom()), now, until, name, kp.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        return JcaX509CertificateConverter().getCertificate(b.build(signer))
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Relay"

    fun shell(cmd: String): String {
        val s = openStream("shell:$cmd")
        return try {
            s.openInputStream().bufferedReader().readText()
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile private var inst: RelayAdb? = null
        fun get(ctx: Context): RelayAdb =
            inst ?: synchronized(this) { inst ?: RelayAdb(ctx.applicationContext).also { inst = it } }
    }
}

/** Finds the port of the other device's Wireless debugging services on the Wi-Fi (mDNS). */
object Nsd {
    fun findPort(ctx: Context, type: String, host: String, timeoutMs: Long): Int {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return -1
        val queue = LinkedBlockingQueue<NsdServiceInfo>()
        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { queue.add(serviceInfo) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl)
        } catch (e: Exception) {
            Log.w("RelaySetup", "nsd", e); return -1
        }
        var found = -1
        val resolved = mutableListOf<Int>()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (found < 0 && System.currentTimeMillis() < deadline) {
                val info = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val done = CountDownLatch(1)
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done.countDown() }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val h = serviceInfo.host?.hostAddress
                        synchronized(resolved) { resolved += serviceInfo.port }
                        if (h == host) found = serviceInfo.port
                        done.countDown()
                    }
                })
                done.await(4, TimeUnit.SECONDS)
            }
        } finally {
            try { nsd.stopServiceDiscovery(dl) } catch (_: Exception) {}
        }
        // Only one device advertising it? Then that's the one, even if its address came back as IPv6.
        val distinct = synchronized(resolved) { resolved.distinct() }
        if (found < 0 && distinct.size == 1) found = distinct[0]
        return found
    }
}

/**
 * One-time Universal Clipboard unlock, done FROM the other device:
 * device A shows "Pair device with pairing code"; the user types the code into Relay on device B;
 * B pairs with A's Wireless debugging and grants A's Relay its permissions, exactly what a computer would do.
 */
object AdbSetup {
    private const val TAG = "RelaySetup"
    const val KEY_CODE = "code"

    /** Per other-device host: "working:<msg>", "done", "error:<msg>". */
    val state = MutableStateFlow<Map<String, String>>(emptyMap())

    private fun set(host: String, v: String) { state.value = state.value + (host to v) }

    fun isDevOptionsOn(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) { false }

    /** Opens Developer options, scrolled to Wireless debugging where supported. */
    fun openWirelessDebugging(ctx: Context): Boolean = try {
        val args = Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") }
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .putExtra(":settings:show_fragment_args", args)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ); true
    } catch (_: Exception) { false }

    private fun notifId(host: String) = 600 + (host.hashCode() and 0xffff)

    /** On this device: "type the code shown on Phone here". */
    fun showRequest(ctx: Context, host: String, name: String, error: String? = null) {
        val remote = RemoteInput.Builder(KEY_CODE).setLabel("Code shown on $name").build()
        val pi = PendingIntent.getBroadcast(
            ctx, notifId(host),
            Intent(ctx, PairReceiver::class.java).putExtra("host", host).putExtra("name", name),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = error ?: "Type the 6-digit code shown on $name."
        val n = NotificationCompat.Builder(ctx, RelayApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Unlock Universal Clipboard on $name")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(NotificationCompat.Action.Builder(0, "Type code", pi).addRemoteInput(remote).build())
            .setContentIntent(PendingIntent.getActivity(ctx, notifId(host), Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(notifId(host), n) } catch (_: Exception) {}
    }

    private fun progress(ctx: Context, host: String, name: String, text: String, ongoing: Boolean) {
        val n = NotificationCompat.Builder(ctx, RelayApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (ongoing) "Unlocking $name…" else "Universal Clipboard")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(0, 0, true) else setTimeoutAfter(60_000) }
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(notifId(host), n) } catch (_: Exception) {}
    }

    @Volatile private var busy = false

    /** [input] is the 6-digit code, optionally followed by "IP:port" if automatic finding fails. */
    fun setupPeer(ctx: Context, host: String, name: String, input: String) {
        val app = ctx.applicationContext
        if (busy) return
        busy = true
        thread(name = "relay-setup") {
            try { run(app, host, name, input) } finally { busy = false }
        }
    }

    private fun run(app: Context, host: String, name: String, input: String) {
        fun step(msg: String) { set(host, "working:$msg"); progress(app, host, name, msg, true) }
        fun fail(msg: String) { set(host, "error:$msg"); showRequest(app, host, name, msg) }

        val manual = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""").find(input)
        val code = (manual?.let { input.replace(it.value, "") } ?: input).filter { it.isDigit() }
        if (code.length != 6) { fail("Type the 6-digit code shown on $name."); return }
        val target = manual?.groupValues?.get(1) ?: host

        step("Finding $name's pairing screen…")
        val pairPort = manual?.groupValues?.get(2)?.toInt()
            ?: Nsd.findPort(app, "_adb-tls-pairing._tcp", target, 12_000)
        if (pairPort <= 0) {
            fail(
                "Couldn't find $name's pairing screen. Keep “Pair device with pairing code” open on $name and try again. " +
                    "Still stuck? Type the code, a space, then the IP address & Port shown under it (e.g. 123456 192.168.1.20:37155)."
            )
            return
        }

        val adb = RelayAdb.get(app)
        step("Pairing with $name…")
        val paired = try {
            adb.pair(target, pairPort, code)
        } catch (e: Exception) {
            Log.w(TAG, "pair", e); false
        }
        if (!paired) { fail("That code didn't work. It changes every time, so check $name and type the new one."); return }

        step("Connecting to $name…")
        val connectPort = Nsd.findPort(app, "_adb-tls-connect._tcp", target, 12_000)
        if (connectPort <= 0) { fail("Paired, but couldn't connect. Make sure Wireless debugging is still on on $name, then type a new code."); return }
        val connected = try {
            if (adb.isConnected) adb.disconnect()
            adb.connect(target, connectPort)
        } catch (e: Exception) {
            Log.w(TAG, "connect", e); false
        }
        if (!connected) { fail("Paired, but $name didn't accept the connection. Type a new code to retry."); return }

        try {
            step("Unlocking $name…")
            val pkg = app.packageName // same app on both devices
            adb.shell("pm grant $pkg android.permission.READ_LOGS")
            adb.shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            adb.shell("cmd deviceidle whitelist +$pkg")
            val check = adb.shell("dumpsys package $pkg | grep 'android.permission.READ_LOGS: granted=true'")
            if (!check.contains("granted=true")) {
                fail("$name refused the permission. Is Relay installed on $name?")
                return
            }
            // Restart Relay on that device so the new permission takes effect.
            adb.shell(
                "setsid sh -c 'sleep 1; am force-stop $pkg; sleep 1; " +
                    "am start-foreground-service -n $pkg/.RelayService; am start -n $pkg/.MainActivity' " +
                    "> /dev/null 2>&1 < /dev/null &"
            )
            set(host, "done")
            progress(app, host, name, "Done ✓ Universal Clipboard is on for $name. You can turn Wireless debugging off there.", false)
        } catch (e: Exception) {
            Log.w(TAG, "grant", e)
            fail("Stopped: ${e.message ?: "unknown error"}. Type a new code to retry.")
        } finally {
            try { adb.disconnect() } catch (_: Exception) {}
        }
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbSetup.KEY_CODE)?.toString() ?: return
        val host = intent.getStringExtra("host") ?: return
        val name = intent.getStringExtra("name") ?: "other device"
        AdbSetup.setupPeer(context, host, name, code)
    }
}
