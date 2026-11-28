// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.wear

import android.app.Activity
import android.app.RemoteInput
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Como = Color(0xFF7CC4A4)
private val Sos = Color(0xFFE5484D)
private val QUICK = listOf("OK", "On my way", "Copy", "Need help", "73")

class MainActivity : ComponentActivity() {
    private lateinit var link: PhoneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = PhoneLink(applicationContext)
        setContent { MaterialTheme { WatchApp(link) } }
    }
    override fun onResume() { super.onResume(); link.attach() }
    override fun onPause() { link.detach(); super.onPause() }
}

private fun Context.buzz() = runCatching {
    getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
}

@Composable
fun WatchApp(link: PhoneLink) {
    val state by link.state.collectAsState()
    val toast by link.toast.collectAsState()
    val phone by link.phoneFound.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var sosConfirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { link.load() }
    LaunchedEffect(toast) { if (toast != null) { delay(3000); link.toast.value = null } }

    when {
        sosConfirm -> SosConfirm(link) { sosConfirm = false }
        open != null -> {
            BackHandler { open = null }
            val conv = state?.convs?.firstOrNull { it.id == open }
            if (conv == null) open = null else ConversationScreen(link, conv, toast)
        }
        else -> Home(link, state, phone, toast, onOpen = { open = it }, onSos = { sosConfirm = true })
    }
}

@Composable
private fun Home(link: PhoneLink, state: WState?, phone: Boolean?, toast: String?, onOpen: (String) -> Unit, onSos: () -> Unit) {
    val list = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text("Highbridge", color = Como, fontWeight = FontWeight.Bold) } }
            item {
                val line = when {
                    toast != null -> toast
                    phone == false -> "Phone not reachable"
                    state == null -> "Waiting for phone…"
                    state.radios.isEmpty() -> "No radios connected"
                    else -> listOfNotNull(state.grid.takeIf { it.isNotBlank() }, "${state.radios.size} radio" + if (state.radios.size == 1) "" else "s").joinToString(" · ")
                }
                Text(line, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ctx.buzz(); scope.launch { if (!link.send(PhoneLink.PATH_CHECKIN, "")) link.toast.value = "Phone not reachable" } },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Como)) { Text("OK", color = Color.Black) }
                    Button(onClick = { ctx.buzz(); onSos() }, colors = ButtonDefaults.buttonColors(backgroundColor = Sos)) { Text("SOS") }
                }
            }
            val convs = state?.convs.orEmpty()
            if (convs.isEmpty() && state != null) item { Text("No messages yet", style = MaterialTheme.typography.caption2) }
            items(convs, key = { it.id }) { c ->
                Chip(
                    onClick = { ctx.buzz(); onOpen(c.id); scope.launch { if (c.unread > 0) link.markRead(c.id) } },
                    label = { Text(c.title + if (c.unread > 0) "  •${c.unread}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(c.msgs.lastOrNull()?.let { (if (it.out) "You: " else "") + it.text } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = if (c.unread > 0) ChipDefaults.primaryChipColors(backgroundColor = Como.copy(alpha = 0.35f)) else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ConversationScreen(link: PhoneLink, c: WConv, toast: String?) {
    val list = rememberScalingLazyListState(initialCenterItemIndex = c.msgs.size)
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val text = r.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence("reply")?.toString()?.trim()
            if (!text.isNullOrEmpty()) scope.launch { link.reply(c.id, text) }
        }
    }
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Como) } }
            items(c.msgs) { m ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = if (m.out) Alignment.End else Alignment.Start) {
                    if (!m.out) Text(m.from, style = MaterialTheme.typography.caption3, color = Como)
                    Text(m.text, style = MaterialTheme.typography.body2, textAlign = if (m.out) TextAlign.End else TextAlign.Start)
                }
            }
            if (toast != null) item { Text(toast, style = MaterialTheme.typography.caption2) }
            item {
                Chip(onClick = {
                    ctx.buzz()
                    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("reply").setLabel("Reply").build()))
                    voice.launch(intent)
                }, label = { Text("Reply") }, secondaryLabel = { Text("Voice or keyboard") },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = Como), modifier = Modifier.fillMaxWidth())
            }
            items(QUICK) { q ->
                CompactChip(onClick = { ctx.buzz(); scope.launch { link.reply(c.id, q) } }, label = { Text(q) },
                    colors = ChipDefaults.secondaryChipColors())
            }
        }
    }
}

@Composable
private fun SosConfirm(link: PhoneLink, done: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    BackHandler { done() }
    Scaffold(timeText = { TimeText() }) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Send SOS?", fontWeight = FontWeight.Bold, color = Sos)
            Text("Goes to every connected radio with your location.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { done() }, colors = ButtonDefaults.secondaryButtonColors()) { Text("No") }
                Button(onClick = {
                    ctx.buzz()
                    scope.launch { if (!link.send(PhoneLink.PATH_SOS, "")) link.toast.value = "Phone not reachable"; done() }
                }, colors = ButtonDefaults.buttonColors(backgroundColor = Sos)) { Text("Send") }
            }
        }
    }
}
