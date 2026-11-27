// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.toast
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.radio.BleScanner
import org.highbridge.app.ui.BridgeSpans
import org.highbridge.app.ui.Gap
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.ProtocolBadge
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.MeshCorePresets
import org.highbridge.core.protocol.MeshtasticLoRaConfig
import org.highbridge.core.protocol.MeshtasticModemPreset
import org.highbridge.core.protocol.MeshtasticRegion
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.StPaulQuickJoin
import org.highbridge.core.protocol.link.UsbDevices
import org.highbridge.feature.bridge.BridgeMode
import org.highbridge.feature.bridge.BridgeModeStore
import org.highbridge.feature.bridge.BridgePrefs
import org.highbridge.feature.bridge.MeshBridgeService
import java.util.Locale


/** Region picker for every LoRa band, plus radio settings for each connected protocol. */
@Composable
fun RadioScreen() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val region by app.prefs.region.collectAsState()
    val radios by app.radios.radios.collectAsState()
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val country = remember { Locale.getDefault().country }
    val suggested = remember { MeshtasticRegion.suggestFor(country) }
    var preset by remember { mutableStateOf(MeshtasticModemPreset.MEDIUM_FAST) }
    var power by remember { mutableIntStateOf(0) }
    var mcPreset by remember { mutableStateOf(MeshCorePresets.suggestFor(country) ?: MeshCorePresets.ALL.first()) }
    var mcPower by remember { mutableIntStateOf(20) }

    val groups = listOf(
        "Americas" to setOf("US", "BR_902"),
        "Europe, Africa, Middle East" to setOf("EU_868", "EU_433", "UA_868", "UA_433", "RU", "KZ_433", "KZ_863"),
        "Asia & Pacific" to setOf("ANZ", "ANZ_433", "NZ_865", "JP", "KR", "TW", "IN", "CN", "TH", "MY_433", "MY_919", "SG_923", "PH_433", "PH_868", "PH_915", "NP_865"),
        "Anywhere (2.4 GHz radios)" to setOf("LORA_24"),
    )
    val featured = setOf("US", "BR_902", "EU_868", "EU_433", "UA_868", "ANZ", "JP", "IN", "CN", "LORA_24")
    val visible = MeshtasticRegion.entries.filter { it != MeshtasticRegion.UNSET }.filter {
        query.isBlank() || it.label.contains(query, true) || it.name.contains(query, true) || it.countries.any { c -> c.equals(query, true) } || it.bandLabel.contains(query)
    }.filter { showAll || query.isNotBlank() || it.name in featured || it == region }

    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("Radio & region", radios.values.joinToString(" · ") { it.deviceInfo.value ?: it.protocol.shortTag }.ifBlank { "No radio connected" }) }
        item {
            Column(Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp)) {
                HbCard {
                    Text(if (suggested != null) "Your phone says you're in ${Locale("", country).displayCountry}" else "Pick the band that's legal where you are",
                        fontWeight = FontWeight.Bold)
                    Text(if (suggested != null) "We suggest ${suggested.name} · ${suggested.bandLabel}. Pick a different region if you're traveling or near a border. Only use the band that's legal where your radio is."
                        else "LoRa bands differ by country. Choose your region below.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (suggested != null) Button(onClick = { app.prefs.setRegion(suggested) }) { Text("Use ${suggested.name}") }
                        OutlinedButton(onClick = {
                            app.prefs.setRegion(MeshtasticRegion.US); preset = MeshtasticModemPreset.MEDIUM_FAST; mcPreset = StPaulQuickJoin.meshCore
                            scope.launch {
                                app.radios.meshtastic?.applyLoRa(StPaulQuickJoin.meshtastic)?.let { r -> ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "Meshtastic set to US · Medium Fast") }
                                app.radios.meshcore?.applyPreset(StPaulQuickJoin.meshCore, 22)?.let { r -> ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "MeshCore set to USA / Canada") }
                            }
                        }) { Text("Join Saint Paul Mesh") }
                    }
                }
                Gap(12)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search a country or frequency") }, singleLine = true)
            }
        }
        for ((label, names) in groups) {
            val rows = visible.filter { it.name in names }
            if (rows.isEmpty()) continue
            item { SectionLabel(label, Modifier.padding(top = 8.dp)) }
            items(rows) { r ->
                Row(Modifier.fillMaxWidth().background(if (r == region) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                    .clickable { app.prefs.setRegion(r) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(r == region, { app.prefs.setRegion(r) })
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontWeight = FontWeight.Bold)
                        Text("${r.name} · ${r.bandLabel} · up to ${r.powerLimitDbm} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.hasDutyCycleLimit) Text("${r.dutyCyclePercent}% airtime", Modifier.background(Color4E1, RoundedCornerShape(4.dp)).padding(6.dp, 3.dp),
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color6B4)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
        item { TextButton(onClick = { showAll = !showAll }, Modifier.padding(horizontal = 12.dp)) { Text(if (showAll) "Show fewer regions" else "Show all ${MeshtasticRegion.entries.size - 1} regions") } }

        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HbCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ProtocolBadge(ProtocolType.MESHTASTIC); Text("Meshtastic radio settings", fontWeight = FontWeight.Bold) }
                    var open by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Preset: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { open = true }) { Text(preset.label) }
                        DropdownMenu(open, { open = false }) { MeshtasticModemPreset.entries.forEach { p -> DropdownMenuItem({ Text(p.label) }, { preset = p; open = false }) } }
                    }
                    Text("Transmit power: ${if (power == 0) "region maximum (${region.powerLimitDbm} dBm)" else "$power dBm"}")
                    Slider(power.toFloat(), { power = it.toInt() }, valueRange = 0f..region.powerLimitDbm.toFloat().coerceAtLeast(1f))
                    Text("Capped at your region's legal limit. Lower power saves battery.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = radios.containsKey(ProtocolType.MESHTASTIC) && region != MeshtasticRegion.UNSET, onClick = {
                        scope.launch { app.radios.meshtastic?.applyLoRa(MeshtasticLoRaConfig(region, preset, 3, power))?.let { ctx.toast(long = true, message = it.exceptionOrNull()?.message ?: "Sent to the radio. It will restart.") } }
                    }) { Text("Apply to Meshtastic radio") }
                }
                HbCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ProtocolBadge(ProtocolType.MESHCORE); Text("MeshCore radio preset", fontWeight = FontWeight.Bold) }
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = true }) { Text(mcPreset.name + " · %.3f MHz".format(mcPreset.frequencyMHz)) }
                    DropdownMenu(open, { open = false }) { MeshCorePresets.ALL.forEach { p -> DropdownMenuItem({ Text("${p.name} · %.3f MHz".format(p.frequencyMHz)) }, { mcPreset = p; open = false }) } }
                    Text("SF ${mcPreset.spreadingFactor} · BW ${mcPreset.bandwidthKhz} kHz · CR ${mcPreset.codingRate}", fontSize = 12.sp)
                    val problems = MeshCorePresets.validate(mcPreset)
                    if (problems.isEmpty()) Text("Fits inside the ${mcPreset.band.name} band", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    else problems.forEach { Text(it, color = StPaulMeshPalette.Danger) }
                    Text("Transmit power: $mcPower dBm"); Slider(mcPower.toFloat(), { mcPower = it.toInt() }, valueRange = 1f..minOf(22, mcPreset.band.powerLimitDbm).toFloat())
                    Button(enabled = radios.containsKey(ProtocolType.MESHCORE) && problems.isEmpty(), onClick = {
                        scope.launch { app.radios.meshcore?.applyPreset(mcPreset, mcPower)?.let { ctx.toast(long = true, message = it.exceptionOrNull()?.message ?: "MeshCore radio updated") } }
                    }) { Text("Apply to MeshCore radio") }
                }
                HbCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ProtocolBadge(ProtocolType.RETICULUM); Text("RNode (Reticulum)", fontWeight = FontWeight.Bold) }
                    val p = app.radios.rnodeParamsForRegion()
                    Text("An RNode is set to %.3f MHz · 125 kHz · SF8 · CR5 · ${p.txPowerDbm} dBm when it connects, inside your region's band.".format(p.frequencyHz / 1e6),
                        style = MaterialTheme.typography.bodyMedium)
                }
                Gap(24)
            }
        }
    }
}

private val Color4E1 = androidx.compose.ui.graphics.Color(0xFFF4E1C1)
private val Color6B4 = androidx.compose.ui.graphics.Color(0xFF6B4309)

@Composable
fun BridgeScreen() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val radios by app.radios.radios.collectAsState()
    var enabled by remember { mutableStateOf(BridgePrefs.isEnabled(ctx)) }
    var mode by remember { mutableStateOf(MeshBridgeService.current?.mode?.value ?: BridgeModeStore.mode) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled) { while (true) { kotlinx.coroutines.delay(1500); tick++ } }
    val engine = remember(tick, enabled) { MeshBridgeService.current }
    val stats = engine?.stats?.collectAsState()?.value
    val pairs = when (mode) {
        BridgeMode.MESHTASTIC_MESHCORE -> setOf(ProtocolType.MESHTASTIC to ProtocolType.MESHCORE)
        BridgeMode.MESHTASTIC_RETICULUM -> setOf(ProtocolType.MESHTASTIC to ProtocolType.RETICULUM)
        BridgeMode.MESHCORE_RETICULUM -> setOf(ProtocolType.MESHCORE to ProtocolType.RETICULUM)
        BridgeMode.ALL_THREE -> setOf(ProtocolType.MESHTASTIC to ProtocolType.MESHCORE, ProtocolType.MESHCORE to ProtocolType.RETICULUM, ProtocolType.MESHTASTIC to ProtocolType.RETICULUM)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("The Bridge", "Carry public messages from one mesh to another") }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) "Bridge is on" else "Bridge is off", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        Text("Keeps running in the background, even after a restart", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .85f), fontSize = 12.sp)
                    }
                    Switch(enabled, { on ->
                        enabled = on
                        if (on) { BridgeModeStore.mode = mode; MeshBridgeService.enableAndStart(ctx) } else MeshBridgeService.disable(ctx)
                    })
                }
                HbCard {
                    BridgeSpans(pairs)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        supportedProtocols.forEach { p ->
                            Text("${p.shortTag} ${if (radios.containsKey(p)) "connected" else "offline"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SectionLabel("Which networks", Modifier.padding(0.dp))
                supportedBridgeModes.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable {
                        mode = m; BridgeModeStore.mode = m; engine?.setBridgeMode(m)
                    }, verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == m, { mode = m; BridgeModeStore.mode = m; engine?.setBridgeMode(m) }); Text(m.label) }
                }
                SectionLabel("Safety rules", Modifier.padding(0.dp))
                Text("• Direct messages are never bridged\n• Messages are tagged with where they came from, like [MC]\n• Long messages are trimmed to fit the other network (160 bytes on MeshCore)\n• Echoes are stopped so nothing loops forever", style = MaterialTheme.typography.bodyMedium)
                if (stats != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("${stats.forwardedCount}", "relayed", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    StatBox("${stats.loopsBlocked}", "echoes stopped", StPaulMeshPalette.Bronze, Modifier.weight(1f))
                    StatBox("${stats.failedCount}", "failed to send", StPaulMeshPalette.Danger, Modifier.weight(1f))
                }
                stats?.lastEvent?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun StatBox(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color); Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Find radios over Bluetooth or USB-C, or start Reticulum over Wi-Fi. */
@Composable
fun ConnectScreen() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val scanner = remember { BleScanner(ctx) }
    val found by scanner.found.collectAsState()
    val radios by app.radios.radios.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var usbList by remember { mutableStateOf(UsbDevices.drivers(ctx.getSystemService(UsbManager::class.java))) }
    DisposableEffect(Unit) { runCatching { scanner.start() }; onDispose { scanner.stop() } }

    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("Connect a radio", "Bluetooth, USB-C, or Reticulum over Wi-Fi", onBack = { nav.popBackStack() }) }
        if (radios.isNotEmpty()) {
            item { SectionLabel("Connected") }
            items(radios.values.toList()) { r ->
                val st by r.connectionState.collectAsState()
                val dev by r.deviceInfo.collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProtocolBadge(r.protocol)
                    Column(Modifier.weight(1f)) { Text(dev ?: r.protocol.name, fontWeight = FontWeight.Bold); Text(if (st is ConnectionState.Connected) "Connected" else st.toString(), fontSize = 12.sp) }
                    TextButton(onClick = { scope.launch { app.radios.disconnect(r.protocol) } }) { Text("Disconnect") }
                }
            }
        }
        item { SectionLabel("Bluetooth") }
        if (!scanner.bluetoothOn) item { Text("Turn on Bluetooth to find radios.", Modifier.padding(horizontal = 20.dp)) }
        items(found, key = { it.device.address }) { f ->
            var pick by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { pick = true }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                f.guess?.let { ProtocolBadge(it) }
                Column(Modifier.weight(1f)) { Text(f.name, fontWeight = FontWeight.Bold); Text("${f.device.address} · ${f.rssi} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (busy == f.device.address) Text("Connecting…", fontSize = 12.sp)
                DropdownMenu(pick, { pick = false }) {
                    supportedProtocols.forEach { p ->
                        DropdownMenuItem({ Text("Connect as ${p.name.lowercase().replaceFirstChar { it.uppercase() }}${if (p == ProtocolType.RETICULUM) " (RNode)" else ""}${if (p == f.guess) " ✓" else ""}") }, {
                            pick = false; busy = f.device.address
                            scope.launch {
                                scanner.stop()
                                val r = app.radios.connectBle(f.device, p)
                                busy = null
                                ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "Connected")
                                if (r.isSuccess) nav.popBackStack()
                            }
                        })
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scanner.stop(); scanner.start() }) { Text("Scan again") }
            }
        }
        item { SectionLabel("USB-C") }
        if (usbList.isEmpty()) item { Text("Plug a radio into the phone's USB-C port with a data cable.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(usbList) { d ->
            Row(Modifier.fillMaxWidth().clickable {
                busy = d.device.deviceName
                scope.launch {
                    val ok = requestUsbPermission(ctx, d.device)
                    if (!ok) { busy = null; ctx.toast(long = true, message = "USB permission denied"); return@launch }
                    val r = app.radios.connectUsb(d)
                    busy = null
                    ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "Connected over USB as ${r.getOrNull()?.name?.lowercase()}")
                    if (r.isSuccess) nav.popBackStack()
                }
            }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Column(Modifier.weight(1f)) { Text(UsbDevices.describe(d.device), fontWeight = FontWeight.Bold); Text("Tap to detect Meshtastic, MeshCore or RNode", fontSize = 12.sp) }
                if (busy == d.device.deviceName) Text("Detecting…", fontSize = 12.sp)
            }
        }
        item { TextButton(onClick = { usbList = UsbDevices.drivers(ctx.getSystemService(UsbManager::class.java)) }, Modifier.padding(horizontal = 12.dp)) { Text("Refresh USB list") } }
        if (org.highbridge.app.rns.ReticulumSupport.AVAILABLE) item { SectionLabel("Reticulum") }
        if (org.highbridge.app.rns.ReticulumSupport.AVAILABLE) item {
            var installed by remember { mutableStateOf(org.highbridge.app.rns.ReticulumSupport.isInstalled(ctx)) }
            var askDownload by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Talk to Sideband, MeshChat and NomadNet users through an RNode, on the same Wi-Fi (AutoInterface), or through TCP hosts set in Settings.", style = MaterialTheme.typography.bodyMedium)
                if (!installed) {
                    Text("Reticulum support needs a one-time download (about ${org.highbridge.app.rns.ReticulumSupport.DOWNLOAD_KB} KB).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { askDownload = true }) { Text(if (busy == "dl") "Downloading…" else "Get Reticulum") }
                } else Button(onClick = { busy = "rns"; scope.launch { val r = app.radios.startReticulumNetworkOnly(); busy = null; ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "Reticulum started") } }) {
                    Text(if (busy == "rns") "Starting…" else "Start Reticulum")
                }
                Gap(24)
            }
            if (askDownload) androidx.compose.material3.AlertDialog(onDismissRequest = { askDownload = false },
                title = { Text("Download Reticulum?") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Highbridge talks to Reticulum using the official RNS and LXMF packages by Mark Qvist. They aren't built into Highbridge, because their license (the Reticulum License) has rules the GPL doesn't allow inside a GPL app.")
                    Text("That license lets you use them freely, except in systems meant to purposely harm people, or to build AI or machine-learning training datasets. By downloading, you agree to it.")
                    TextButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(org.highbridge.app.rns.ReticulumSupport.LICENSE_URL))) }) { Text("Read the Reticulum License") }
                    Text("Files come from PyPI (pypi.org) and are checked against fixed checksums.", fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = {
                    askDownload = false; busy = "dl"
                    scope.launch {
                        val r = runCatching { org.highbridge.app.rns.ReticulumSupport.install(ctx) }
                        busy = null; installed = org.highbridge.app.rns.ReticulumSupport.isInstalled(ctx)
                        ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "Reticulum is ready")
                    }
                }) { Text("I agree, download") } },
                dismissButton = { TextButton(onClick = { askDownload = false }) { Text("Not now") } })
        }
    }
}

suspend fun requestUsbPermission(ctx: Context, device: UsbDevice): Boolean {
    val usb = ctx.getSystemService(UsbManager::class.java)
    if (usb.hasPermission(device)) return true
    val action = "org.highbridge.USB_PERMISSION"
    val result = CompletableDeferred<Boolean>()
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == action) result.complete(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) }
    }
    ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    usb.requestPermission(device, pi)
    return try { result.await() } finally { runCatching { ctx.unregisterReceiver(receiver) } }
}

/** Protocols this edition can talk to (the store edition has no Reticulum). */
val supportedProtocols: List<ProtocolType> =
    ProtocolType.entries.filter { it != ProtocolType.RETICULUM || org.highbridge.app.rns.ReticulumSupport.AVAILABLE }
val supportedBridgeModes: List<BridgeMode> = BridgeMode.entries.filter { m -> m.activeProtocols.all { it in supportedProtocols } }
