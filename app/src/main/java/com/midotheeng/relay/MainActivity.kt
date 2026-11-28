package com.midotheeng.relay

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Bumped every time Relay comes to the screen, so permission checks refresh. */
val resumeTick = MutableStateFlow(0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RelayTheme {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value = resumeTick.value + 1
    }
}

@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var named by remember { mutableStateOf(prefs.hasName) }
    if (!named) {
        NameScreen(prefs) { named = true; RelayService.start(ctx) }
    } else {
        LaunchedEffect(Unit) { RelayService.start(ctx) }
        HomeScreen(prefs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameScreen(prefs: Prefs, onDone: () -> Unit) {
    var name by remember { mutableStateOf(prefs.deviceName) }
    Scaffold(topBar = { TopAppBar(title = { Text("Welcome to Relay") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Relay links your Android devices: copy on one, paste on another, and send photos, files and links between them.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text("What should we call this device?", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Phone", "Tablet", "Laptop").forEach {
                    FilterChip(selected = name == it, onClick = { name = it }, label = { Text(it) })
                }
            }
            Button(
                onClick = { prefs.deviceName = name.trim().ifEmpty { "My device" }; onDone() },
                enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("Continue") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    val peers by Peers.state.collectAsState()
    val history by History.items.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(10_000); value = System.currentTimeMillis() }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) RelayService.sendFiles(ctx, uris)
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@rememberLauncherForActivityResult
        val name = RelayService.join(ctx, text)
        Toast.makeText(
            ctx, if (name != null) "Linked with $name ✓" else "That isn't a Relay code", Toast.LENGTH_LONG
        ).show()
        if (name != null) showAdd = false
    }
    val startScan = {
        scan.launch(
            ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Point at the code on your other device")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    if (showAdd) AddDeviceDialog(onScan = startScan, onClose = { showAdd = false })

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Relay")
                    Text(prefs.deviceName, style = MaterialTheme.typography.labelMedium)
                }
            })
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { CrashCard() }
            item { DevicesCard(peers.values.sortedByDescending { it.lastSeen }, now) { showAdd = true } }
            item { ChecklistCard(prefs) }
            item {
                Section("Send") {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        placeholder = { Text("Type or paste something…") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { RelayService.sendText(ctx, draft); draft = "" },
                            enabled = draft.isNotBlank()
                        ) { Text("Send text") }
                        FilledTonalButton(onClick = { ClipActivity.sendClipboard(ctx) }) { Text("Send clipboard") }
                    }
                    OutlinedButton(onClick = { pick.launch("*/*") }) { Text("Send photos or files") }
                }
            }
            item { WaysCard(ctx) }
            item { SettingsCard(prefs, ctx) }
            if (history.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("History", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { History.clear() }) { Text("Clear") }
                    }
                }
                items(history, key = { it.id }) { HistoryRow(it, now) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Shown after Relay crashed: lets the user copy the details and send them. */
@Composable
private fun CrashCard() {
    val ctx = LocalContext.current
    val file = remember { java.io.File(ctx.filesDir, "crash.txt") }
    var report by remember { mutableStateOf(if (file.exists()) file.readText() else null) }
    val r = report ?: return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Relay closed unexpectedly last time", style = MaterialTheme.typography.titleMedium)
            Text("Tap Copy report and paste it to Claude so it can be fixed.", style = MaterialTheme.typography.bodyMedium)
            Text(r.lines().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Relay crash", r))
                    Sync.lastText = r
                    Sync.lastWriteAt = android.os.SystemClock.elapsedRealtime()
                    Toast.makeText(ctx, "Report copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy report") }
                TextButton(onClick = { file.delete(); report = null }) { Text("Dismiss") }
            }
        }
    }
}

// ---------------- devices ----------------

@Composable
private fun DevicesCard(peers: List<Peer>, now: Long, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    val internet by RelayService.internetUp.collectAsState()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your devices", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (peers.isEmpty()) {
                Text(
                    "Nothing linked yet. Install Relay on your other device, open it, then tap “Add a device” on both: " +
                        "one shows a code, the other scans it. That's all.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            peers.forEach { p ->
                val wifi = now - p.lastLan < 3 * 60_000
                val online = wifi || now - p.lastSeen < 35 * 60_000
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).background(
                            when {
                                wifi -> Color(0xFF22C55E)
                                online -> Color(0xFF3B82F6)
                                else -> Color(0xFF9CA3AF)
                            }, CircleShape
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name + if (p.battery >= 0) "  ${if (p.charging) "⚡" else "🔋"} ${p.battery}%" else "",
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium
                        )
                        Text(
                            when {
                                wifi -> "Connected on Wi-Fi"
                                online -> "Connected over the internet"
                                else -> "Last seen " + DateUtils.getRelativeTimeSpanString(p.lastSeen, now, DateUtils.MINUTE_IN_MILLIS)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { RelayService.ring(ctx, p.id) }) { Text("Ring") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAdd) { Text("Add a device") }
                if (peers.isNotEmpty()) TextButton(onClick = { RelayService.hello(ctx) }) { Text("Refresh") }
            }
            if (!internet && Prefs(ctx).useInternet) {
                Text(
                    "Internet link is offline right now. Devices on the same Wi-Fi still work.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AddDeviceDialog(onScan: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val qr = remember { qrBitmap(RelayService.qrText(ctx)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add a device") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "On the other device, open Relay → Add a device → Scan a code, and point it at this:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Image(
                    bitmap = qr.asImageBitmap(), contentDescription = "Relay link code",
                    modifier = Modifier.size(240.dp).background(Color.White).padding(8.dp)
                )
                Text(
                    "Or scan the code shown on the other device instead. Either way works, and every device you add joins the same group.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { Button(onClick = onScan) { Text("Scan a code") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) AColor.BLACK else AColor.WHITE
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, size, 0, 0, size, size) }
}

// ---------------- permissions checklist ----------------

private class Check(
    val title: String,
    val why: String,
    val done: Boolean,
    val button: String,
    val onClick: () -> Unit,
    val extra: (@Composable () -> Unit)? = null,
)

@Composable
private fun ChecklistCard(prefs: Prefs) {
    val ctx = LocalContext.current
    val tick by resumeTick.collectAsState()
    val sp = remember { ctx.getSharedPreferences("relay2", Context.MODE_PRIVATE) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick.value++ }
    val isHuawei = Build.MANUFACTURER.equals("HUAWEI", true) || Build.MANUFACTURER.equals("HONOR", true)

    val checks = remember(tick) {
        val list = mutableListOf<Check>()
        if (Build.VERSION.SDK_INT >= 33) {
            list += Check(
                "Show what arrives",
                "Lets Relay tell you when something comes in.",
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                "Allow", { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
            )
        }
        list += Check(
            "Keep running in the background",
            "So things arrive even when Relay is closed.",
            ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
            "Allow", {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        )
        if (isHuawei) {
            list += Check(
                "Huawei: let Relay start by itself",
                "Settings → Battery → App launch → Relay: turn OFF “Manage automatically”, then turn ON Auto-launch, Secondary launch and Run in background.",
                sp.getBoolean("huaweiDone", false),
                "Open", {
                    sp.edit().putBoolean("huaweiDone", true).apply()
                    try {
                        ctx.startActivity(
                            Intent().setComponent(
                                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            )
        }
        list += Check(
            "Send what I copy, automatically",
            if (isHuawei)
                "Settings → Accessibility features → Accessibility → “Relay: send what I copy” → turn on. Then anything you copy is ready to paste on your other devices."
            else
                "Settings → Accessibility → (Downloaded apps / Installed apps) → “Relay: send what I copy” → turn on. Then anything you copy is ready to paste on your other devices.",
            ClipWatchService.isOn(ctx),
            "Turn on", {
                try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            },
            extra = {
                Text(
                    "Switch greyed out? Tap App info → ⋮ (top right) → “Allow restricted settings”, then try again.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("App info") }
            }
        )
        list
    }
    val todo = checks.filter { !it.done }

    Section(if (todo.isEmpty()) "All set ✓" else "Make it seamless (${checks.size - todo.size}/${checks.size})") {
        if (todo.isEmpty()) {
            Text("Relay has everything it needs on this device.", style = MaterialTheme.typography.bodyMedium)
        }
        todo.forEach { c ->
            HorizontalDivider()
            Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(c.why, style = MaterialTheme.typography.bodySmall)
            Button(onClick = c.onClick) { Text(c.button) }
            c.extra?.invoke()
        }
    }
}

// ---------------- ways to send ----------------

@Composable
private fun WaysCard(ctx: Context) {
    Section("Ways to send") {
        Tip("•", "Copy anything. With auto-send on, it's ready to paste on your other devices.")
        Tip("•", "Select text → tap “Relay” in the menu (look under ⋮ if you don't see it).")
        Tip("•", "Share any photo, video, file or link → tap a device at the top of the share menu, or Relay.")
        Tip("•", "The “Send clipboard” button on Relay's notification or Quick Settings tile.")
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(onClick = { addTile(ctx) }) { Text("Add the Quick Settings tile") }
        }
    }
}

@Composable
private fun Tip(n: String, text: String) {
    Row {
        Text(n, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@SuppressLint("NewApi")
private fun addTile(ctx: Context) {
    try {
        ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(ctx, SendTileService::class.java),
            "Send clipboard",
            Icon.createWithResource(ctx, R.drawable.ic_tile),
            ctx.mainExecutor
        ) { }
    } catch (_: Exception) {
        Toast.makeText(ctx, "Pull down Quick Settings → edit → add “Send clipboard”", Toast.LENGTH_LONG).show()
    }
}

// ---------------- settings ----------------

@Composable
private fun SettingsCard(prefs: Prefs, ctx: Context) {
    val tick by resumeTick.collectAsState()
    var autoCopy by remember { mutableStateOf(prefs.autoCopy) }
    var autoSend by remember { mutableStateOf(prefs.autoSend) }
    var internet by remember { mutableStateOf(prefs.useInternet) }
    var links by remember { mutableStateOf(prefs.openLinks) }
    var mirror by remember { mutableStateOf(prefs.mirrorNotifs) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var name by remember { mutableStateOf(prefs.deviceName) }
    var confirmUnlink by remember { mutableStateOf(false) }
    val listenerOn = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink this device?") },
            text = { Text("This device will stop syncing with all your other devices. You can link again with a code.") },
            confirmButton = {
                Button(onClick = { RelayService.unlinkAll(ctx); confirmUnlink = false }) { Text("Unlink") }
            },
            dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Cancel") } }
        )
    }

    Section("Settings") {
        SwitchRow("Copy what arrives to the clipboard", autoCopy) { autoCopy = it; prefs.autoCopy = it }
        SwitchRow("Send what I copy automatically", autoSend) { autoSend = it; prefs.autoSend = it }
        SwitchRow("Use the internet when not on the same Wi-Fi", internet) {
            internet = it; prefs.useInternet = it; RelayService.hello(ctx)
        }
        Text(
            "Encrypted with a key only your devices have, and passed through the free ntfy.sh relay.",
            style = MaterialTheme.typography.bodySmall
        )
        SwitchRow("Open links that arrive here right away", links) { links = it; prefs.openLinks = it }
        SwitchRow("Show this device's notifications on my other devices", mirror) {
            mirror = it
            prefs.mirrorNotifs = it
            if (it && !listenerOn) ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (mirror && !listenerOn) {
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text("Allow notification access")
            }
        }
        SwitchRow("Start when the device turns on", boot) { boot = it; prefs.startOnBoot = it }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("This device's name") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { prefs.deviceName = name.trim().ifEmpty { "My device" }; RelayService.hello(ctx) },
                enabled = name.isNotBlank() && name.trim() != prefs.deviceName
            ) { Text("Save") }
        }
        TextButton(onClick = { confirmUnlink = true }) {
            Text("Unlink this device", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------- history ----------------

@Composable
private fun HistoryRow(item: Item, now: Long) {
    val ctx = LocalContext.current
    Card(
        Modifier.fillMaxWidth().clickable {
            if (item.kind == "text") {
                if (Util.copyText(ctx, item.text.orEmpty()) && Build.VERSION.SDK_INT < 33)
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            } else item.uri?.let {
                try { ctx.startActivity(Util.viewIntent(Uri.parse(it), item.mime)) } catch (_: Exception) {
                    Toast.makeText(ctx, "Can't open this file anymore", Toast.LENGTH_SHORT).show()
                }
            }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                (if (item.outgoing) "↑ to " else "↓ from ") + item.peer + " · " +
                    DateUtils.getRelativeTimeSpanString(item.time, now, DateUtils.MINUTE_IN_MILLIS),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (item.kind == "text") item.text.orEmpty() else "📎 ${item.name}",
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
