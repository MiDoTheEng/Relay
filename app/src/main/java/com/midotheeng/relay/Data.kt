package com.midotheeng.relay

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("relay2", Context.MODE_PRIVATE)

    var deviceName: String
        get() = p.getString("name", null) ?: defaultName()
        set(v) = p.edit().putString("name", v).apply()

    val hasName: Boolean get() = p.contains("name")

    val deviceId: String
        get() = p.getString("id", null)
            ?: UUID.randomUUID().toString().replace("-", "").take(16).also { p.edit().putString("id", it).apply() }

    /**
     * The secret shared by all your linked devices (your "space").
     * Every device starts with its own; scanning another device's QR code adopts that device's key.
     */
    var spaceKey: ByteArray
        get() {
            val s = p.getString("space", null)
            if (s != null) return Base64.decode(s, Base64.NO_WRAP)
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            p.edit().putString("space", Base64.encodeToString(k, Base64.NO_WRAP)).commit()
            return k
        }
        set(v) { p.edit().putString("space", Base64.encodeToString(v, Base64.NO_WRAP)).commit() }

    var autoCopy: Boolean
        get() = p.getBoolean("autoCopy", true)
        set(v) = p.edit().putBoolean("autoCopy", v).apply()

    var autoSend: Boolean
        get() = p.getBoolean("autoSend", true)
        set(v) = p.edit().putBoolean("autoSend", v).apply()

    var useInternet: Boolean
        get() = p.getBoolean("internet", true)
        set(v) = p.edit().putBoolean("internet", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("boot", true)
        set(v) = p.edit().putBoolean("boot", v).apply()

    var mirrorNotifs: Boolean
        get() = p.getBoolean("mirror", false)
        set(v) = p.edit().putBoolean("mirror", v).apply()

    var openLinks: Boolean
        get() = p.getBoolean("openLinks", false)
        set(v) = p.edit().putBoolean("openLinks", v).apply()

    /** Last internet message id seen, so messages missed while offline are picked up. */
    var cloudSince: String
        get() = p.getString("since", "") ?: ""
        set(v) = p.edit().putString("since", v).apply()

    var peersJson: String
        get() = p.getString("peers", "[]") ?: "[]"
        set(v) = p.edit().putString("peers", v).apply()

    private fun defaultName(): String =
        if (app.resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone"
}

data class Peer(
    val id: String,
    val name: String,
    /** Wi-Fi addresses we can try directly, best first. */
    val hosts: List<String>,
    /** Last time we heard from it at all (Wi-Fi or internet). */
    val lastSeen: Long,
    /** Last time it reached us directly over Wi-Fi. */
    val lastLan: Long = 0,
    val battery: Int = -1,
    val charging: Boolean = false,
)

/** Your other linked devices. */
object Peers {
    val state = MutableStateFlow<Map<String, Peer>>(emptyMap())
    private var prefs: Prefs? = null

    @Synchronized
    fun init(p: Prefs) {
        if (prefs != null) return
        prefs = p
        try {
            val arr = JSONArray(p.peersJson)
            val map = mutableMapOf<String, Peer>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hosts = o.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                map[o.getString("id")] = Peer(o.getString("id"), o.getString("name"), hosts, o.optLong("seen"), o.optLong("lan"))
            }
            state.value = map
        } catch (_: Exception) {
        }
    }

    fun all(): List<Peer> = state.value.values.sortedByDescending { it.lastSeen }

    /**
     * Records that we heard from a device. [host] is the address it just reached us from over Wi-Fi;
     * [ips] are addresses it told us about (e.g. over the internet or in its QR code).
     */
    @Synchronized
    fun seen(
        id: String, name: String, host: String? = null, ips: List<String> = emptyList(),
        battery: Int = -1, charging: Boolean = false,
    ) {
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        val old = state.value[id]
        val hosts = (listOfNotNull(host) + ips + (old?.hosts ?: emptyList()))
            .filter { it.isNotBlank() && !it.contains(':') }.distinct().take(4)
        val p = Peer(
            id, name.ifEmpty { old?.name ?: "Device" }, hosts, now,
            if (host != null) now else old?.lastLan ?: 0,
            if (battery >= 0) battery else old?.battery ?: -1,
            if (battery >= 0) charging else old?.charging ?: false,
        )
        state.value = state.value + (id to p)
        if (old == null || old.hosts != hosts || old.name != p.name || now - old.lastSeen > 60_000) save()
    }

    @Synchronized
    fun remove(id: String) {
        state.value = state.value - id; save()
    }

    @Synchronized
    fun clear() {
        state.value = emptyMap(); save()
    }

    private fun save() {
        val arr = JSONArray()
        state.value.values.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("hosts", JSONArray(it.hosts))
                    .put("seen", it.lastSeen).put("lan", it.lastLan)
            )
        }
        prefs?.peersJson = arr.toString()
    }
}

data class Item(
    val id: Long,
    val outgoing: Boolean,
    val kind: String, // "text" or "file"
    val text: String? = null,
    val name: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val peer: String,
    val time: Long = System.currentTimeMillis(),
)

/** Recent sent/received items, kept on disk. */
object History {
    val items = MutableStateFlow<List<Item>>(emptyList())
    private var file: File? = null
    private val ids = AtomicLong(System.currentTimeMillis())

    fun newId(): Long = ids.incrementAndGet()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "history2.json")
        file = f
        try {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                items.value = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Item(
                        id = o.getLong("id"),
                        outgoing = o.getBoolean("out"),
                        kind = o.getString("kind"),
                        text = o.optString("text").ifEmpty { null },
                        name = o.optString("name").ifEmpty { null },
                        uri = o.optString("uri").ifEmpty { null },
                        mime = o.optString("mime").ifEmpty { null },
                        peer = o.optString("peer"),
                        time = o.optLong("time"),
                    )
                }
                items.value.maxOfOrNull { it.id }?.let { if (it > ids.get()) ids.set(it) }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun add(item: Item) {
        items.value = (listOf(item) + items.value).take(200)
        save()
    }

    fun get(id: Long): Item? = items.value.firstOrNull { it.id == id }

    @Synchronized
    fun clear() {
        items.value = emptyList(); save()
    }

    private fun save() {
        val arr = JSONArray()
        items.value.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("out", it.outgoing).put("kind", it.kind)
                    .put("text", it.text ?: "").put("name", it.name ?: "").put("uri", it.uri ?: "")
                    .put("mime", it.mime ?: "").put("peer", it.peer).put("time", it.time)
            )
        }
        try {
            file?.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}

/** Remembers what we last synced so a received item isn't bounced straight back. */
object Sync {
    @Volatile var lastText: String? = null
    @Volatile var lastWriteAt: Long = 0L

    private val seenMsgs = LinkedHashMap<String, Long>()

    /** True the first time a message id is seen (the same message can arrive over Wi-Fi and internet). */
    fun firstTime(msgId: String): Boolean = synchronized(seenMsgs) {
        if (msgId.isEmpty()) return true
        val now = System.currentTimeMillis()
        seenMsgs.entries.removeAll { now - it.value > 3_600_000 }
        if (seenMsgs.containsKey(msgId)) false else { seenMsgs[msgId] = now; true }
    }
}

/** What auto-send did recently, shown in the app so problems are visible. */
object AutoLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    /** Live proof that Android is passing events to the accessibility service. */
    val events = MutableStateFlow(0)
    val lastEvent = MutableStateFlow("")
    val connected = MutableStateFlow(false)
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    /** Recent copy-like signals from other apps (diagnostics). */
    val signals = MutableStateFlow<List<String>>(emptyList())

    fun signal(msg: String) {
        val line = fmt.format(java.util.Date()) + "  " + msg
        if (signals.value.firstOrNull()?.endsWith(msg) == true) return
        signals.value = (listOf(line) + signals.value).take(6)
    }

    fun add(msg: String) {
        val line = fmt.format(java.util.Date()) + "  " + msg
        lines.value = (listOf(line) + lines.value).take(12)
    }
}
