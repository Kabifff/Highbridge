package org.highbridge.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.pro.MicronParser
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.protocol.meshcore.MC

/** Reads NomadNet pages (Micron markup) from Reticulum nodes heard on the network. */
@Composable
fun MicronScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val rns = app.radios.reticulum
    val servers = rns?.nomadNodes?.collectAsState()?.value ?: emptyList()
    var node by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/page/index.mu") }
    var page by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<Pair<String, String>>() }
    val link = MaterialTheme.colorScheme.primary

    fun load(n: String, p: String) {
        if (rns == null) { status = "Start Reticulum first (Connect a radio › Reticulum)"; return }
        node = n; path = p; status = "Loading $p…"; page = null
        scope.launch {
            rns.fetchPage(n, p).onSuccess { page = it; status = null; history.add(n to p) }.onFailure { status = it.message }
        }
    }

    Column(Modifier.fillMaxSize()) {
        HbHeader("NomadNet", "Micron pages over Reticulum", onBack = { if (history.size > 1) { history.removeAt(history.lastIndex); history.last().let { load(it.first, it.second) } } else nav.popBackStack() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(node, { node = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Node address (hex)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(path, { path = it }, Modifier.weight(1f), label = { Text("Page") }, singleLine = true)
                TextButton(onClick = { load(node, path) }, enabled = node.length >= 32) { Text("Open") }
            }
            if (servers.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(servers) { s -> AssistChip({ load(s.id, "/page/index.mu") }, { Text(s.longName.take(20)) }) }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        page?.let { text ->
            val annotated = remember(text) { MicronParser.parseToAnnotatedString(text, link) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                @Suppress("DEPRECATION")
                ClickableText(annotated, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)) { off ->
                    annotated.getStringAnnotations("micron", off, off).firstOrNull()?.let { a ->
                        val target = a.item
                        // Links look like "node:/page/x.mu", ":/page/x.mu" (same node) or "/page/x.mu".
                        val (n, p) = when {
                            target.startsWith(":") -> node to target.removePrefix(":")
                            target.contains(":/") -> target.substringBefore(":/") to "/" + target.substringAfter(":/")
                            else -> node to target
                        }
                        load(n.ifBlank { node }, p)
                    }
                }
            }
        }
    }
}

/** MeshCore repeater and room-server admin: log in, read status, run commands, and hand-edit the route. */
@Composable
fun RepeaterAdminScreen(id: String) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val mc = app.radios.meshcore
    val contacts = mc?.contacts?.collectAsState()?.value ?: emptyMap()
    val c = contacts[id]
    var password by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    val replies = remember { mutableStateListOf<String>() }
    val path = remember(c) { mutableStateListOf<String>().apply { c?.directPath?.forEach { add("%02x".format(it)) } } }
    LaunchedEffect(Unit) { app.radios.adminReplies.collect { if (it.fromId.startsWith(id.take(8)) || it.fromId == id) replies.add(0, it.text) } }
    fun toast(s: String?) = Toast.makeText(ctx, s ?: "Sent", Toast.LENGTH_SHORT).show()

    if (mc == null || c == null) { Column { HbHeader("Repeater admin", onBack = { nav.popBackStack() }); Text("Connect the MeshCore radio that knows this contact.", Modifier.padding(20.dp)) }; return }
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader(c.name, if (c.isRepeater) "Repeater" else if (c.isRoom) "Room server" else "Contact", onBack = { nav.popBackStack() }) }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HbCard {
                    Text("Log in", fontWeight = FontWeight.Bold)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Admin or guest password") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { toast(mc.login(id, password).exceptionOrNull()?.message ?: "Login sent; the reply shows below") } }) { Text("Log in") }
                        OutlinedButton(onClick = { scope.launch { toast(mc.requestStatus(id).exceptionOrNull()?.message) } }) { Text("Get status") }
                    }
                    Text("The password goes in MeshCore's encrypted login request, not as a chat message.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HbCard {
                    Text("Remote command", fontWeight = FontWeight.Bold)
                    OutlinedTextField(cmd, { cmd = it }, Modifier.fillMaxWidth(), label = { Text("e.g. ver, get name, set tx 20, advert, reboot") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    Button(enabled = cmd.isNotBlank(), onClick = { val k = cmd; cmd = ""; replies.add(0, "> $k"); scope.launch { mc.remoteCli(id, k).onFailure { toast(it.message) } } }) { Text("Send command") }
                    replies.take(20).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
                HbCard {
                    Text("Path editor", fontWeight = FontWeight.Bold)
                    Text(if (c.directPath == null) "No known path: messages flood the mesh." else "Route: You → ${path.joinToString(" → ")} → ${c.name}", fontSize = 13.sp)
                    Text("Each hop is a repeater's 1-byte ID. Tap a repeater below to add it, tap a hop to remove it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(path.toList().withIndex().toList()) { (i, h) -> InputChip(true, { path.removeAt(i) }, { Text(contacts.values.firstOrNull { "%02x".format(it.publicKey[0]) == h }?.name?.take(12) ?: h) }) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(contacts.values.filter { it.isRepeater && it.prefixHex != id }) { r -> AssistChip({ if (path.size < MC.MAX_PATH_SIZE) path.add("%02x".format(r.publicKey[0])) }, { Text("+ ${r.name.take(14)}") }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { toast(mc.setPath(id, path.map { it.toInt(16).toByte() }.toByteArray()).exceptionOrNull()?.message ?: "Path saved") } }) { Text("Save path") }
                        OutlinedButton(onClick = { scope.launch { toast(mc.resetPath(id).exceptionOrNull()?.message ?: "Path reset; the next message will flood") } }) { Text("Reset path") }
                    }
                }
            }
        }
    }
}

