package com.midotheeng.relay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayService : Service() {

    companion object {
        private const val TAG = "Relay"
        private const val NOTIF_ID = 1
        private const val PROGRESS_ID = 2
        const val ACTION_SEND_TEXT = "com.midotheeng.relay.SEND_TEXT"
        const val ACTION_SEND_FILES = "com.midotheeng.relay.SEND_FILES"
        const val ACTION_HELLO = "com.midotheeng.relay.HELLO"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TEXT_FILE = "textFile"

        val running = MutableStateFlow(false)

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java))
        }

        fun hello(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java).setAction(ACTION_HELLO))
        }

        fun sendText(ctx: Context, text: String) {
            val i = Intent(ctx, RelayService::class.java).setAction(ACTION_SEND_TEXT)
            if (text.length > 100_000) {
                val f = File(ctx.cacheDir, "out-${System.nanoTime()}.txt")
                f.writeText(text)
                i.putExtra(EXTRA_TEXT_FILE, f.path)
            } else {
                i.putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun sendFiles(ctx: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val i = Intent(ctx, RelayService::class.java).setAction(ACTION_SEND_FILES)
            val clip = ClipData.newRawUri("files", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private lateinit var prefs: Prefs
    private val io = Executors.newCachedThreadPool()
    private val sender = Executors.newSingleThreadExecutor()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var alive = true
    @Volatile private var server: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        startFg()
        running.value = true
        try {
            mcLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("relay").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock", e)
        }
        io.execute { tcpLoop() }
        io.execute { udpLoop() }
        registerNetwork()
        sched.scheduleWithFixedDelay({ hello() }, 1, 120, TimeUnit.SECONDS)
        scope.launch { Peers.state.collect { updateNotif() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            ACTION_SEND_TEXT -> {
                val path = intent.getStringExtra(EXTRA_TEXT_FILE)
                val text = if (path != null) {
                    File(path).let { f -> f.readText().also { f.delete() } }
                } else intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) sender.execute { sendText(text) }
            }
            ACTION_SEND_FILES -> {
                val uris = mutableListOf<Uri>()
                intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(uris::add) }
                if (uris.isNotEmpty()) sender.execute { sendFiles(uris) }
            }
            ACTION_HELLO -> hello()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        running.value = false
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { mcLock?.release() } catch (_: Exception) {}
        try {
            netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        scope.cancel()
        sched.shutdownNow(); sender.shutdownNow(); io.shutdownNow()
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun buildNotif(): Notification {
        val peers = Peers.all()
        val text = when {
            !prefs.isSetUp -> "Open Relay to finish setup"
            peers.isEmpty() -> "Looking for your other device…"
            else -> "Linked with " + peers.joinToString { it.name }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val sendClip = PendingIntent.getActivity(
            this, 1,
            Intent(this, ClipActivity::class.java).setAction(ClipActivity.ACTION_SEND_CLIPBOARD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RelayApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Relay is on")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Send clipboard", sendClip)
            .build()
    }

    private fun startFg() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotif(), type)
    }

    private fun updateNotif() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif()) } catch (_: Exception) {}
    }

    private fun toast(msg: String) = main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

    // ---------- receiving ----------

    private fun tcpLoop() {
        while (alive) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(Wire.PORT))
                    server = ss
                    while (alive) {
                        val s = ss.accept()
                        io.execute { handleConnection(s) }
                    }
                }
            } catch (e: Exception) {
                if (!alive) return
                Log.w(TAG, "server", e)
                Thread.sleep(3000)
            }
        }
    }

    private fun handleConnection(s: Socket) {
        s.use {
            val code = prefs.pairCode
            if (code.isEmpty()) return
            val inc = try {
                Wire.receive(s, code, cacheDir)
            } catch (e: Exception) {
                Log.w(TAG, "receive", e); null
            } ?: return
            val h = inc.header
            val from = h.optString("from", "Other device")
            val fromId = h.optString("id")
            if (fromId.isNotEmpty() && fromId != prefs.deviceId) {
                Peers.seen(fromId, from, inc.from.hostAddress ?: "")
            }
            try {
                when (h.optString("type")) {
                    "text" -> {
                        val text = inc.file.readText()
                        inc.file.delete()
                        onText(text, from)
                    }
                    "file" -> onFile(
                        inc.file, h.optString("name", "file"),
                        h.optString("mime", "application/octet-stream"), from
                    )
                    else -> inc.file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "handle", e)
                inc.file.delete()
            }
        }
    }

    private fun onText(text: String, from: String) {
        val item = Item(History.newId(), outgoing = false, kind = "text", text = text.take(100_000), peer = from)
        History.add(item)
        if (prefs.autoCopy) main.post { Util.copyText(this, text) }
        notifyIncoming(item, null)
    }

    private fun onFile(tmp: File, name: String, mime: String, from: String) {
        val thumb = if (mime.startsWith("image/")) Util.thumbnail(tmp) else null
        val uri = Util.saveIncoming(this, tmp, name, mime)
        val item = Item(History.newId(), outgoing = false, kind = "file", name = name, uri = uri.toString(), mime = mime, peer = from)
        History.add(item)
        if (prefs.autoCopy && mime.startsWith("image/")) main.post { Util.copyUri(this, uri, name) }
        notifyIncoming(item, thumb)
    }

    private fun notifyIncoming(item: Item, thumb: Bitmap?) {
        val nid = (item.id % 1_000_000).toInt() + 100
        val b = NotificationCompat.Builder(this, RelayApp.CH_IN)
            .setSmallIcon(R.drawable.ic_stat)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000)
        val copyPi = PendingIntent.getBroadcast(
            this, nid,
            Intent(this, CopyReceiver::class.java).putExtra("id", item.id).putExtra("nid", nid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (item.kind == "text") {
            val t = item.text.orEmpty()
            b.setContentTitle(if (prefs.autoCopy) "Copied from ${item.peer}" else "Text from ${item.peer}")
                .setContentText(t.take(200))
                .setStyle(NotificationCompat.BigTextStyle().bigText(t.take(1500)))
                .setContentIntent(PendingIntent.getActivity(this, nid, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, "Copy", copyPi)
            if (Util.looksLikeUrl(t)) {
                val open = Intent(Intent.ACTION_VIEW, Uri.parse(t.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                b.addAction(0, "Open link", PendingIntent.getActivity(this, nid + 1, open, PendingIntent.FLAG_IMMUTABLE))
            }
        } else {
            val uri = Uri.parse(item.uri)
            val view = PendingIntent.getActivity(this, nid, Util.viewIntent(uri, item.mime), PendingIntent.FLAG_IMMUTABLE)
            val share = Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentTitle("${item.name} from ${item.peer}")
                .setContentText(if (thumb != null && prefs.autoCopy) "Saved and copied — tap to open" else "Saved — tap to open")
                .setContentIntent(view)
                .addAction(0, "Share", PendingIntent.getActivity(this, nid + 1, share, PendingIntent.FLAG_IMMUTABLE))
            if (thumb != null) {
                b.setLargeIcon(thumb).setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb).bigLargeIcon(null as Bitmap?))
                b.addAction(0, "Copy", copyPi)
            }
        }
        try { getSystemService(NotificationManager::class.java).notify(nid, b.build()) } catch (_: Exception) {}
    }

    // ---------- discovery (UDP broadcast on the local Wi-Fi) ----------

    private fun udpLoop() {
        while (alive) {
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.broadcast = true
                    sock.bind(InetSocketAddress(Wire.UDP_PORT))
                    udp = sock
                    hello()
                    val buf = ByteArray(2048)
                    while (alive) {
                        val pk = DatagramPacket(buf, buf.size)
                        sock.receive(pk)
                        handleUdp(pk)
                    }
                }
            } catch (e: Exception) {
                udp = null
                if (!alive) return
                Log.w(TAG, "udp", e)
                Thread.sleep(3000)
            }
        }
    }

    private fun handleUdp(pk: DatagramPacket) {
        val s = String(pk.data, 0, pk.length, Charsets.UTF_8)
        if (!s.startsWith("RLY1")) return
        val j = try { JSONObject(s.substring(4)) } catch (_: Exception) { return }
        val code = prefs.pairCode
        val id = j.optString("id")
        val name = j.optString("name")
        if (code.isEmpty() || id.isEmpty() || id == prefs.deviceId) return
        if (j.optString("tag") != Crypto.tag(code, "hello|$id|$name")) return
        val host = pk.address.hostAddress ?: return
        Peers.seen(id, name, host)
        if (j.optString("t") == "hello") sendUdp(helloPacket("ack") ?: return, pk.address)
    }

    private fun helloPacket(t: String): ByteArray? {
        val code = prefs.pairCode
        if (code.isEmpty()) return null
        val id = prefs.deviceId
        val name = prefs.deviceName
        val j = JSONObject().put("t", t).put("id", id).put("name", name).put("tag", Crypto.tag(code, "hello|$id|$name"))
        return ("RLY1$j").toByteArray()
    }

    private fun sendUdp(data: ByteArray, addr: InetAddress) {
        try {
            val s = udp
            if (s != null) {
                s.send(DatagramPacket(data, data.size, addr, Wire.UDP_PORT))
            } else {
                DatagramSocket().use { t ->
                    t.broadcast = true
                    t.send(DatagramPacket(data, data.size, addr, Wire.UDP_PORT))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "udp send to $addr: ${e.message}")
        }
    }

    /** Shout "I'm here" on the network, and knock directly on devices we already know. */
    private fun hello() {
        if (!alive) return
        try {
            io.execute {
                val data = helloPacket("hello") ?: return@execute
                Util.broadcastAddresses().forEach { sendUdp(data, it) }
                val direct = Peers.all().map { it.host } + listOfNotNull(prefs.manualPeer.ifEmpty { null })
                direct.distinct().forEach { h -> try { sendUdp(data, InetAddress.getByName(h)) } catch (_: Exception) {} }
            }
        } catch (_: Exception) {
        }
    }

    private fun registerNetwork() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try { sched.schedule(Runnable { hello() }, 2, TimeUnit.SECONDS) } catch (_: Exception) {}
                }
            }
            cm.registerNetworkCallback(req, cb)
            netCb = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback", e)
        }
    }

    // ---------- sending ----------

    private data class Target(val id: String?, val host: String, val name: String)

    private fun targets(): List<Target> {
        val list = Peers.all().map { Target(it.id, it.host, it.name) }.toMutableList()
        val m = prefs.manualPeer
        if (m.isNotEmpty() && list.none { it.host == m }) list += Target(null, m, m)
        return list.distinctBy { it.host }
    }

    private var lastError: Exception? = null

    private fun tryOnce(host: String, action: (String) -> Unit): Boolean = try {
        action(host); true
    } catch (e: Exception) {
        Log.w(TAG, "send to $host", e); lastError = e; false
    }

    /** Sends to every known device. Returns the names that got it. */
    private fun deliver(action: (String) -> Unit): List<String> {
        if (!prefs.isSetUp) return emptyList()
        lastError = null
        var list = targets()
        if (list.isEmpty()) {
            hello()
            val until = System.currentTimeMillis() + 4000
            while (Peers.state.value.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(200)
            list = targets()
        }
        val reached = mutableListOf<String>()
        for (t in list) {
            if (tryOnce(t.host, action)) { reached += t.name; continue }
            if (lastError is PairMismatch) continue
            // The other device may have a new IP: ask again, then retry once.
            hello()
            Thread.sleep(2500)
            val host = t.id?.let { Peers.state.value[it]?.host } ?: t.host
            if (tryOnce(host, action)) reached += t.name
        }
        return reached
    }

    private fun header(type: String) =
        JSONObject().put("type", type).put("from", prefs.deviceName).put("id", prefs.deviceId)

    private fun failMessage(): String = when (lastError) {
        is PairMismatch -> "The other device has a different pair code"
        else -> if (targets().isEmpty()) "No device found — open Relay on the other device (same Wi-Fi)"
        else "Couldn't reach your other device — is it on the same Wi-Fi?"
    }

    private fun sendText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reached = deliver { host ->
            Wire.send(host, header("text"), ByteArrayInputStream(bytes), bytes.size.toLong(), prefs.pairCode)
        }
        if (reached.isNotEmpty()) {
            History.add(Item(History.newId(), outgoing = true, kind = "text", text = text.take(100_000), peer = reached.joinToString()))
            toast("Sent to ${reached.joinToString()} ✓")
        } else toast(failMessage())
    }

    private fun sendFiles(uris: List<Uri>) {
        val nm = getSystemService(NotificationManager::class.java)
        var ok = 0
        var names = emptyList<String>()
        uris.forEachIndexed { idx, uri ->
            try {
                val meta = Util.meta(this, uri)
                val progress = NotificationCompat.Builder(this, RelayApp.CH_SERVICE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(if (uris.size > 1) "Sending ${idx + 1} of ${uris.size}" else "Sending")
                    .setContentText(meta.name)
                    .setProgress(0, 0, true)
                    .setOngoing(true)
                    .build()
                try { nm.notify(PROGRESS_ID, progress) } catch (_: Exception) {}

                // If the size is unknown, copy it locally first so we know how much to send.
                val cached: File? = if (meta.size == null) {
                    File.createTempFile("out", ".bin", cacheDir).also { f ->
                        contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                    }
                } else null
                val size = cached?.length() ?: meta.size!!
                val reached = deliver { host ->
                    val input = cached?.inputStream() ?: contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Can't open ${meta.name}")
                    input.use {
                        Wire.send(host, header("file").put("name", meta.name).put("mime", meta.mime), it, size, prefs.pairCode)
                    }
                }
                cached?.delete()
                if (reached.isNotEmpty()) {
                    ok++; names = reached
                    History.add(Item(History.newId(), outgoing = true, kind = "file", name = meta.name, uri = uri.toString(), mime = meta.mime, peer = reached.joinToString()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "file", e); lastError = e
            }
        }
        try { nm.cancel(PROGRESS_ID) } catch (_: Exception) {}
        when {
            ok == uris.size -> toast(if (ok == 1) "Sent to ${names.joinToString()} ✓" else "Sent $ok files to ${names.joinToString()} ✓")
            ok > 0 -> toast("Sent $ok of ${uris.size} files")
            else -> toast(failMessage())
        }
    }
}
