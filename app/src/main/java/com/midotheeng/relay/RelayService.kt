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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
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
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayService : Service() {

    companion object {
        private const val TAG = "Relay"
        private const val NOTIF_ID = 1
        private const val PROGRESS_ID = 2
        private const val RING_ID = 3
        const val SHARE_CATEGORY = "com.midotheeng.relay.SHARE_TARGET"
        private const val ACTION_SEND_TEXT = "com.midotheeng.relay.SEND_TEXT"
        private const val ACTION_SEND_FILES = "com.midotheeng.relay.SEND_FILES"
        private const val ACTION_HELLO = "com.midotheeng.relay.HELLO"
        private const val ACTION_RING = "com.midotheeng.relay.RING"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TEXT_FILE = "textFile"
        private const val EXTRA_QUIET = "quiet"
        private const val EXTRA_PEER = "peer"

        val running = MutableStateFlow(false)
        val internetUp = MutableStateFlow(false)

        @Volatile var instance: RelayService? = null
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start", e)
            }
        }

        fun hello(ctx: Context) {
            instance?.let { it.sayHello(cloud = true); return }
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java).setAction(ACTION_HELLO))
            } catch (_: Exception) {}
        }

        fun ring(ctx: Context, peerId: String) {
            instance?.let { s -> s.sender.execute { s.doRing(peerId) }; return }
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, RelayService::class.java).setAction(ACTION_RING).putExtra(EXTRA_PEER, peerId)
                )
            } catch (_: Exception) {}
        }

        fun sendText(ctx: Context, text: String, quiet: Boolean = false) {
            instance?.let { s -> s.sender.execute { s.doSendText(text, quiet) }; return }
            val i = Intent(ctx, RelayService::class.java).setAction(ACTION_SEND_TEXT).putExtra(EXTRA_QUIET, quiet)
            if (text.length > 100_000) {
                val f = File(ctx.cacheDir, "out-${System.nanoTime()}.txt")
                f.writeText(text)
                i.putExtra(EXTRA_TEXT_FILE, f.path)
            } else {
                i.putExtra(EXTRA_TEXT, text)
            }
            try { ContextCompat.startForegroundService(ctx, i) } catch (e: Exception) { Log.w(TAG, "sendText", e) }
        }

        fun sendFiles(ctx: Context, uris: List<Uri>, quiet: Boolean = false) {
            if (uris.isEmpty()) return
            // From the service itself (clipboard), access is already ours; from screens, pass a grant along.
            if (ctx is RelayService) { ctx.sender.execute { ctx.doSendFiles(uris, quiet) }; return }
            val i = Intent(ctx, RelayService::class.java).setAction(ACTION_SEND_FILES).putExtra(EXTRA_QUIET, quiet)
            val clip = ClipData.newRawUri("files", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { ContextCompat.startForegroundService(ctx, i) } catch (e: Exception) { Log.w(TAG, "sendFiles", e) }
        }

        /** Builds the text inside this device's QR code. */
        fun qrText(ctx: Context): String {
            val p = Prefs(ctx)
            val key = Base64.encodeToString(p.spaceKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val name = Uri.encode(p.deviceName)
            return "RELAY2|$key|${p.deviceId}|$name|${Util.localIps().joinToString(",")}"
        }

        /** Joins the devices of the QR code that was scanned. Returns the other device's name, or null if not a Relay code. */
        fun join(ctx: Context, qr: String): String? {
            val parts = qr.trim().split("|")
            if (parts.size < 4 || parts[0] != "RELAY2") return null
            val key = try {
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            } catch (_: Exception) { return null }
            if (key.size != 32) return null
            val id = parts[2]
            val name = Uri.decode(parts[3])
            val ips = parts.getOrNull(4)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val p = Prefs(ctx)
            if (id == p.deviceId) return null
            if (!key.contentEquals(p.spaceKey)) {
                p.spaceKey = key
                p.cloudSince = ""
                Peers.clear()
            }
            Peers.seen(id, name, ips = ips)
            instance?.onSpaceChanged() ?: start(ctx)
            return name
        }

        /** Leaves all linked devices: new secret, nobody else can reach this device any more. */
        fun unlinkAll(ctx: Context) {
            val p = Prefs(ctx)
            val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            instance?.let { s -> s.sender.execute { s.broadcastBye() } }
            Handler(Looper.getMainLooper()).postDelayed({
                p.spaceKey = k
                p.cloudSince = ""
                Peers.clear()
                instance?.onSpaceChanged()
            }, 1500)
        }
    }

    private lateinit var prefs: Prefs
    private val io = Executors.newCachedThreadPool()
    internal val sender = Executors.newSingleThreadExecutor()
    private val sched = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var alive = true
    @Volatile private var server: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    private var mcLock: WifiManager.MulticastLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private lateinit var cloud: Cloud

    @Volatile private var keysCache: Pair<String, Keys>? = null
    fun keys(): Keys {
        val k = prefs.spaceKey
        val s = Base64.encodeToString(k, Base64.NO_WRAP)
        keysCache?.let { if (it.first == s) return it.second }
        return Keys(k).also { keysCache = s to it }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        instance = this
        startFg()
        running.value = true
        try {
            mcLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("relay").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock", e)
        }
        cloud = Cloud(
            keys = { keys() },
            myId = { prefs.deviceId },
            enabled = { prefs.useInternet },
            since = { prefs.cloudSince },
            saveSince = { prefs.cloudSince = it },
            onPacket = { h, b, age ->
                io.execute {
                    // Never let one bad message take the whole app down.
                    try { onCloudPacket(h, b, age) } catch (e: Exception) { Log.w(TAG, "cloud packet", e) }
                }
            },
        )
        cloud.start()
        io.execute { tcpLoop() }
        io.execute { udpLoop() }
        registerNetwork()
        // Quick local "I'm here" every minute; over the internet only now and then (it has limits).
        sched.scheduleWithFixedDelay({ sayHello(cloud = false) }, 1, 60, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ sayHello(cloud = true) }, 3, 30 * 60, TimeUnit.SECONDS)
        sched.scheduleWithFixedDelay({ internetUp.value = cloud.connected }, 2, 5, TimeUnit.SECONDS)
        var lastNames = ""
        scope.launch {
            Peers.state.collect { map ->
                updateNotif()
                val names = map.values.joinToString { it.id + it.name }
                if (names != lastNames) { lastNames = names; updateShareTargets() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        val quiet = intent?.getBooleanExtra(EXTRA_QUIET, false) ?: false
        when (intent?.action) {
            ACTION_SEND_TEXT -> {
                val path = intent.getStringExtra(EXTRA_TEXT_FILE)
                val text = if (path != null) {
                    File(path).let { f -> f.readText().also { f.delete() } }
                } else intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) sender.execute { doSendText(text, quiet) }
            }
            ACTION_SEND_FILES -> {
                val uris = mutableListOf<Uri>()
                intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(uris::add) }
                if (uris.isNotEmpty()) sender.execute { doSendFiles(uris, quiet) }
            }
            ACTION_HELLO -> sayHello(cloud = true)
            ACTION_RING -> intent.getStringExtra(EXTRA_PEER)?.let { id -> sender.execute { doRing(id) } }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        instance = null
        running.value = false
        cloud.stop()
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { mcLock?.release() } catch (_: Exception) {}
        try {
            netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        stopRing()
        scope.cancel()
        sched.shutdownNow(); sender.shutdownNow(); io.shutdownNow()
        super.onDestroy()
    }

    fun onSpaceChanged() {
        keysCache = null
        cloud.restart()
        updateNotif()
        // Introduce ourselves to the devices we just joined, directly and over the internet.
        sender.execute {
            Thread.sleep(500)
            val h = header("hello").put("ips", JSONArray(Util.localIps())).put("reply", true)
            deliver(h, ByteArray(0))
        }
        sayHello(cloud = false)
    }

    // ---------- notification ----------

    private fun peerLine(p: Peer): String =
        if (p.battery >= 0) "${p.name} ${if (p.charging) "⚡" else "🔋"}${p.battery}%" else p.name

    private fun buildNotif(): Notification {
        val peers = Peers.all()
        val text = if (peers.isEmpty()) "No linked devices yet. Tap to add one."
        else peers.joinToString(" · ") { peerLine(it) }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val sendClip = PendingIntent.getActivity(
            this, 1,
            Intent(this, ClipActivity::class.java).setAction(ClipActivity.ACTION_SEND_CLIPBOARD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RelayApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Relay")
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
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotif(), type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground", e)
        }
    }

    private fun updateNotif() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif()) } catch (_: Exception) {}
    }

    private fun toast(msg: String) = main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

    /** Linked devices appear as their own icons at the top of the share menu (like AirDrop). */
    private fun updateShareTargets() {
        try {
            val list = Peers.all().take(4).map { p ->
                ShortcutInfoCompat.Builder(this, "peer_${p.id}")
                    .setShortLabel(p.name)
                    .setLongLabel("Send to ${p.name}")
                    .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                    .setCategories(setOf(SHARE_CATEGORY))
                    .setLongLived(true)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(this, list)
        } catch (e: Exception) {
            Log.w(TAG, "shortcuts", e)
        }
    }

    // ---------- Wi-Fi: listening ----------

    private fun tcpLoop() {
        while (alive) {
            try {
                ServerSocket().use { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(Wire.PORT))
                    server = ss
                    AutoLog.add("Ready for Wi-Fi connections on ${Util.localIps().joinToString().ifEmpty { "(no Wi-Fi)" }}")
                    while (alive) {
                        val s = ss.accept()
                        io.execute {
                            try { handleConnection(s) } catch (e: Exception) { Log.w(TAG, "connection", e) }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!alive) return
                AutoLog.add("Can't accept Wi-Fi connections: ${e.message}")
                Log.w(TAG, "server", e)
                Thread.sleep(3000)
            }
        }
    }

    private fun handleConnection(s: Socket) {
        s.use {
            val inc = try {
                Wire.receive(s, keys(), cacheDir)
            } catch (e: Exception) {
                Log.w(TAG, "receive", e); null
            } ?: return
            dispatch(inc.header, inc.file, inc.from.hostAddress, 0)
        }
    }

    private fun onCloudPacket(h: JSONObject, body: ByteArray, ageSec: Long) {
        val tmp = File.createTempFile("relay_cloud", ".part", cacheDir)
        tmp.writeBytes(body)
        dispatch(h, tmp, null, ageSec)
    }

    private val lastReply = HashMap<String, Long>()

    /** Everything that arrives (over Wi-Fi or the internet) ends up here. */
    private fun dispatch(h: JSONObject, file: File, lanHost: String?, ageSec: Long) {
        val fromId = h.optString("id")
        val from = h.optString("from", "Other device")
        if (fromId.isEmpty() || fromId == prefs.deviceId) { file.delete(); return }
        val ips = h.optJSONArray("ips")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val known = Peers.state.value.containsKey(fromId)
        Peers.seen(fromId, from, lanHost, ips, h.optInt("bat", -1), h.optBoolean("chg"))
        if (!Sync.firstTime(h.optString("msg"))) { file.delete(); return }
        try {
            when (h.optString("type")) {
                "hello" -> {
                    file.delete()
                    // A new device (or one asking for an answer): introduce ourselves back.
                    val now = System.currentTimeMillis()
                    val should = synchronized(lastReply) {
                        val due = (!known || h.optBoolean("reply")) && now - (lastReply[fromId] ?: 0) > 60_000
                        if (due) lastReply[fromId] = now
                        due
                    }
                    if (should) sender.execute {
                        deliver(header("hello").put("ips", JSONArray(Util.localIps())), ByteArray(0), only = fromId)
                    }
                }
                "bye" -> { file.delete(); Peers.remove(fromId) }
                "text" -> {
                    val text = file.readText()
                    file.delete()
                    onText(text, from, fresh = ageSec < 180)
                }
                "file" -> onFile(file, h.optString("name", "file"), h.optString("mime", "application/octet-stream"), from, ageSec < 180)
                "notif" -> {
                    val text = file.readText()
                    file.delete()
                    if (ageSec < 600) showMirrored(h, text, from)
                }
                "notif_rm" -> {
                    file.delete()
                    try { getSystemService(NotificationManager::class.java).cancel(mirrorId(h.optString("key"))) } catch (_: Exception) {}
                }
                "ring" -> { file.delete(); if (ageSec < 120) startRing(from) }
                else -> file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle", e)
            file.delete()
        }
    }

    private fun onText(text: String, from: String, fresh: Boolean) {
        val item = Item(History.newId(), outgoing = false, kind = "text", text = text.take(100_000), peer = from)
        History.add(item)
        if (!fresh) { AutoLog.add("Received late from $from (kept in history only)"); return }
        val preview = text.take(25).replace('\n', ' ')
        if (prefs.autoCopy) main.post {
            val ok = Util.copyText(this, text)
            AutoLog.add("Received from $from: “$preview” → " + if (ok) "on clipboard ✓" else "Android refused the clipboard ✗")
        } else AutoLog.add("Received from $from: “$preview” (copy to clipboard is off)")
        if (prefs.openLinks && Util.looksLikeUrl(text)) {
            main.post {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }
        notifyIncoming(item, null)
    }

    private fun onFile(tmp: File, name: String, mime: String, from: String, fresh: Boolean) {
        val thumb = if (mime.startsWith("image/")) Util.thumbnail(tmp) else null
        val uri = Util.saveIncoming(this, tmp, name, mime)
        val item = Item(History.newId(), outgoing = false, kind = "file", name = name, uri = uri.toString(), mime = mime, peer = from)
        History.add(item)
        if (fresh && prefs.autoCopy && mime.startsWith("image/")) main.post { Util.copyUri(this, uri, name) }
        notifyIncoming(item, thumb)
    }

    private fun notifyIncoming(item: Item, thumb: Bitmap?) {
        val nid = (item.id % 1_000_000).toInt() + 100
        val b = NotificationCompat.Builder(this, RelayApp.CH_IN)
            .setSmallIcon(R.drawable.ic_stat)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000)
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
                .setSilent(true)
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
                .setContentText(if (thumb != null && prefs.autoCopy) "Saved and copied. Tap to open." else "Saved. Tap to open.")
                .setContentIntent(view)
                .setTimeoutAfter(30 * 60_000)
                .addAction(0, "Share", PendingIntent.getActivity(this, nid + 1, share, PendingIntent.FLAG_IMMUTABLE))
            if (thumb != null) {
                b.setLargeIcon(thumb).setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb).bigLargeIcon(null as Bitmap?))
                b.addAction(0, "Copy", copyPi)
            }
        }
        try { getSystemService(NotificationManager::class.java).notify(nid, b.build()) } catch (_: Exception) {}
    }

    // ---------- Wi-Fi: discovery ----------

    private fun udpLoop() {
        while (alive) {
            try {
                DatagramSocket(null).use { sock ->
                    sock.reuseAddress = true
                    sock.broadcast = true
                    sock.bind(InetSocketAddress(Wire.UDP_PORT))
                    udp = sock
                    sayHello(cloud = false)
                    val buf = ByteArray(4096)
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
        if (!s.startsWith("RLY2")) return
        val plain = try { keys().open(Base64.decode(s.substring(4), Base64.NO_WRAP)) } catch (_: Exception) { null } ?: return
        val j = try { JSONObject(String(plain)) } catch (_: Exception) { return }
        val id = j.optString("id")
        if (id.isEmpty() || id == prefs.deviceId) return
        val host = pk.address.hostAddress ?: return
        Peers.seen(id, j.optString("name"), host, emptyList(), j.optInt("bat", -1), j.optBoolean("chg"))
        if (j.optString("t") == "hello") helloPacket("ack")?.let { sendUdp(it, pk.address) }
    }

    private fun helloPacket(t: String): ByteArray? = try {
        val (bat, chg) = Util.battery(this)
        val j = JSONObject().put("t", t).put("id", prefs.deviceId).put("name", prefs.deviceName)
            .put("bat", bat).put("chg", chg)
        ("RLY2" + Base64.encodeToString(keys().seal(j.toString().toByteArray()), Base64.NO_WRAP)).toByteArray()
    } catch (_: Exception) { null }

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

    /** "I'm here": shouted on the Wi-Fi, knocked directly on known devices, and (if [cloud]) sent over the internet. */
    fun sayHello(cloud: Boolean) {
        if (!alive) return
        try {
            io.execute {
                helloPacket("hello")?.let { data ->
                    Util.broadcastAddresses().forEach { sendUdp(data, it) }
                    Peers.all().flatMap { it.hosts }.distinct().forEach { h ->
                        try { sendUdp(data, InetAddress.getByName(h)) } catch (_: Exception) {}
                    }
                }
                if (cloud && prefs.useInternet && Peers.all().isNotEmpty()) {
                    try {
                        this.cloud.publish(header("hello").put("ips", JSONArray(Util.localIps())), ByteArray(0))
                    } catch (e: Exception) {
                        Log.d(TAG, "cloud hello: ${e.message}")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun registerNetwork() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cloud.restart()
                    try { sched.schedule(Runnable { sayHello(cloud = true) }, 3, TimeUnit.SECONDS) } catch (_: Exception) {}
                }
            }
            cm.registerNetworkCallback(req, cb)
            netCb = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback", e)
        }
    }

    // ---------- sending ----------

    private fun header(type: String): JSONObject {
        val (bat, chg) = Util.battery(this)
        return JSONObject().put("type", type).put("from", prefs.deviceName).put("id", prefs.deviceId)
            .put("bat", bat).put("chg", chg).put("msg", UUID.randomUUID().toString())
    }

    @Volatile private var lastError: String? = null
    @Volatile private var lastVia: String = ""

    /**
     * Sends to every linked device (or only [only]): straight over Wi-Fi when possible,
     * otherwise over the internet. Returns the names of the devices it reached.
     */
    private fun deliver(header: JSONObject, body: ByteArray, only: String? = null): List<String> =
        deliverStream(header, body.size.toLong(), only) { ByteArrayInputStream(body) }

    private fun deliverStream(header: JSONObject, size: Long, only: String? = null, open: () -> java.io.InputStream): List<String> {
        lastError = null
        lastVia = ""
        val targets = Peers.all().filter { only == null || it.id == only }
        if (targets.isEmpty()) { lastError = "No linked devices yet. Open Relay and tap “Add a device”."; return emptyList() }

        // Small things (text, notifications) go over the internet AND Wi-Fi at the same time:
        // whichever arrives first wins, the other is ignored. No waiting for Wi-Fi to time out.
        val raceCloud = prefs.useInternet && size <= 64 * 1024
        val cloudJob: java.util.concurrent.Future<Boolean>? = if (raceCloud) {
            val bytes = open().use { it.readBytes() }
            io.submit<Boolean> {
                try {
                    cloud.publish(JSONObject(header.toString()).put("size", size), bytes); true
                } catch (e: Exception) {
                    Log.w(TAG, "internet", e); lastError = e.message ?: "No internet"; false
                }
            }
        } else null

        val reached = mutableListOf<String>()
        val missed = mutableListOf<Peer>()
        for (p in targets) {
            var ok = false
            var why = ""
            for (h in p.hosts) {
                try {
                    open().use { Wire.send(h, keys(), JSONObject(header.toString()), it, size) }
                    Peers.seen(p.id, p.name, h)
                    ok = true
                    break
                } catch (e: WrongSpace) {
                    lastError = "${p.name} is linked to other devices. Link it again."
                    why = "linked to other devices"
                } catch (e: Exception) {
                    why = "$h: ${e.message ?: e.javaClass.simpleName}"
                    Log.d(TAG, "wifi ${p.name} $why")
                }
            }
            if (ok) { reached += p.name; lastVia = "Wi-Fi" } else {
                missed += p
                noteWifiProblem(p.name, if (p.hosts.isEmpty()) "no Wi-Fi address known yet" else why)
            }
        }
        if (missed.isEmpty()) return reached
        if (cloudJob != null) {
            val ok = try { cloudJob.get(20, TimeUnit.SECONDS) } catch (_: Exception) { false }
            if (ok) {
                reached += missed.map { it.name }
                lastVia = if (lastVia.isEmpty()) "internet" else "Wi-Fi + internet"
            }
        } else if (prefs.useInternet) {
            try {
                if (size > Cloud.MAX_BYTES) throw Cloud.TooBig()
                val bytes = open().use { it.readBytes() }
                cloud.publish(JSONObject(header.toString()).put("size", size), bytes)
                reached += missed.map { it.name }
                lastVia = if (lastVia.isEmpty()) "internet" else "Wi-Fi + internet"
            } catch (e: Exception) {
                Log.w(TAG, "internet", e)
                lastError = e.message ?: "No internet"
            }
        } else {
            lastError = "Couldn't reach ${missed.joinToString { it.name }} on Wi-Fi (internet sending is off)."
        }
        return reached
    }

    private val wifiNotes = HashMap<String, Long>()

    /** Shows (at most once a minute per device) why the direct Wi-Fi connection failed. */
    private fun noteWifiProblem(name: String, why: String) {
        val now = System.currentTimeMillis()
        synchronized(wifiNotes) {
            if (now - (wifiNotes[name] ?: 0) < 60_000) return
            wifiNotes[name] = now
        }
        AutoLog.add("Wi-Fi to $name failed ($why)")
    }

    private fun failMessage(): String = lastError ?: "Couldn't reach your other devices."

    internal fun doSendText(text: String, quiet: Boolean) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reached = deliver(header("text"), bytes)
        AutoLog.add(
            if (reached.isNotEmpty()) "Delivered to ${reached.joinToString()} via $lastVia"
            else "Not delivered: ${failMessage()}"
        )
        if (reached.isNotEmpty()) {
            History.add(Item(History.newId(), outgoing = true, kind = "text", text = text.take(100_000), peer = reached.joinToString()))
            if (!quiet) toast("Sent to ${reached.joinToString()} ✓")
        } else if (!quiet || lastError?.startsWith("No linked") == false) {
            toast(failMessage())
        }
    }

    internal fun doSendFiles(uris: List<Uri>, quiet: Boolean) {
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
                // Copy locally first so we know the size and can read it more than once.
                val cached = File.createTempFile("relay_out", ".bin", cacheDir)
                contentResolver.openInputStream(uri)!!.use { i -> cached.outputStream().use { i.copyTo(it) } }
                val reached = deliverStream(
                    header("file").put("name", meta.name).put("mime", meta.mime), cached.length()
                ) { cached.inputStream() }
                cached.delete()
                if (reached.isNotEmpty()) {
                    ok++; names = reached
                    History.add(Item(History.newId(), outgoing = true, kind = "file", name = meta.name, uri = uri.toString(), mime = meta.mime, peer = reached.joinToString()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "file", e); lastError = e.message
            }
        }
        try { nm.cancel(PROGRESS_ID) } catch (_: Exception) {}
        if (quiet && ok > 0) return
        when {
            ok == uris.size -> toast(if (ok == 1) "Sent to ${names.joinToString()} ✓" else "Sent $ok files to ${names.joinToString()} ✓")
            ok > 0 -> toast("Sent $ok of ${uris.size} files")
            else -> toast(failMessage())
        }
    }

    internal fun doRing(peerId: String) {
        val reached = deliver(header("ring"), ByteArray(0), only = peerId)
        toast(if (reached.isNotEmpty()) "Ringing ${reached.joinToString()}…" else failMessage())
    }

    internal fun broadcastBye() {
        deliver(header("bye"), ByteArray(0))
    }

    // ---------- notification mirroring ----------

    private fun mirrorId(key: String) = (key.hashCode() and 0x7fffffff) % 1_000_000 + 2_000_000

    private fun showMirrored(h: JSONObject, text: String, from: String) {
        val key = h.optString("key")
        val pkg = h.optString("pkg")
        val app = h.optString("app", "App")
        val title = h.optString("title")
        val nid = mirrorId(key)
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: Intent(this, MainActivity::class.java)
        val b = NotificationCompat.Builder(this, RelayApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("$app · $from")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(PendingIntent.getActivity(this, nid, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        try { getSystemService(NotificationManager::class.java).notify(nid, b.build()) } catch (_: Exception) {}
    }

    private val recentMirror = LinkedHashMap<String, Long>()

    /** Called by [MirrorListener] for each new notification on this device. */
    fun mirror(pkg: String, app: String, key: String, title: String, text: String) {
        if (!prefs.mirrorNotifs || Peers.all().isEmpty()) return
        val sig = "$key|$title|$text"
        synchronized(recentMirror) {
            val now = System.currentTimeMillis()
            recentMirror.entries.removeAll { now - it.value > 60_000 }
            if (recentMirror.containsKey(sig)) return
            recentMirror[sig] = now
        }
        sender.execute {
            deliver(
                header("notif").put("pkg", pkg).put("app", app).put("key", key).put("title", title),
                text.toByteArray()
            )
        }
    }

    fun mirrorRemoved(key: String) {
        if (!prefs.mirrorNotifs || Peers.all().isEmpty()) return
        sender.execute { deliver(header("notif_rm").put("key", key), ByteArray(0)) }
    }

    // ---------- find my device ----------

    private var ringtone: Ringtone? = null

    private fun startRing(from: String) = main.post {
        stopRing()
        try {
            val am = getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val r = RingtoneManager.getRingtone(this, uri)
        if (r != null) {
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
        }
        main.postDelayed({ stopRing() }, 30_000)
        val stop = PendingIntent.getBroadcast(
            this, RING_ID, Intent(this, CopyReceiver::class.java).setAction(CopyReceiver.ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, RelayApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$from is looking for this device")
            .setContentText("Tap to stop the sound")
            .setContentIntent(stop)
            .addAction(0, "Stop", stop)
            .setDeleteIntent(stop)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(RING_ID, n) } catch (_: Exception) {}
    }

    fun stopRing() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { getSystemService(NotificationManager::class.java).cancel(RING_ID) } catch (_: Exception) {}
    }
}
