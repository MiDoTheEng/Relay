package com.midotheeng.relay

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.core.app.NotificationManagerCompat
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
        // Starting the clipboard watcher while on screen lets Android show its one-time "allow logs" prompt.
        RelayService.instance?.startWatcher()
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
    var setUp by remember { mutableStateOf(prefs.isSetUp) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (!setUp) {
        SetupScreen(prefs) {
            setUp = true
            RelayService.start(ctx)
            RelayService.hello(ctx)
        }
    } else {
        LaunchedEffect(Unit) { RelayService.start(ctx) }
        HomeScreen(prefs) { setUp = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(prefs: Prefs, onDone: () -> Unit) {
    var name by remember { mutableStateOf(prefs.deviceName) }
    var code by remember { mutableStateOf(prefs.pairCode) }
    Scaffold(topBar = { TopAppBar(title = { Text("Set up Relay") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Install Relay on your phone and your tablet, then fill this in on each one. " +
                    "Both devices need to be on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("This device's name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Phone", "Tablet").forEach {
                    FilterChip(selected = name == it, onClick = { name = it }, label = { Text(it) })
                }
            }
            OutlinedTextField(
                value = code, onValueChange = { code = it.trim() },
                label = { Text("Pair code") }, singleLine = true,
                supportingText = {
                    Text("Make one up (4+ characters) and type the SAME code on both devices. It encrypts everything so only your devices can read it.")
                },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val changed = code != prefs.pairCode
                    prefs.deviceName = name.trim().ifEmpty { "My device" }
                    prefs.pairCode = code
                    if (changed) Peers.clear()
                    onDone()
                },
                enabled = code.length >= 4 && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(prefs: Prefs, onEditPairing: () -> Unit) {
    val ctx = LocalContext.current
    val peers by Peers.state.collectAsState()
    val history by History.items.collectAsState()
    val running by RelayService.running.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(10_000); value = System.currentTimeMillis() }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) RelayService.sendFiles(ctx, uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Relay")
                        Text(prefs.deviceName, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        RelayService.hello(ctx)
                        Toast.makeText(ctx, "Looking for devices…", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Find devices") }
                }
            )
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
            item { StatusCard(running, peers.values.sortedByDescending { it.lastSeen }, now) }
            item { UniversalClipboardCard(prefs, ctx) }
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
            item { HowToCard(ctx) }
            item { ExtrasCard(prefs, ctx) }
            item { SettingsCard(prefs, ctx, onEditPairing) }
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

@Composable
private fun StatusCard(running: Boolean, peers: List<Peer>, now: Long) {
    // Only "Linked" if the other device actually answered in the last 3 minutes.
    val linked = peers.any { now - it.lastSeen < 3 * 60_000 }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (linked) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    !running -> "Starting…"
                    linked -> "Linked"
                    peers.isNotEmpty() -> "Can't reach ${peers.first().name} right now"
                    else -> "Looking for your other device…"
                },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
            )
            if (!linked) {
                Text(
                    "Open Relay on the other device (same Wi-Fi, same pair code, latest version). " +
                        "On the Huawei tablet also allow Relay in Settings → Battery → App launch. Then tap ⟳ above.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            peers.forEach { p ->
                val fresh = now - p.lastSeen < 5 * 60_000
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).background(
                            if (fresh) Color(0xFF22C55E) else Color(0xFF9CA3AF), CircleShape
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name + if (p.battery >= 0) "  ${if (p.charging) "⚡" else "🔋"} ${p.battery}%" else "",
                            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${p.host} · seen " + DateUtils.getRelativeTimeSpanString(p.lastSeen, now, DateUtils.MINUTE_IN_MILLIS),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    val ctx = LocalContext.current
                    FilledTonalButton(onClick = { RelayService.ring(ctx) }) { Text("Ring") }
                }
            }
            val ips = Util.localIps()
            if (ips.isNotEmpty()) Text("This device: ${ips.joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HowToCard(ctx: Context) {
    Section("Send without opening Relay") {
        Tip("1", "Select any text → tap “Relay” in the menu (look under ⋮ if you don't see it). It goes straight to your other device and is already copied there.")
        Tip("2", "After copying something, tap the “Send clipboard” tile in Quick Settings, or the button on Relay's notification.")
        Tip("3", "Share any photo, video, file or link → tap your other device's icon at the top of the share menu (or Relay).")
        Tip("4", "Anything that arrives is copied to the clipboard automatically — just paste. Photos are also saved to Pictures/Relay.")
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(onClick = { addTile(ctx) }) { Text("Add the Quick Settings tile") }
        } else {
            Text(
                "To add the tile: pull down Quick Settings → edit (pencil) → drag “Send clipboard” in.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Tip(n: String, text: String) {
    Row {
        Text("$n.", fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
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

@Composable
private fun SettingsCard(prefs: Prefs, ctx: Context, onEditPairing: () -> Unit) {
    var autoCopy by remember { mutableStateOf(prefs.autoCopy) }
    var boot by remember { mutableStateOf(prefs.startOnBoot) }
    var manual by remember { mutableStateOf(prefs.manualPeer) }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val tick by resumeTick.collectAsState()
    val unrestricted = remember(tick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }

    Section("Settings") {
        SwitchRow("Copy received items to clipboard", autoCopy) { autoCopy = it; prefs.autoCopy = it }
        SwitchRow("Start when the device turns on", boot) { boot = it; prefs.startOnBoot = it }
        HorizontalDivider()
        if (!unrestricted) {
            Text(
                "Let Relay run in the background, or the system may stop it and items won't arrive.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = {
                try {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    )
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) { Text("Allow background running") }
        }
        if (Build.MANUFACTURER.equals("HUAWEI", true) || Build.MANUFACTURER.equals("HONOR", true)) {
            Text(
                "Huawei tablet: also go to Settings → Battery → App launch → Relay → turn off “Manage automatically” and turn on all three switches.",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = {
                try {
                    ctx.startActivity(Intent().setComponent(
                        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
            }) { Text("Open App launch settings") }
        }
        HorizontalDivider()
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it.trim(); prefs.manualPeer = it },
            label = { Text("Other device's IP (only if it isn't found)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = onEditPairing) { Text("Change name or pair code") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

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

@Composable
private fun UniversalClipboardCard(prefs: Prefs, ctx: Context) {
    val state by RelayService.clipState.collectAsState()
    var enabled by remember { mutableStateOf(prefs.universalClipboard) }
    Section("Universal Clipboard") {
        SwitchRow("Copy here, paste there — automatically", enabled) {
            enabled = it
            prefs.universalClipboard = it
            RelayService.instance?.startWatcher() ?: RelayService.hello(ctx)
        }
        if (!enabled) return@Section
        when (state) {
            "active" -> Text(
                "On ✓  Copy anything on this device and it's ready to paste on your other device. " +
                    "Do this setup on both devices to make it work both ways.",
                style = MaterialTheme.typography.bodyMedium
            )
            "no_logs" -> SelfSetup(ctx)
            "no_overlay" -> {
                Text(
                    "Almost there: allow Relay to display over other apps. It uses an invisible 1-pixel window to read the clipboard the moment you copy.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                }) { Text("Allow display over other apps") }
            }
            "needs_approval" -> {
                Text(
                    "Tap Turn on. If Android asks “Allow Relay to access all device logs?”, choose Allow. " +
                        "Newer Android asks again after each restart — Relay's notification will remind you.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = { RelayService.instance?.startWatcher() ?: RelayService.start(ctx) }) { Text("Turn on") }
            }
            else -> Text("Starting…", style = MaterialTheme.typography.bodyMedium)
        }
        PeerSetup(ctx)
    }
}

@Composable
private fun ExtrasCard(prefs: Prefs, ctx: Context) {
    val tick by resumeTick.collectAsState()
    var mirror by remember { mutableStateOf(prefs.mirrorNotifs) }
    var links by remember { mutableStateOf(prefs.openLinks) }
    val listenerOn = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }
    val overlayOn = remember(tick) { Settings.canDrawOverlays(ctx) }
    Section("Ecosystem") {
        SwitchRow("Show this device's notifications on the other one", mirror) {
            mirror = it
            prefs.mirrorNotifs = it
            if (it && !listenerOn) ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (mirror && !listenerOn) {
            Text("Give Relay notification access so it can forward them.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text("Allow notification access")
            }
        }
        Text(
            "Dismissing one on this device clears it on the other. Tapping it opens the same app there, if it's installed.",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        SwitchRow("Open links that arrive here straight away", links) { links = it; prefs.openLinks = it }
        if (links && !overlayOn) {
            Text(
                "Needs “Display over other apps” (same as Universal Clipboard), or links will only show as a notification.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** This device isn't unlocked yet: show only the step that's needed now. */
@Composable
private fun SelfSetup(ctx: Context) {
    val tick by resumeTick.collectAsState()
    val peers by Peers.state.collectAsState()
    val devOn = remember(tick) { AdbSetup.isDevOptionsOn(ctx) }
    val other = peers.values.maxByOrNull { it.lastSeen }?.name ?: "other device"
    var asked by remember { mutableStateOf(false) }
    var skipDev by remember { mutableStateOf(false) }

    Text(
        "One-time unlock. Your $other does it for you over Wi-Fi, so you don't need a computer.",
        style = MaterialTheme.typography.bodyMedium
    )
    when {
        peers.isEmpty() -> Text(
            "First, link your other device (open Relay on it with the same pair code).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error
        )
        !devOn && !skipDev -> {
            Tip("1", "Turn on Developer options: tap the button, find “Build number” and tap it 7 times. Then come back here.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    try { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Exception) {}
                }) { Text("Open About") }
                TextButton(onClick = { skipDev = true }) { Text("It's already on →") }
            }
        }
        else -> {
            Tip("1", "Tap the button. Turn on Wireless debugging, then tap “Pair device with pairing code”.")
            Tip("2", "Leave the code on screen and type it into Relay on your $other (a notification pops up there).")
            Button(onClick = {
                RelayService.requestSetup(ctx)
                asked = true
                if (!AdbSetup.openWirelessDebugging(ctx)) {
                    Toast.makeText(ctx, "Open Settings → Developer options → Wireless debugging", Toast.LENGTH_LONG).show()
                }
            }) { Text("Open Wireless debugging") }
            if (asked) Text(
                "Your $other is waiting for the code.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** On this device: type in the code shown on another device to unlock it. */
@Composable
private fun PeerSetup(ctx: Context) {
    val peers by Peers.state.collectAsState()
    val states by AdbSetup.state.collectAsState()
    val waiting = peers.values.filter { it.uc == "no_logs" || states.containsKey(it.host) }
    waiting.forEach { p -> key(p.host) { PeerSetupRow(ctx, p, states[p.host]) } }
}

@Composable
private fun PeerSetupRow(ctx: Context, p: Peer, st: String?) {
    var code by remember { mutableStateOf("") }
    HorizontalDivider()
    Text("Unlock ${p.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (st == "done") {
        Text("Done ✓ ${p.name} is restarting with Universal Clipboard on.", color = MaterialTheme.colorScheme.primary)
    } else {
        Text(
            "On ${p.name}, open Relay and tap “Open Wireless debugging”, then type the 6-digit code it shows:",
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = code, onValueChange = { code = it },
                placeholder = { Text("123456") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { AdbSetup.setupPeer(ctx, p.host, p.name, code) },
                enabled = code.count { it.isDigit() } >= 6 && st?.startsWith("working") != true
            ) { Text("Unlock") }
        }
        if (st != null && st.startsWith("working:")) Text(st.removePrefix("working:"), color = MaterialTheme.colorScheme.primary)
        if (st != null && st.startsWith("error:")) Text(st.removePrefix("error:"), color = MaterialTheme.colorScheme.error)
    }
}
