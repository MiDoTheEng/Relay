package com.midotheeng.relay

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Internet fallback through ntfy.sh (a free, open-source message relay; no account needed).
 * Everything is encrypted with your devices' shared secret before it leaves the device, and the
 * channel name is derived from that secret. Used when devices can't reach each other on Wi-Fi.
 */
class Cloud(
    private val keys: () -> Keys,
    private val myId: () -> String,
    private val enabled: () -> Boolean,
    private val since: () -> String,
    private val saveSince: (String) -> Unit,
    private val onPacket: (header: JSONObject, body: ByteArray, ageSec: Long) -> Unit,
) {
    companion object {
        private const val TAG = "RelayCloud"
        private const val BASE = "https://ntfy.sh"
        const val MAX_BYTES = 14L * 1024 * 1024
        private const val INLINE_MAX = 2900
    }

    class TooBig : IOException("Too big to send over the internet (max 14 MB). Connect both devices to the same Wi-Fi.")

    @Volatile private var alive = false
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile var connected = false
        private set
    private var thread: Thread? = null

    fun start() {
        if (alive) return
        alive = true
        thread = Thread({ loop() }, "relay-cloud").apply { isDaemon = true; start() }
    }

    fun stop() {
        alive = false
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    /** Reconnect, e.g. after the shared secret changed or the network came back. */
    fun restart() {
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    private fun loop() {
        var backoff = 3_000L
        while (alive) {
            if (!enabled()) { connected = false; Thread.sleep(5_000); continue }
            try {
                val topic = keys().topic
                val s = since().ifEmpty { "10m" }
                val c = URL("$BASE/$topic/json?since=$s").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 120_000 // the server sends a keepalive about every 45 s
                c.setRequestProperty("User-Agent", "Relay-Android")
                conn = c
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                connected = true
                backoff = 3_000L
                c.inputStream.bufferedReader().use { r ->
                    while (alive) {
                        val line = r.readLine() ?: break
                        if (topic != keys().topic) break
                        handleLine(line)
                    }
                }
            } catch (e: Exception) {
                if (alive) Log.d(TAG, "stream: ${e.message}")
            } finally {
                connected = false
                try { conn?.disconnect() } catch (_: Exception) {}
                conn = null
            }
            if (!alive) break
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun handleLine(line: String) {
        val j = try { JSONObject(line) } catch (_: Exception) { return }
        if (j.optString("event") != "message") return
        j.optString("id").takeIf { it.isNotEmpty() }?.let(saveSince)
        val ageSec = System.currentTimeMillis() / 1000 - j.optLong("time", System.currentTimeMillis() / 1000)
        try {
            val att = j.optJSONObject("attachment")
            val blob: ByteArray = if (att != null) {
                download(att.optString("url")) ?: return
            } else {
                Base64.decode(j.optString("message"), Base64.NO_WRAP)
            }
            val plain = keys().open(blob) ?: return
            val (header, body) = Packet.unpack(plain) ?: return
            if (header.optString("id") == myId()) return // our own message coming back
            onPacket(header, body, ageSec)
        } catch (e: Exception) {
            Log.w(TAG, "message", e)
        }
    }

    private fun download(url: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        return try {
            if (c.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { i ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES + 1_000_000) return null
                }
            }
            out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /** Publishes one message to all your other devices. Throws on failure. */
    fun publish(header: JSONObject, body: ByteArray) {
        if (body.size > MAX_BYTES) throw TooBig()
        val blob = keys().seal(Packet.pack(header, body))
        val topic = keys().topic
        val c = URL("$BASE/$topic").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("User-Agent", "Relay-Android")
            if (blob.size <= INLINE_MAX) {
                c.requestMethod = "POST"
                val text = Base64.encodeToString(blob, Base64.NO_WRAP).toByteArray()
                c.setRequestProperty("Content-Type", "text/plain")
                c.setFixedLengthStreamingMode(text.size)
                c.outputStream.use { it.write(text) }
            } else {
                c.requestMethod = "PUT"
                c.setRequestProperty("Filename", "relay.bin")
                c.setFixedLengthStreamingMode(blob.size)
                c.outputStream.use { it.write(blob) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Internet relay answered $code")
        } finally {
            c.disconnect()
        }
    }
}
