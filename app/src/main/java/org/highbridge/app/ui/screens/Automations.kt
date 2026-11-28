// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.pro.Automations
import org.highbridge.app.pro.Automations.Action
import org.highbridge.app.pro.Automations.Trigger
import org.highbridge.app.ui.HbAlertDialog
import org.highbridge.app.ui.HbDropdownMenu
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.toast
import org.highbridge.app.ui.tr

/** "When this happens, do that": scheduled messages, keyword replies, alerts and webhooks (IFTTT and others). */
@Composable
fun AutomationsScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val json by app.prefs.automations.collectAsState()
    val rules = remember(json) { Automations.load(json) }
    val log by app.radios.automationLog.collectAsState()
    var editing by remember { mutableStateOf<Automations.Rule?>(null) }
    var creating by remember { mutableStateOf(false) }
    fun saveAll(list: List<Automations.Rule>) = app.prefs.setAutomations(Automations.save(list))

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { HbHeader(tr("Automations"), tr("When this happens, do that"), onBack = { nav.popBackStack() }) }
            item {
                Text(tr("Automations run while Highbridge is running, including in the background with a radio connected. Messages they send use airtime like any other, so keep schedules sparse. Each runs at most once a minute."),
                    Modifier.padding(20.dp, 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (rules.isEmpty()) item { Text(tr("No automations yet. Tap New to make one."), Modifier.padding(20.dp, 12.dp)) }
            items(rules, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.Bold)
                            Text(describe(r), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(r.enabled, { on -> saveAll(rules.map { if (it.id == r.id) it.copy(enabled = on) else it }) })
                    }
                    Row {
                        TextButton(onClick = { scope.launch { app.radios.runAutomation(r, Automations.Event("Test", "test", "")); ctx.toast(app.radios.automationLog.value.firstOrNull()) } }) { Text(tr("Test")) }
                        TextButton(onClick = { editing = r }) { Text(tr("Edit")) }
                        TextButton(onClick = { saveAll(rules.filter { it.id != r.id }) }) { Text(tr("Delete")) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
            if (log.isNotEmpty()) {
                item { SectionLabel(tr("Recent runs")) }
                items(log) { Text(it, Modifier.padding(horizontal = 20.dp), fontSize = 12.sp) }
            }
            item { org.highbridge.app.ui.Gap(96) }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Text(tr("New")) }
    }
    if (creating || editing != null) RuleEditor(editing, onDismiss = { creating = false; editing = null }) { saved ->
        saveAll(if (rules.any { it.id == saved.id }) rules.map { if (it.id == saved.id) saved else it } else rules + saved)
        creating = false; editing = null
    }
}

private fun describe(r: Automations.Rule): String {
    val whenText = when (r.trigger) {
        Trigger.DAILY -> "Every day at ${r.triggerValue}" + if (r.triggerScope.length == 7 && r.triggerScope != "1111111")
            " (" + "MTWTFSS".filterIndexed { i, _ -> r.triggerScope[i] == '1' } + ")" else ""
        Trigger.EVERY -> "Every ${r.triggerValue} min"
        Trigger.KEYWORD -> "A message says \"${r.triggerValue}\"" + if (r.triggerScope.isNotBlank()) " in ${r.triggerScope.substringAfterLast(':')}" else ""
        Trigger.NODE_BACK -> (if (r.triggerScope.isBlank()) "Any node" else r.triggerScope.substringAfter(':')) + " is back after ${r.triggerValue} min"
        Trigger.BATTERY -> "Phone battery below ${r.triggerValue}%"
        Trigger.GEOFENCE -> "A geofence alert"
    }
    return "$whenText → ${tr(r.action.label).lowercase()}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(start: Automations.Rule?, onDismiss: () -> Unit, onSave: (Automations.Rule) -> Unit) {
    val app = HighbridgeApp.instance
    val convs by app.db.dao().conversations().collectAsState(emptyList())
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val names = nodes.associate { it.key to it.longName }
    var name by remember { mutableStateOf(start?.name ?: "") }
    var trigger by remember { mutableStateOf(start?.trigger ?: Trigger.DAILY) }
    var tValue by remember { mutableStateOf(start?.triggerValue ?: "08:00") }
    var tScope by remember { mutableStateOf(start?.triggerScope ?: "1111111") }
    var action by remember { mutableStateOf(start?.action ?: Action.SEND) }
    var target by remember { mutableStateOf(start?.target ?: "") }
    var text by remember { mutableStateOf(start?.text ?: "") }
    var pickConv by remember { mutableStateOf(false) }
    var pickScope by remember { mutableStateOf(false) }
    val ok = name.isNotBlank() && when (action) {
        Action.SEND -> target.isNotBlank() && text.isNotBlank()
        Action.WEBHOOK -> target.startsWith("http")
        Action.IFTTT -> target.substringBefore('|').isNotBlank() && target.substringAfter('|', "").isNotBlank()
        Action.NOTIFY -> text.isNotBlank()
        else -> true
    } && when (trigger) {
        Trigger.DAILY -> Regex("^\\d{1,2}:\\d{2}$").matches(tValue)
        Trigger.EVERY -> (tValue.toIntOrNull() ?: 0) >= 10
        Trigger.KEYWORD -> tValue.isNotBlank()
        Trigger.NODE_BACK, Trigger.BATTERY -> tValue.toIntOrNull() != null
        Trigger.GEOFENCE -> true
    }
    HbAlertDialog(onDismissRequest = onDismiss, title = { Text(if (start == null) tr("New automation") else tr("Edit automation")) },
        confirmButton = { TextButton(onClick = {
            onSave((start ?: Automations.Rule(name = name, trigger = trigger, action = action)).copy(name = name.trim(), trigger = trigger,
                triggerValue = tValue.trim(), triggerScope = tScope, action = action, target = target.trim(), text = text))
        }, enabled = ok) { Text(tr("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text(tr("Name")) }, singleLine = true)
            Text(tr("When"), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Trigger.entries.forEach { t -> FilterChip(trigger == t, {
                    trigger = t
                    tValue = when (t) { Trigger.DAILY -> "08:00"; Trigger.EVERY -> "60"; Trigger.NODE_BACK -> "60"; Trigger.BATTERY -> "20"; else -> "" }
                    tScope = if (t == Trigger.DAILY) "1111111" else ""
                }, { Text(tr(t.label), fontSize = 12.sp) }) }
            }
            when (trigger) {
                Trigger.DAILY -> {
                    OutlinedTextField(tValue, { tValue = it.filter { c -> c.isDigit() || c == ':' }.take(5) }, label = { Text(tr("Time (24-hour, e.g. 20:00)")) }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d ->
                            val on = tScope.getOrElse(i) { '1' } == '1'
                            FilterChip(on, { tScope = tScope.padEnd(7, '1').let { s -> s.substring(0, i) + (if (on) '0' else '1') + s.substring(i + 1) } }, { Text(d) })
                        }
                    }
                }
                Trigger.EVERY -> OutlinedTextField(tValue, { tValue = it.filter(Char::isDigit).take(4) }, label = { Text(tr("Minutes (10 or more)")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Trigger.KEYWORD -> {
                    OutlinedTextField(tValue, { tValue = it.take(30) }, label = { Text(tr("Word or phrase")) }, singleLine = true)
                    TextButton(onClick = { pickScope = true }) { Text(tr("In") + ": " + if (tScope.isBlank()) tr("any chat") else conversationTitle(tScope, names, emptyMap())) }
                    HbDropdownMenu(pickScope, { pickScope = false }) {
                        DropdownMenuItem({ Text(tr("any chat")) }, { tScope = ""; pickScope = false })
                        convs.take(40).forEach { c -> DropdownMenuItem({ Text("${c.protocol.take(2)} · " + conversationTitle(c.conversation, names, emptyMap())) }, { tScope = c.conversation; pickScope = false }) }
                    }
                }
                Trigger.NODE_BACK -> {
                    OutlinedTextField(tValue, { tValue = it.filter(Char::isDigit).take(5) }, label = { Text(tr("Quiet for at least (minutes)")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    TextButton(onClick = { pickScope = true }) { Text(tr("Node") + ": " + if (tScope.isBlank()) tr("any node") else names[tScope] ?: tScope) }
                    HbDropdownMenu(pickScope, { pickScope = false }) {
                        DropdownMenuItem({ Text(tr("any node")) }, { tScope = ""; pickScope = false })
                        nodes.filter { it.favorite }.ifEmpty { nodes.sortedByDescending { it.lastHeardMs }.take(40) }.forEach { n ->
                            DropdownMenuItem({ Text(n.longName) }, { tScope = n.key; pickScope = false }) }
                    }
                }
                Trigger.BATTERY -> OutlinedTextField(tValue, { tValue = it.filter(Char::isDigit).take(2) }, label = { Text(tr("Below this percent")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Trigger.GEOFENCE -> Text(tr("Runs whenever one of your geofences sends an alert."), fontSize = 12.sp)
            }
            Text(tr("Do"), fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Action.entries.forEach { a -> FilterChip(action == a, { action = a; target = "" }, { Text(tr(a.label), fontSize = 12.sp) }) }
            }
            when (action) {
                Action.SEND -> {
                    TextButton(onClick = { pickConv = true }) { Text(tr("To") + ": " + if (target.isBlank()) tr("choose a chat") else conversationTitle(target, names, emptyMap())) }
                    HbDropdownMenu(pickConv, { pickConv = false }) {
                        convs.take(40).forEach { c -> DropdownMenuItem({ Text("${c.protocol.take(2)} · " + conversationTitle(c.conversation, names, emptyMap())) }, { target = c.conversation; pickConv = false }) }
                    }
                }
                Action.WEBHOOK -> {
                    OutlinedTextField(target, { target = it.trim() }, Modifier.fillMaxWidth(), label = { Text(tr("Web address")) }, singleLine = true,
                        placeholder = { Text("https://…") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Text(tr("Highbridge POSTs JSON with the rule, sender, text, network and time. Works with Home Assistant, ntfy, Zapier, n8n and others."), fontSize = 11.sp)
                }
                Action.IFTTT -> {
                    var key by remember { mutableStateOf(target.substringBefore('|')) }
                    var event by remember { mutableStateOf(target.substringAfter('|', "")) }
                    OutlinedTextField(key, { key = it.trim(); target = "$key|$event" }, Modifier.fillMaxWidth(), label = { Text(tr("IFTTT Webhooks key")) }, singleLine = true)
                    OutlinedTextField(event, { event = it.trim().replace(' ', '_'); target = "$key|$event" }, Modifier.fillMaxWidth(), label = { Text(tr("Event name")) }, singleLine = true)
                    Text(tr("Find your key at ifttt.com/maker_webhooks › Documentation. Value 1 is who sent it, value 2 the text, value 3 the network."), fontSize = 11.sp)
                }
                else -> {}
            }
            if (action == Action.SEND || action == Action.NOTIFY || action == Action.WEBHOOK || action == Action.IFTTT) {
                OutlinedTextField(text, { text = it.take(200) }, Modifier.fillMaxWidth(), label = { Text(tr(if (action == Action.SEND || action == Action.NOTIFY) "Text" else "Text (if the trigger has none)")) })
                Text(tr("You can use {from} {text} {network} {time} {battery} {grid}."), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } })
}
