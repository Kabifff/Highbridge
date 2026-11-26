// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.toast
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.flasher.BleDfu
import org.highbridge.app.flasher.EspRomFlasher
import org.highbridge.app.flasher.Uf2Flasher
import org.highbridge.app.flasher.UpdateChecker
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.link.UsbDevices
import java.io.File

@Composable
fun UsbScreen() {
    val nav = LocalNav.current
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().imePadding()) {
        HbHeader("USB-C & flashing", "Terminal, firmware flashers and Bluetooth DFU", onBack = { nav.popBackStack() })
        TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Terminal", "Flash firmware", "Bluetooth DFU").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) { 0 -> TerminalTab(); 1 -> FlashTab(); else -> DfuTab() }
    }
}

/** A monospace command line for every connected radio, with their debug logs mixed in. */
@Composable
private fun TerminalTab() {
    val app = HighbridgeApp.instance
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { app.radios.log.collect { (p, l) -> lines.add("[${p.shortTag}] $l"); if (lines.size > 1000) lines.removeRange(0, 200) } }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }

    suspend fun run(cmd: String) {
        lines.add("> $cmd")
        val parts = cmd.trim().split(Regex("\\s+"), limit = 3)
        val r = app.radios
        val out: String = when (parts.getOrNull(0)?.lowercase()) {
            "help" -> """commands:
  status                     radios and firmware
  mt|mc|rns send <text>      send on channel 0
  mt|mc|rns nodes            list heard nodes
  mt traceroute <!id>        Meshtastic traceroute
  mt reboot | mt dfu         reboot / enter bootloader
  mc cli <command>           run a command on the MeshCore radio itself
  mc advert                  send a MeshCore advert
  rns announce | rns status  Reticulum
  clear"""
            "status" -> r.radios.value.values.joinToString("\n") { "${it.protocol.shortTag}: ${it.deviceInfo.value ?: "?"} · me=${it.myNodeId.value}" }.ifBlank { "No radios connected" }
            "clear" -> { lines.clear(); "" }
            "mt", "mc", "rns" -> {
                val proto = when (parts[0].lowercase()) { "mt" -> ProtocolType.MESHTASTIC; "mc" -> ProtocolType.MESHCORE; else -> ProtocolType.RETICULUM }
                val radio = r.radio(proto) ?: return run { lines.add("${proto.shortTag} isn't connected") }
                when (parts.getOrNull(1)?.lowercase()) {
                    "send" -> radio.sendText(0, parts.getOrElse(2) { "" }, null).fold({ "sent $it" }, { "error: ${it.message}" })
                    "nodes" -> radio.discoveredNodes.value.joinToString("\n") { "${it.id}  ${it.longName}  hops=${it.hopsAway} snr=${it.snr}" }.ifBlank { "none yet" }
                    "traceroute" -> radio.requestTraceroute(parts.getOrElse(2) { "" }).fold({ "traceroute sent" }, { "error: ${it.message}" })
                    "reboot" -> r.meshtastic?.reboot()?.fold({ "rebooting" }, { "error: ${it.message}" }) ?: r.meshcore?.reboot()?.fold({ "rebooting" }, { "error: ${it.message}" }) ?: "?"
                    "dfu" -> r.meshtastic?.enterDfuMode()?.fold({ "entering bootloader" }, { "error: ${it.message}" }) ?: "Meshtastic only"
                    "cli" -> r.meshcore?.localCli(parts.getOrElse(2) { "" })?.fold({ it }, { "error: ${it.message}" }) ?: "MeshCore only"
                    "advert" -> r.meshcore?.advertise()?.fold({ "advert sent" }, { "error: ${it.message}" }) ?: "MeshCore only"
                    "announce" -> r.reticulum?.announce()?.fold({ "announced" }, { "error: ${it.message}" }) ?: "Reticulum only"
                    "status" -> r.reticulum?.deviceInfo?.value ?: "?"
                    else -> "unknown subcommand; type help"
                }
            }
            else -> "unknown command; type help"
        }
        if (out.isNotEmpty()) lines.addAll(out.lines())
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(StPaulMeshPalette.NightBg).padding(8.dp), state = list) {
            items(lines) { Text(it, color = if (it.startsWith(">")) StPaulMeshPalette.NightAmber else StPaulMeshPalette.NightFg, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
        Row(Modifier.padding(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("type help") }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            TextButton(onClick = { val c = input; input = ""; scope.launch { run(c) } }) { Text("Run") }
        }
    }
}

/** nRF52 UF2 drag-and-drop and ESP32 ROM flashing over USB-C. */
@Composable
private fun FlashTab() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val usb = ctx.getSystemService(UsbManager::class.java)
    var drivers by remember { mutableStateOf(UsbDevices.drivers(usb)) }
    var file by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var kind by remember { mutableStateOf("ESP32") }
    var offset by remember { mutableStateOf("0x0") }
    var progress by remember { mutableFloatStateOf(0f) }
    val log = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: run { log.add("Couldn't read $name"); return@rememberLauncherForActivityResult }
        file = name to bytes
        kind = if (name.endsWith(".uf2", true)) "UF2" else "ESP32"
        offset = "0x%x".format(EspRomFlasher.offsetFor(name))
        log.add("Loaded $name (${bytes.size / 1024} KB)")
    }
    val pickDrive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        val f = file ?: return@rememberLauncherForActivityResult
        tree ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching { Uf2Flasher.copyToDrive(ctx, tree, f.first, f.second) { progress = it } }
                .onSuccess { log.add("Copied. The radio restarts with the new firmware.") }.onFailure { log.add("Error: ${it.message}") }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HbCard {
            Text("1. Choose firmware", fontWeight = FontWeight.Bold)
            Text("Get it from Firmware updates in the menu, or from flasher.meshtastic.org, flasher.meshcore.io or the RNode releases.", fontSize = 12.sp)
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(file?.first ?: "Pick a .bin or .uf2 file") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("ESP32", "UF2").forEach { k -> FilterChip(kind == k, { kind = k }, { Text(if (k == "UF2") "nRF52 (.uf2)" else "ESP32 (.bin)") }) } }
            file?.takeIf { kind == "UF2" }?.let { f -> runCatching { Uf2Flasher.validate(f.second) }.fold({ Text("Valid UF2 · ${it.blocks} blocks · ${Uf2Flasher.familyName(it.familyId)}", fontSize = 12.sp) }, { Text(it.message ?: "Invalid", color = StPaulMeshPalette.Danger, fontSize = 12.sp) }) }
        }
        HbCard {
            Text("2. Plug in the radio", fontWeight = FontWeight.Bold)
            if (drivers.isEmpty()) Text("No USB serial device found.", fontSize = 12.sp)
            drivers.forEach { d -> Text(UsbDevices.describe(d.device), fontSize = 13.sp) }
            TextButton(onClick = { drivers = UsbDevices.drivers(usb) }) { Text("Refresh") }
        }
        HbCard {
            Text("3. Flash", fontWeight = FontWeight.Bold)
            if (kind == "ESP32") {
                OutlinedTextField(offset, { offset = it }, Modifier.fillMaxWidth(), label = { Text("Flash offset (0x0 for factory/merged images, 0x10000 for app updates)") }, singleLine = true)
                Button(enabled = !busy && file != null && drivers.isNotEmpty(), onClick = {
                    val d = drivers.first(); val f = file!!
                    busy = true; progress = 0f
                    scope.launch {
                        if (!requestUsbPermission(ctx, d.device)) { log.add("USB permission denied"); busy = false; return@launch }
                        app.radios.radios.value.keys.forEach { app.radios.disconnect(it) }
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val conn = usb.openDevice(d.device) ?: error("Can't open the device")
                                val port = d.ports.first(); port.open(conn)
                                try {
                                    val flasher = EspRomFlasher(port, d.device.vendorId == 0x303A) { log.add(it) }
                                    flasher.connect(); flasher.speedUp()
                                    flasher.flash(f.second, offset.removePrefix("0x").toInt(16)) { progress = it }
                                } finally { runCatching { port.close() } }
                            }.onSuccess { log.add("Done!") }.onFailure { log.add("Error: ${it.message}") }
                        }
                        busy = false
                    }
                }) { Text("Flash ESP32") }
            } else {
                Text("This restarts the board into its bootloader drive. Then pick the drive (often called RAK4631, TECHOBOOT or similar) in the folder picker.", fontSize = 12.sp)
                Button(enabled = !busy && file != null, onClick = {
                    scope.launch {
                        drivers.firstOrNull()?.let { d -> if (requestUsbPermission(ctx, d.device)) runCatching { Uf2Flasher.touch1200(usb, d) }.onSuccess { log.add("Sent 1200-baud reset; wait for the drive to appear") } }
                        kotlinx.coroutines.delay(2500)
                        pickDrive.launch(null)
                    }
                }) { Text("Reboot to bootloader and copy") }
                TextButton(onClick = { pickDrive.launch(null) }, enabled = file != null) { Text("The drive is already showing") }
            }
            if (progress > 0f) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        }
        log.takeLast(30).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DfuTab() {
    val ctx = LocalContext.current
    var address by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf<Uri?>(null) }
    val bonded = remember { runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.toList() }.getOrDefault(emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { zip = it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Update nRF52 radios (RAK4631, T-Echo, T114) over Bluetooth with the firmware's \"ota.zip\". On Meshtastic, run \"mt dfu\" in the Terminal first.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(address, { address = it.uppercase() }, Modifier.fillMaxWidth(), label = { Text("Bluetooth address") }, singleLine = true)
        bonded.forEach { d -> TextButton(onClick = { address = d.address }) { Text("${d.name ?: "Device"} · ${d.address}") } }
        OutlinedButton(onClick = { pick.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(zip?.lastPathSegment ?: "Pick firmware .zip") }
        Button(enabled = address.length == 17 && zip != null, onClick = {
            BleDfu.start(ctx, address, null, zip!!)
            ctx.toast("Update started; progress is in the notification", long = true)
        }) { Text("Start Bluetooth update") }
    }
}

/** Checks GitHub Releases and compares with what your radios run. */
@Composable
fun UpdatesScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf("Meshtastic") }
    var releases by remember { mutableStateOf<List<UpdateChecker.Release>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var dl by remember { mutableFloatStateOf(0f) }
    val radios by app.radios.radios.collectAsState()
    val radio = radios[when (project) { "Meshtastic" -> ProtocolType.MESHTASTIC; "MeshCore" -> ProtocolType.MESHCORE; else -> ProtocolType.RETICULUM }]
    val installed = radio?.deviceInfo?.collectAsState()?.value
    LaunchedEffect(project) { error = null; releases = emptyList(); runCatching { UpdateChecker.releases(project) }.onSuccess { releases = it }.onFailure { error = it.message } }
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("Firmware updates", "From each project's GitHub releases", onBack = { nav.popBackStack() }) }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { UpdateChecker.PROJECTS.keys.forEach { p -> FilterChip(project == p, { project = p }, { Text(p) }) } }
            installed?.let { Text("Your radio: $it", Modifier.padding(16.dp, 8.dp)) }
            error?.let { Text("Couldn't check: $it", Modifier.padding(16.dp), color = StPaulMeshPalette.Danger) }
            if (dl > 0f && dl < 1f) LinearProgressIndicator(progress = { dl }, Modifier.fillMaxWidth().padding(16.dp))
        }
        items(releases) { r ->
            Column(Modifier.padding(16.dp, 8.dp)) {
                Text("${r.tag} ${if (UpdateChecker.isNewer(r.tag, installed?.substringAfter(' ')?.substringBefore(' '))) "· newer than yours" else ""}", fontWeight = FontWeight.Bold)
                Text(r.publishedAt.take(10), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.assets.filter { it.name.endsWith(".zip") || it.name.endsWith(".bin") || it.name.endsWith(".uf2") }.take(12).forEach { a ->
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { UpdateChecker.download(a, File(ctx.getExternalFilesDir(null), "firmware")) { dl = it } }
                                .onSuccess { ctx.toast("Saved ${it.name}. Open USB-C & flashing to install it.", long = true); dl = 0f }
                                .onFailure { ctx.toast(it.message, long = true) }
                        }
                    }) { Text("${a.name} · ${a.sizeBytes / 1024} KB", fontSize = 12.sp) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

