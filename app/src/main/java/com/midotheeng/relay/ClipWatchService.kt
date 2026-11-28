package com.midotheeng.relay

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Auto clipboard sync (Accessibility permission).
 *
 * Android hides the clipboard from background apps. This service notices the moment you copy:
 * you tap a "Copy" button, an app says "Copied", or Android shows its own "copied" preview.
 * Then it gives a 1-pixel invisible window focus for a split second, which is enough
 * to read the clipboard legally, and sends it to your other devices.
 */
class ClipWatchService : AccessibilityService() {

    companion object {
        private const val TAG = "RelayClip"

        fun isOn(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val full = "${ctx.packageName}/${ClipWatchService::class.java.name}"
            val short = "${ctx.packageName}/.ClipWatchService"
            return s.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }

        // Button labels that mean "copy" (several languages).
        private val COPY_WORDS = listOf(
            "copy", "copy text", "copy link", "copy link address", "copy message", "copy to clipboard",
            "نسخ", "نسخ النص", "نسخ الرابط", "نسخ الرابط", "kopieren", "copier", "copiar", "copia", "kopyala", "复制", "копировать",
        )

        // Messages apps show after copying.
        private val COPIED_WORDS = listOf(
            "copied", "copy to clipboard", "clipboard", "تم النسخ", "نُسخ", "الحافظة", "kopiert", "copié", "copiado", "copiato",
            "kopyalandı", "已复制", "скопировано",
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L
    private var lastSelection: String? = null
    private var lastSelectionAt = 0L
    private var overlay: View? = null
    @Volatile private var waitingForFocus = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutoLog.connected.value = true
        AutoLog.add("Auto-send is on and listening")
        RelayService.start(this)
    }

    override fun onInterrupt() {}

    private var clipboardWindowShown = false

    private fun isOurNotice(t: String) = t.contains("pasted") || t.contains("relay") || t.contains("لصق")

    private fun mentionsCopied(t: String) = !isOurNotice(t) && COPIED_WORDS.any { t.contains(it) }

    /** Remembers signals that might be a copy, so the app can show what this phone does when you copy. */
    private fun note(kind: String, e: AccessibilityEvent, label: String) {
        val l = label.take(30).ifEmpty { "(no label)" }
        AutoLog.signal("$kind “$l” · ${appName(e)}")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        if (e.packageName == packageName) return
        AutoLog.events.value = AutoLog.events.value + 1
        try {
            when (e.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    val label = labelOf(e)
                    AutoLog.lastEvent.value = "tap on “${label.take(30).ifEmpty { "(no label)" }}” in ${appName(e)}"
                    val viewId = try { e.source?.viewIdResourceName?.lowercase() } catch (_: Exception) { null } ?: ""
                    val byId = viewId.endsWith("/copy") || viewId.contains("copy")
                    val byLabel = label.length <= 60 && COPY_WORDS.any {
                        label == it || label.startsWith("$it ") || label.endsWith(" $it") || label.contains(" $it ")
                    }
                    if (byId || byLabel) {
                        note("Tap", e, label.ifEmpty { viewId })
                        trigger(usedSelection = true, where = "tapped “${label.ifEmpty { viewId }}” in ${appName(e)}")
                    }
                }
                // Spoken announcements, e.g. Android or an app saying "Copied".
                AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                    val t = labelOf(e)
                    note("Announcement", e, t)
                    if (mentionsCopied(t)) trigger(usedSelection = false, where = "“$t” announced by ${appName(e)}")
                }
                // Toasts like "Copied to clipboard" / "Link copied".
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (e.className?.toString()?.contains("Toast") == true) {
                        val t = labelOf(e)
                        note("Message", e, t)
                        if (mentionsCopied(t)) trigger(usedSelection = false, where = "“$t” message in ${appName(e)}")
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val t = labelOf(e) + " " + (e.className ?: "").toString().lowercase()
                    val pane = if (android.os.Build.VERSION.SDK_INT >= 28) e.source?.paneTitle?.toString()?.lowercase() ?: "" else ""
                    val sys = e.packageName == "com.android.systemui"
                    if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && sys) note("System window", e, t.trim())
                    // Android 13+ clipboard preview, snackbars like "Message copied", etc.
                    if ((sys && (t.contains("clipboard") || pane.contains("clipboard"))) || mentionsCopied(t) || mentionsCopied(pane)) {
                        trigger(usedSelection = false, where = if (sys) "Android's copied preview" else "“${t.trim().take(30)}” in ${appName(e)}")
                    }
                }
                // Android 13+: the clipboard preview appears as its own window.
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    val shown = try {
                        windows.any { w ->
                            val title = w.title?.toString()?.lowercase() ?: ""
                            title.contains("clipboard") || title.contains("copied")
                        }
                    } catch (_: Exception) { false }
                    if (shown && !clipboardWindowShown) {
                        AutoLog.signal("Clipboard preview window appeared")
                        trigger(usedSelection = false, where = "Android's clipboard preview window")
                    }
                    clipboardWindowShown = shown
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    val text = e.text?.joinToString("") ?: return
                    val from = minOf(e.fromIndex, e.toIndex)
                    val to = maxOf(e.fromIndex, e.toIndex)
                    if (from in 0 until to && to <= text.length) {
                        lastSelection = text.substring(from, to)
                        lastSelectionAt = SystemClock.elapsedRealtime()
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "event", ex)
        }
    }

    private fun labelOf(e: AccessibilityEvent): String =
        ((e.text?.joinToString(" ") ?: "") + " " + (e.contentDescription ?: "")).trim().lowercase()

    private var triggerUsedSelection = false

    private fun appName(e: AccessibilityEvent): String = try {
        val pkg = e.packageName?.toString() ?: return "an app"
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { e.packageName?.toString() ?: "an app" }

    private fun trigger(usedSelection: Boolean, where: String) {
        val p = Prefs(this)
        if (!p.autoSend) { AutoLog.add("Copy noticed ($where), but auto-send is off in Settings"); return }
        if (Peers.all().isEmpty()) { AutoLog.add("Copy noticed, but no devices are linked"); return }
        val now = SystemClock.elapsedRealtime()
        if (now - Sync.lastWriteAt < 2500) return // that was us pasting something we received
        if (now - lastTrigger < 700) return
        AutoLog.add("Copy noticed: $where")
        lastTrigger = now
        triggerUsedSelection = usedSelection
        // Give the app a moment to actually put the text on the clipboard.
        main.postDelayed({ grab() }, 250)
    }

    /** Briefly takes focus with an invisible 1-pixel window so Android lets us read the clipboard. */
    private fun grab() {
        try {
            val wm = getSystemService(WindowManager::class.java)
            removeOverlay()
            val v = object : View(this) {
                override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                    super.onWindowFocusChanged(hasWindowFocus)
                    if (hasWindowFocus && waitingForFocus) {
                        waitingForFocus = false
                        readAndSend()
                        main.post { removeOverlay() }
                    }
                }
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            waitingForFocus = true
            wm.addView(v, lp)
            overlay = v
            main.postDelayed({
                if (waitingForFocus) {
                    waitingForFocus = false
                    removeOverlay()
                    fallback()
                }
            }, 600)
        } catch (ex: Exception) {
            Log.w(TAG, "overlay", ex)
            waitingForFocus = false
            fallback()
        }
    }

    private fun readAndSend() {
        try {
            AutoLog.add("Read clipboard: " + ClipActivity.sendClipboard(this, auto = true))
        } catch (ex: Exception) {
            AutoLog.add("Couldn't read clipboard: ${ex.message}")
            Log.w(TAG, "read", ex)
        }
    }

    /** If the invisible window didn't get focus, use an invisible screen instead, or the selected text. */
    private fun fallback() {
        val sel = lastSelection
        if (triggerUsedSelection && sel != null && SystemClock.elapsedRealtime() - lastSelectionAt < 15_000) {
            AutoLog.add("Using the selected text instead")
            if (sel != Sync.lastText) {
                Sync.lastText = sel
                RelayService.sendText(this, sel, quiet = true)
            }
            return
        }
        AutoLog.add("Invisible window didn't get focus; trying an invisible screen")
        try {
            startActivity(
                Intent(this, ClipActivity::class.java).setAction(ClipActivity.ACTION_AUTO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (ex: Exception) {
            Log.w(TAG, "fallback", ex)
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutoLog.connected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutoLog.connected.value = false
        removeOverlay()
        super.onDestroy()
    }

    @Suppress("unused")
    private fun clipboard() = getSystemService(ClipboardManager::class.java)
}
