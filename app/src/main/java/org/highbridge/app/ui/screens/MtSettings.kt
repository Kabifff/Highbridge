// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.toast
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig

/** Meshtastic radio and module settings, like the official app's Settings › Radio and Modules. */
@Composable
fun MeshtasticSettingsScreen(target: String? = null) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mt = app.radios.meshtastic
    if (mt == null) {
        Column { HbHeader(tr("Meshtastic settings"), tr("Not connected"), onBack = { nav.popBackStack() }); Text(tr("Connect your Meshtastic radio first."), Modifier.padding(20.dp)) }
        return
    }
    val remote by mt.remoteSettings.collectAsState()
    val local by mt.settings.collectAsState()
    val s = if (target == null) local else remote[target] ?: org.highbridge.protocol.meshtastic.MeshtasticRadio.Settings()
    androidx.compose.runtime.LaunchedEffect(target) { if (target != null) mt.requestRemoteSettings(target).onFailure { ctx.toast(it.message, long = true) } }
    suspend fun cfg(c: Config) = mt.setConfig(c, target)
    suspend fun mod(m: ModuleConfig) = mt.setModule(m, target)
    fun saved(r: Result<Unit>, what: String) = ctx.toast(r.exceptionOrNull()?.message ?: "$what saved. Some changes restart the radio.")
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Result<Unit>>?>(null) }

    LazyColumn {
        item { HbHeader(if (target == null) "Meshtastic settings" else "Remote admin", if (target == null) "Stored on your radio" else "Node $target, over the mesh", onBack = { nav.popBackStack() }) }
        if (target != null) item {
            Text("Settings arrive one section at a time over the mesh and can take a minute. This only works if that node lists your radio's public key " +
                "as an admin key (its Settings › Security) or still uses a legacy \"admin\" channel.", Modifier.padding(20.dp, 4.dp), fontSize = 12.sp)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { mt.requestRemoteSettings(target) } }) { Text(tr("Fetch again")) }
                OutlinedButton(onClick = { confirm = "Restart node $target?" to { mt.remoteAction(target, org.meshtastic.proto.AdminMessage(reboot_seconds = 5)) } }) { Text(tr("Restart it")) }
            }
        }

        s.device?.let { d -> item {
            Section("Device") {
                var role by remember(d) { mutableStateOf(d.role) }
                var rebroadcast by remember(d) { mutableStateOf(d.rebroadcast_mode) }
                var nodeInfo by remember(d) { mutableStateOf(d.node_info_broadcast_secs.toString()) }
                EnumPick("Role", role, Config.DeviceConfig.Role.entries) { role = it }
                Text(roleHelp(role), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EnumPick("Rebroadcast", rebroadcast, Config.DeviceConfig.RebroadcastMode.entries) { rebroadcast = it }
                NumField("Node info every (seconds)", nodeInfo) { nodeInfo = it }
                Button(onClick = { scope.launch { saved(cfg(Config(device = d.copy(role = role, rebroadcast_mode = rebroadcast,
                    node_info_broadcast_secs = nodeInfo.toIntOrNull() ?: d.node_info_broadcast_secs))), "Device") } }) { Text(tr("Save device")) }
            }
        } }

        s.position?.let { p -> item {
            Section("Position") {
                var every by remember(p) { mutableStateOf(p.position_broadcast_secs.toString()) }
                var smart by remember(p) { mutableStateOf(p.position_broadcast_smart_enabled) }
                var fixed by remember(p) { mutableStateOf(p.fixed_position) }
                var gps by remember(p) { mutableStateOf(p.gps_mode) }
                NumField("Broadcast every (seconds)", every) { every = it }
                Toggle("Smart position (send when moving)", smart) { smart = it }
                Toggle("Fixed position (use the radio's stored spot)", fixed) { fixed = it }
                EnumPick("GPS", gps, Config.PositionConfig.GpsMode.entries) { gps = it }
                Button(onClick = { scope.launch { saved(cfg(Config(position = p.copy(position_broadcast_secs = every.toIntOrNull() ?: p.position_broadcast_secs,
                    position_broadcast_smart_enabled = smart, fixed_position = fixed, gps_mode = gps))), "Position") } }) { Text(tr("Save position")) }
            }
        } }

        s.power?.let { p -> item {
            Section("Power") {
                var saving by remember(p) { mutableStateOf(p.is_power_saving) }
                var shutdownAfter by remember(p) { mutableStateOf(p.on_battery_shutdown_after_secs.toString()) }
                Toggle("Power saving (sleeps; best for solar nodes without Bluetooth use)", saving) { saving = it }
                NumField("Shut down after on battery (seconds, 0 = never)", shutdownAfter) { shutdownAfter = it }
                Button(onClick = { scope.launch { saved(cfg(Config(power = p.copy(is_power_saving = saving,
                    on_battery_shutdown_after_secs = shutdownAfter.toIntOrNull() ?: p.on_battery_shutdown_after_secs))), "Power") } }) { Text(tr("Save power")) }
            }
        } }

        s.mqtt?.let { m -> item {
            Section("MQTT (internet uplink)") {
                var on by remember(m) { mutableStateOf(m.enabled) }
                var addr by remember(m) { mutableStateOf(m.address) }
                var user by remember(m) { mutableStateOf(m.username) }
                var pass by remember(m) { mutableStateOf(m.password) }
                var root by remember(m) { mutableStateOf(m.root) }
                var enc by remember(m) { mutableStateOf(m.encryption_enabled) }
                var json by remember(m) { mutableStateOf(m.json_enabled) }
                var tls by remember(m) { mutableStateOf(m.tls_enabled) }
                var proxy by remember(m) { mutableStateOf(m.proxy_to_client_enabled) }
                var mapRep by remember(m) { mutableStateOf(m.map_reporting_enabled) }
                Toggle("Enabled", on) { on = it }
                OutlinedTextField(addr, { addr = it }, Modifier.fillMaxWidth(), label = { Text(tr("Server (blank = mqtt.meshtastic.org)")) }, singleLine = true)
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text(tr("Username")) }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text(tr("Password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(root, { root = it }, Modifier.fillMaxWidth(), label = { Text(tr("Root topic")) }, singleLine = true)
                Toggle("Encrypted uplink", enc) { enc = it }
                Toggle("Also publish JSON", json) { json = it }
                Toggle("TLS", tls) { tls = it }
                Toggle("Use the phone's internet (client proxy)", proxy) { proxy = it }
                Toggle("Report to the public map", mapRep) { mapRep = it }
                Button(onClick = { scope.launch { saved(mod(ModuleConfig(mqtt = m.copy(enabled = on, address = addr.trim(), username = user.trim(), password = pass,
                    root = root.trim(), encryption_enabled = enc, json_enabled = json, tls_enabled = tls, proxy_to_client_enabled = proxy, map_reporting_enabled = mapRep))), "MQTT") } }) { Text(tr("Save MQTT")) }
            }
        } }

        s.telemetry?.let { t -> item {
            Section("Telemetry") {
                var dev by remember(t) { mutableStateOf(t.device_update_interval.toString()) }
                var envOn by remember(t) { mutableStateOf(t.environment_measurement_enabled) }
                var env by remember(t) { mutableStateOf(t.environment_update_interval.toString()) }
                var powOn by remember(t) { mutableStateOf(t.power_measurement_enabled) }
                NumField("Device metrics every (seconds)", dev) { dev = it }
                Toggle("Environment sensors", envOn) { envOn = it }
                NumField("Environment every (seconds)", env) { env = it }
                Toggle("Power sensors", powOn) { powOn = it }
                Button(onClick = { scope.launch { saved(mod(ModuleConfig(telemetry = t.copy(device_update_interval = dev.toIntOrNull() ?: t.device_update_interval,
                    environment_measurement_enabled = envOn, environment_update_interval = env.toIntOrNull() ?: t.environment_update_interval,
                    power_measurement_enabled = powOn))), "Telemetry") } }) { Text(tr("Save telemetry")) }
            }
        } }

        item {
            Section("Other modules") {
                s.neighborInfo?.let { n ->
                    Toggle("Neighbor info (shares who it hears)", n.enabled) { v -> scope.launch { saved(mod(ModuleConfig(neighbor_info = n.copy(enabled = v, update_interval = maxOf(n.update_interval, 14400)))), "Neighbor info") } }
                }
                s.storeForward?.let { f ->
                    Toggle("Store & forward server (needs PSRAM)", f.enabled) { v -> scope.launch { saved(mod(ModuleConfig(store_forward = f.copy(enabled = v))), "Store & forward") } }
                }
                s.rangeTest?.let { r ->
                    Toggle("Range test module", r.enabled) { v -> scope.launch { saved(mod(ModuleConfig(range_test = r.copy(enabled = v))), "Range test") } }
                }
                s.externalNotification?.let { e ->
                    Toggle("External notification (buzzer/LED on message)", e.enabled) { v ->
                        scope.launch { saved(mod(ModuleConfig(external_notification = e.copy(enabled = v, alert_message = v || e.alert_message))), "External notification") } }
                }
                s.detectionSensor?.let { d ->
                    Toggle("Detection sensor", d.enabled) { v -> scope.launch { saved(mod(ModuleConfig(detection_sensor = d.copy(enabled = v))), "Detection sensor") } }
                }
                s.paxcounter?.let { p ->
                    Toggle("Paxcounter (counts nearby phones)", p.enabled) { v -> scope.launch { saved(mod(ModuleConfig(paxcounter = p.copy(enabled = v))), "Paxcounter") } }
                }
                s.serial?.let { m -> Toggle("Serial module (text over the UART pins)", m.enabled) { v -> scope.launch { saved(mod(ModuleConfig(serial = m.copy(enabled = v))), "Serial") } } }
                s.audio?.let { m -> Toggle("Audio (Codec2 voice on supported boards)", m.codec2_enabled) { v -> scope.launch { saved(mod(ModuleConfig(audio = m.copy(codec2_enabled = v))), "Audio") } } }
                s.remoteHardware?.let { m -> Toggle("Remote hardware (GPIO control over the mesh)", m.enabled) { v -> scope.launch { saved(mod(ModuleConfig(remote_hardware = m.copy(enabled = v))), "Remote hardware") } } }
                s.ambientLighting?.let { m -> Toggle("Ambient lighting LED", m.led_state) { v -> scope.launch { saved(mod(ModuleConfig(ambient_lighting = m.copy(led_state = v))), "Ambient lighting") } } }
                s.trafficManagement?.let { m ->
                    var minPos by remember(m) { mutableStateOf(m.position_min_interval_secs.toString()) }
                    var window by remember(m) { mutableStateOf(m.rate_limit_window_secs.toString()) }
                    var maxPk by remember(m) { mutableStateOf(m.rate_limit_max_packets.toString()) }
                    var unknown by remember(m) { mutableStateOf(m.unknown_packet_threshold.toString()) }
                    Text(tr("Traffic management (routers; 0 turns each rule off)"), fontWeight = FontWeight.Bold)
                    NumField("Drop repeat positions sooner than (seconds)", minPos) { minPos = it }
                    NumField("Rate limit window (seconds)", window) { window = it }
                    NumField("Most packets per node per window (max 60)", maxPk) { maxPk = it }
                    NumField("Undecryptable packets allowed per 5 min (max 60)", unknown) { unknown = it }
                    TextButton(onClick = { scope.launch { saved(mod(ModuleConfig(traffic_management = m.copy(
                        position_min_interval_secs = minPos.toIntOrNull() ?: 0, rate_limit_window_secs = window.toIntOrNull() ?: 0,
                        rate_limit_max_packets = (maxPk.toIntOrNull() ?: 0).coerceAtMost(60), unknown_packet_threshold = (unknown.toIntOrNull() ?: 0).coerceAtMost(60)))), "Traffic management") } }) { Text(tr("Save traffic management")) }
                }
                s.tak?.let { m ->
                    EnumPick("TAK team", m.team, org.meshtastic.proto.Team.entries) { v -> scope.launch { saved(mod(ModuleConfig(tak = m.copy(team = v))), "TAK") } }
                    EnumPick("TAK role", m.role, org.meshtastic.proto.MemberRole.entries) { v -> scope.launch { saved(mod(ModuleConfig(tak = m.copy(role = v))), "TAK") } }
                }
                s.cannedMessage?.let {
                    var canned by remember { mutableStateOf("") }
                    OutlinedTextField(canned, { canned = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(tr("Canned messages for the radio's buttons (separate with |)")) })
                    TextButton(onClick = { scope.launch { saved(mt.setCannedMessages(canned), "Canned messages") } }, enabled = canned.isNotBlank()) { Text(tr("Save canned messages")) }
                }
            }
        }

        s.security?.let { sec -> item {
            Section("Security") {
                Text(tr("Public key: ") + sec.public_key.base64().take(24) + "…", fontSize = 12.sp)
                Text(tr("Direct messages are end-to-end encrypted with these keys on firmware 2.5 and later."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Managed mode (only admins can change settings)", sec.is_managed) { v -> scope.launch { saved(cfg(Config(security = sec.copy(is_managed = v))), "Security") } }
                Toggle("Legacy admin channel", sec.admin_channel_enabled) { v -> scope.launch { saved(cfg(Config(security = sec.copy(admin_channel_enabled = v))), "Security") } }
            }
        } }

        if (target == null) item { ProfileSection() }
        if (target == null) item { OtaSection() }

        if (target == null) item {
            Section("Licensed (ham) mode") {
                var call by remember { mutableStateOf("") }
                var power by remember { mutableStateOf("30") }
                Text("For licensed amateur operators. Uses your call sign as the node name and turns off encryption, " +
                    "as amateur rules don't allow encrypted traffic. Your messages can then be read by anyone.", fontSize = 12.sp)
                OutlinedTextField(call, { call = it.uppercase().take(8) }, Modifier.fillMaxWidth(), label = { Text(tr("Call sign")) }, singleLine = true)
                NumField("Transmit power (dBm, your radio's limit applies)", power) { power = it }
                Button(enabled = call.length >= 3, onClick = { confirm = "Switch to licensed mode as $call? Encryption is turned off." to { mt.setHamMode(call, power.toIntOrNull() ?: 30) } }) { Text(tr("Turn on licensed mode")) }
            }
        }

        if (target == null) item {
            Section("Radio actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirm = "Restart the radio?" to { mt.reboot() } }) { Text(tr("Restart")) }
                    OutlinedButton(onClick = { confirm = "Turn the radio off? You'll need to press its button to turn it back on." to { mt.shutdown() } }) { Text(tr("Shut down")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirm = "Forget every node the radio knows? It relearns them as they're heard." to { mt.nodeDbReset() } }) { Text(tr("Reset node list")) }
                    OutlinedButton(onClick = { confirm = "Erase all radio settings and channels? Your keys and node list stay. This can't be undone." to { mt.factoryReset(false) } }) {
                        Text(tr("Factory reset"), color = StPaulMeshPalette.Danger)
                    }
                }
            }
        }
        item { Text(tr("Settings appear after the radio finishes sending them, a few seconds after connecting."), Modifier.padding(20.dp), fontSize = 12.sp) }
    }
    confirm?.let { (q, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(tr("Are you sure?")) }, text = { Text(q) },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { ctx.toast(action().exceptionOrNull()?.message ?: "Sent to the radio") } }) { Text(tr("Yes")) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Cancel")) } })
    }
}

private fun roleHelp(r: Config.DeviceConfig.Role): String = when (r.name) {
    "CLIENT" -> "Normal: sends, receives and relays. Right for most people."
    "CLIENT_MUTE" -> "Sends and receives but never relays. Good in crowded places."
    "CLIENT_HIDDEN" -> "Only talks when needed; hidden from others' node lists."
    "TRACKER" -> "Sends its GPS position often. For a tracker with no phone."
    "SENSOR" -> "Sends sensor readings often."
    "TAK", "TAK_TRACKER" -> "Tuned for ATAK users."
    "LOST_AND_FOUND" -> "Broadcasts its location to help find a lost radio."
    "ROUTER" -> "Always relays first. Only for well-placed, always-on infrastructure."
    "ROUTER_LATE" -> "Relays only when others didn't. For infrastructure filling gaps."
    "CLIENT_BASE" -> "A home base that relays your own nodes' traffic first."
    else -> ""
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    HbCard(Modifier.padding(16.dp, 4.dp)) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, onChange)
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() }.take(9)) }, Modifier.fillMaxWidth(), label = { Text(tr(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun <E : Enum<E>> EnumPick(label: String, value: E, options: List<E>, onPick: (E) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", fontWeight = FontWeight.Bold)
        TextButton(onClick = { open = true }) { Text(value.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) }
        DropdownMenu(open, { open = false }) {
            options.filter { !it.name.startsWith("UNRECOGNIZED") && !it.name.contains("DEPRECATED") && it.name != "REPEATER" && it.name != "ROUTER_CLIENT" }.forEach { o ->
                DropdownMenuItem({ Text(o.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) }, { onPick(o); open = false })
            }
        }
    }
}

/** Export or import the whole radio setup as a Meshtastic .cfg DeviceProfile (the official apps' format). */
@Composable
private fun ProfileSection() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withSecurity by remember { mutableStateOf(false) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val mt = app.radios.meshtastic!!
            val me = mt.discoveredNodes.value.firstOrNull { it.id == mt.myNodeId.value }
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(mt.exportProfile(me?.longName, me?.shortName)) }
        }.fold({ ctx.toast(tr("Profile saved. It can include keys: keep it private."), long = true) }, { ctx.toast(it.message, long = true) })
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull() ?: return@launch ctx.toast(tr("Couldn't read that file"))
            app.radios.meshtastic?.importProfile(bytes, keepSecurity = !withSecurity)?.fold({ ctx.toast("Wrote $it sections. The radio restarts to apply them.", long = true) },
                { ctx.toast("That isn't a Meshtastic profile: ${it.message}", long = true) })
        }
    }
    Section("Back up or copy this radio") {
        Text(tr("Saves every setting as a .cfg profile, the same format the official Meshtastic apps use, so you can restore it or set up another radio the same way."), fontSize = 12.sp)
        Toggle("Also import security keys from the file", withSecurity) { withSecurity = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { save.launch("meshtastic-profile.cfg") }) { Text(tr("Export profile")) }
            OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text(tr("Import profile")) }
        }
    }
}

/** ESP32 over-the-air update through Meshtastic's OTA loader, over Wi-Fi or Bluetooth. */
@Composable
private fun OtaSection() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var wifi by remember { mutableStateOf(app.radios.tcpHosts.containsKey(org.highbridge.core.protocol.ProtocolType.MESHTASTIC)) }
    var host by remember { mutableStateOf(app.radios.tcpHosts[org.highbridge.core.protocol.ProtocolType.MESHTASTIC] ?: "") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val log = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin"
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) ctx.toast(tr("Couldn't read that file")) else runCatching { org.highbridge.app.flasher.Esp32Ota.checkImage(name, bytes) }
            .onSuccess { image = name to bytes }.onFailure { ctx.toast(it.message, long = true) }
    }
    Section("Update firmware over the air (ESP32)") {
        Text("For ESP32 radios (Heltec V3/V4, T-Beam, Station G2…) on firmware 2.7.18 or newer that were installed with the web flasher. " +
            "nRF52 radios update over Bluetooth from USB-C & flashing › Bluetooth DFU.", fontSize = 12.sp)
        Text(tr("Get firmware-<your board>-<version>.bin from Firmware updates, then choose it here."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { pick.launch(arrayOf("application/octet-stream", "*/*")) }) { Text(image?.first ?: "Choose firmware file") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.FilterChip(wifi, { wifi = true }, { Text("Wi-Fi") })
            androidx.compose.material3.FilterChip(!wifi, { wifi = false }, { Text("Bluetooth") })
        }
        if (wifi) OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text(tr("Radio's IP address")) }, singleLine = true)
        if (busy) androidx.compose.material3.LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
        log.takeLast(6).forEach { Text(it, fontSize = 11.sp) }
        Button(enabled = image != null && !busy && (!wifi || host.isNotBlank()), onClick = {
            val (_, bytes) = image ?: return@Button
            val mt = app.radios.meshtastic ?: run { ctx.toast(tr("Connect the radio first")); return@Button }
            val bleAddr = app.prefs.lastDevices.value.firstOrNull { it.startsWith("BLE|MESHTASTIC|") }?.substringAfterLast('|')
            if (!wifi && bleAddr == null) { ctx.toast(tr("Connect this radio over Bluetooth once first"), long = true); return@Button }
            busy = true; log.clear(); progress = 0f
            scope.launch {
                val hash = org.highbridge.app.flasher.Esp32Ota.sha256(bytes)
                log.add("Asking the radio to restart into its updater…")
                val r = mt.rebootToOta(wifi, hash).mapCatching {
                    app.radios.disconnect(org.highbridge.core.protocol.ProtocolType.MESHTASTIC)
                    if (wifi) org.highbridge.app.flasher.Esp32Ota.updateWifi(host, bytes, { log.add(it) }, { progress = it }).getOrThrow()
                    else {
                        val dev = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(bleAddr)
                        org.highbridge.app.flasher.Esp32Ota.updateBle(ctx, dev, bytes, { log.add(it) }, { progress = it }).getOrThrow()
                    }
                }
                busy = false
                ctx.toast(r.exceptionOrNull()?.let { "Update failed: ${it.message}. If the radio doesn't start, update it over USB-C." } ?: "Firmware updated. Reconnect in a minute.", long = true)
            }
        }) { Text(if (busy) "Updating… keep the app open" else "Update now") }
    }
}
