package com.midotheeng.relay

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.IntentCompat

class RelayApp : Application() {
    companion object {
        const val CH_SERVICE = "service"
        const val CH_IN = "incoming"
        const val CH_MIRROR = "mirrored"
    }

    override fun onCreate() {
        super.onCreate()
        Peers.init(Prefs(this))
        History.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Relay running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Relay listening for your other device"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_IN, "Received items", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Text and files that arrive from your other device"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MIRROR, "From your other device", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications mirrored from your other device, and Find my device"
            }
        )
    }
}

/**
 * Invisible screen used by: the text-selection menu ("Relay"), the share sheet,
 * the Quick Settings tile and the notification's "Send clipboard" button.
 * Android only lets an app read the clipboard while it has focus, so this
 * activity flashes open (you won't see it), grabs the clipboard and closes.
 */
class ClipActivity : Activity() {
    companion object {
        const val ACTION_SEND_CLIPBOARD = "com.midotheeng.relay.SEND_CLIPBOARD"

        const val ACTION_AUTO = "com.midotheeng.relay.AUTO"

        /**
         * Reads the clipboard (caller must have focus) and sends it.
         * auto = Universal Clipboard: silent, skips what we just synced, only sends text and images.
         */
        fun sendClipboard(ctx: Context, auto: Boolean = false) {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            val clip = try { cm.primaryClip } catch (_: Exception) { null }
            val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val uri = item?.uri
            if (uri != null && item?.text.isNullOrEmpty()) {
                if (auto) {
                    val mime = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
                        ?: clip?.description?.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0) ?: ""
                    if (!mime.startsWith("image/")) return
                    if (Sync.lastText == "uri:$uri") return
                }
                Sync.lastText = "uri:$uri"
                RelayService.sendFiles(ctx, listOf(uri), quiet = auto)
                return
            }
            val t = try { item?.coerceToText(ctx)?.toString() } catch (_: Exception) { null }
            if (t.isNullOrEmpty()) {
                if (!auto) Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                return
            }
            if (auto && t == Sync.lastText) return
            Sync.lastText = t
            RelayService.sendText(ctx, t, quiet = auto)
        }
    }

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs(this).hasName) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            close(); return
        }
        val i = intent
        when (i?.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                    ?.takeIf { it.isNotEmpty() }?.let { RelayService.sendText(this, it) }
                close()
            }
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) RelayService.sendFiles(this, listOf(uri))
                else (i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                    ?.takeIf { it.isNotEmpty() }?.let { RelayService.sendText(this, it) }
                close()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (!list.isNullOrEmpty()) RelayService.sendFiles(this, list)
                close()
            }
            else -> {
                // Clipboard mode: wait for focus. Safety net in case focus never arrives.
                window.decorView.postDelayed({ grab() }, 1500)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val a = intent?.action
        if (hasFocus && (a == ACTION_SEND_CLIPBOARD || a == ACTION_AUTO)) window.decorView.post { grab() }
    }

    private fun grab() {
        if (handled || isFinishing) return
        handled = true
        sendClipboard(this, auto = intent?.action == ACTION_AUTO)
        close()
    }

    @Suppress("DEPRECATION")
    private fun close() {
        finish()
        overridePendingTransition(0, 0)
    }
}

/** Quick Settings tile: one tap sends whatever is on the clipboard. */
class SendTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Send clipboard"
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, ClipActivity::class.java)
            .setAction(ClipActivity.ACTION_SEND_CLIPBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            startActivityAndCollapse(i)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs(context)
        if (p.hasName && p.startOnBoot) {
            try { RelayService.start(context) } catch (_: Exception) {}
        }
    }
}

/** "Copy" button on a received-item notification, and "Stop" for Find my device. */
class CopyReceiver : BroadcastReceiver() {
    companion object { const val ACTION_STOP_RING = "com.midotheeng.relay.STOP_RING" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_RING) { RelayService.instance?.stopRing(); return }
        val item = History.get(intent.getLongExtra("id", -1)) ?: return
        val ok = if (item.kind == "text") Util.copyText(context, item.text.orEmpty())
        else item.uri?.let { Util.copyUri(context, Uri.parse(it), item.name ?: "file") } ?: false
        Toast.makeText(context, if (ok) "Copied" else "Couldn't copy", Toast.LENGTH_SHORT).show()
        val nid = intent.getIntExtra("nid", 0)
        if (nid != 0) context.getSystemService(NotificationManager::class.java).cancel(nid)
    }
}

/** Forwards this device's notifications to the other device (when turned on in Relay). */
class MirrorListener : android.service.notification.NotificationListenerService() {
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) {
        val svc = RelayService.instance ?: return
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if ((n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category in setOf(
                android.app.Notification.CATEGORY_PROGRESS, android.app.Notification.CATEGORY_SERVICE,
                android.app.Notification.CATEGORY_TRANSPORT, android.app.Notification.CATEGORY_SYSTEM,
                android.app.Notification.CATEGORY_NAVIGATION, android.app.Notification.CATEGORY_STATUS,
            )
        ) return
        val ex = n.extras
        val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return
        val text = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName }
        svc.mirror(sbn.packageName, app, sbn.key, title, text)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) {
        if (sbn.packageName == packageName) return
        RelayService.instance?.mirrorRemoved(sbn.key)
    }
}
