// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import org.highbridge.app.ui.toast
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.ProtocolBadge
import org.highbridge.app.ui.SectionLabel
import org.highbridge.core.protocol.EncryptedChannel
import org.highbridge.core.protocol.ProtocolType

/** Make private channels with strong random keys, and share them face to face with a QR code (no internet). */
@Composable
fun ChannelsScreen(importUri: String?) {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val saved by app.db.dao().channels().collectAsState(emptyList())
    var proto by remember { mutableStateOf(ProtocolType.MESHTASTIC) }
    var name by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf("") } // empty = next free slot
    var showing by remember { mutableStateOf<EncryptedChannel?>(null) }
    var pendingImport by remember { mutableStateOf<EncryptedChannel?>(null) }
    val slotNum = slot.toIntOrNull() ?: EncryptedChannel.AUTO_SLOT
    val error = EncryptedChannel.validate(proto, slotNum, name.trim())

    LaunchedEffect(importUri) { importUri?.let { EncryptedChannel.fromShareUri(it)?.let { c -> pendingImport = c } ?: ctx.toast(tr("That channel link isn't valid"), long = true) } }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents
        if (text != null && text.startsWith("meshcore://contact/", true)) {
            // A MeshCore contact code: add it to the radio's contacts.
            scope.launch {
                val mc = app.radios.meshcore ?: run { ctx.toast(tr("Connect your MeshCore radio to add contacts"), long = true); return@launch }
                mc.importContact(text).onSuccess { ctx.toast("Added $it to your MeshCore contacts") }.onFailure { ctx.toast(it.message, long = true) }
            }
            return@rememberLauncherForActivityResult
        }
        val c = text?.let { EncryptedChannel.fromShareUri(it) }
        if (c == null) { if (text != null) ctx.toast(tr("That QR code isn't a channel or contact code"), long = true) } else pendingImport = c
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader(tr("Channels"), tr("Private channels with random keys"), onBack = { nav.popBackStack() }) }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HbCard {
                    Text(tr("New private channel"), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { supportedProtocols.forEach { p -> FilterChip(proto == p, { proto = p }, { Text(p.shortTag) }) } }
                    Text(when (proto) {
                        ProtocolType.MESHTASTIC -> "256-bit AES key, slots 1–7 (slot 0 is your primary channel)"
                        ProtocolType.MESHCORE -> "128-bit key (the most MeshCore firmware accepts)"
                        ProtocolType.RETICULUM -> "512-bit key for a Reticulum group"
                        ProtocolType.APRS -> "APRS has no private channels"
                    }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(tr("Name")) }, singleLine = true)
                    OutlinedTextField(slot, { slot = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(tr("Slot (empty = next free slot)")) }, singleLine = true)
                    if (error != null && name.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Button(enabled = error == null, onClick = {
                        val c = EncryptedChannel.createRandom(proto, slotNum, name)
                        name = ""
                        scope.launch {
                            app.radios.applyChannel(c).onSuccess { showing = it; ctx.toast("Loaded in slot ${it.slotIndex}") }
                                .onFailure { showing = c.takeIf { it.slotIndex >= 0 }; ctx.toast(it.message, long = true) }
                        }
                    }) { Text(tr("Create and load")) }
                }
                OutlinedButton(onClick = { scan.launch(ScanOptions().setPrompt("Scan a Highbridge channel QR code").setBeepEnabled(false).setOrientationLocked(false)) }, Modifier.fillMaxWidth()) {
                    Text(tr("Scan a channel QR code"))
                }
            }
        }
        if (saved.isNotEmpty()) item { SectionLabel(tr("Saved channels")) }
        items(saved, key = { it.id }) { c ->
            val ch = EncryptedChannel(c.id, ProtocolType.valueOf(c.protocol), c.slot, c.name, c.keyB64)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtocolBadge(ch.protocol)
                Column(Modifier.weight(1f)) { Text(c.name, fontWeight = FontWeight.Bold); Text("Slot ${c.slot}", fontSize = 12.sp) }
                TextButton(onClick = { showing = ch }) { Text(tr("Share")) }
                TextButton(onClick = { scope.launch { app.radios.applyChannel(ch).let { ctx.toast(it.exceptionOrNull()?.message ?: "Loaded") } } }) { Text(tr("Load")) }
                TextButton(onClick = { scope.launch { app.db.dao().deleteChannel(c.id) } }) { Text(tr("Delete")) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }

    showing?.let { c -> ChannelQrDialog(c) { showing = null } }
    pendingImport?.let { c ->
        org.highbridge.app.ui.HbAlertDialog(onDismissRequest = { pendingImport = null },
            confirmButton = { TextButton(onClick = { pendingImport = null; scope.launch { app.radios.applyChannel(c).let { ctx.toast(it.exceptionOrNull()?.message ?: "Joined ${c.name}", long = true) } } }) { Text(tr("Join")) } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text(tr("Cancel")) } },
            title = { Text("Join ${c.name}?") }, text = { Text("${c.protocol.name.lowercase().replaceFirstChar { it.uppercase() }} channel. " +
                if (c.slotIndex == EncryptedChannel.AUTO_SLOT) "It goes in the next free slot on your radio." else "Slot ${c.slotIndex}: this replaces whatever is in that slot on your radio.") })
    }
}
