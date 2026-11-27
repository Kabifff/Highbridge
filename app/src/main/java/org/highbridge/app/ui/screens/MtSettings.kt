// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

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
fun MeshtasticSettingsScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mt = app.radios.meshtastic
    if (mt == null) {
        Column { HbHeader("Meshtastic settings", "Not connected", onBack = { nav.popBackStack() }); Text("Connect your Meshtastic radio first.", Modifier.padding(20.dp)) }
        return
    }
    val s by mt.settings.collectAsState()
    fun saved(r: Result<Unit>, what: String) = ctx.toast(r.exceptionOrNull()?.message ?: "$what saved. Some changes restart the radio.")
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Result<Unit>>?>(null) }

    LazyColumn {
        item { HbHeader("Meshtastic settings", "Stored on your radio", onBack = { nav.popBackStack() }) }

        s.device?.let { d -> item {
            Section("Device") {
                var role by remember(d) { mutableStateOf(d.role) }
                var rebroadcast by remember(d) { mutableStateOf(d.rebroadcast_mode) }
                var nodeInfo by remember(d) { mutableStateOf(d.node_info_broadcast_secs.toString()) }
                EnumPick("Role", role, Config.DeviceConfig.Role.entries) { role = it }
                Text(roleHelp(role), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                EnumPick("Rebroadcast", rebroadcast, Config.DeviceConfig.RebroadcastMode.entries) { rebroadcast = it }
                NumField("Node info every (seconds)", nodeInfo) { nodeInfo = it }
                Button(onClick = { scope.launch { saved(mt.setConfig(Config(device = d.copy(role = role, rebroadcast_mode = rebroadcast,
                    node_info_broadcast_secs = nodeInfo.toIntOrNull() ?: d.node_info_broadcast_secs))), "Device") } }) { Text("Save device") }
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
                Button(onClick = { scope.launch { saved(mt.setConfig(Config(position = p.copy(position_broadcast_secs = every.toIntOrNull() ?: p.position_broadcast_secs,
                    position_broadcast_smart_enabled = smart, fixed_position = fixed, gps_mode = gps))), "Position") } }) { Text("Save position") }
            }
        } }

        s.power?.let { p -> item {
            Section("Power") {
                var saving by remember(p) { mutableStateOf(p.is_power_saving) }
                var shutdownAfter by remember(p) { mutableStateOf(p.on_battery_shutdown_after_secs.toString()) }
                Toggle("Power saving (sleeps; best for solar nodes without Bluetooth use)", saving) { saving = it }
                NumField("Shut down after on battery (seconds, 0 = never)", shutdownAfter) { shutdownAfter = it }
                Button(onClick = { scope.launch { saved(mt.setConfig(Config(power = p.copy(is_power_saving = saving,
                    on_battery_shutdown_after_secs = shutdownAfter.toIntOrNull() ?: p.on_battery_shutdown_after_secs))), "Power") } }) { Text("Save power") }
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
                OutlinedTextField(addr, { addr = it }, Modifier.fillMaxWidth(), label = { Text("Server (blank = mqtt.meshtastic.org)") }, singleLine = true)
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(root, { root = it }, Modifier.fillMaxWidth(), label = { Text("Root topic") }, singleLine = true)
                Toggle("Encrypted uplink", enc) { enc = it }
                Toggle("Also publish JSON", json) { json = it }
                Toggle("TLS", tls) { tls = it }
                Toggle("Use the phone's internet (client proxy)", proxy) { proxy = it }
                Toggle("Report to the public map", mapRep) { mapRep = it }
                Button(onClick = { scope.launch { saved(mt.setModule(ModuleConfig(mqtt = m.copy(enabled = on, address = addr.trim(), username = user.trim(), password = pass,
                    root = root.trim(), encryption_enabled = enc, json_enabled = json, tls_enabled = tls, proxy_to_client_enabled = proxy, map_reporting_enabled = mapRep))), "MQTT") } }) { Text("Save MQTT") }
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
                Button(onClick = { scope.launch { saved(mt.setModule(ModuleConfig(telemetry = t.copy(device_update_interval = dev.toIntOrNull() ?: t.device_update_interval,
                    environment_measurement_enabled = envOn, environment_update_interval = env.toIntOrNull() ?: t.environment_update_interval,
                    power_measurement_enabled = powOn))), "Telemetry") } }) { Text("Save telemetry") }
            }
        } }

        item {
            Section("Other modules") {
                s.neighborInfo?.let { n ->
                    Toggle("Neighbor info (shares who it hears)", n.enabled) { v -> scope.launch { saved(mt.setModule(ModuleConfig(neighbor_info = n.copy(enabled = v, update_interval = maxOf(n.update_interval, 14400)))), "Neighbor info") } }
                }
                s.storeForward?.let { f ->
                    Toggle("Store & forward server (needs PSRAM)", f.enabled) { v -> scope.launch { saved(mt.setModule(ModuleConfig(store_forward = f.copy(enabled = v))), "Store & forward") } }
                }
                s.rangeTest?.let { r ->
                    Toggle("Range test module", r.enabled) { v -> scope.launch { saved(mt.setModule(ModuleConfig(range_test = r.copy(enabled = v))), "Range test") } }
                }
                s.externalNotification?.let { e ->
                    Toggle("External notification (buzzer/LED on message)", e.enabled) { v ->
                        scope.launch { saved(mt.setModule(ModuleConfig(external_notification = e.copy(enabled = v, alert_message = v || e.alert_message))), "External notification") } }
                }
                s.detectionSensor?.let { d ->
                    Toggle("Detection sensor", d.enabled) { v -> scope.launch { saved(mt.setModule(ModuleConfig(detection_sensor = d.copy(enabled = v))), "Detection sensor") } }
                }
                s.paxcounter?.let { p ->
                    Toggle("Paxcounter (counts nearby phones)", p.enabled) { v -> scope.launch { saved(mt.setModule(ModuleConfig(paxcounter = p.copy(enabled = v))), "Paxcounter") } }
                }
                s.cannedMessage?.let {
                    var canned by remember { mutableStateOf("") }
                    OutlinedTextField(canned, { canned = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("Canned messages for the radio's buttons (separate with |)") })
                    TextButton(onClick = { scope.launch { saved(mt.setCannedMessages(canned), "Canned messages") } }, enabled = canned.isNotBlank()) { Text("Save canned messages") }
                }
            }
        }

        s.security?.let { sec -> item {
            Section("Security") {
                Text("Public key: " + sec.public_key.base64().take(24) + "…", fontSize = 12.sp)
                Text("Direct messages are end-to-end encrypted with these keys on firmware 2.5 and later.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Toggle("Managed mode (only admins can change settings)", sec.is_managed) { v -> scope.launch { saved(mt.setConfig(Config(security = sec.copy(is_managed = v))), "Security") } }
                Toggle("Legacy admin channel", sec.admin_channel_enabled) { v -> scope.launch { saved(mt.setConfig(Config(security = sec.copy(admin_channel_enabled = v))), "Security") } }
            }
        } }

        item {
            Section("Radio actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirm = "Restart the radio?" to { mt.reboot() } }) { Text("Restart") }
                    OutlinedButton(onClick = { confirm = "Turn the radio off? You'll need to press its button to turn it back on." to { mt.shutdown() } }) { Text("Shut down") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirm = "Forget every node the radio knows? It relearns them as they're heard." to { mt.nodeDbReset() } }) { Text("Reset node list") }
                    OutlinedButton(onClick = { confirm = "Erase all radio settings and channels? Your keys and node list stay. This can't be undone." to { mt.factoryReset(false) } }) {
                        Text("Factory reset", color = StPaulMeshPalette.Danger)
                    }
                }
            }
        }
        item { Text("Settings appear after the radio finishes sending them, a few seconds after connecting.", Modifier.padding(20.dp), fontSize = 12.sp) }
    }
    confirm?.let { (q, action) ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Are you sure?") }, text = { Text(q) },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { ctx.toast(action().exceptionOrNull()?.message ?: "Sent to the radio") } }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
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
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() }.take(9)) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
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
