package com.midotheeng.relay

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
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
import kotlin.concurrent.thread

/** Talks to this device's own "Wireless debugging" (adb), like Shizuku does. */
class RelayAdb private constructor(ctx: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
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

/**
 * One-time, on-device setup for Universal Clipboard — no computer needed.
 * The user turns on Wireless debugging and types the pairing code into Relay's notification;
 * Relay then grants itself READ_LOGS + "display over other apps" + no battery limits, and restarts.
 */
object AdbSetup {
    private const val TAG = "RelaySetup"
    private const val NOTIF = 60
    const val KEY_CODE = "code"

    /** idle, waiting, pairing, connecting, granting, done, or "error:<message>" */
    val state = MutableStateFlow("idle")

    private var mdns: AdbMdns? = null
    @Volatile private var pairPort = -1
    @Volatile private var busy = false

    fun begin(ctx: Context) {
        val app = ctx.applicationContext
        if (busy) return
        state.value = "waiting"
        thread(name = "relay-setup") {
            // Already paired before and Wireless debugging is on? Then just connect.
            if (connectAndGrant(app, quick = true)) return@thread
            startPairingDiscovery(app)
            showCodeNotification(app, null)
        }
    }

    fun cancel(ctx: Context) {
        try { mdns?.stop() } catch (_: Exception) {}
        mdns = null
        state.value = "idle"
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
    }

    private fun startPairingDiscovery(app: Context) {
        try { mdns?.stop() } catch (_: Exception) {}
        pairPort = -1
        mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port -> if (port > 0) pairPort = port }
            .also { it.start() }
    }

    fun onCode(ctx: Context, code: String) {
        val app = ctx.applicationContext
        if (busy) return
        busy = true
        thread(name = "relay-pair") {
            try {
                state.value = "pairing"
                showProgress(app, "Pairing…")
                if (mdns == null) startPairingDiscovery(app)
                val until = System.currentTimeMillis() + 15_000
                while (pairPort <= 0 && System.currentTimeMillis() < until) Thread.sleep(300)
                if (pairPort <= 0) {
                    fail(app, "Couldn't see the pairing screen. Keep “Pair device with pairing code” open, then enter the code.")
                    return@thread
                }
                val ok = try {
                    RelayAdb.get(app).pair("127.0.0.1", pairPort, code.filter { it.isDigit() })
                } catch (e: Exception) {
                    Log.w(TAG, "pair", e); false
                }
                if (!ok) {
                    fail(app, "That code didn't work. Codes change each time — check the screen and try again.")
                    return@thread
                }
                try { mdns?.stop() } catch (_: Exception) {}
                mdns = null
                connectAndGrant(app, quick = false)
            } finally {
                busy = false
            }
        }
    }

    private fun connectAndGrant(app: Context, quick: Boolean): Boolean {
        val adb = RelayAdb.get(app)
        if (!quick) { state.value = "connecting"; showProgress(app, "Connecting…") }
        val connected = try {
            adb.isConnected || adb.connectTls(app, if (quick) 4_000 else 20_000)
        } catch (e: Exception) {
            Log.w(TAG, "connect", e); false
        }
        if (!connected) {
            if (!quick) fail(app, "Paired, but couldn't connect. Make sure Wireless debugging is still on, then tap Start again.")
            return false
        }
        return try {
            state.value = "granting"
            showProgress(app, "Setting up…")
            val pkg = app.packageName
            adb.shell("pm grant $pkg android.permission.READ_LOGS")
            adb.shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            adb.shell("cmd deviceidle whitelist +$pkg")
            val check = adb.shell("dumpsys package $pkg | grep 'android.permission.READ_LOGS: granted=true'")
            if (!check.contains("granted=true")) {
                fail(app, "Android didn't accept the permission. Please tell Claude this message.")
                return false
            }
            state.value = "done"
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.notify(
                NOTIF,
                NotificationCompat.Builder(app, RelayApp.CH_MIRROR)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Universal Clipboard is ready ✓")
                    .setContentText("Relay is restarting. You can turn Wireless debugging off now.")
                    .setTimeoutAfter(60_000)
                    .build()
            )
            // A new permission needs a fresh process: restart Relay from the adb shell, which outlives us.
            adb.shell(
                "setsid sh -c 'sleep 1; am force-stop $pkg; sleep 1; " +
                    "am start-foreground-service -n $pkg/.RelayService; am start -n $pkg/.MainActivity' " +
                    "> /dev/null 2>&1 < /dev/null &"
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "grant", e)
            fail(app, "Setup stopped: ${e.message ?: "unknown error"}")
            false
        }
    }

    private fun fail(app: Context, msg: String) {
        state.value = "error:$msg"
        showCodeNotification(app, msg)
    }

    private fun showCodeNotification(app: Context, error: String?) {
        state.value = if (error != null) "error:$error" else "waiting"
        val remote = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val pi = PendingIntent.getBroadcast(
            app, NOTIF, Intent(app, PairReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = NotificationCompat.Action.Builder(0, "Enter pairing code", pi)
            .addRemoteInput(remote).build()
        val text = error ?: "In Wireless debugging, tap “Pair device with pairing code”. Then tap Enter pairing code here and type the 6 digits."
        val n = NotificationCompat.Builder(app, RelayApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Relay setup")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(error == null)
            .addAction(action)
            .build()
        try { app.getSystemService(NotificationManager::class.java).notify(NOTIF, n) } catch (_: Exception) {}
    }

    private fun showProgress(app: Context, text: String) {
        val n = NotificationCompat.Builder(app, RelayApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Relay setup")
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        try { app.getSystemService(NotificationManager::class.java).notify(NOTIF, n) } catch (_: Exception) {}
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AdbSetup.KEY_CODE)?.toString() ?: return
        AdbSetup.onCode(context, code)
    }
}
