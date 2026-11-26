// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.data.GeofenceEntity
import org.highbridge.app.data.NodeEntity
import org.highbridge.app.data.RangeTestEntity
import org.highbridge.app.pro.Geo
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.app.ui.timeAgo
import org.highbridge.core.protocol.ProtocolType
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.tilesource.FileBasedTileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

private val SAINT_PAUL = GeoPoint(44.9370, -93.1050) // the High Bridge

/** Offline-capable community map with per-protocol or combined views. */
@Composable
fun MapScreen() {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val range by app.db.dao().rangeTests().collectAsState(emptyList())
    val fences by app.db.dao().geofences().collectAsState(emptyList())
    val me by app.radios.myLocation.collectAsState()
    val style by app.prefs.mapStyle.collectAsState()
    val stale by app.prefs.staleMinutes.collectAsState()
    var filter by remember { mutableStateOf<Set<ProtocolType>>(ProtocolType.entries.toSet()) }
    var rings by remember { mutableStateOf(true) }
    var heatmap by remember { mutableStateOf(false) }
    var hideStale by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var mbtiles by remember { mutableStateOf(File(ctx.filesDir, "osmdroid").listFiles()?.firstOrNull { it.extension == "mbtiles" }) }
    val mapView = remember { MapView(ctx).apply { setMultiTouchControls(true); controller.setZoom(13.0); controller.setCenter(SAINT_PAUL); zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) mapView.onResume() else if (e == Lifecycle.Event.ON_PAUSE) mapView.onPause() }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs); mapView.onDetach() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val dir = File(ctx.filesDir, "osmdroid").apply { mkdirs() }
        val f = File(dir, "imported.mbtiles")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        mbtiles = f; app.prefs.setMapStyle("MBTILES")
        Toast.makeText(ctx, "Offline map imported", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        HbHeader("Map", "${nodes.count { it.lat != null }} nodes with a position", actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Layers, "Map style") }
            DropdownMenu(menu, { menu = false }) {
                listOf("STANDARD" to "Standard", "TOPO" to "Topographic", "NIGHT" to "Night").forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { app.prefs.setMapStyle(k); menu = false }) }
                if (mbtiles != null) DropdownMenuItem({ Text("Offline (.mbtiles)") }, { app.prefs.setMapStyle("MBTILES"); menu = false })
                DropdownMenuItem({ Text("Import .mbtiles file…") }, { menu = false; importer.launch(arrayOf("*/*")) })
                DropdownMenuItem({ Text("Save this area for offline use") }, {
                    menu = false
                    runCatching {
                        CacheManager(mapView).downloadAreaAsync(ctx, mapView.boundingBox, mapView.zoomLevelDouble.toInt(), (mapView.zoomLevelDouble + 2).toInt().coerceAtMost(16))
                    }.onFailure { Toast.makeText(ctx, "This map's tile server doesn't allow bulk downloads. Import an .mbtiles file instead.", Toast.LENGTH_LONG).show() }
                })
            }
        })
        LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ProtocolType.entries) { p -> FilterChip(p in filter, { filter = if (p in filter) filter - p else filter + p }, { Text(p.shortTag) }) }
            item { FilterChip(rings, { rings = !rings }, { Text("Signal rings") }) }
            item { FilterChip(heatmap, { heatmap = !heatmap }, { Text("Range test") }) }
            item { FilterChip(hideStale, { hideStale = !hideStale }, { Text("Hide stale") }) }
        }
        Box(Modifier.weight(1f)) {
            AndroidView({ mapView }, Modifier.fillMaxSize(), update = { mv ->
                applyStyle(mv, style, mbtiles)
                mv.overlays.clear()
                val now = System.currentTimeMillis()
                if (heatmap) drawRange(mv, range)
                drawFences(mv, fences)
                me?.let { (lat, lon) ->
                    val p = GeoPoint(lat, lon)
                    if (rings) listOf(1000.0, 3000.0, 8000.0).forEach { r ->
                        mv.overlays += Polygon(mv).apply { points = Polygon.pointsAsCircle(p, r); fillPaint.color = AColor.TRANSPARENT
                            outlinePaint.color = StPaulMeshPalette.Gold.toArgb(); outlinePaint.strokeWidth = 2f; setOnClickListener { _, _, _ -> false } }
                    }
                    mv.overlays += Marker(mv).apply { position = p; title = "You"; icon = dot(StPaulMeshPalette.Gold.toArgb(), 26); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) }
                }
                nodes.filter { it.lat != null && it.lon != null && ProtocolType.valueOf(it.protocol) in filter }
                    .filter { !hideStale || it.lastHeardMs == 0L || now - it.lastHeardMs < stale * 60_000L }
                    .forEach { n -> mv.overlays += nodeMarker(mv, n, me) { nav.navigate("node/${Uri.encode(n.key)}") } }
                mv.invalidate()
            })
            IconButton(onClick = { me?.let { mapView.controller.animateTo(GeoPoint(it.first, it.second)) } ?: mapView.controller.animateTo(SAINT_PAUL) },
                Modifier.align(Alignment.TopEnd).padding(12.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))) { Icon(Icons.Filled.MyLocation, "Center on me") }
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("● Meshtastic", color = StPaulMeshPalette.Meshtastic); Text("● MeshCore", color = StPaulMeshPalette.MeshCore); Text("● Reticulum", color = StPaulMeshPalette.Reticulum)
            }
            Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomEnd).padding(6.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun applyStyle(mv: MapView, style: String, mbtiles: File?) {
    val tag = mv.tag as? String
    if (tag == style) return
    mv.tag = style
    mv.overlayManager.tilesOverlay.setColorFilter(null)
    when (style) {
        "MBTILES" -> if (mbtiles != null) runCatching {
            val provider = OfflineTileProvider(SimpleRegisterReceiver(mv.context), arrayOf(mbtiles))
            mv.setTileProvider(provider)
            provider.archives.firstOrNull()?.tileSources?.firstOrNull()?.let { mv.setTileSource(FileBasedTileSource.getSource(it)) }
            mv.setUseDataConnection(false)
        }
        "TOPO" -> { mv.setUseDataConnection(true); mv.setTileSource(TileSourceFactory.OpenTopo) }
        "NIGHT" -> { mv.setUseDataConnection(true); mv.setTileSource(TileSourceFactory.MAPNIK); mv.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS) }
        else -> { mv.setUseDataConnection(true); mv.setTileSource(TileSourceFactory.MAPNIK) }
    }
}

private fun dot(color: Int, sizePx: Int, square: Boolean = false) = GradientDrawable().apply {
    shape = if (square) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
    if (square) cornerRadius = 4f
    setColor(color); setStroke(3, AColor.WHITE); setSize(sizePx, sizePx)
}

private fun protoColor(p: String) = when (p) {
    "MESHTASTIC" -> StPaulMeshPalette.Meshtastic; "MESHCORE" -> StPaulMeshPalette.MeshCore; else -> StPaulMeshPalette.Reticulum
}.toArgb()

private fun nodeMarker(mv: MapView, n: NodeEntity, me: Pair<Double, Double>?, onOpen: () -> Unit) = Marker(mv).apply {
    position = GeoPoint(n.lat!!, n.lon!!)
    val repeater = n.longName.contains("repeater", true) || n.longName.contains("rptr", true)
    icon = dot(protoColor(n.protocol), if (repeater) 30 else 26, square = repeater)
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    title = n.longName
    snippet = buildString {
        append(timeAgo(n.lastHeardMs))
        n.snr?.let { append(" · SNR %.1f dB".format(it)) }
        if (me != null) append(" · " + Geo.formatDistance(Geo.distanceM(me.first, me.second, n.lat, n.lon), false))
    }
    setOnMarkerClickListener { m, _ -> if (m.isInfoWindowShown) { onOpen(); true } else { m.showInfoWindow(); true } }
}

/** Colours each range-test reception by SNR: green strong, gold fair, brick weak. */
private fun drawRange(mv: MapView, samples: List<RangeTestEntity>) {
    for (r in samples) {
        val lat = r.lat ?: continue; val lon = r.lon ?: continue
        val snr = r.snr ?: -20f
        val c = when { snr >= 0 -> StPaulMeshPalette.Wave; snr >= -10 -> StPaulMeshPalette.Gold; else -> StPaulMeshPalette.SchmidtBrick }
        mv.overlays += Polygon(mv).apply {
            points = Polygon.pointsAsCircle(GeoPoint(lat, lon), 60.0)
            fillPaint.color = c.copy(alpha = .55f).toArgb(); outlinePaint.color = AColor.TRANSPARENT
            title = "SNR %.1f · RSSI ${r.rssi}".format(snr)
        }
    }
}

private fun drawFences(mv: MapView, fences: List<GeofenceEntity>) {
    for (g in fences.filter { it.active }) {
        val pts = Geo.parsePoints(g.points).map { GeoPoint(it.first, it.second) }
        if (pts.isEmpty()) continue
        val color = StPaulMeshPalette.SchmidtBrick.toArgb()
        when (g.kind) {
            "CIRCLE" -> mv.overlays += Polygon(mv).apply { points = Polygon.pointsAsCircle(pts[0], g.radiusM); fillPaint.color = AColor.argb(30, 142, 59, 43); outlinePaint.color = color; title = g.name }
            "POLYGON" -> mv.overlays += Polygon(mv).apply { points = pts; fillPaint.color = AColor.argb(30, 142, 59, 43); outlinePaint.color = color; title = g.name }
            "TRAIL" -> mv.overlays += Polyline(mv).apply { setPoints(pts); outlinePaint.color = color; outlinePaint.strokeWidth = 6f; title = g.name }
        }
    }
}
