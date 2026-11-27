// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.aprs.AprsFormat
import org.highbridge.app.aprs.CallsignLookup
import org.highbridge.app.pro.Geo
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.timeAgo
import org.highbridge.app.ui.toast
import org.highbridge.app.ui.tr
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.link.UsbDevices

/** APRS: your station, how to connect (internet or a TNC), beacons, the mesh gateway, stations heard and the packet log. */
@SuppressLint("MissingPermission")
@Composable
fun AprsScreen() {
    val app = HighbridgeApp.instance
    val p = app.prefs
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val radios by app.radios.radios.collectAsState()
    val aprs = radios[ProtocolType.APRS] as? org.highbridge.app.aprs.AprsRadio
    val dev by (aprs?.deviceInfo ?: MutableStateFlow(null)).collectAsState()
    val verified by (aprs?.isVerified ?: MutableStateFlow(false)).collectAsState()
    val stations by (aprs?.stations ?: MutableStateFlow(emptyMap())).collectAsState()
    val me by app.radios.myLocation.collectAsState()
    val metric by p.metric.collectAsState()
    val log = remember { mutableStateListOf<String>() }
    LaunchedEffect(aprs) { aprs?.rawLog?.collect { log.add(it); while (log.size > 150) log.removeAt(0) } }

    var call by remember { mutableStateOf(p.aprsCall.value) }
    var comment by remember { mutableStateOf(p.aprsComment.value) }
    var path by remember { mutableStateOf(p.aprsPath.value) }
    val symbol by p.aprsSymbol.collectAsState()
    val useIs by p.aprsIs.collectAsState()
    val radius by p.aprsRadiusKm.collectAsState()
    val beacon by p.aprsBeacon.collectAsState()
    val gateway by p.aprsGateway.collectAsState()
    val regs by p.aprsGatewayRegs.collectAsState()
    val autoId by p.autoStationId.collectAsState()
    var symOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val callOk = AprsFormat.isCallsign(call.trim())

    fun saveStation() { p.setAprsCall(call.trim().uppercase()); p.setAprsComment(comment.trim()); p.setAprsPath(path.trim().uppercase()) }
    fun connectWith(block: suspend () -> Result<Unit>) {
        saveStation(); busy = true
        scope.launch { block().fold({ ctx.toast("APRS connected") }, { ctx.toast(it.message, long = true) }); busy = false }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("APRS", tr("Amateur radio positions and messages"), onBack = { nav.popBackStack() }) }
        item {
            Text(tr("APRS is amateur radio. Listening is open to anyone; sending needs a license, and everything you send is public and unencrypted."),
                Modifier.padding(20.dp, 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (aprs != null) item {
            HbCard(Modifier.padding(16.dp, 4.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dev ?: "APRS", fontWeight = FontWeight.Bold)
                Text(if (aprs.canTransmit) tr("Sending and receiving") else if (verified) "" else tr("Listening only: APRS-IS didn't accept the call sign, and there's no TNC"), fontSize = 12.sp)
                me?.let { Text(tr("Your grid square") + ": " + Geo.maidenhead(it.first, it.second), fontSize = 12.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch {
                        val loc = me ?: return@launch ctx.toast(tr("Waiting for your location"))
                        ctx.toast(aprs.sendPosition(loc.first, loc.second, app.radios.myAltitudeM.value?.toInt()).exceptionOrNull()?.message ?: tr("Position sent"))
                    } }, enabled = aprs.canTransmit) { Text(tr("Beacon now")) }
                    TextButton(onClick = { scope.launch { app.radios.disconnect(ProtocolType.APRS) } }) { Text(tr("Disconnect")) }
                }
            } }
        }

        item { SectionLabel(tr("Your station")) }
        item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(call, { call = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(9) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr("Call sign and SSID (e.g. N0CALL-7)")) }, isError = call.isNotBlank() && !callOk,
                supportingText = { Text(tr("SSID: -7 handheld, -9 car, -10 internet, none for home")) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Map symbol") + ": ", fontWeight = FontWeight.Bold)
                TextButton(onClick = { symOpen = true }) { Text(AprsFormat.symbolName(symbol)) }
                DropdownMenu(symOpen, { symOpen = false }) {
                    AprsFormat.SYMBOLS.entries.distinctBy { it.value }.take(20).forEach { (k, v) -> DropdownMenuItem({ Text(v) }, { p.setAprsSymbol(k); symOpen = false }) }
                }
            }
            OutlinedTextField(comment, { comment = it.take(43) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Comment shown with your position")) })
            OutlinedTextField(path, { path = it.uppercase().take(40) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr("Digipeater path (radio only)")) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(path == "WIDE1-1,WIDE2-1", { path = "WIDE1-1,WIDE2-1" }, { Text("2 m: WIDE1-1,WIDE2-1") })
                FilterChip(path == "WIDE1-1", { path = "WIDE1-1" }, { Text("LoRa: WIDE1-1") })
            }
            Button(onClick = { saveStation(); ctx.toast(tr("Saved")) }, enabled = callOk) { Text(tr("Save")) }
        } }

        item { SectionLabel(tr("Connect")) }
        item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Internet (APRS-IS)"))
                    Text(tr("See stations near you and send messages without a radio. Uses your call sign's public passcode."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(useIs, { p.setAprsIs(it) })
            }
            Text(tr("Area to show") + ": ${if (metric) "$radius km" else "${(radius / 1.609).toInt()} mi"}", fontSize = 13.sp)
            Slider(radius.toFloat(), { p.setAprsRadiusKm(it.toInt()) }, valueRange = 10f..300f, onValueChangeFinished = { aprs?.refreshFilter() })
            Button(onClick = { connectWith { app.radios.connectAprsInternet() } }, enabled = callOk && !busy) { Text(tr("Connect over the internet")) }
            Text(tr("A TNC puts you on the air: a LoRa APRS tracker, a Mobilinkd or other Bluetooth TNC with a radio, or Direwolf on a computer. Bluetooth LE TNCs show under Connect a radio."),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        // Classic Bluetooth TNCs must be paired in Android settings first; list them here.
        val bonded = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (bonded.isNotEmpty()) item { Text(tr("Paired Bluetooth devices"), Modifier.padding(20.dp, 8.dp, 20.dp, 0.dp), fontWeight = FontWeight.Bold) }
        items(bonded, key = { "bt" + it.address }) { d ->
            Row(Modifier.fillMaxWidth().clickable(enabled = callOk && !busy) { connectWith { app.radios.connectAprsSpp(d) } }.padding(20.dp, 8.dp)) {
                Column(Modifier.weight(1f)) { Text(d.name ?: d.address); Text(tr("Tap to use as a KISS TNC"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            var baud by remember { mutableStateOf(9600) }
            val usb = remember { runCatching { UsbDevices.drivers(ctx.getSystemService(UsbManager::class.java)) }.getOrDefault(emptyList()) }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (usb.isNotEmpty()) {
                    Text(tr("USB TNC"), fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(9600, 57600, 115200).forEach { b -> FilterChip(baud == b, { baud = b }, { Text("$b") }) } }
                    usb.forEach { d -> OutlinedButton(onClick = { connectWith {
                        if (!requestUsbPermission(ctx, d.device)) Result.failure(IllegalStateException("USB permission denied")) else app.radios.connectAprsUsb(d, baud)
                    } }, enabled = callOk && !busy) { Text(UsbDevices.describe(d.device)) } }
                }
                var host by remember { mutableStateOf("") }
                Text(tr("Network TNC (Direwolf, a LoRa iGate)"), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it.trim() }, Modifier.weight(1f), singleLine = true, placeholder = { Text("192.168.1.20:8001") })
                    OutlinedButton(onClick = { connectWith { app.radios.connectTcp(host, ProtocolType.APRS) } }, enabled = callOk && host.isNotBlank() && !busy) { Text(tr("Connect")) }
                }
            }
        }

        item { SectionLabel(tr("Beacons")) }
        item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Beacon my position on APRS"))
                    Text(tr("At most every 15 minutes, or every 2 minutes while you move. Separate from sharing on the mesh."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(beacon, { p.setAprsBeacon(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(status, { status = it.take(62) }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Status (e.g. Net control, 146.52)")) })
                OutlinedButton(onClick = { scope.launch { ctx.toast(aprs?.sendStatus(status)?.exceptionOrNull()?.message ?: tr("Status sent")) } },
                    enabled = aprs?.canTransmit == true && status.isNotBlank()) { Text(tr("Send")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Station ID in licensed mode"))
                    Text(tr("Meshtastic in licensed mode: re-sends your call sign at least every 10 minutes while you transmit, and when you stop, as FCC rules require."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(autoId, { p.setAutoStationId(it) })
            }
        } }

        item { SectionLabel(tr("Mesh gateway")) }
        item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Let licensed mesh users reach APRS"))
                    Text(tr("Licensed hams on Meshtastic or MeshCore message this node \"!register THEIRCALL-SSID\", then \"CALL: message\" to send on APRS under their own call sign. Replies come back to them. Nothing from anyone else goes to APRS."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(gateway, { p.setAprsGateway(it); aprs?.refreshFilter() })
            }
            val list = app.radios.aprsGateway.registrations().also { regs.length }
            if (gateway && list.isEmpty()) Text(tr("No one has registered yet."), fontSize = 12.sp)
            list.forEach { (key, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$c · ${key.substringBefore(':').let { runCatching { ProtocolType.valueOf(it).shortTag }.getOrDefault(it) }} ${key.substringAfter(':')}", Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { p.setAprsGatewayRegs(regs.lineSequence().filter { !it.startsWith("$key=") }.joinToString("\n")) }) { Text(tr("Remove")) }
                }
            }
        } }

        item { SectionLabel(tr("Stations heard") + if (stations.isNotEmpty()) " (${stations.size})" else "") }
        if (stations.isEmpty()) item { Text(tr("None yet."), Modifier.padding(horizontal = 20.dp), fontSize = 13.sp) }
        items(stations.values.sortedByDescending { it.heardMs }.take(100), key = { "st" + it.call }) { st ->
            Column(Modifier.fillMaxWidth().clickable { nav.navigate("node/${Uri.encode("APRS:${st.call}")}") }.padding(20.dp, 8.dp)) {
                Text(st.call + "  ·  " + AprsFormat.symbolName(st.symbol), fontWeight = FontWeight.Bold)
                val dist = if (me != null && st.lat != null && st.lon != null) Geo.formatDistance(Geo.distanceM(me!!.first, me!!.second, st.lat, st.lon), metric) + " · " else ""
                Text(dist + (if (st.viaInternet) "internet · " else "radio · ") + timeAgo(st.heardMs) + if (st.comment.isNotBlank()) " · " + st.comment else "",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        if (log.isNotEmpty()) {
            item { SectionLabel(tr("Packet log")) }
            items(log.reversed().take(60)) { l -> Text(l, Modifier.padding(horizontal = 16.dp), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 2) }
        }
        item { org.highbridge.app.ui.Gap(24) }
    }
}

/** Callsign lookup (FCC data via callook.info, worldwide via HamDB). */
@Composable
fun CallsignScreen(initial: String = "") {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var call by remember { mutableStateOf(initial) }
    var result by remember { mutableStateOf<Result<CallsignLookup.Info>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun go() { busy = true; scope.launch { result = CallsignLookup.lookup(call); busy = false } }
    LaunchedEffect(initial) { if (initial.isNotBlank()) go() }
    Column(Modifier.fillMaxSize()) {
        HbHeader(tr("Call sign lookup"), tr("Who holds a ham radio call sign"), onBack = { nav.popBackStack() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(call, { call = it.uppercase().trim().take(10) }, Modifier.weight(1f), singleLine = true, label = { Text(tr("Call sign")) })
                Button(onClick = { go() }, enabled = call.length >= 3 && !busy) { Text(tr("Look up")) }
            }
            result?.onSuccess { i ->
                HbCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(i.call, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    if (i.name.isNotBlank()) Text(i.name, fontWeight = FontWeight.Bold)
                    CallsignLookup.className(i.licenseClass)?.let { Text(tr("License") + ": $it") }
                    i.place?.let { Text(it) }
                    i.grid?.let { Text(tr("Grid square") + ": $it") }
                    i.expires?.let { Text(tr("Expires") + ": $it") }
                    Text(tr("Source") + ": ${i.source}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }?.onFailure { Text(it.message ?: "Lookup failed") }
            Text(tr("Lookups go to callook.info (US) or hamdb.org (worldwide) and need internet."), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Extra lines for an APRS station on its node page. */
@Composable
fun AprsStationDetails(call: String) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val st = (app.radios.aprs?.stations ?: MutableStateFlow(emptyMap())).collectAsState().value[call]
    HbCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (st != null) {
            Text(AprsFormat.symbolName(st.symbol), fontWeight = FontWeight.Bold)
            if (st.comment.isNotBlank()) Text(st.comment)
            if (st.status.isNotBlank()) Text(tr("Status") + ": ${st.status}")
            Text((if (st.viaInternet) tr("Heard over the internet") else tr("Heard on the radio")) + if (st.path.isNotBlank()) " · ${st.path}" else "", fontSize = 12.sp)
            st.altitudeFt?.let { Text(tr("Altitude") + ": $it ft", fontSize = 12.sp) }
        }
        TextButton(onClick = { nav.navigate("callsign/${Uri.encode(call.substringBefore('-'))}") }) { Text(tr("Look up this call sign")) }
    } }
}
