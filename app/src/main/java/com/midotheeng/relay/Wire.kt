package com.midotheeng.relay

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Keys come from the pair code, so only devices with the same code can read each other. */
object Crypto {
    private var cachedCode: String? = null
    private var encKey = ByteArray(32)
    private var macKey = ByteArray(32)

    @Synchronized
    fun keys(code: String): Pair<ByteArray, ByteArray> {
        if (code != cachedCode) {
            val spec = PBEKeySpec(code.toCharArray(), "relay-pair-v1".toByteArray(), 20000, 512)
            val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            encKey = k.copyOfRange(0, 32)
            macKey = k.copyOfRange(32, 64)
            cachedCode = code
        }
        return encKey to macKey
    }

    fun mac(key: ByteArray): Mac =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

    fun tag(code: String, s: String): String =
        mac(keys(code).second).doFinal(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
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

class PairMismatch : IOException("The other device has a different pair code")

/**
 * Frame: "RLY1" | iv(16) | enc[ hdrLen(4) | header JSON | body ] | HMAC-SHA256(iv + ciphertext)
 * Receiver answers 1 = ok, 2 = wrong pair code.
 */
object Wire {
    const val PORT = 47821
    const val UDP_PORT = 47822
    private val MAGIC = "RLY1".toByteArray()

    fun send(host: String, header: JSONObject, body: InputStream, bodyLen: Long, code: String) {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PORT), 4000)
            s.soTimeout = 20000
            val out = BufferedOutputStream(s.getOutputStream(), 65536)
            val (ek, mk) = Crypto.keys(code)
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val ctr = Ctr(ek, iv)
            val mac = Crypto.mac(mk)
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
                2 -> throw PairMismatch()
                else -> throw IOException("No answer from the other device")
            }
        }
    }

    class Incoming(val header: JSONObject, val file: File, val from: InetAddress)

    fun receive(s: Socket, code: String, tmpDir: File): Incoming? {
        s.soTimeout = 30000
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
        val out = s.getOutputStream()
        val magic = ByteArray(4)
        inp.readFully(magic)
        if (!magic.contentEquals(MAGIC)) return null
        val iv = ByteArray(16)
        inp.readFully(iv)
        val (ek, mk) = Crypto.keys(code)
        val ctr = Ctr(ek, iv)
        val mac = Crypto.mac(mk)
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

        val tmp = File.createTempFile("in", ".part", tmpDir)
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
