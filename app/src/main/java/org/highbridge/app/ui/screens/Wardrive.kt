// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.timeAgo
import org.highbridge.app.ui.toast
import org.highbridge.core.protocol.ProtocolType
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon

/** Wardriving: drive or walk around with your radio and map where the mesh reaches. */
@Composable
fun WardriveScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val wd = app.radios.wardriver
    val st by wd.state.collectAsState()
    val radios by app.radios.radios.collectAsState()
    val upload by app.prefs.wardriveUpload.collectAsState()
    val ping by app.prefs.wardrivePing.collectAsState()
    val home by app.prefs.wardriveHome.collectAsState()
    val hideM by app.prefs.wardriveHideM.collectAsState()
    val me by app.radios.myLocation.collectAsState()
    val sessions by app.db.dao().wardriveSessions().collectAsState(emptyList())
    val choices = listOf(ProtocolType.MESHTASTIC, ProtocolType.MESHCORE)
    var proto by remember { mutableStateOf(st.protocol ?: choices.firstOrNull { it in radios } ?: ProtocolType.MESHTASTIC) }
    val samples by remember(st.session) { app.db.dao().wardriveSession(st.session) }.collectAsState(emptyList())
    var askShare by remember { mutableStateOf(false) }
    // Prominent disclosure and consent before any location leaves the phone.
    if (askShare) org.highbridge.app.ui.HbAlertDialog(onDismissRequest = { askShare = false },
        title = { Text(tr("Share your coverage?")) },
        text = { Text("While wardriving, Highbridge uses your precise location, also with the screen off, to record where your radio hears the mesh. " +
            "If you share, it sends stpaulmesh.com your position rounded to about 10 m, the time and signal readings, for the public coverage map. " +
            "No names, node IDs or device details are sent. You can change this any time on this screen.") },
        confirmButton = { TextButton(onClick = {
            askShare = false; app.prefs.setWardriveUpload(true); app.prefs.setWardriveAsked(true)
            runCatching { wd.start(proto) }.onFailure { ctx.toast(it.message, long = true) }
        }) { Text(tr("Share and start")) } },
        dismissButton = { TextButton(onClick = {
            askShare = false; app.prefs.setWardriveUpload(false); app.prefs.setWardriveAsked(true)
            runCatching { wd.start(proto) }.onFailure { ctx.toast(it.message, long = true) }
        }) { Text(tr("Keep on my phone")) } })

    // Keep the screen on while this screen is open and recording, like a dash-mounted map.
    DisposableEffect(st.running) {
        val w = (ctx as? ComponentActivity)?.window
        if (st.running) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val mapView = remember { MapView(ctx).apply { setMultiTouchControls(true); setTileSource(TileSourceFactory.MAPNIK); controller.setZoom(15.0); controller.setCenter(GeoPoint(44.9340, -93.1180)) } }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    LazyColumn {
        item {
            HbHeader(tr("Wardriving"), tr("Map where the mesh reaches"), onBack = { nav.popBackStack() }, actions = {
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(wd.coverageUrl(proto)))) }) { Text(tr("Live map")) }
            })
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { p -> FilterChip(proto == p, { if (!st.running) proto = p }, { Text(if (p == ProtocolType.MESHCORE) "MeshCore" else "Meshtastic") }) }
            }
        }
        item {
            HbCard(Modifier.padding(16.dp, 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(if (st.running) Color(0xFF2EA043) else MaterialTheme.colorScheme.outline, CircleShape))
                    Text(if (st.running) "  Recording · ${st.note}" else "  Not recording", fontWeight = FontWeight.Bold)
                }
                if (st.running || st.samples > 0) {
                    Text("${st.samples} samples · ${st.pings} pings, ${st.pingsHeard} heard" +
                        (st.bestSnr?.let { " · best SNR %.1f dB".format(it) } ?: "") + (st.accuracyM?.let { " · GPS ±%.0f m".format(it) } ?: ""), fontSize = 13.sp)
                    if (upload) Text("Shared with stpaulmesh.com: ${st.uploaded}" + (st.uploadError?.let { " · last try failed: $it" } ?: ""), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!st.running) Button(onClick = {
                        if (proto !in radios) ctx.toast("Connect your ${if (proto == ProtocolType.MESHCORE) "MeshCore" else "Meshtastic"} radio first", long = true)
                        else if (!app.prefs.wardriveAsked.value) askShare = true
                        else runCatching { wd.start(proto) }.onFailure { ctx.toast(it.message, long = true) }
                    }) { Text(tr("Start")) }
                    else Button(onClick = { wd.stop() }) { Text(tr("Stop")) }
                    if (upload) OutlinedButton(onClick = { scope.launch { wd.upload(proto).onSuccess { ctx.toast("Shared $it samples") }.onFailure { ctx.toast(it.message, long = true) } } }) { Text(tr("Share now")) }
                }
            }
        }
        item {
            AndroidView({ mapView }, Modifier.fillMaxWidth().height(320.dp).padding(horizontal = 16.dp), update = { mv ->
                mv.overlays.clear()
                samples.forEach { s ->
                    mv.overlays += Polygon(mv).apply {
                        points = Polygon.pointsAsCircle(GeoPoint(s.lat, s.lon), 30.0)
                        val c = coverageColor(s.kind, s.heard, s.snr); fillPaint.color = c; outlinePaint.color = c
                        setOnClickListener { _, _, _ -> false }
                    }
                }
                st.fix?.let { (la, lo) -> mv.controller.animateTo(GeoPoint(la, lo)) }
                mv.invalidate()
            })
            Row(Modifier.padding(16.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Color(0xFF2EA043) to "Strong", Color(0xFFA0BE28) to "Fair", Color(0xFFE6961E) to "Weak", Color(0xFFC83228) to "Very weak", Color(0xFF787878) to "Nothing heard")
                    .forEach { (c, l) -> Text("● $l", color = c, fontSize = 12.sp) }
            }
        }
        item { SectionLabel(tr("How it works")) }
        item {
            Text(
                (if (proto == ProtocolType.MESHCORE)
                    "Highbridge listens for repeaters and records where you hear them and how strongly. With pings on, it sends your position to the #wardriving channel about once a minute after you've moved, and counts the repeaters that repeat it."
                else
                    "Highbridge records the signal of every packet your radio hears. With pings on, it broadcasts your position every couple of minutes after you've moved; it counts as heard when a nearby node rebroadcasts it.") +
                    " Pings stop near your region's airtime limit.",
                Modifier.padding(horizontal = 16.dp), fontSize = 13.sp)
        }
        item { SectionLabel(tr("Settings")) }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Send pings"))
                        Text(tr("Measures where the mesh hears you. Off = listen only, no airtime."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(ping, { app.prefs.setWardrivePing(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Share with the stpaulmesh.com map"))
                        Text(tr("Sends position (rounded to about 10 m), time and signal only. No names, node IDs or anything about you."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(upload, { app.prefs.setWardriveUpload(it) })
                }
                Text(tr("Home privacy circle"), Modifier.padding(top = 8.dp))
                Text(if (home.isBlank()) "Not set. Set it at home so nothing is recorded within $hideM m of your house."
                    else "Nothing is recorded within $hideM m of your home.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        val p = st.fix ?: me
                        if (p == null) ctx.toast(tr("Waiting for your location")) else { app.prefs.setWardriveHome("%.5f,%.5f".format(java.util.Locale.US, p.first, p.second)); ctx.toast(tr("Home circle set here")) }
                    }) { Text(tr("I'm home: set it here")) }
                    if (home.isNotBlank()) TextButton(onClick = { app.prefs.setWardriveHome("") }) { Text(tr("Clear")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(200, 400, 800).forEach { m -> FilterChip(hideM == m, { app.prefs.setWardriveHideM(m) }, { Text("$m m") }) }
                }
            }
        }
        item { SectionLabel(tr("Your sessions")) }
        if (sessions.isEmpty()) item { Text(tr("None yet."), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp) }
        items(sessions, key = { it.session }) { s ->
            Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (s.protocol == "MESHCORE") "MeshCore" else "Meshtastic"} · ${timeAgo(s.firstMs)}", fontWeight = FontWeight.Bold)
                    Text("${s.samples} samples · ${(s.lastMs - s.firstMs) / 60_000} min" + if (s.pending > 0) " · ${s.pending} not shared yet" else "", fontSize = 12.sp)
                }
                TextButton(onClick = { scope.launch { app.db.dao().deleteWardriveSession(s.session) } }) { Text(tr("Delete")) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        item { Text(tr("Deleting a session removes it from this phone. Coverage already shared stays on the map, mixed with everyone else's."),
            Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
