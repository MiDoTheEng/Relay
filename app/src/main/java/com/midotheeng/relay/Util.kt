package com.midotheeng.relay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object Util {
    fun copyText(ctx: Context, text: String): Boolean = try {
        Sync.lastText = text
        Sync.lastWriteAt = android.os.SystemClock.elapsedRealtime()
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Relay", text)); true
    } catch (_: Exception) { false }

    fun copyUri(ctx: Context, uri: Uri, label: String): Boolean = try {
        Sync.lastText = "uri:$uri"
        Sync.lastWriteAt = android.os.SystemClock.elapsedRealtime()
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newUri(ctx.contentResolver, label, uri)); true
    } catch (_: Exception) { false }

    /** Saves a received file where the user will find it: Pictures/Relay, Movies/Relay or Download/Relay. */
    fun saveIncoming(ctx: Context, tmp: File, name: String, mime: String): Uri {
        val r = ctx.contentResolver
        val (collection, dir) = when {
            mime.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Relay")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(collection, values) ?: throw IllegalStateException("Can't save $name")
        r.openOutputStream(uri)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        r.update(uri, values, null, null)
        tmp.delete()
        return uri
    }

    fun thumbnail(file: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        var s = 1
        while (o.outWidth / s > 1200 || o.outHeight / s > 1200) s *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Exception) { null }

    data class Meta(val name: String, val size: Long?, val mime: String)

    fun meta(ctx: Context, uri: Uri): Meta {
        var name: String? = null
        var size: Long? = null
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si).takeIf { it >= 0 }
                }
            }
        } catch (_: Exception) {
        }
        if (uri.scheme == "file") uri.path?.let { p -> File(p).let { name = it.name; size = it.length() } }
        val n = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val ext = n.substringAfterLast('.', "").lowercase()
        val mime = ctx.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        return Meta(n, size, mime)
    }

    fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { ia -> ia.broadcast?.let { out.add(it) } }
            }
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("eth")) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) { emptyList() }

    fun viewIntent(uri: Uri, mime: String?): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun battery(ctx: Context): Pair<Int, Boolean> = try {
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
    } catch (_: Exception) { -1 to false }

    fun looksLikeUrl(t: String): Boolean {
        val s = t.trim()
        return !s.contains(' ') && !s.contains('\n') &&
            (s.startsWith("http://") || s.startsWith("https://"))
    }
}
