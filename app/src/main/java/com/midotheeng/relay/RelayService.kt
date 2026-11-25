package com.midotheeng.relay

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayService : Service() {

    companion object {
        private const val TAG = "Relay"
        private const val NOTIF_ID = 1
        private const val PROGRESS_ID = 2
        private const val RING_ID = 3
            const val SHARE_CATEGORY = "com.midotheeng.relay.SHARE_TARGET"
        const val ACTION_SEND_TEXT = "com.midotheeng.relay.SEND_TEXT"
        const val ACTION_SEND_FILES = "com.midotheeng.relay.SEND_FILES"
        const val ACTION_HELLO = "com.midotheeng.relay.HELLO"
        const val ACTION_RING = "com.midotheeng.relay.RING"
        const val ACTION_SETUP_REQ = "com.midotheeng.relay.SETUP_REQ"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TEXT_FILE = "textFile"
        private const val EXTRA_QUIET = "quiet"

        val running = MutableStateFlow(false)

        /** Universal Clipboard state: off, no_logs, no_overlay, needs_approval, active */
        val clipState = MutableStateFlow("off")

        @Volatile var instance: RelayService? = null
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java))
        }

        fun hello(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java).setAction(ACTION_HELLO))
        }

        /** Ask the other device to show "type the code shown on <this device>". */
        fun requestSetup(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java).setAction(ACTION_SETUP_REQ))
        }

        fun ring(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java).setAction(ACTION_RING))
        }

        fun sendText(ctx: Context, text: String, quiet: Boolean = false) {
            if (ctx is RelayService) { ctx.sender.execute { ctx.doSendText(text, quiet) }; return }
            val i = Intent(ctx, RelayService::class.java).setAction(ACTION_SEND_TEXT).putExtra(EXTRA_QUIET, quiet)
            if (text.length > 100_000) {
                val f = File(ctx.cacheDir, "out-${System.nanoTime()}.txt")
                f.writeText(text)
                i.putExtra(EXTRA_TEXT_FILE, f.path)
            } else {
                i.putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun sendFiles(ctx: Context, uris: List<Uri>, quiet: Boolean = false) {
            if (uris.isEmpty()) return
            if (ctx is RelayService) { ctx.sender.execute { ctx.doSendFiles(uris, quiet) }; return }
            val i = Intent(ctx, RelayService::class.java).setAction(ACTION_SEND_FILES).putExtra(EXTRA_QUIET, quiet)
            val clip = ClipData.newRawUri("files", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(ctx, i)
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
        io.execute { tcpLoop() }
        io.execute { udpLoop() }
        registerNetwork()
        sched.scheduleWithFixedDelay({ hello() }, 1, 120, TimeUnit.SECONDS)
        var lastNames = ""
        scope.launch {
            Peers.state.collect { map ->
                updateNotif()
                val names = map.values.joinToString { it.id + it.name }
                if (names != lastNames) { lastNames = names; updateShareTargets() }
            }
        }
        main.post { registerClipListener() }
        // Let the other device know when our Universal Clipboard state changes.
        scope.launch { clipState.collect { hello() } }
        startWatcher()
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
            ACTION_HELLO -> { hello(); startWatcher() }
            ACTION_RING -> sender.execute { doRing() }
            ACTION_SETUP_REQ -> sender.execute {
                deliver(quietRetry = true) { host ->
                    Wire.send(host, header("setup_req"), ByteArrayInputStream(ByteArray(0)), 0, prefs.pairCode)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        instance = null
        running.value = false
        clipState.value = "off"
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        try { mcLock?.release() } catch (_: Exception) {}
        try { logProc?.destroy() } catch (_: Exception) {}
        try { overlay?.let { getSystemService(WindowManager::class.java).removeView(it) } } catch (_: Exception) {}
        try {
            netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        stopRing()
        scope.cancel()
        sched.shutdownNow(); sender.shutdownNow(); io.shutdownNow()
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun peerLine(p: Peer): String =
        if (p.battery >= 0) "${p.name} ${if (p.charging) "⚡" else "🔋"}${p.battery}%" else p.name

    private fun buildNotif(): Notification {
        val peers = Peers.all()
        val text = when {
            !prefs.isSetUp -> "Open Relay to finish setup"
            clipState.value == "needs_approval" -> "Tap to switch Universal Clipboard back on"
            peers.isEmpty() -> "Looking for your other device…"
            else -> "Linked with " + peers.joinToString { peerLine(it) }
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
        val b = NotificationCompat.Builder(this, RelayApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (clipState.value == "active") "Relay · Universal Clipboard on" else "Relay is on")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
        if (clipState.value != "active") b.addAction(0, "Send clipboard", sendClip)
        return b.build()
    }

    private fun startFg() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotif(), type)
    }

    private fun updateNotif() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif()) } catch (_: Exception) {}
    }

    private fun toast(msg: String) = main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }

    /** The other devices appear as their own icons at the top of the share sheet (like AirDrop). */
    private fun updateShareTargets() {
        try {
            val list = Peers.all().take(3).map { p ->
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

    // ---------- Universal Clipboard ----------
    //
    // Android hides clipboard changes from background apps. But each time the clipboard
    // changes, the system writes "Denying clipboard access to com.midotheeng.relay" to its
    // log. With READ_LOGS (granted once over adb) we watch for that line, then give a
    // 1-pixel invisible overlay window focus for a moment, which is enough to read the clip.

    @Volatile private var logProc: Process? = null
    private var overlay: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    @Volatile private var grabPending = false
    private var lastTrigger = 0L
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { }

    private fun registerClipListener() {
        try { getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
    }

    private fun hasLogs() = checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    /** Call any time; safe to repeat. Shows the system "allow logs" prompt only if called while Relay is on screen. */
    fun startWatcher() {
        if (!prefs.universalClipboard || !prefs.isSetUp) { stopWatcher(); clipState.value = "off"; updateNotif(); return }
        if (!hasLogs()) { clipState.value = "no_logs"; updateNotif(); return }
        if (!Settings.canDrawOverlays(this)) { clipState.value = "no_overlay"; updateNotif(); return }
        main.post { ensureOverlay() }
        if (watcherRunning) return
        try {
            io.execute { runWatcher() }
        } catch (_: Exception) {
        }
    }

    fun stopWatcher() {
        try { logProc?.destroy() } catch (_: Exception) {}
        logProc = null
    }

    /** On Android 13+ log access needs a one-time "Allow" each time the watcher starts. Check we really got it. */
    private fun canSeeSystemLogs(): Boolean = try {
        val myPid = android.os.Process.myPid().toString()
        val pidRe = Regex("""\(\s*(\d+)\)""")
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300", "-v", "brief"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.destroy()
        lines.any { l -> pidRe.find(l)?.groupValues?.get(1)?.let { it != myPid } ?: false }
    } catch (_: Exception) { false }

    @Volatile private var watcherRunning = false

    private fun runWatcher() {
        synchronized(this) {
            if (watcherRunning) return
            watcherRunning = true
        }
        try {
            if (!canSeeSystemLogs()) {
                clipState.value = "needs_approval"; updateNotif(); return
            }
            val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-T", ts, "ClipboardService:E", "*:S"))
            logProc = p
            clipState.value = "active"
            updateNotif()
            p.inputStream.bufferedReader().use { r ->
                while (alive) {
                    val line = r.readLine() ?: break
                    if (line.contains(packageName)) main.post { onExternalClipChange() }
                }
            }
            if (alive && prefs.universalClipboard) {
                clipState.value = "needs_approval"; updateNotif()
            }
        } catch (e: Exception) {
            Log.w(TAG, "watcher", e)
        } finally {
            logProc = null
            watcherRunning = false
        }
    }

    private fun ensureOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        try {
            val v = object : View(this) {
                override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                    super.onWindowFocusChanged(hasWindowFocus)
                    if (hasWindowFocus) onOverlayFocused()
                }
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            getSystemService(WindowManager::class.java).addView(v, lp)
            overlay = v; overlayLp = lp
        } catch (e: Exception) {
            Log.w(TAG, "overlay", e)
        }
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val v = overlay ?: return
        val lp = overlayLp ?: return
        lp.flags = if (focusable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { getSystemService(WindowManager::class.java).updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    private fun onExternalClipChange() {
        val now = SystemClock.elapsedRealtime()
        if (now - Sync.lastWriteAt < 2000) return // we just wrote it ourselves
        if (now - lastTrigger < 600) return
        lastTrigger = now
        ensureOverlay()
        if (overlay == null) { launchGrabActivity(); return }
        grabPending = true
        setOverlayFocusable(true)
        main.postDelayed({
            if (grabPending) { grabPending = false; setOverlayFocusable(false); launchGrabActivity() }
        }, 700)
    }

    private fun onOverlayFocused() {
        if (!grabPending) return
        grabPending = false
        try { ClipActivity.sendClipboard(this, auto = true) } catch (e: Exception) { Log.w(TAG, "grab", e) }
        setOverlayFocusable(false)
    }

    private fun launchGrabActivity() {
        try {
            startActivity(
                Intent(this, ClipActivity::class.java).setAction(ClipActivity.ACTION_AUTO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "grab activity", e)
        }
    }

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
                Peers.seen(fromId, from, inc.from.hostAddress ?: "", h.optInt("bat", -1), h.optBoolean("chg"), h.optString("uc"))
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
                    "notif" -> {
                        val text = inc.file.readText()
                        inc.file.delete()
                        showMirrored(h, text, from)
                    }
                    "notif_rm" -> {
                        inc.file.delete()
                        try { getSystemService(NotificationManager::class.java).cancel(mirrorId(h.optString("key"))) } catch (_: Exception) {}
                    }
                    "ring" -> { inc.file.delete(); startRing(from) }
                    "setup_req" -> {
                        inc.file.delete()
                        AdbSetup.showRequest(this, inc.from.hostAddress ?: "", from)
                    }
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
        if (prefs.openLinks && Util.looksLikeUrl(text)) {
            main.post {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }
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
            // With Universal Clipboard on, text arrives constantly: keep it quiet.
            if (clipState.value == "active") b.setSilent(true).setTimeoutAfter(60_000)
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
        if (!prefs.mirrorNotifs || !prefs.isSetUp) return
        val sig = "$key|$title|$text"
        synchronized(recentMirror) {
            val now = System.currentTimeMillis()
            recentMirror.entries.removeAll { now - it.value > 60_000 }
            if (recentMirror.containsKey(sig)) return
            recentMirror[sig] = now
        }
        sender.execute {
            val bytes = text.toByteArray()
            deliver(quietRetry = true) { host ->
                Wire.send(
                    host,
                    header("notif").put("pkg", pkg).put("app", app).put("key", key).put("title", title),
                    ByteArrayInputStream(bytes), bytes.size.toLong(), prefs.pairCode
                )
            }
        }
    }

    fun mirrorRemoved(key: String) {
        if (!prefs.mirrorNotifs || !prefs.isSetUp) return
        sender.execute {
            deliver(quietRetry = true) { host ->
                Wire.send(host, header("notif_rm").put("key", key), ByteArrayInputStream(ByteArray(0)), 0, prefs.pairCode)
            }
        }
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

    private fun doRing() {
        val reached = deliver { host ->
            Wire.send(host, header("ring"), ByteArrayInputStream(ByteArray(0)), 0, prefs.pairCode)
        }
        toast(if (reached.isNotEmpty()) "Ringing ${reached.joinToString()}…" else failMessage())
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
        Peers.seen(id, name, host, j.optInt("bat", -1), j.optBoolean("chg"), j.optString("uc"))
        if (j.optString("t") == "hello") sendUdp(helloPacket("ack") ?: return, pk.address)
    }

    private fun helloPacket(t: String): ByteArray? {
        val code = prefs.pairCode
        if (code.isEmpty()) return null
        val id = prefs.deviceId
        val name = prefs.deviceName
        val (bat, chg) = Util.battery(this)
        val j = JSONObject().put("t", t).put("id", id).put("name", name)
            .put("bat", bat).put("chg", chg).put("uc", clipState.value)
            .put("tag", Crypto.tag(code, "hello|$id|$name"))
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

    @Volatile private var lastError: Exception? = null

    private fun tryOnce(host: String, action: (String) -> Unit): Boolean = try {
        action(host); true
    } catch (e: Exception) {
        Log.w(TAG, "send to $host", e); lastError = e; false
    }

    /** Sends to every known device. Returns the names that got it. */
    private fun deliver(quietRetry: Boolean = false, action: (String) -> Unit): List<String> {
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
            Thread.sleep(if (quietRetry) 1500 else 2500)
            val host = t.id?.let { Peers.state.value[it]?.host } ?: t.host
            if (tryOnce(host, action)) reached += t.name
        }
        return reached
    }

    private fun header(type: String): JSONObject {
        val (bat, chg) = Util.battery(this)
        return JSONObject().put("type", type).put("from", prefs.deviceName).put("id", prefs.deviceId)
            .put("bat", bat).put("chg", chg).put("uc", clipState.value)
    }

    private fun failMessage(): String = when (lastError) {
        is PairMismatch -> "The other device has a different pair code"
        else -> if (targets().isEmpty()) "No device found — open Relay on the other device (same Wi-Fi)"
        else "Couldn't reach your other device — is it on the same Wi-Fi?"
    }

    internal fun doSendText(text: String, quiet: Boolean) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reached = deliver(quietRetry = quiet) { host ->
            Wire.send(host, header("text"), ByteArrayInputStream(bytes), bytes.size.toLong(), prefs.pairCode)
        }
        if (reached.isNotEmpty()) {
            History.add(Item(History.newId(), outgoing = true, kind = "text", text = text.take(100_000), peer = reached.joinToString()))
            if (!quiet) toast("Sent to ${reached.joinToString()} ✓")
        } else if (!quiet) toast(failMessage())
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

                // If the size is unknown, copy it locally first so we know how much to send.
                val cached: File? = if (meta.size == null) {
                    File.createTempFile("out", ".bin", cacheDir).also { f ->
                        contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                    }
                } else null
                val size = cached?.length() ?: meta.size!!
                val reached = deliver(quietRetry = quiet) { host ->
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
        if (quiet) return
        when {
            ok == uris.size -> toast(if (ok == 1) "Sent to ${names.joinToString()} ✓" else "Sent $ok files to ${names.joinToString()} ✓")
            ok > 0 -> toast("Sent $ok of ${uris.size} files")
            else -> toast(failMessage())
        }
    }
}
