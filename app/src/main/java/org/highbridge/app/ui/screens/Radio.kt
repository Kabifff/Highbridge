// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import org.highbridge.app.ui.tr

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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RadioScreen() {
    val navRadio = LocalNav.current
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
    var joining by remember { mutableStateOf(false) }
    var joinReport by remember { mutableStateOf<List<Pair<Boolean, String>>?>(null) }

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
        item { HbHeader(tr("Radio & region"), radios.values.joinToString(" · ") { it.deviceInfo.value ?: it.protocol.shortTag }.ifBlank { "No radio connected" }) }
        item {
            Column(Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp)) {
                HbCard {
                    Text(if (suggested != null) "Your phone says you're in ${Locale("", country).displayCountry}" else tr("Pick the band that's legal where you are"),
                        fontWeight = FontWeight.Bold)
                    Text(if (suggested != null) "We suggest ${suggested.name} · ${suggested.bandLabel}. Pick a different region if you're traveling or near a border. Only use the band that's legal where your radio is."
                        else tr("LoRa bands differ by country. Choose your region below."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (suggested != null) {
                            val using = region == suggested
                            // Picks the band for this screen and the airtime meter; "Apply" below sends it to a radio.
                            if (using) androidx.compose.material3.FilledTonalButton(colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer), onClick = { ctx.toast("${suggested.label} (${suggested.bandLabel}) is already selected. Apply it to a radio below.") }) {
                                Icon(Icons.Filled.Check, null); Text(" Using ${suggested.name}") }
                            else Button(onClick = {
                                app.prefs.setRegion(suggested); org.highbridge.app.ui.Haptics.confirm(ctx)
                                ctx.toast(long = true, message = "Region set to ${suggested.label} (${suggested.bandLabel}). Apply it to your radio below.")
                            }) { Text("Use ${suggested.name}") }
                        }
                        OutlinedButton(onClick = {
                            joining = true; preset = MeshtasticModemPreset.MEDIUM_FAST; mcPreset = StPaulQuickJoin.meshCore
                            scope.launch {
                                joinReport = app.radios.joinSaintPaul(); joining = false
                                if (joinReport.orEmpty().all { it.first }) org.highbridge.app.ui.Haptics.confirm(ctx)
                            }
                        }, enabled = !joining) {
                            if (joining) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else if (joinReport != null) Icon(Icons.Filled.Check, null)
                            Text((if (joining) "  " else if (joinReport != null) " " else "") + tr(if (joinReport != null && !joining) "Joined Saint Paul Mesh" else "Join Saint Paul Mesh"))
                        }
                    }
                    joinReport?.let { lines ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            lines.forEach { (ok, text) ->
                                Text((if (ok) "✓ " else "⚠ ") + text, style = MaterialTheme.typography.bodySmall,
                                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                Gap(12)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text(tr("Search a country or frequency")) }, singleLine = true)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ProtocolBadge(ProtocolType.MESHTASTIC); Text(tr("Meshtastic radio settings"), fontWeight = FontWeight.Bold) }
                    var open by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Preset: "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { open = true }) { Text(preset.label) }
                        org.highbridge.app.ui.HbDropdownMenu(open, { open = false }) { MeshtasticModemPreset.entries.forEach { p -> DropdownMenuItem({ Text(p.label) }, { preset = p; open = false }) } }
                    }
                    Text("Transmit power: ${if (power == 0) "region maximum (${region.powerLimitDbm} dBm)" else "$power dBm"}")
                    Slider(power.toFloat(), { power = it.toInt() }, valueRange = 0f..region.powerLimitDbm.toFloat().coerceAtLeast(1f))
                    Text(tr("Capped at your region's legal limit. Lower power saves battery."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = radios.containsKey(ProtocolType.MESHTASTIC) && region != MeshtasticRegion.UNSET, onClick = {
                        scope.launch { app.radios.meshtastic?.applyLoRa(MeshtasticLoRaConfig(region, preset, 3, power))?.let { ctx.toast(long = true, message = it.exceptionOrNull()?.message ?: "Sent to the radio. It will restart.") } }
                    }) { Text(tr("Apply to Meshtastic radio")) }
                }
                HbCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ProtocolBadge(ProtocolType.MESHCORE); Text(tr("MeshCore radio preset"), fontWeight = FontWeight.Bold) }
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = true }) { Text(mcPreset.name + " · %.3f MHz".format(mcPreset.frequencyMHz)) }
                    org.highbridge.app.ui.HbDropdownMenu(open, { open = false }) { MeshCorePresets.ALL.forEach { p -> DropdownMenuItem({ Text("${p.name} · %.3f MHz".format(p.frequencyMHz)) }, { mcPreset = p; open = false }) } }
                    Text("SF ${mcPreset.spreadingFactor} · BW ${mcPreset.bandwidthKhz} kHz · CR ${mcPreset.codingRate}", fontSize = 12.sp)
                    val problems = MeshCorePresets.validate(mcPreset)
                    if (problems.isEmpty()) Text("Fits inside the ${mcPreset.band.name} band", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    else problems.forEach { Text(it, color = StPaulMeshPalette.Danger) }
                    Text("Transmit power: $mcPower dBm"); Slider(mcPower.toFloat(), { mcPower = it.toInt() }, valueRange = 1f..minOf(22, mcPreset.band.powerLimitDbm).toFloat())
                    Button(enabled = radios.containsKey(ProtocolType.MESHCORE) && problems.isEmpty(), onClick = {
                        scope.launch { app.radios.meshcore?.applyPreset(mcPreset, mcPower)?.let { ctx.toast(long = true, message = it.exceptionOrNull()?.message ?: "MeshCore radio updated") } }
                    }) { Text(tr("Apply to MeshCore radio")) }
                }
                if (radios.containsKey(ProtocolType.MESHTASTIC)) HbCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ProtocolBadge(ProtocolType.MESHTASTIC); Text(tr("All Meshtastic settings"), fontWeight = FontWeight.Bold) }
                    Text(tr("Role, position, power, MQTT, telemetry, modules, security, restart and reset."), fontSize = 12.sp)
                    TextButton(onClick = { navRadio.navigate("mtsettings") }) { Text(tr("Open settings")) }
                }
                if (radios.containsKey(ProtocolType.MESHCORE)) HbCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { ProtocolBadge(ProtocolType.MESHCORE); Text(tr("Announce and share"), fontWeight = FontWeight.Bold) }
                    Text(tr("An advert lets others add you as a contact. Zero-hop reaches radios in direct range only; flood goes across the mesh."), fontSize = 12.sp)
                    var showQr by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scope.launch { ctx.toast(app.radios.meshcore?.advertise(false)?.exceptionOrNull()?.message ?: "Zero-hop advert sent") } }) { Text(tr("Zero-hop")) }
                        OutlinedButton(onClick = { scope.launch { ctx.toast(app.radios.meshcore?.advertise(true)?.exceptionOrNull()?.message ?: "Flood advert sent") } }) { Text(tr("Flood")) }
                        TextButton(onClick = { showQr = true }) { Text(tr("My QR")) }
                    }
                    TextButton(onClick = { navRadio.navigate("discover") }) { Text(tr("Discover adverts heard nearby ›")) }
                    // Region scope (flood scope): keeps your flood packets inside a region on meshes whose repeaters use regions.
                    var region by remember { mutableStateOf(app.prefs.mcRegion.value) }
                    Text(tr("Region scope"), fontWeight = FontWeight.Bold)
                    Text(tr("On meshes that use regions (firmware 1.10+), repeaters only flood your packets inside this region. Leave blank for normal flooding."), fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(region, { region = it.trim().take(30) }, Modifier.weight(1f), singleLine = true, placeholder = { Text(tr("e.g. #twincities")) })
                        OutlinedButton(onClick = { scope.launch {
                            app.radios.meshcore?.setRegionScope(region)?.fold({ app.prefs.setMcRegion(region); ctx.toast(if (region.isBlank()) "Region scope cleared" else "Flooding scoped to $region") },
                                { ctx.toast(it.message, long = true) })
                        } }) { Text(tr("Set")) }
                    }
                    McConfigBackup()
                    val link = app.radios.meshcore?.myContactUri()
                    if (showQr && link != null) org.highbridge.app.ui.HbAlertDialog(onDismissRequest = { showQr = false },
                        title = { Text(tr("Your MeshCore contact")) },
                        text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val bmp = remember(link) { com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(link, com.google.zxing.BarcodeFormat.QR_CODE, 640, 640) }
                            androidx.compose.foundation.Image(bmp.asImageBitmap(), "Contact QR code", Modifier.size(240.dp))
                            Text(tr("Others scan this in Highbridge (Channels › Scan) or the MeshCore app to add you."), fontSize = 12.sp)
                        } },
                        confirmButton = { TextButton(onClick = { showQr = false }) { Text(tr("Done")) } },
                        dismissButton = { TextButton(onClick = { ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link), "Share contact")) }) { Text(tr("Share link")) } })
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
        item { HbHeader(tr("The Bridge"), tr("Carry public messages from one mesh to another")) }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) "Bridge is on" else "Bridge is off", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        Text(tr("Keeps running in the background, even after a restart"), color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .85f), fontSize = 12.sp)
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
                SectionLabel(tr("Which networks"), Modifier.padding(0.dp))
                supportedBridgeModes.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable {
                        mode = m; BridgeModeStore.mode = m; engine?.setBridgeMode(m)
                    }, verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == m, { mode = m; BridgeModeStore.mode = m; engine?.setBridgeMode(m) }); Text(m.label) }
                }
                SectionLabel(tr("Safety rules"), Modifier.padding(0.dp))
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
        item { HbHeader(tr("Connect a radio"), tr("Bluetooth, USB-C, or Reticulum over Wi-Fi"), onBack = { nav.popBackStack() }) }
        if (radios.isNotEmpty()) {
            item { SectionLabel(tr("Connected")) }
            items(radios.values.toList()) { r ->
                val st by r.connectionState.collectAsState()
                val dev by r.deviceInfo.collectAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProtocolBadge(r.protocol)
                    Column(Modifier.weight(1f)) { Text(dev ?: r.protocol.name, fontWeight = FontWeight.Bold); Text(if (st is ConnectionState.Connected) "Connected" else st.toString(), fontSize = 12.sp) }
                    TextButton(onClick = { scope.launch { app.radios.disconnect(r.protocol) } }) { Text(tr("Disconnect")) }
                }
            }
        }
        item { SectionLabel("Bluetooth") }
        if (!scanner.bluetoothOn) item { Text(tr("Turn on Bluetooth to find radios."), Modifier.padding(horizontal = 20.dp)) }
        items(found, key = { it.device.address }) { f ->
            var pick by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { pick = true }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                f.guess?.let { ProtocolBadge(it) }
                Column(Modifier.weight(1f)) { Text(f.name, fontWeight = FontWeight.Bold); Text("${f.device.address} · ${f.rssi} dBm", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (busy == f.device.address) Text(tr("Connecting…"), fontSize = 12.sp)
                org.highbridge.app.ui.HbDropdownMenu(pick, { pick = false }) {
                    (supportedProtocols + ProtocolType.APRS).forEach { p ->
                        DropdownMenuItem({ Text(if (p == ProtocolType.APRS) "Connect as APRS TNC (KISS)${if (p == f.guess) " ✓" else ""}"
                            else "Connect as ${p.name.lowercase().replaceFirstChar { it.uppercase() }}${if (p == ProtocolType.RETICULUM) " (RNode)" else ""}${if (p == f.guess) " ✓" else ""}") }, {
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
                OutlinedButton(onClick = { scanner.stop(); scanner.start() }) { Text(tr("Scan again")) }
            }
        }
        item { SectionLabel("USB-C") }
        if (usbList.isEmpty()) item { Text(tr("Plug a radio into the phone's USB-C port with a data cable."), Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                Column(Modifier.weight(1f)) { Text(UsbDevices.describe(d.device), fontWeight = FontWeight.Bold); Text(tr("Tap to detect Meshtastic, MeshCore or RNode"), fontSize = 12.sp) }
                if (busy == d.device.deviceName) Text(tr("Detecting…"), fontSize = 12.sp)
            }
        }
        item { TextButton(onClick = { usbList = UsbDevices.drivers(ctx.getSystemService(UsbManager::class.java)) }, Modifier.padding(horizontal = 12.dp)) { Text(tr("Refresh USB list")) } }
        item { SectionLabel("Wi-Fi") }
        item {
            var host by remember { mutableStateOf("") }
            var netProto by remember { mutableStateOf(ProtocolType.MESHTASTIC) }
            var busyNet by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("A radio with Wi-Fi on the same network as this phone (Meshtastic network API, or the MeshCore Wi-Fi companion firmware)."), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ProtocolType.MESHTASTIC, ProtocolType.MESHCORE).forEach { p -> FilterChip(netProto == p, { netProto = p }, { Text(p.shortTag) }) }
                }
                OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("IP address or name")) },
                    placeholder = { Text(if (netProto == ProtocolType.MESHCORE) "192.168.1.50:5000" else "192.168.1.50 or meshtastic.local") })
                Button(enabled = host.isNotBlank() && !busyNet, onClick = {
                    busyNet = true
                    scope.launch {
                        app.radios.connectTcp(host, netProto).onSuccess { ctx.toast(tr("Connected over Wi-Fi")); nav.popBackStack() }
                            .onFailure { ctx.toast(long = true, message = "Couldn't connect: ${it.message}") }
                        busyNet = false
                    }
                }) { Text(if (busyNet) "Connecting…" else "Connect") }
            }
        }
        if (org.highbridge.app.rns.ReticulumSupport.AVAILABLE) item { SectionLabel("Reticulum") }
        if (org.highbridge.app.rns.ReticulumSupport.AVAILABLE) item {
            var installed by remember { mutableStateOf(org.highbridge.app.rns.ReticulumSupport.isInstalled(ctx)) }
            var askDownload by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Talk to Sideband, MeshChat and NomadNet users through an RNode, on the same Wi-Fi (AutoInterface), or through TCP hosts set in Settings."), style = MaterialTheme.typography.bodyMedium)
                if (!installed) {
                    Text("Reticulum support needs a one-time download (about ${org.highbridge.app.rns.ReticulumSupport.DOWNLOAD_KB} KB).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { askDownload = true }) { Text(if (busy == "dl") "Downloading…" else "Get Reticulum") }
                } else Button(onClick = { busy = "rns"; scope.launch { val r = app.radios.startReticulumNetworkOnly(); busy = null; ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "Reticulum started") } }) {
                    Text(if (busy == "rns") "Starting…" else "Start Reticulum")
                }
                Gap(24)
            }
            if (askDownload) org.highbridge.app.ui.HbAlertDialog(onDismissRequest = { askDownload = false },
                title = { Text(tr("Download Reticulum?")) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Highbridge talks to Reticulum using the official RNS and LXMF packages by Mark Qvist. They aren't built into Highbridge, because their license (the Reticulum License) has rules the GPL doesn't allow inside a GPL app."))
                    Text(tr("That license lets you use them freely, except in systems meant to purposely harm people, or to build AI or machine-learning training datasets. By downloading, you agree to it."))
                    TextButton(onClick = { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(org.highbridge.app.rns.ReticulumSupport.LICENSE_URL))) }) { Text(tr("Read the Reticulum License")) }
                    Text(tr("Files come from PyPI (pypi.org) and are checked against fixed checksums."), fontSize = 12.sp)
                } },
                confirmButton = { TextButton(onClick = {
                    askDownload = false; busy = "dl"
                    scope.launch {
                        val r = runCatching { org.highbridge.app.rns.ReticulumSupport.install(ctx) }
                        busy = null; installed = org.highbridge.app.rns.ReticulumSupport.isInstalled(ctx)
                        ctx.toast(long = true, message = r.exceptionOrNull()?.message ?: "Reticulum is ready")
                    }
                }) { Text(tr("I agree, download")) } },
                dismissButton = { TextButton(onClick = { askDownload = false }) { Text(tr("Not now")) } })
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

/** Mesh networks this edition can talk to (the store edition has no Reticulum). APRS is separate: see [shownProtocols]. */
val supportedProtocols: List<ProtocolType> =
    ProtocolType.entries.filter { it != ProtocolType.APRS && (it != ProtocolType.RETICULUM || org.highbridge.app.rns.ReticulumSupport.AVAILABLE) }
/** Everything that can put nodes on the map and in the node list: the meshes plus APRS stations. */
val shownProtocols: List<ProtocolType> = supportedProtocols + ProtocolType.APRS
val supportedBridgeModes: List<BridgeMode> = BridgeMode.entries.filter { m -> m.activeProtocols.all { it in supportedProtocols } }


/** Export or import a MeshCore radio's name, radio settings, power and channels as a JSON file. */
@Composable
private fun McConfigBackup() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { val j = app.radios.meshcore!!.exportConfig(app.prefs.mcRegion.value); ctx.contentResolver.openOutputStream(uri)!!.use { it.write(j.toByteArray()) } }
            .fold({ ctx.toast(tr("Config saved. It includes channel keys: keep it private."), long = true) }, { ctx.toast(it.message, long = true) })
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull() ?: return@launch ctx.toast(tr("Couldn't read that file"))
            app.radios.meshcore?.importConfig(text)?.fold({ region ->
                if (region != null) { app.prefs.setMcRegion(region); app.radios.meshcore?.setRegionScope(region) }
                ctx.toast(tr("Config applied"))
            }, { ctx.toast(it.message, long = true) })
        }
    }
    Text(tr("Back up or copy settings"), fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save.launch("meshcore-config.json") }) { Text(tr("Export")) }
        OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(tr("Import")) }
    }
}
