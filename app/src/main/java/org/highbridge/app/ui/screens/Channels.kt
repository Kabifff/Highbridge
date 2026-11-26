// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

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
    var slot by remember { mutableStateOf("1") }
    var showing by remember { mutableStateOf<EncryptedChannel?>(null) }
    var pendingImport by remember { mutableStateOf<EncryptedChannel?>(null) }
    val error = EncryptedChannel.validate(proto, slot.toIntOrNull() ?: -1, name.trim())

    LaunchedEffect(importUri) { importUri?.let { EncryptedChannel.fromShareUri(it)?.let { c -> pendingImport = c } ?: ctx.toast("That channel link isn't valid", long = true) } }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val c = r.contents?.let { EncryptedChannel.fromShareUri(it) }
        if (c == null) { if (r.contents != null) ctx.toast("That QR code isn't a Highbridge channel", long = true) } else pendingImport = c
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("Channels", "Private channels with random keys", onBack = { nav.popBackStack() }) }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HbCard {
                    Text("New private channel", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ProtocolType.entries.forEach { p -> FilterChip(proto == p, { proto = p }, { Text(p.shortTag) }) } }
                    Text(when (proto) {
                        ProtocolType.MESHTASTIC -> "256-bit AES key, slots 1–7 (slot 0 is your primary channel)"
                        ProtocolType.MESHCORE -> "128-bit key (the most MeshCore firmware accepts)"
                        ProtocolType.RETICULUM -> "512-bit key for a Reticulum group"
                    }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(slot, { slot = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Slot") }, singleLine = true)
                    if (error != null && name.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Button(enabled = error == null, onClick = {
                        val c = EncryptedChannel.createRandom(proto, slot.toInt(), name)
                        scope.launch { val r = app.radios.applyChannel(c); ctx.toast(r.exceptionOrNull()?.message ?: "Channel loaded on your radio", long = true) }
                        showing = c; name = ""
                    }) { Text("Create and load") }
                }
                OutlinedButton(onClick = { scan.launch(ScanOptions().setPrompt("Scan a Highbridge channel QR code").setBeepEnabled(false).setOrientationLocked(false)) }, Modifier.fillMaxWidth()) {
                    Text("Scan a channel QR code")
                }
            }
        }
        if (saved.isNotEmpty()) item { SectionLabel("Saved channels") }
        items(saved, key = { it.id }) { c ->
            val ch = EncryptedChannel(c.id, ProtocolType.valueOf(c.protocol), c.slot, c.name, c.keyB64)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtocolBadge(ch.protocol)
                Column(Modifier.weight(1f)) { Text(c.name, fontWeight = FontWeight.Bold); Text("Slot ${c.slot}", fontSize = 12.sp) }
                TextButton(onClick = { showing = ch }) { Text("Share") }
                TextButton(onClick = { scope.launch { app.radios.applyChannel(ch).let { ctx.toast(it.exceptionOrNull()?.message ?: "Loaded") } } }) { Text("Load") }
                TextButton(onClick = { scope.launch { app.db.dao().deleteChannel(c.id) } }) { Text("Delete") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }

    showing?.let { c ->
        val bmp = remember(c) { BarcodeEncoder().encodeBitmap(c.toShareUri(), BarcodeFormat.QR_CODE, 720, 720) }
        AlertDialog(onDismissRequest = { showing = null }, confirmButton = { TextButton(onClick = { showing = null }) { Text("Done") } },
            dismissButton = { TextButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.toShareUri()), "Share channel"))
            }) { Text("Share link") } },
            title = { Text(c.name) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(bmp.asImageBitmap(), "Channel QR code", Modifier.size(260.dp))
                Text("Anyone who scans this can read and post on this channel. Only show it to people you trust.", fontSize = 12.sp)
            } })
    }
    pendingImport?.let { c ->
        AlertDialog(onDismissRequest = { pendingImport = null },
            confirmButton = { TextButton(onClick = { pendingImport = null; scope.launch { app.radios.applyChannel(c).let { ctx.toast(it.exceptionOrNull()?.message ?: "Joined ${c.name}", long = true) } } }) { Text("Join") } },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
            title = { Text("Join ${c.name}?") }, text = { Text("${c.protocol.name.lowercase().replaceFirstChar { it.uppercase() }} channel, slot ${c.slotIndex}. This replaces whatever is in that slot on your radio.") })
    }
}
