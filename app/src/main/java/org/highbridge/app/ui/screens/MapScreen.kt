// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import org.highbridge.app.ui.toast
import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import org.highbridge.app.ui.theme.protocolColorOf
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
    var favoritesOnly by remember { mutableStateOf(false) }
    var coverage by remember { mutableStateOf(false) }
    var showRoutes by remember { mutableStateOf(false) }
    val predicted by org.highbridge.app.pro.LinkBudget.shownOnMap.collectAsState()
    val routes by app.db.dao().routes().collectAsState(emptyList())
    val waypoints by remember { app.db.dao().waypoints(System.currentTimeMillis()) }.collectAsState(emptyList())
    val myCoverage by app.db.dao().wardriveAllProtocols().collectAsState(emptyList())
    var dropAt by remember { mutableStateOf<GeoPoint?>(null) }
    var openWaypoint by remember { mutableStateOf<org.highbridge.app.data.WaypointEntity?>(null) }
    var menu by remember { mutableStateOf(false) }
    var mbtiles by remember { mutableStateOf(File(ctx.filesDir, "osmdroid").listFiles()?.firstOrNull { it.extension == "mbtiles" }) }
    val mapView = remember { MapView(ctx).apply { setMultiTouchControls(true); controller.setZoom(13.0); controller.setCenter(SAINT_PAUL); zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER) } }
    LaunchedEffect(predicted) { predicted?.let { c -> mapView.controller.animateTo(GeoPoint(c.lat, c.lon)); mapView.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(Polygon.pointsAsCircle(GeoPoint(c.lat, c.lon), c.radiusM)), true, 40) } }
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
        ctx.toast(tr("Offline map imported"))
    }

    Column(Modifier.fillMaxSize()) {
        HbHeader(tr("Map"), "${nodes.count { it.lat != null }} nodes with a position", actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Layers, "Map style") }
            org.highbridge.app.ui.HbDropdownMenu(menu, { menu = false }) {
                listOf("STANDARD" to "Street", "TOPO" to "Topographic", "USGS_TOPO" to "USGS topo (US)", "SATELLITE" to "Satellite (US)",
                    "HYBRID" to "Hybrid: satellite with labels (US)", "TERRAIN" to "Terrain: shaded relief (US)", "HYDRO" to "Water features (US)",
                    "NIGHT" to "Night").forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { app.prefs.setMapStyle(k); menu = false }) }
                if (mbtiles != null) DropdownMenuItem({ Text(tr("Offline (.mbtiles)")) }, { app.prefs.setMapStyle("MBTILES"); menu = false })
                DropdownMenuItem({ Text(tr("Import .mbtiles file…")) }, { menu = false; importer.launch(arrayOf("*/*")) })
                DropdownMenuItem({ Text(tr("Save this area for offline use")) }, {
                    menu = false
                    runCatching {
                        CacheManager(mapView).downloadAreaAsync(ctx, mapView.boundingBox, mapView.zoomLevelDouble.toInt(), (mapView.zoomLevelDouble + 2).toInt().coerceAtMost(16))
                    }.onFailure { ctx.toast(tr("This map's tile server doesn't allow bulk downloads. Import an .mbtiles file instead."), long = true) }
                })
            }
        })
        LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shownProtocols) { p -> FilterChip(p in filter, { filter = if (p in filter) filter - p else filter + p }, { Text(p.shortTag) }) }
            item { FilterChip(rings, { rings = !rings }, { Text(tr("Signal rings")) }) }
            item { FilterChip(heatmap, { heatmap = !heatmap }, { Text(tr("Range test")) }) }
            item { FilterChip(hideStale, { hideStale = !hideStale }, { Text(tr("Hide stale")) }) }
            item { FilterChip(favoritesOnly, { favoritesOnly = !favoritesOnly }, { Text(tr("Favorites only")) }) }
            item { FilterChip(coverage, { coverage = !coverage }, { Text(tr("My coverage")) }) }
            item { FilterChip(showRoutes, { showRoutes = !showRoutes }, { Text(tr("Traceroutes")) }) }
            if (predicted != null) item { FilterChip(true, { org.highbridge.app.pro.LinkBudget.shownOnMap.value = null }, { Text(tr("Prediction ✕")) }) }
        }
        Box(Modifier.weight(1f).clipToBounds()) {   // osmdroid can draw outside its bounds and cover the chips above
            AndroidView({ mapView }, Modifier.fillMaxSize(), update = { mv ->
                applyStyle(mv, style, mbtiles)
                mv.overlays.clear()
                val now = System.currentTimeMillis()
                if (heatmap) drawRange(mv, range)
                if (coverage) drawCoverage(mv, myCoverage)
                drawFences(mv, fences)
                if (showRoutes) drawRoutes(mv, routes, nodes, me)
                predicted?.let { c ->
                    mv.overlays += Polygon(mv).apply { points = Polygon.pointsAsCircle(GeoPoint(c.lat, c.lon), c.radiusM)
                        fillPaint.color = AColor.argb(40, 46, 160, 67); outlinePaint.color = AColor.argb(200, 46, 160, 67); outlinePaint.strokeWidth = 4f; title = c.label }
                }
                waypoints.forEach { w ->
                    mv.overlays += Marker(mv).apply {
                        position = GeoPoint(w.lat, w.lon); title = w.name; snippet = w.description
                        icon = dot(StPaulMeshPalette.SchmidtBrick.toArgb(), 34, square = true); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> openWaypoint = w; true }
                    }
                }
                // Long-press anywhere to drop a pin.
                mv.overlays += org.osmdroid.views.overlay.MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                    override fun longPressHelper(p: GeoPoint?): Boolean { dropAt = p; return p != null }
                })
                me?.let { (lat, lon) ->
                    val p = GeoPoint(lat, lon)
                    if (rings) listOf(1000.0, 3000.0, 8000.0).forEach { r ->
                        mv.overlays += Polygon(mv).apply { points = Polygon.pointsAsCircle(p, r); fillPaint.color = AColor.TRANSPARENT
                            outlinePaint.color = StPaulMeshPalette.Gold.toArgb(); outlinePaint.strokeWidth = 2f; setOnClickListener { _, _, _ -> false } }
                    }
                    mv.overlays += Marker(mv).apply { position = p; title = "You"; icon = dot(StPaulMeshPalette.Gold.toArgb(), 26); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) }
                }
                nodes.filter { it.lat != null && it.lon != null && ProtocolType.valueOf(it.protocol) in filter && !it.ignored && (!favoritesOnly || it.favorite) }
                    .filter { !hideStale || it.lastHeardMs == 0L || now - it.lastHeardMs < stale * 60_000L }
                    .forEach { n -> mv.overlays += nodeMarker(mv, n, me) { nav.navigate("node/${Uri.encode(n.key)}") } }
                mv.invalidate()
            })
            IconButton(onClick = { me?.let { mapView.controller.animateTo(GeoPoint(it.first, it.second)) } ?: mapView.controller.animateTo(SAINT_PAUL) },
                Modifier.align(Alignment.TopEnd).padding(12.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))) { Icon(Icons.Filled.MyLocation, "Center on me") }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 26.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(8.dp, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("● Meshtastic", color = StPaulMeshPalette.Meshtastic); Text("● MeshCore", color = StPaulMeshPalette.MeshCore); if (org.highbridge.app.rns.ReticulumSupport.AVAILABLE) Text("● Reticulum", color = StPaulMeshPalette.Reticulum)
                if (nodes.any { it.protocol == "APRS" }) Text("● APRS", color = StPaulMeshPalette.Aprs)
            }
            Text(if (style in setOf("SATELLITE", "USGS_TOPO", "HYBRID", "TERRAIN", "HYDRO")) "USGS The National Map" else "© OpenStreetMap contributors",
                Modifier.align(Alignment.BottomEnd).padding(6.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    dropAt?.let { p -> DropWaypointDialog(p.latitude, p.longitude) { dropAt = null } }
    openWaypoint?.let { w ->
        org.highbridge.app.ui.HbAlertDialog(onDismissRequest = { openWaypoint = null },
            title = { Text(w.name.ifBlank { "Waypoint" }) },
            text = { Text(listOfNotNull(w.description.takeIf { it.isNotBlank() }, "%.5f, %.5f".format(w.lat, w.lon),
                if (w.fromId == "me") "Shared by you" else "Shared by ${w.fromId}",
                if (w.expireMs > 0) "Expires ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(w.expireMs))}" else null).joinToString("\n")) },
            confirmButton = { TextButton(onClick = {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("geo:${w.lat},${w.lon}?q=${w.lat},${w.lon}(${Uri.encode(w.name)})")))
            }) { Text(tr("Navigate")) } },
            dismissButton = { TextButton(onClick = {
                val wp = w; openWaypoint = null
                kotlinx.coroutines.MainScope().launch { app.radios.deleteWaypoint(wp) }
            }) { Text(tr("Delete")) } })
    }
}

/** Long-press on the map: name a pin and share it with your Meshtastic channel. */
@Composable
internal fun DropWaypointDialog(lat: Double, lon: Double, onDone: () -> Unit) {
    val app = HighbridgeApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf(24) }
    val canShare = ProtocolType.MESHTASTIC in app.radios.radios.collectAsState().value
    org.highbridge.app.ui.HbAlertDialog(onDismissRequest = onDone,
        title = { Text(tr("Drop a pin")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("%.5f, %.5f".format(lat, lon), style = MaterialTheme.typography.labelMedium)
            androidx.compose.material3.OutlinedTextField(name, { name = it.take(29) }, label = { Text(tr("Name")) }, singleLine = true)
            androidx.compose.material3.OutlinedTextField(desc, { desc = it.take(99) }, label = { Text(tr("Note (optional)")) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "1 h", 24 to "1 day", 168 to "1 week", 0 to "Never").forEach { (h, l) -> FilterChip(hours == h, { hours = h }, { Text(l) }) }
            }
            if (!canShare) Text(tr("Connect a Meshtastic radio to share pins. The pin is kept on this phone either way."), style = MaterialTheme.typography.labelSmall)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            scope.launch {
                if (canShare) app.radios.shareWaypoint(ProtocolType.MESHTASTIC, name.trim(), desc.trim(), lat, lon, 0x1F4CD, hours)
                    .onSuccess { ctx.toast(tr("Pin shared")) }.onFailure { ctx.toast(it.message, long = true) }
                else app.db.dao().upsertWaypoint(org.highbridge.app.data.WaypointEntity("LOCAL:${System.currentTimeMillis()}", "LOCAL", 0, name.trim(), desc.trim(), lat, lon, 0,
                    if (hours > 0) System.currentTimeMillis() + hours * 3_600_000L else 0L, "me", System.currentTimeMillis()))
                onDone()
            }
        }) { Text(if (canShare) "Share" else "Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text(tr("Cancel")) } })
}

/** Your own wardriving samples: green strong, yellow fair, orange weak, red very weak, grey nothing heard. */
private fun drawCoverage(mv: MapView, samples: List<org.highbridge.app.data.WardriveSampleEntity>) {
    samples.take(3000).forEach { s ->
        mv.overlays += Polygon(mv).apply {
            points = Polygon.pointsAsCircle(GeoPoint(s.lat, s.lon), 35.0)
            val c = coverageColor(s.kind, s.heard, s.snr)
            fillPaint.color = c; outlinePaint.color = c; outlinePaint.strokeWidth = 1f
            setOnClickListener { _, _, _ -> false }
        }
    }
}

/** Shared with the website legend: same thresholds as stpaulmesh.com. */
fun coverageColor(kind: Int, heard: Int, snr: Float?): Int = when {
    kind == 2 || (kind == 1 && heard == 0) -> AColor.argb(170, 120, 120, 120)
    snr == null -> AColor.argb(190, 46, 160, 67)
    snr >= 5f -> AColor.argb(190, 46, 160, 67)
    snr >= 0f -> AColor.argb(190, 160, 190, 40)
    snr >= -7f -> AColor.argb(190, 230, 150, 30)
    else -> AColor.argb(190, 200, 50, 40)
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
        "SATELLITE" -> { mv.setUseDataConnection(true); mv.setTileSource(UsgsTiles("USGSImageryOnly")) }
        "USGS_TOPO" -> { mv.setUseDataConnection(true); mv.setTileSource(UsgsTiles("USGSTopo")) }
        "HYBRID" -> { mv.setUseDataConnection(true); mv.setTileSource(UsgsTiles("USGSImageryTopo")) }
        "TERRAIN" -> { mv.setUseDataConnection(true); mv.setTileSource(UsgsTiles("USGSShadedReliefOnly")) }
        "HYDRO" -> { mv.setUseDataConnection(true); mv.setTileSource(UsgsTiles("USGSHydroCached")) }
        "NIGHT" -> { mv.setUseDataConnection(true); mv.setTileSource(TileSourceFactory.MAPNIK); mv.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS) }
        else -> { mv.setUseDataConnection(true); mv.setTileSource(TileSourceFactory.MAPNIK) }
    }
}

private fun dot(color: Int, sizePx: Int, square: Boolean = false) = GradientDrawable().apply {
    shape = if (square) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
    if (square) cornerRadius = 4f
    setColor(color); setStroke(3, AColor.WHITE); setSize(sizePx, sizePx)
}


private fun nodeMarker(mv: MapView, n: NodeEntity, me: Pair<Double, Double>?, onOpen: () -> Unit) = Marker(mv).apply {
    position = GeoPoint(n.lat!!, n.lon!!)
    val repeater = n.longName.contains("repeater", true) || n.longName.contains("rptr", true)
    icon = dot(protocolColorOf(n.protocol).toArgb(), if (repeater) 30 else 26, square = repeater)
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

/**
 * USGS The National Map tiles: public domain (US government work), so saving areas for offline use is
 * allowed, unlike OpenStreetMap's servers. Coverage is the United States. ArcGIS order is z/y/x.
 */
private class UsgsTiles(private val layer: String) : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
    "USGS-$layer", 0, 16, 256, "", arrayOf("https://basemap.nationalmap.gov/arcgis/rest/services/$layer/MapServer/tile/"),
    "USGS The National Map",
    org.osmdroid.tileprovider.tilesource.TileSourcePolicy(2, org.osmdroid.tileprovider.tilesource.TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" + org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "/" + org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
}

/** Draws each node's latest traceroute as a line through every hop whose position is known; dashed where a hop has no position. */
private fun drawRoutes(mv: MapView, routes: List<org.highbridge.app.data.RouteEntity>, nodes: List<NodeEntity>, me: Pair<Double, Double>?) {
    val pos = nodes.filter { it.lat != null && it.lon != null }.associate { "${it.protocol}|${it.id}" to GeoPoint(it.lat!!, it.lon!!) }
    for (r in routes.distinctBy { it.protocol + it.destId }) {
        val ids = r.hops.split(',').filter { it.isNotBlank() } + r.destId
        val known = ids.map { pos["${r.protocol}|$it"] }
        val pts = listOfNotNull(me?.let { GeoPoint(it.first, it.second) }) + known.filterNotNull()
        if (pts.size < 2) continue
        mv.overlays += Polyline(mv).apply {
            setPoints(pts)
            outlinePaint.color = protocolColorOf(r.protocol).toArgb(); outlinePaint.strokeWidth = 5f
            if (known.any { it == null }) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
            title = "Traceroute to ${r.destId}: ${ids.size - 1} hop${if (ids.size == 2) "" else "s"}" + (if (r.snrs.isNotBlank()) " · SNR ${r.snrs}" else "")
        }
    }
}
