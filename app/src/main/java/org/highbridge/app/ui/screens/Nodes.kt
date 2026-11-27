// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.toast
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.data.TelemetryEntity
import org.highbridge.app.pro.Geo
import org.highbridge.app.ui.EmptyState
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.ProtocolBadge
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.app.ui.timeAgo
import org.highbridge.core.protocol.ProtocolType

@Composable
fun NodesScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val stale by app.prefs.staleMinutes.collectAsState()
    val me by app.radios.myLocation.collectAsState()
    val metric by app.prefs.metric.collectAsState()
    var filter by remember { mutableStateOf<ProtocolType?>(null) }
    var favOnly by remember { mutableStateOf(false) }
    var showIgnored by remember { mutableStateOf(false) }
    var showStale by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val shown = nodes.filter { filter == null || it.protocol == filter!!.name }.filter { (!favOnly || it.favorite) && (showIgnored || !it.ignored) }.filter { showStale || it.lastHeardMs == 0L || now - it.lastHeardMs < stale * 60_000L }
        .sortedWith(compareByDescending<org.highbridge.app.data.NodeEntity> { it.favorite }.thenByDescending { it.lastHeardMs })

    Column(Modifier.fillMaxSize()) {
        HbHeader("Nodes", "${shown.size} heard in the last ${stale / 60} h")
        LazyRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(filter == null, { filter = null }, { Text("All") }) }
            items(supportedProtocols) { p -> FilterChip(filter == p, { filter = p }, { Text("${p.shortTag} ${nodes.count { it.protocol == p.name }}") }) }
            item { FilterChip(showStale, { showStale = !showStale }, { Text("Include stale") }) }
            item { FilterChip(favOnly, { favOnly = !favOnly }, { Text("Favorites") }) }
            if (nodes.any { it.ignored }) item { FilterChip(showIgnored, { showIgnored = !showIgnored }, { Text("Ignored") }) }
        }
        if (shown.isEmpty()) EmptyState("No nodes yet", "Nodes appear as your radios hear them. Try sending an advert or a message.")
        LazyColumn {
            items(shown, key = { it.key }) { n ->
                Row(Modifier.fillMaxWidth().clickable { nav.navigate("node/${Uri.encode(n.key)}") }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProtocolBadge(ProtocolType.valueOf(n.protocol))
                    Column(Modifier.weight(1f)) {
                        Text(n.longName, fontWeight = FontWeight.Bold)
                        val dist = if (me != null && n.lat != null && n.lon != null) " · " + Geo.formatDistance(Geo.distanceM(me!!.first, me!!.second, n.lat, n.lon), metric) else ""
                        val hops = when (n.hops) { null, -1 -> "flood"; 0 -> "direct"; else -> "${n.hops} hop${if (n.hops == 1) "" else "s"}" }
                        Text("$hops${n.snr?.let { " · SNR %.1f dB".format(it) } ?: ""}$dist · ${timeAgo(n.lastHeardMs)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(n.battery?.let { if (it > 100) "plugged in" else "$it%" } ?: n.voltage?.let { "%.2f V".format(it) } ?: "", fontSize = 12.sp)
                    if (n.favorite) Icon(Icons.Filled.Star, "Favorite", tint = StPaulMeshPalette.Gold)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun NodeDetailScreen(key: String) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val n = nodes.firstOrNull { it.key == key }
    val telemetry by app.db.dao().telemetry(key).collectAsState(emptyList())
    val routes by app.db.dao().routes().collectAsState(emptyList())
    val me by app.radios.myLocation.collectAsState()
    val metric by app.prefs.metric.collectAsState()
    if (n == null) { HbHeader("Node", onBack = { nav.popBackStack() }); return }
    val proto = ProtocolType.valueOf(n.protocol)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HbHeader(n.longName, "${proto.shortTag} · ${n.id}", onBack = { nav.popBackStack() }, actions = {
            IconButton(onClick = { scope.launch { app.db.dao().setFavorite(key, !n.favorite); if (proto == ProtocolType.MESHTASTIC) app.radios.meshtastic?.setFavoriteOnRadio(n.id, !n.favorite) } }) {
                Icon(if (n.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite", tint = StPaulMeshPalette.Gold)
            }
        })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HbCard {
                Text("Last heard ${timeAgo(n.lastHeardMs)}")
                n.snr?.let { Text("SNR %.1f dB · RSSI ${n.rssi ?: "?"} dBm".format(it)) }
                n.hops?.let { Text(if (it <= 0) "Heard directly" else "$it hops away") }
                if (n.lat != null && n.lon != null) {
                    Text("Position %.5f, %.5f".format(n.lat, n.lon))
                    me?.let { Text("${Geo.formatDistance(Geo.distanceM(it.first, it.second, n.lat, n.lon), metric)} · ${Geo.compassPoint(Geo.bearingDeg(it.first, it.second, n.lat, n.lon))}") }
                }
                n.altM?.let { Text("Altitude ${if (metric) "$it m" else "${(it * 3.281).toInt()} ft"}") }
                (n.battery ?: n.voltage)?.let { Text("Battery ${n.battery?.let { "$it%" } ?: ""} ${n.voltage?.let { "%.2f V".format(it) } ?: ""}") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ignore this node")
                    Text("Hides it from lists and the map and drops its messages.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(n.ignored, { v -> scope.launch { app.db.dao().setIgnored(key, v); if (proto == ProtocolType.MESHTASTIC) app.radios.meshtastic?.setIgnoredOnRadio(n.id, v) } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("chat/${Uri.encode("${n.protocol}:dm:${n.id}")}") }) { Text("Message") }
                OutlinedButton(onClick = { scope.launch { val r = app.radios.radio(proto)?.requestTraceroute(n.id); ctx.toast(r?.exceptionOrNull()?.message ?: "Traceroute sent") } }) { Text("Traceroute") }
                if (proto == ProtocolType.MESHCORE) OutlinedButton(onClick = {
                    scope.launch { ctx.toast(app.radios.meshcore?.requestTelemetry(n.id)?.exceptionOrNull()?.message ?: "Asked for telemetry; the answer shows below when it arrives") }
                }) { Text("Telemetry") }
                if (n.lat != null) OutlinedButton(onClick = { nav.navigate("compass/${Uri.encode(key)}") }) { Text("Compass") }
            }
            if (proto == ProtocolType.MESHCORE) OutlinedButton(onClick = { nav.navigate("repeater/${Uri.encode(n.id)}") }) { Text("Repeater admin & path") }
            if (telemetry.isNotEmpty()) {
                SectionLabel("Telemetry", Modifier.padding(0.dp))
                TelemetryChart("Battery %", telemetry.mapNotNull { t -> t.battery?.takeIf { it <= 100 }?.let { t.timeMs to it.toFloat() } })
                TelemetryChart("Voltage", telemetry.mapNotNull { t -> t.voltage?.let { t.timeMs to it } })
                TelemetryChart("Channel use %", telemetry.mapNotNull { t -> t.channelUtil?.let { t.timeMs to it } })
                TelemetryChart("Temperature °C", telemetry.mapNotNull { t -> t.temperatureC?.let { t.timeMs to it } })
            }
            val myRoutes = routes.filter { it.destId == n.id && it.protocol == n.protocol }.take(5)
            if (myRoutes.isNotEmpty()) {
                SectionLabel("Traceroutes", Modifier.padding(0.dp))
                myRoutes.forEach { r ->
                    Text("${timeAgo(r.timeMs)}: You → ${r.hops.split(',').filter { it.isNotBlank() }.joinToString(" → ").ifBlank { "direct" }} → ${n.shortName}" +
                        (if (r.snrs.isNotBlank()) "  (SNR ${r.snrs})" else ""), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun TelemetryChart(label: String, points: List<Pair<Long, Float>>) {
    if (points.size < 2) return
    val line = MaterialTheme.colorScheme.primary
    val sorted = points.sortedBy { it.first }
    Column {
        Text("$label · latest %.1f".format(sorted.last().second), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            val t0 = sorted.first().first; val t1 = sorted.last().first.coerceAtLeast(t0 + 1)
            val lo = sorted.minOf { it.second }; val hi = sorted.maxOf { it.second }.let { if (it == lo) lo + 1 else it }
            val path = Path()
            sorted.forEachIndexed { i, (t, v) ->
                val x = (t - t0).toFloat() / (t1 - t0) * size.width
                val y = size.height - (v - lo) / (hi - lo) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(3f))
            drawLine(StPaulMeshPalette.Line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
        }
    }
}

