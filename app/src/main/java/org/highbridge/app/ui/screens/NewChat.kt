// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.radio.fromEntity
import org.highbridge.app.ui.ProtocolBadge
import org.highbridge.app.ui.timeAgo
import org.highbridge.app.ui.toast
import org.highbridge.core.protocol.EncryptedChannel
import org.highbridge.core.protocol.ProtocolType

private enum class NewChatMode { MENU, DIRECT, PRIVATE, HASHTAG }

/** The chat list's + button: message a person, start a private channel, or join a MeshCore #channel. */
@Composable
fun NewChatDialog(onDismiss: () -> Unit) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val radios by app.radios.radios.collectAsState()
    val connected = supportedProtocols.filter { it in radios.keys }
    var mode by remember { mutableStateOf(NewChatMode.MENU) }
    var name by remember { mutableStateOf("") }
    var proto by remember { mutableStateOf(connected.firstOrNull() ?: ProtocolType.MESHTASTIC) }
    var busy by remember { mutableStateOf(false) }

    fun open(conv: String) { onDismiss(); nav.navigate("chat/${Uri.encode(conv)}") }
    fun join(c: EncryptedChannel) {
        busy = true
        scope.launch {
            app.radios.applyChannel(c)
                .onSuccess { placed -> busy = false; ctx.toast("${placed.name} is on your radio, slot ${placed.slotIndex}"); open("${placed.protocol.name}:ch:${placed.slotIndex}") }
                .onFailure { busy = false; ctx.toast(it.message, long = true) }
        }
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(when (mode) {
            NewChatMode.MENU -> "New chat"
            NewChatMode.DIRECT -> "Message someone"
            NewChatMode.PRIVATE -> "New private channel"
            NewChatMode.HASHTAG -> "Join a MeshCore #channel"
        }) },
        text = {
            when (mode) {
                NewChatMode.MENU -> Column {
                    MenuRow("Message someone directly", "A private conversation with one node, end-to-end encrypted by the radio") { mode = NewChatMode.DIRECT }
                    MenuRow("New private channel", "A group with a random key. Share it by QR code with the people you invite") { mode = NewChatMode.PRIVATE }
                    if (ProtocolType.MESHCORE in radios) MenuRow("Join a #channel", "MeshCore hashtag channels: anyone who knows the name can join") { mode = NewChatMode.HASHTAG }
                    MenuRow("Scan a channel QR code", "Join a channel someone shared with you (Highbridge or MeshCore app codes)") { onDismiss(); nav.navigate("channels") }
                }
                NewChatMode.DIRECT -> DirectPicker(name, { name = it }) { conv -> open(conv) }
                NewChatMode.PRIVATE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { connected.forEach { p -> FilterChip(proto == p, { proto = p }, { Text(p.shortTag) }) } }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Channel name") }, singleLine = true)
                    val err = name.trim().takeIf { it.isNotEmpty() }?.let { EncryptedChannel.validate(proto, EncryptedChannel.AUTO_SLOT, it) }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Text("It goes in the next free slot on your ${proto.shortTag} radio. Only people you share the code with can read it.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                NewChatMode.HASHTAG -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Channel") }, singleLine = true,
                        prefix = { Text("#") }, placeholder = { Text("stpaul") })
                    if (name.isNotBlank()) Text("Joins ${EncryptedChannel.normalizeHashtag(name)}", fontSize = 12.sp)
                    Text("Hashtag channels aren't private: anyone who knows the name can read them. Use a private channel for anything personal.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            when (mode) {
                NewChatMode.PRIVATE -> TextButton(enabled = !busy && name.isNotBlank() && proto in connected &&
                    EncryptedChannel.validate(proto, EncryptedChannel.AUTO_SLOT, name.trim()) == null, onClick = {
                    join(EncryptedChannel.createRandom(proto, EncryptedChannel.AUTO_SLOT, name))
                }) { Text(if (busy) "Creating…" else "Create") }
                NewChatMode.HASHTAG -> TextButton(enabled = !busy && name.trim().trimStart('#').isNotEmpty(), onClick = {
                    runCatching { EncryptedChannel.meshCoreHashtag(name) }.onSuccess { join(it) }.onFailure { ctx.toast(it.message) }
                }) { Text(if (busy) "Joining…" else "Join") }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = { if (mode == NewChatMode.MENU) onDismiss() else { mode = NewChatMode.MENU; name = "" } }) {
                Text(if (mode == NewChatMode.MENU) "Cancel" else "Back")
            }
        })
}

@Composable
private fun MenuRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun DirectPicker(query: String, onQuery: (String) -> Unit, onPick: (String) -> Unit) {
    val app = HighbridgeApp.instance
    val radios by app.radios.radios.collectAsState()
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val shown = nodes.filter { n ->
        runCatching { ProtocolType.valueOf(n.protocol) }.getOrNull() in radios.keys && !n.id.startsWith("name:") &&
            (query.isBlank() || n.longName.contains(query, true) || n.shortName.contains(query, true) || n.id.contains(query, true))
    }.sortedByDescending { it.lastHeardMs }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), label = { Text("Search nodes") }, singleLine = true)
        if (shown.isEmpty()) Text(if (nodes.isEmpty()) "No nodes heard yet. Leave your radio on for a few minutes." else "No match.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            items(shown, key = { it.key }) { n ->
                Row(Modifier.fillMaxWidth().clickable { onPick("${n.protocol}:dm:${n.id}") }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProtocolBadge(ProtocolType.valueOf(n.protocol))
                    Column(Modifier.weight(1f)) {
                        Text(n.longName, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${n.shortName} · ${timeAgo(n.lastHeardMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Header buttons in a channel chat: share its key by QR code, or leave it. */
@Composable
fun ChannelActions(protocol: ProtocolType, slot: Int, title: String) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by app.db.dao().channels().collectAsState(emptyList())
    val entity = saved.firstOrNull { it.protocol == protocol.name && it.slot == slot }
    var share by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    if (entity != null) IconButton(onClick = { share = true }) { Icon(Icons.Filled.QrCode2, "Share channel") }
    if (slot > 0) IconButton(onClick = { leave = true }) { Icon(Icons.AutoMirrored.Filled.Logout, "Leave channel") }
    if (share && entity != null) ChannelQrDialog(EncryptedChannel.fromEntity(entity)) { share = false }
    if (leave) AlertDialog(onDismissRequest = { leave = false },
        title = { Text("Leave $title?") },
        text = { Text("This removes the channel from your radio and forgets its key. Your message history stays. You can rejoin later with its code.") },
        confirmButton = { TextButton(onClick = {
            leave = false
            scope.launch { app.radios.leaveChannel(protocol, slot).onSuccess { ctx.toast("Left $title"); nav.popBackStack() }.onFailure { ctx.toast(it.message, long = true) } }
        }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("Cancel") } })
}

/** QR code and share link for a channel. MeshCore channels also get a link the MeshCore app opens. */
@Composable
fun ChannelQrDialog(c: EncryptedChannel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val hashtag = EncryptedChannel.isHashtag(c)
    val code = if (c.protocol == ProtocolType.MESHCORE) EncryptedChannel.toMeshCoreUri(c) else c.toShareUri()
    val bmp = remember(code) { BarcodeEncoder().encodeBitmap(code, BarcodeFormat.QR_CODE, 720, 720) }
    fun send(text: String) = ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share channel"))
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { send(code) }) { Text("Share link") } },
        title = { Text(c.name) },
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(bmp.asImageBitmap(), "Channel QR code", Modifier.size(260.dp))
            Text(when {
                hashtag -> "Anyone can join ${c.name} by typing its name. This code just saves them the typing."
                c.protocol == ProtocolType.MESHCORE -> "Works in Highbridge and the MeshCore app. Anyone who scans it can read and post here, so only show it to people you trust."
                else -> "Anyone who scans this can read and post on this channel. Only show it to people you trust."
            }, fontSize = 12.sp)
        } })
}
