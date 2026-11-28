package com.midotheeng.relay

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Keys for one "space" (your set of linked devices). */
class Keys(space: ByteArray) {
    val enc: ByteArray = hmac(space, "relay-enc").copyOf(32)
    val mac: ByteArray = hmac(space, "relay-mac").copyOf(32)
    /** Internet channel name. Derived from the secret, so nobody can guess it. */
    val topic: String = "relay_" + hmac(space, "relay-topic").joinToString("") { "%02x".format(it) }.take(40)

    fun newMac(): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(mac, "HmacSHA256")) }

    fun tag(s: String): String = newMac().doFinal(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** Encrypt-then-MAC a small blob: iv(16) | AES-CTR(data) | HMAC(32). */
    fun seal(data: ByteArray): ByteArray {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val c = data.copyOf()
        Ctr(enc, iv).apply(c, 0, c.size)
        val m = newMac().apply { update(iv); update(c) }.doFinal()
        return iv + c + m
    }

    fun open(blob: ByteArray): ByteArray? {
        if (blob.size < 48) return null
        val iv = blob.copyOfRange(0, 16)
        val c = blob.copyOfRange(16, blob.size - 32)
        val m = blob.copyOfRange(blob.size - 32, blob.size)
        val expect = newMac().apply { update(iv); update(c) }.doFinal()
        if (!MessageDigest.isEqual(m, expect)) return null
        Ctr(enc, iv).apply(c, 0, c.size)
        return c
    }

    companion object {
        private fun hmac(key: ByteArray, s: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(s.toByteArray())
    }
}

/** AES-CTR keystream, generated in chunks so big files stay fast. */
class Ctr(key: ByteArray, iv: ByteArray) {
    private val aes = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    private val counter = iv.copyOf()
    private val ctrBuf = ByteArray(CHUNK)
    private val ks = ByteArray(CHUNK)
    private var pos = CHUNK

    fun apply(buf: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            if (pos == CHUNK) refill()
            buf[i] = (buf[i].toInt() xor ks[pos++].toInt()).toByte()
        }
    }

    private fun refill() {
        for (b in 0 until CHUNK / 16) {
            System.arraycopy(counter, 0, ctrBuf, b * 16, 16)
            for (j in 15 downTo 0) {
                counter[j]++
                if (counter[j] != 0.toByte()) break
            }
        }
        aes.doFinal(ctrBuf, 0, CHUNK, ks, 0)
        pos = 0
    }

    companion object { private const val CHUNK = 8192 }
}

class WrongSpace : IOException("That device belongs to a different set of linked devices")

/** A message between devices: a JSON header plus an optional body. */
object Packet {
    /** [hdrLen(4) | header JSON | body] — used inside both Wi-Fi frames and internet blobs. */
    fun pack(header: JSONObject, body: ByteArray): ByteArray {
        val hb = header.toString().toByteArray()
        val out = ByteArrayOutputStream(4 + hb.size + body.size)
        out.write(ByteBuffer.allocate(4).putInt(hb.size).array())
        out.write(hb)
        out.write(body)
        return out.toByteArray()
    }

    fun unpack(data: ByteArray): Pair<JSONObject, ByteArray>? = try {
        val hl = ByteBuffer.wrap(data, 0, 4).int
        if (hl !in 1..65536 || 4 + hl > data.size) null
        else JSONObject(String(data, 4, hl)) to data.copyOfRange(4 + hl, data.size)
    } catch (_: Exception) { null }
}

/**
 * Direct Wi-Fi transfer.
 * Frame: "RLY2" | iv(16) | enc[ hdrLen(4) | header | body ] | HMAC(iv + ciphertext). Reply: 1 ok, 2 wrong space.
 */
object Wire {
    const val PORT = 47831
    const val UDP_PORT = 47832
    private val MAGIC = "RLY2".toByteArray()

    fun send(host: String, keys: Keys, header: JSONObject, body: InputStream, bodyLen: Long, connectTimeout: Int = 2500) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PORT), connectTimeout)
            s.soTimeout = 20000
            val out = BufferedOutputStream(s.getOutputStream(), 65536)
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val ctr = Ctr(keys.enc, iv)
            val mac = keys.newMac()
            mac.update(iv)
            out.write(MAGIC)
            out.write(iv)
            fun w(b: ByteArray, off: Int = 0, len: Int = b.size) {
                ctr.apply(b, off, len); mac.update(b, off, len); out.write(b, off, len)
            }
            header.put("size", bodyLen)
            val hb = header.toString().toByteArray()
            w(ByteBuffer.allocate(4).putInt(hb.size).array())
            w(hb)
            val buf = ByteArray(65536)
            var left = bodyLen
            while (left > 0) {
                val n = body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("File ended early")
                w(buf, 0, n)
                left -= n
            }
            out.write(mac.doFinal())
            out.flush()
            s.soTimeout = 60000
            when (s.getInputStream().read()) {
                1 -> Unit
                2 -> throw WrongSpace()
                else -> throw IOException("No answer")
            }
        }
    }

    class Incoming(val header: JSONObject, val file: File, val from: InetAddress)

    fun receive(s: Socket, keys: Keys, tmpDir: File): Incoming? {
        s.soTimeout = 30000
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
        val out = s.getOutputStream()
        val magic = ByteArray(4)
        inp.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val iv = ByteArray(16)
        inp.readFully(iv)
        val ctr = Ctr(keys.enc, iv)
        val mac = keys.newMac()
        mac.update(iv)
        fun r(b: ByteArray, off: Int = 0, len: Int = b.size) {
            inp.readFully(b, off, len); mac.update(b, off, len); ctr.apply(b, off, len)
        }
        fun reject(): Incoming? { out.write(2); out.flush(); return null }

        val lb = ByteArray(4)
        r(lb)
        val hl = ByteBuffer.wrap(lb).int
        if (hl !in 1..65536) return reject()
        val hb = ByteArray(hl)
        r(hb)
        val header = try { JSONObject(String(hb)) } catch (_: Exception) { return reject() }
        val size = header.optLong("size", -1)
        if (size < 0) return reject()

        val tmp = File.createTempFile("relay_in", ".part", tmpDir)
        try {
            FileOutputStream(tmp).use { fo ->
                val buf = ByteArray(65536)
                var left = size
                while (left > 0) {
                    val n = minOf(buf.size.toLong(), left).toInt()
                    r(buf, 0, n)
                    fo.write(buf, 0, n)
                    left -= n
                }
            }
            val tag = ByteArray(32)
            inp.readFully(tag)
            if (!MessageDigest.isEqual(tag, mac.doFinal())) {
                tmp.delete(); return reject()
            }
            out.write(1)
            out.flush()
            return Incoming(header, tmp, s.inetAddress)
        } catch (e: Exception) {
            tmp.delete(); throw e
        }
    }
}
