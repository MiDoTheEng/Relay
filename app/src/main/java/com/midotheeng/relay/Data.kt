package com.midotheeng.relay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("relay", Context.MODE_PRIVATE)

    var deviceName: String
        get() = p.getString("name", null) ?: defaultName()
        set(v) = p.edit().putString("name", v).apply()

    var pairCode: String
        get() = p.getString("code", "") ?: ""
        set(v) = p.edit().putString("code", v).apply()

    val deviceId: String
        get() = p.getString("id", null)
            ?: UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }

    var autoCopy: Boolean
        get() = p.getBoolean("autoCopy", true)
        set(v) = p.edit().putBoolean("autoCopy", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("boot", true)
        set(v) = p.edit().putBoolean("boot", v).apply()

    /** Optional fixed IP of the other device, used if discovery fails. */
    var manualPeer: String
        get() = p.getString("manual", "") ?: ""
        set(v) = p.edit().putString("manual", v.trim()).apply()

    /** Copy on one device, paste on the other (needs the one-time log permission). */
    var universalClipboard: Boolean
        get() = p.getBoolean("uc", true)
        set(v) = p.edit().putBoolean("uc", v).apply()

    var mirrorNotifs: Boolean
        get() = p.getBoolean("mirror", false)
        set(v) = p.edit().putBoolean("mirror", v).apply()

    var openLinks: Boolean
        get() = p.getBoolean("openLinks", false)
        set(v) = p.edit().putBoolean("openLinks", v).apply()

    var peersJson: String
        get() = p.getString("peers", "[]") ?: "[]"
        set(v) = p.edit().putString("peers", v).apply()

    val isSetUp: Boolean get() = pairCode.length >= 4

    private fun defaultName(): String =
        if (app.resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone"
}

data class Peer(
    val id: String, val name: String, val host: String, val lastSeen: Long,
    val battery: Int = -1, val charging: Boolean = false,
)

/** Remembers what we last synced so a received item isn't bounced straight back. */
object Sync {
    @Volatile var lastText: String? = null
    @Volatile var lastWriteAt: Long = 0L
}

/** The other devices running Relay with the same pair code. */
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
                map[o.getString("id")] =
                    Peer(o.getString("id"), o.getString("name"), o.getString("host"), o.optLong("seen"))
            }
            state.value = map
        } catch (_: Exception) {
        }
    }

    fun all(): List<Peer> = state.value.values.sortedByDescending { it.lastSeen }

    @Synchronized
    fun seen(id: String, name: String, host: String, battery: Int = -1, charging: Boolean = false) {
        if (host.isEmpty()) return
        val now = System.currentTimeMillis()
        val old = state.value[id]
        // A reinstalled device gets a new id but keeps its IP: drop stale entries on the same host.
        val map = state.value.filterValues { it.host != host || it.id == id }.toMutableMap()
        map[id] = Peer(
            id, name, host, now,
            if (battery >= 0) battery else old?.battery ?: -1,
            if (battery >= 0) charging else old?.charging ?: false,
        )
        state.value = map
        if (old == null || old.host != host || old.name != name || now - old.lastSeen > 60_000) save()
    }

    @Synchronized
    fun clear() {
        state.value = emptyMap(); save()
    }

    private fun save() {
        val arr = JSONArray()
        state.value.values.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("seen", it.lastSeen))
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
        val f = File(ctx.filesDir, "history.json")
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
