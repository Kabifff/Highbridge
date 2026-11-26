// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.highbridge.app.BuildConfig
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.data.ChannelEntity
import org.highbridge.app.data.GeofenceEntity
import org.highbridge.app.data.MessageEntity
import org.highbridge.app.data.NodeEntity
import org.highbridge.app.pro.AppSecurityManager
import org.highbridge.app.pro.EncryptedBackupManager
import org.highbridge.app.pro.PinResult
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.HeroLock
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.core.protocol.LocationUpdateMode
import org.highbridge.core.protocol.PowerProfile
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsScreen() {
    val app = HighbridgeApp.instance
    val p = app.prefs
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val name by p.displayName.collectAsState()
    val mode by p.locationMode.collectAsState()
    val share by p.shareLocation.collectAsState()
    val power by p.powerProfile.collectAsState()
    val auto by p.autoLowBattery.collectAsState()
    val metric by p.metric.collectAsState()
    val dark by p.darkMode.collectAsState()
    val stale by p.staleMinutes.collectAsState()
    val rnsAuto by p.rnsAuto.collectAsState()
    val tcp by p.rnsTcpHosts.collectAsState()
    val pin by p.pinRecord.collectAsState()
    val battery by app.power.batteryPct.collectAsState()
    var newPin by remember { mutableStateOf("") }
    var duressPin by remember { mutableStateOf("") }
    var backupPw by remember { mutableStateOf("") }
    var nameDraft by remember { mutableStateOf(name) }

    val exportDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val pw = backupPw.toCharArray()
        scope.launch {
            runCatching {
                val blob = withContext(Dispatchers.IO) { EncryptedBackupManager.encrypt(Backup.export(app).toByteArray(), pw) }
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(blob) }
            }.let { Toast.makeText(ctx, it.exceptionOrNull()?.message ?: "Backup saved (encrypted with your password)", Toast.LENGTH_LONG).show() }
        }
    }
    val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val pw = backupPw.toCharArray()
        scope.launch {
            runCatching {
                val blob = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val json = withContext(Dispatchers.IO) { String(EncryptedBackupManager.decrypt(blob, pw)) }
                Backup.import(app, json)
            }.let { Toast.makeText(ctx, it.exceptionOrNull()?.let { "Couldn't restore: wrong password or damaged file" } ?: "Restored", Toast.LENGTH_LONG).show() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HbHeader("Settings", "Highbridge ${BuildConfig.VERSION_NAME}", onBack = { nav.popBackStack() })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HbCard {
                Text("Your name on the mesh", fontWeight = FontWeight.Bold)
                OutlinedTextField(nameDraft, { nameDraft = it.take(39) }, Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    p.setDisplayName(nameDraft)
                    scope.launch {
                        app.radios.radios.value.values.forEach { it.setOwnerName(nameDraft, nameDraft.filter { c -> c.isLetterOrDigit() }.take(4).ifBlank { "HB" }) }
                        Toast.makeText(ctx, "Saved and sent to your radios", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save") }
            }
            HbCard {
                Text("Location", fontWeight = FontWeight.Bold)
                LocationUpdateMode.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == m, { p.setLocationMode(m) }); Column { Text(m.title + if (m.isRecommended) " (recommended)" else ""); Text(m.subtitle, fontSize = 12.sp) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Share my position with the mesh", Modifier.weight(1f)); Switch(share, { p.setShareLocation(it) }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Metric units", Modifier.weight(1f)); Switch(metric, { p.setMetric(it) }) }
                Text("Hide nodes not heard for ${stale / 60} h", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(60, 120, 720, 1440, 10080).forEach { m -> FilterChip(stale == m, { p.setStaleMinutes(m) }, { Text(if (m < 1440) "${m / 60} h" else "${m / 1440} d") }) } }
            }
            HbCard {
                Text("Power", fontWeight = FontWeight.Bold)
                battery?.let { Text("Phone battery $it%", fontSize = 12.sp) }
                PowerProfile.entries.forEach { pr ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(power == pr, { p.setPowerProfile(pr) }); Column { Text(pr.title); Text(pr.summary, fontSize = 12.sp) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Switch to Ultra-Low Power below 20% battery", Modifier.weight(1f)); Switch(auto, { p.setAutoLowBattery(it) }) }
            }
            HbCard {
                Text("Appearance", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("SYSTEM" to "Match phone", "LIGHT" to "Light", "DARK" to "Fort Road at night").forEach { (k, l) -> FilterChip(dark == k, { p.setDarkMode(k) }, { Text(l) }) } }
            }
            HbCard {
                Text("Reticulum", fontWeight = FontWeight.Bold); Text("Changes here take effect the next time the app starts.", fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Find peers on Wi-Fi (AutoInterface)", Modifier.weight(1f)); Switch(rnsAuto, { p.setRnsAuto(it) }) }
                var hosts by remember { mutableStateOf(tcp) }
                OutlinedTextField(hosts, { hosts = it }, Modifier.fillMaxWidth(), label = { Text("TCP hosts, comma-separated (host:port)") }, singleLine = true)
                TextButton(onClick = { p.setRnsTcpHosts(hosts) }) { Text("Save hosts") }
            }
            HbCard {
                Text("PIN lock", fontWeight = FontWeight.Bold)
                Text(if (pin == null) "Off" else "On. A duress PIN, if set, erases everything when entered.", fontSize = 12.sp)
                OutlinedTextField(newPin, { newPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("New PIN (4–12 digits)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(duressPin, { duressPin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("Duress PIN (optional, wipes the app)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = newPin.length >= 4 && duressPin != newPin, onClick = {
                        scope.launch {
                            val rec = withContext(Dispatchers.Default) { AppSecurityManager.create(newPin) }
                            p.setPin("${rec.saltB64}|${rec.hashB64}|${rec.iterations}")
                            if (duressPin.length >= 4) withContext(Dispatchers.Default) { AppSecurityManager.create(duressPin) }.let { d -> p.setDuress("${d.saltB64}|${d.hashB64}|${d.iterations}") } else p.setDuress(null)
                            newPin = ""; duressPin = ""
                            Toast.makeText(ctx, "PIN lock on", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Turn on") }
                    if (pin != null) OutlinedButton(onClick = { p.setPin(null); p.setDuress(null) }) { Text("Turn off") }
                }
            }
            HbCard {
                Text("Encrypted backup", fontWeight = FontWeight.Bold)
                Text("Messages, nodes, channel keys, geofences and settings, sealed with AES-256-GCM and your password.", fontSize = 12.sp)
                OutlinedTextField(backupPw, { backupPw = it }, Modifier.fillMaxWidth(), label = { Text("Backup password (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = backupPw.length >= 8, onClick = { exportDoc.launch("highbridge-backup.hbk") }) { Text("Back up") }
                    OutlinedButton(enabled = backupPw.length >= 8, onClick = { importDoc.launch(arrayOf("*/*")) }) { Text("Restore") }
                }
            }
            SectionLabel("About", Modifier.padding(0.dp))
            Text("Highbridge is named for Saint Paul's High Bridge, which joins West 7th to the West Side bluffs.", style = MaterialTheme.typography.bodyMedium)
            Text(LEGAL_NOTICE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { nav.navigate("licenses") }) { Text("Open-source licenses") }
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }) { Text("Source code") }
            }
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://stpaulmesh.com"))) }) { Text("stpaulmesh.com") }
            Text("Map data © OpenStreetMap contributors.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

object Backup {
    suspend fun export(app: HighbridgeApp): String = withContext(Dispatchers.IO) {
        val dao = app.db.dao()
        JSONObject().apply {
            put("version", 1)
            put("messages", JSONArray(dao.recentMessages(20_000).map { m -> JSONObject(mapOf("packetId" to m.packetId, "protocol" to m.protocol, "conversation" to m.conversation,
                "fromId" to m.fromId, "fromName" to m.fromName, "text" to m.text, "timeMs" to m.timeMs, "outgoing" to m.outgoing, "status" to m.status)) }))
            put("nodes", JSONArray(dao.nodesNow().map { n -> JSONObject(mapOf("key" to n.key, "protocol" to n.protocol, "id" to n.id, "shortName" to n.shortName, "longName" to n.longName,
                "lat" to n.lat, "lon" to n.lon, "lastHeardMs" to n.lastHeardMs, "favorite" to n.favorite)) }))
            put("channels", JSONArray(dao.channelsNow().map { c -> JSONObject(mapOf("id" to c.id, "protocol" to c.protocol, "slot" to c.slot, "name" to c.name, "key" to c.keyB64, "created" to c.createdMs)) }))
            put("geofences", JSONArray(dao.activeGeofences().map { g -> JSONObject(mapOf("name" to g.name, "kind" to g.kind, "points" to g.points, "radiusM" to g.radiusM, "nodeKey" to g.nodeKey)) }))
            put("displayName", app.prefs.displayName.value)
            put("region", app.prefs.region.value.code)
        }.toString()
    }

    suspend fun import(app: HighbridgeApp, json: String) = withContext(Dispatchers.IO) {
        val o = JSONObject(json); val dao = app.db.dao()
        o.optJSONArray("channels")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { c -> dao.upsertChannel(ChannelEntity(c.getString("id"), c.getString("protocol"), c.getInt("slot"), c.getString("name"), c.getString("key"), c.optLong("created"))) } }
        o.optJSONArray("nodes")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { n -> dao.upsertNode(NodeEntity(n.getString("key"), n.getString("protocol"), n.getString("id"), n.getString("shortName"), n.getString("longName"),
            n.optDouble("lat").takeIf { !it.isNaN() }, n.optDouble("lon").takeIf { !it.isNaN() }, lastHeardMs = n.optLong("lastHeardMs"), favorite = n.optBoolean("favorite"))) } }
        o.optJSONArray("messages")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { m ->
            if (dao.countPacket(m.getString("packetId"), m.getString("protocol")) == 0) dao.insertMessage(MessageEntity(packetId = m.getString("packetId"), protocol = m.getString("protocol"),
                conversation = m.getString("conversation"), fromId = m.getString("fromId"), fromName = m.getString("fromName"), text = m.getString("text"), timeMs = m.getLong("timeMs"),
                outgoing = m.getBoolean("outgoing"), status = m.optInt("status", 4), read = true))
        } }
        o.optJSONArray("geofences")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { g -> dao.upsertGeofence(GeofenceEntity(name = g.getString("name"), kind = g.getString("kind"),
            points = g.getString("points"), radiusM = g.getDouble("radiusM"), nodeKey = g.optString("nodeKey").ifBlank { null }.takeIf { it != "null" })) } }
        o.optString("displayName").takeIf { it.isNotBlank() }?.let { app.prefs.setDisplayName(it) }
        org.highbridge.core.protocol.MeshtasticRegion.fromCode(o.optInt("region", 0))?.let { app.prefs.setRegion(it) }
    }
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val app = HighbridgeApp.instance
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val manager = remember {
        fun rec(s: String?) = s?.split('|')?.takeIf { it.size == 3 }?.let { AppSecurityManager.PinRecord(it[0], it[1], it[2].toInt()) }
        AppSecurityManager(rec(app.prefs.pinRecord.value)!!, rec(app.prefs.duressRecord.value))
    }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        HeroLock()
        Text("Highbridge", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Button(enabled = pin.length >= 4, onClick = {
            val entered = pin; pin = ""
            scope.launch {
                when (withContext(Dispatchers.Default) { manager.verify(entered) }) {
                    PinResult.UNLOCKED -> onUnlocked()
                    PinResult.DURESS_WIPE -> app.wipeEverything()
                    PinResult.LOCKED_OUT -> msg = "Too many tries. Wait a bit and try again."
                    PinResult.INVALID -> msg = "Wrong PIN"
                }
            }
        }) { Text("Unlock") }
        msg?.let { Text(it, color = StPaulMeshPalette.Danger, textAlign = TextAlign.Center) }
    }
}
