// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import org.highbridge.app.ui.toast
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.data.MessageEntity
import org.highbridge.app.ui.Dot
import org.highbridge.app.ui.EmptyState
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.ProtocolBadge
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.theme.DisplayFont
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.app.ui.theme.protocolColor
import org.highbridge.app.ui.timeAgo
import org.highbridge.core.media.VoiceIO
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.ProtocolType
import java.io.File
import java.text.DateFormat
import java.util.Date

fun conversationTitle(conv: String, nodeNames: Map<String, String>, channelNames: Map<String, String>): String {
    val parts = conv.split(':', limit = 3)
    return if (parts.getOrNull(1) == "ch") channelNames[conv] ?: if (parts[2] == "0") "Primary channel" else "Channel ${parts[2]}"
    else nodeNames["${parts[0]}:${parts.getOrElse(2) { "" }}"] ?: parts.getOrElse(2) { "?" }
}

@Composable
fun ChatsScreen(openDrawer: () -> Unit) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val convs by app.db.dao().conversations().collectAsState(emptyList())
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val radios by app.radios.radios.collectAsState()
    val bridge = org.highbridge.feature.bridge.MeshBridgeService.current
    val names = nodes.associate { it.key to it.longName }
    val radioChannels by remember(radios) {
        if (radios.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList())
        else kotlinx.coroutines.flow.combine(radios.values.map { it.channels }) { lists -> lists.flatMap { it.toList() } }
    }.collectAsState(emptyList())
    val channelNames = radioChannels.associate { "${it.protocol.name}:ch:${it.index}" to it.name }
    var newChat by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        HbHeader("Highbridge", "West 7th · ${radios.size} radio${if (radios.size == 1) "" else "s"} connected", skyline = true, actions = {
            IconButton(onClick = { nav.navigate("search") }) { Icon(Icons.Filled.Search, "Search messages") }
            IconButton(onClick = openDrawer) { Icon(Icons.Filled.Menu, "Menu") }
        })
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (radios.isEmpty()) TextButton(onClick = { nav.navigate("connect") }) { Text(tr("Connect a radio")) }
            radios.values.forEach { r ->
                val dev by r.deviceInfo.collectAsState()
                Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(protocolColor(r.protocol)); Text("${r.protocol.shortTag} · ${dev?.substringAfter("· ")?.take(14) ?: "connected"}", fontSize = 12.sp)
                }
            }
        }
        if (bridge != null) {
            val st by bridge.stats.collectAsState()
            if (st.isRunning) Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                .clickable { nav.navigate("bridge") }.padding(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Bridge is on"), fontWeight = FontWeight.Bold)
                    Text("${st.activeMode.label} · ${st.forwardedCount} relayed", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (convs.isEmpty()) {
            EmptyState("No messages yet", if (radios.isEmpty()) "Connect a Meshtastic, MeshCore or RNode radio to start." else "Messages from your mesh will show up here.") {
                if (radios.isNotEmpty()) TextButton(onClick = { radios.keys.first().let { nav.navigate("chat/${Uri.encode("${it.name}:ch:0")}") } }) { Text(tr("Open the primary channel")) }
            }
        }
        Box(Modifier.weight(1f)) {
            LazyColumn {
                val (channels, dms) = convs.partition { it.conversation.split(':')[1] == "ch" }
                // Every channel on a connected radio shows here, even before its first message.
                val quiet = channelNames.keys.filter { k -> channels.none { it.conversation == k } }.sorted()
                if (channels.isNotEmpty() || quiet.isNotEmpty()) item { SectionLabel(tr("Channels")) }
                items(channels) { c -> ConversationRow(c.conversation, conversationTitle(c.conversation, names, channelNames), "${c.lastFrom}: ${c.lastText}", c.lastTime, c.unread) }
                items(quiet) { k -> ConversationRow(k, conversationTitle(k, names, channelNames), "No messages yet", 0L, 0) }
                if (dms.isNotEmpty()) item { SectionLabel(tr("Direct messages")) }
                items(dms) { c -> ConversationRow(c.conversation, conversationTitle(c.conversation, names, channelNames), c.lastText, c.lastTime, c.unread) }
            }
            if (radios.isNotEmpty()) FloatingActionButton(onClick = { newChat = true }, Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Filled.Add, "New chat") }
        }
    }
    if (newChat) NewChatDialog(onDismiss = { newChat = false })
}

@Composable
private fun ConversationRow(conv: String, title: String, preview: String, time: Long, unread: Int) {
    val nav = LocalNav.current
    val proto = runCatching { ProtocolType.valueOf(conv.substringBefore(':')) }.getOrDefault(ProtocolType.MESHTASTIC)
    val isChannel = conv.split(':')[1] == "ch"
    Row(Modifier.fillMaxWidth().clickable { nav.navigate("chat/${Uri.encode(conv)}") }.padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(if (isChannel) RoundedCornerShape(12.dp) else CircleShape).background(if (isChannel) protocolColor(proto) else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) {
            Text(title.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontFamily = DisplayFont,
                fontWeight = FontWeight.Bold, color = if (isChannel) StPaulMeshPalette.Cream else protocolColor(proto))
        }
        Column(Modifier.weight(1f)) {
            Row { Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (time > 0) timeAgo(time) else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProtocolBadge(proto)
                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (unread > 0) Badge(containerColor = StPaulMeshPalette.Gold, contentColor = StPaulMeshPalette.Ink) { Text("$unread") }
    }
    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outline)
}

@Composable
fun ChatScreen(conversation: String) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs by app.db.dao().messages(conversation).collectAsState(emptyList())
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val radios by app.radios.radios.collectAsState()
    val target = remember(conversation) { app.radios.parse(conversation) }
    val radio = radios[target.protocol]
    val chName = radio?.channels?.collectAsState()?.value?.firstOrNull { it.index == target.channel }?.name
    val title = conversationTitle(conversation, nodes.associate { it.key to it.longName }, if (chName != null) mapOf(conversation to chName) else emptyMap())
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<MessageEntity?>(null) }
    var actionsFor by remember { mutableStateOf<MessageEntity?>(null) }
    var quick by remember { mutableStateOf(false) }
    val shown = remember(msgs) { msgs.filter { !it.emoji } }
    val reactions = remember(msgs) { msgs.filter { it.emoji && it.replyTo != null }.groupBy { it.replyTo!! } }
    val byPacket = remember(msgs) { msgs.associateBy { it.packetId } }
    var recording by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    val progress = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }
    val list = rememberLazyListState()

    DisposableEffect(conversation) { app.radios.openConversation = conversation; app.radios.clearNotification(conversation); onDispose { app.radios.openConversation = null } }
    var bell by remember { mutableStateOf(false) }
    var notifyLevel by remember(conversation) { mutableStateOf(app.prefs.notifyLevel(conversation)) }
    LaunchedEffect(shown.size) { app.db.dao().markRead(conversation); if (shown.isNotEmpty()) list.animateScrollToItem(shown.size) }
    LaunchedEffect(Unit) { app.radios.media.progress.collect { progress[it.transferId] = it.done to it.total } }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ -> d.isMutableRequired = true }
                else ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            app.radios.sendPhoto(conversation, bmp).onFailure { ctx.toast(it.message, long = true) }
        }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val maxBytes = radio?.maxPayloadBytes ?: 200
    val left = maxBytes - text.toByteArray().size
    val stateLabel = when (val s = radio?.connectionState?.collectAsState()?.value) {
        is ConnectionState.Connected -> "${target.protocol.shortTag} · ${if (target.toNodeId == null) "channel ${target.channel}" else "direct message"}"
        null -> "Not connected"
        else -> s.toString()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        HbHeader(title, stateLabel, onBack = { nav.popBackStack() }, skyline = true, actions = {
            Box {
                IconButton(onClick = { bell = true }) {
                    Icon(when (notifyLevel) { "NONE" -> Icons.Filled.NotificationsOff; "MENTIONS" -> Icons.Filled.AlternateEmail; else -> Icons.Filled.Notifications },
                        tr("Notifications for this chat"))
                }
                androidx.compose.material3.DropdownMenu(bell, { bell = false }) {
                    listOf("ALL" to "Every message", "MENTIONS" to "Only when someone @mentions me", "NONE" to "Off (SOS still alerts)").forEach { (k, label) ->
                        androidx.compose.material3.DropdownMenuItem({ Text((if (notifyLevel == k) "✓ " else "") + tr(label)) },
                            { app.prefs.setNotifyLevel(conversation, k); notifyLevel = k; bell = false })
                    }
                }
            }
            if (target.toNodeId == null) ChannelActions(target.protocol, target.channel, title)
        })
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { org.highbridge.app.ui.Gap(8) }
            items(shown, key = { it.rowId }) { m ->
                MessageBubble(m, progress, reactions[m.packetId].orEmpty(), m.replyTo?.let { byPacket[it] }, !m.outgoing && app.radios.mentionsMe(m.text)) { actionsFor = m }
            }
        }
        replyTo?.let { q ->
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying to ${q.fromName}: ${q.text}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                TextButton(onClick = { replyTo = null }) { Text(tr("Cancel")) }
            }
        }
        if (quick) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(QUICK_REPLIES) { q -> androidx.compose.material3.AssistChip({ quick = false; scope.launch { app.radios.sendText(conversation, q).onFailure { ctx.toast(it.message, long = true) } } }, { Text(q) }) }
            }
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }, enabled = radio != null && !recording) {
                    Icon(if (live) Icons.Filled.RecordVoiceOver else Icons.Filled.Add, "More: quick replies, photo, live talk",
                        tint = if (live) MaterialTheme.colorScheme.tertiary else LocalContentColor.current)
                }
                androidx.compose.material3.DropdownMenu(more, { more = false }) {
                    androidx.compose.material3.DropdownMenuItem({ Text(tr("Quick replies")) }, { more = false; quick = !quick }, leadingIcon = { Icon(Icons.Filled.Bolt, null) })
                    if (target.protocol != ProtocolType.RETICULUM) {
                        androidx.compose.material3.DropdownMenuItem({ Text(tr("Photo")) }, { more = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            leadingIcon = { Icon(Icons.Filled.Image, null) })
                        androidx.compose.material3.DropdownMenuItem({ Text((if (live) "✓ " else "") + tr("Live talk")) }, {
                            more = false; live = !live
                            ctx.toast(tr(if (live) "Live talk on: the mic button now streams as you speak. Best on fast presets." else "Live talk off: the mic button records a voice message."), long = true)
                        }, leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, null) })
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                OutlinedTextField(text, { if (it.toByteArray().size <= maxBytes) text = it }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    placeholder = { Text(if (recording && live) "Live — talking… tap stop to end" else if (recording) "Recording… tap stop to send" else "Message $title") }, enabled = !recording, maxLines = 4)
                Text("$left bytes left", Modifier.align(Alignment.End), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (text.isBlank()) {
                IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { micPerm.launch(Manifest.permission.RECORD_AUDIO); return@IconButton }
                    if (recording) { recording = false; return@IconButton }
                    recording = true
                    if (live) scope.launch {
                        // Live push-to-talk: one second at a time, played on the other end as it arrives.
                        val session = (1..255).random(); var seq = 0
                        val mine = ArrayList<Short>()
                        runCatching {
                            VoiceIO.stream({ !recording }, onLevel = { level = it }) { block ->
                                mine.addAll(block.toList())
                                app.radios.sendLiveBlock(conversation, session, seq++ and 0xFF, block).onFailure { recording = false; ctx.toast(it.message, long = true) }
                            }
                        }.onFailure { ctx.toast(it.message, long = true) }
                        recording = false
                        app.radios.saveLiveSent(conversation, mine.toShortArray())
                    } else scope.launch {
                        val pcm = VoiceIO.record({ !recording }) { level = it }
                        recording = false
                        if (pcm.size > VoiceIO.SAMPLE_RATE / 2) app.radios.sendVoice(conversation, pcm).onFailure { ctx.toast(it.message, long = true) }
                    }
                }, enabled = radio != null && target.protocol != ProtocolType.RETICULUM) { Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, if (recording) "Stop and send" else "Hold to record voice") }
            } else {
                IconButton(onClick = {
                    val t = text.trim(); text = ""
                    val rt = replyTo?.packetId; replyTo = null
                    scope.launch { app.radios.sendText(conversation, t, replyTo = rt).onFailure { ctx.toast(it.message, long = true) } }
                }, enabled = radio != null) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
        if (recording) LinearProgressIndicator(progress = { level }, Modifier.fillMaxWidth())
    }
    actionsFor?.let { m ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        androidx.compose.material3.AlertDialog(onDismissRequest = { actionsFor = null },
            title = { Text(m.text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
            text = { Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    REACTIONS.forEach { e -> TextButton(onClick = {
                        actionsFor = null
                        scope.launch { app.radios.sendText(conversation, e, replyTo = m.packetId, emoji = true).onFailure { ctx.toast(it.message, long = true) } }
                    }, enabled = radio != null && !m.packetId.startsWith("pending")) { Text(e, fontSize = 20.sp) } }
                }
                TextButton(onClick = { replyTo = m; actionsFor = null }) { Text(tr("Reply")) }
                if (!m.outgoing && target.toNodeId == null) TextButton(onClick = {
                    val tag = "@[${m.fromName.substringBefore(" · ")}] "
                    if (text.toByteArray().size + tag.toByteArray().size <= maxBytes) text = tag + text
                    actionsFor = null
                }) { Text("Mention ${m.fromName.substringBefore(" · ")}") }
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(m.text)); actionsFor = null }) { Text(tr("Copy text")) }
                if (m.status == 3) TextButton(onClick = { actionsFor = null; scope.launch {
                    app.db.dao().deleteMessage(m.rowId); app.radios.sendText(conversation, m.text, m.replyTo).onFailure { ctx.toast(it.message, long = true) } } }) { Text(tr("Send again")) }
                TextButton(onClick = { actionsFor = null; scope.launch { app.db.dao().deleteMessage(m.rowId) } }) { Text(tr("Delete from this phone")) }
            } },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text(tr("Close")) } })
    }
}

private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "🙏", "✅")
private val QUICK_REPLIES = listOf("OK", "Copy that", "On my way", "Where are you?", "Running late", "Need help", "All good here", "Signing off")

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun MessageBubble(m: MessageEntity, progress: Map<Int, Pair<Int, Int>>, reactions: List<MessageEntity>, quoted: MessageEntity?, mentionsMe: Boolean, onLongPress: () -> Unit) {
    val mine = m.outgoing
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.fromName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            if (m.viaBridge) Text(tr("via bridge"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            m.snr?.let { Text("SNR %.1f".format(it), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(if (mine) 12.dp else 4.dp, if (mine) 4.dp else 12.dp, 12.dp, 12.dp))
            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(if (mentionsMe) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)) else Modifier)
            .combinedClickable(onClick = {}, onLongClick = onLongPress).padding(10.dp)) {
            quoted?.let { q ->
                Text("↪ ${q.fromName}: ${q.text}", Modifier.padding(bottom = 4.dp), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (m.mediaType) {
                "IMAGE_WEBP" -> m.mediaPath?.let { path ->
                    val bmp = remember(path) { BitmapFactory.decodeFile(path) }
                    if (bmp != null) Image(bmp.asImageBitmap(), "Photo", Modifier.size(220.dp, 165.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Text(m.text, fontSize = 12.sp)
                }
                "VOICE_CODEC2" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { m.mediaPath?.let { p -> scope.launch { VoiceIO.play(wavToPcm(File(p).readBytes())) } } }) { Icon(Icons.Filled.PlayArrow, "Play voice message") }
                    Text(m.text)
                }
                else -> Text(m.text, style = MaterialTheme.typography.bodyLarge)
            }
            if (mine && m.packetId.startsWith("media-out-")) m.packetId.removePrefix("media-out-").toIntOrNull()?.let { id ->
                progress[id]?.let { (d, t) -> if (d < t) { LinearProgressIndicator(progress = { d / t.toFloat() }, Modifier.fillMaxWidth().padding(top = 6.dp)); Text("Sending $d of $t chunks", fontSize = 11.sp) } }
            }
        }
        if (reactions.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            reactions.groupBy { it.text }.forEach { (e, who) ->
                Text("$e ${if (who.size > 1) who.size else ""}".trim(), Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp, 2.dp), fontSize = 13.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.timeMs)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mine) Icon(when (m.status) { 0 -> Icons.Filled.Schedule; 2 -> Icons.Filled.DoneAll; 3 -> Icons.Filled.ErrorOutline; else -> Icons.Filled.Done },
                when (m.status) { 0 -> "Sending"; 2 -> "Delivered"; 3 -> "Failed"; else -> "Sent" }, Modifier.size(14.dp),
                tint = if (m.status == 3) StPaulMeshPalette.Danger else MaterialTheme.colorScheme.primary)
        }
    }
}

/** Reads the 16-bit PCM back out of a .wav written by VoiceIO.toWav. */
fun wavToPcm(wav: ByteArray): ShortArray {
    if (wav.size <= 44) return ShortArray(0)
    val n = (wav.size - 44) / 2
    return ShortArray(n) { i -> ((wav[44 + 2 * i].toInt() and 0xFF) or (wav[45 + 2 * i].toInt() shl 8)).toShort() }
}
