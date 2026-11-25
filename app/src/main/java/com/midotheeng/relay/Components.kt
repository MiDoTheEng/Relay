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

        /** Reads the clipboard (caller must have focus) and sends it. */
        fun sendClipboard(ctx: Context) {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            val clip = try { cm.primaryClip } catch (_: Exception) { null }
            val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val uri = item?.uri
            if (uri != null && item?.text.isNullOrEmpty()) {
                RelayService.sendFiles(ctx, listOf(uri))
                return
            }
            val t = try { item?.coerceToText(ctx)?.toString() } catch (_: Exception) { null }
            if (t.isNullOrEmpty()) Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            else RelayService.sendText(ctx, t)
        }
    }

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs(this).isSetUp) {
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
        if (hasFocus && intent?.action == ACTION_SEND_CLIPBOARD) window.decorView.post { grab() }
    }

    private fun grab() {
        if (handled || isFinishing) return
        handled = true
        sendClipboard(this)
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
        if (p.isSetUp && p.startOnBoot) {
            try { RelayService.start(context) } catch (_: Exception) {}
        }
    }
}

/** "Copy" button on a received-item notification. */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val item = History.get(intent.getLongExtra("id", -1)) ?: return
        val ok = if (item.kind == "text") Util.copyText(context, item.text.orEmpty())
        else item.uri?.let { Util.copyUri(context, Uri.parse(it), item.name ?: "file") } ?: false
        Toast.makeText(context, if (ok) "Copied" else "Couldn't copy", Toast.LENGTH_SHORT).show()
        val nid = intent.getIntExtra("nid", 0)
        if (nid != 0) context.getSystemService(NotificationManager::class.java).cancel(nid)
    }
}
