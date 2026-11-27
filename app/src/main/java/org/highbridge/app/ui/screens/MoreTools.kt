// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.pro.Geo
import org.highbridge.app.pro.LinkBudget
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.LocalNav
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.timeAgo
import org.highbridge.app.ui.toast
import org.highbridge.core.protocol.ProtocolType
import java.io.File
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------- Discover

/** MeshCore Discover: every advert heard since connecting, with Add for ones the radio hasn't saved. */
@Composable
fun DiscoverScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val radios by app.radios.radios.collectAsState()
    val mc = app.radios.meshcore
    val found by (mc?.discovered ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState()
    val contacts by (mc?.contacts ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())).collectAsState()
    val me by app.radios.myLocation.collectAsState()
    val metric by app.prefs.metric.collectAsState()
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader(tr("Discover"), tr("MeshCore adverts heard since you connected"), onBack = { nav.popBackStack() }) }
        if (!radios.containsKey(ProtocolType.MESHCORE)) item { Text(tr("Connect a MeshCore radio first."), Modifier.padding(20.dp)) }
        else item {
            Row(Modifier.padding(16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { ctx.toast(mc?.advertise(false)?.exceptionOrNull()?.message ?: "Zero-hop advert sent; nearby radios often answer with theirs") } }) { Text(tr("Advertise nearby")) }
                OutlinedButton(onClick = { scope.launch { ctx.toast(mc?.advertise(true)?.exceptionOrNull()?.message ?: "Flood advert sent") } }) { Text(tr("Advertise to mesh")) }
            }
            if (found.isEmpty()) Text(tr("Nothing heard yet. Adverts arrive as radios announce themselves; repeaters usually do every few hours."),
                Modifier.padding(20.dp, 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(found.values.sortedByDescending { it.heardMs }, key = { it.prefixHex }) { d ->
            val c = d.contact ?: contacts[d.prefixHex]
            val saved = mc?.isSaved(d.prefixHex) == true
            Row(Modifier.fillMaxWidth().padding(20.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c?.name?.ifBlank { null } ?: "Unknown ${d.prefixHex.take(6)}", fontWeight = FontWeight.Bold)
                    val kind = when (c?.type) { 2 -> "Repeater"; 3 -> "Room server"; 4 -> "Sensor"; 1 -> "Companion"; else -> "Advert" }
                    val dist = if (me != null && c != null && (c.lat != 0.0 || c.lon != 0.0)) " · " + Geo.formatDistance(Geo.distanceM(me!!.first, me!!.second, c.lat, c.lon), metric) else ""
                    val path = c?.directPath?.let { " · ${it.size} hop${if (it.size == 1) "" else "s"}" } ?: ""
                    Text("$kind · heard ${timeAgo(d.heardMs)}$dist$path", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (saved) Text(tr("Saved"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                else TextButton(onClick = { scope.launch { mc?.addDiscovered(d.prefixHex)?.fold({ ctx.toast("Added $it") }, { ctx.toast(it.message, long = true) }) } }) { Text(tr("Add")) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---------------------------------------------------------------------------------------------- Coverage prediction

/** Estimates how far a node will reach from its antenna height, power, gains and LoRa settings. */
@Composable
fun CoveragePredictScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val metric by app.prefs.metric.collectAsState()
    val me by app.radios.myLocation.collectAsState()
    val self = app.radios.meshcore?.selfInfo?.collectAsState()?.value
    val ft = if (metric) 1.0 else 3.281
    var h1 by remember { mutableStateOf(if (metric) "10" else "30") }
    var h2 by remember { mutableStateOf(if (metric) "1.5" else "5") }
    var tx by remember { mutableStateOf((self?.txPower ?: 22).toString()) }
    var g1 by remember { mutableStateOf("3") }
    var g2 by remember { mutableStateOf("2") }
    var loss by remember { mutableStateOf("1") }
    var sf by remember { mutableStateOf(self?.sf ?: 11) }
    var bw by remember { mutableStateOf(self?.bwHz?.toDouble() ?: 250_000.0) }
    var freq by remember { mutableStateOf(self?.freqKhz?.let { (it / 1000.0).toString() } ?: "915") }
    var terrain by remember { mutableStateOf(LinkBudget.Terrain.SUBURBAN) }
    fun d(s: String) = s.replace(',', '.').toDoubleOrNull()
    val r = LinkBudget.predict(d(tx) ?: 22.0, d(g1) ?: 0.0, d(g2) ?: 0.0, d(loss) ?: 0.0, sf, bw, d(freq) ?: 915.0,
        (d(h1) ?: 0.0) / ft, (d(h2) ?: 0.0) / ft, terrain)
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader(tr("Coverage prediction"), tr("How far a node should reach, before you climb the ladder"), onBack = { nav.popBackStack() }) }
        item {
            HbCard(Modifier.padding(16.dp, 4.dp)) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About ${Geo.formatDistance(r.rangeKm * 1000, metric)}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(if (r.limitedByHorizon) "Limited by the radio horizon: raise either antenna to reach further." else "Limited by signal strength over this ground: more gain or height helps.", fontSize = 13.sp)
                Text(tr("Link budget %.0f dB · sensitivity %.0f dBm").format(r.budgetDb, r.sensitivityDbm), fontSize = 12.sp)
                Text("Radio horizon ${Geo.formatDistance(r.horizonKm * 1000, metric)} · clear line of sight could reach ${Geo.formatDistance(minOf(r.freeSpaceKm, r.horizonKm) * 1000, metric)}", fontSize = 12.sp)
                Text(tr("A planning estimate, not a terrain survey. Hills, buildings and leaves can halve it; a rooftop or a hill can double it. Wardrive to check."), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    val p = me ?: return@Button ctx.toast(tr("Waiting for your location"))
                    LinkBudget.shownOnMap.value = LinkBudget.Circle(p.first, p.second, r.rangeKm * 1000, "Predicted coverage")
                    nav.navigate("map")
                }, enabled = me != null) { Text(tr("Show around me on the map")) }
            } }
        }
        item { SectionLabel(tr("Antennas")) }
        item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val u = if (metric) "m" else "ft"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("This node's height ($u)", h1, Modifier.weight(1f)) { h1 = it }; Num("Other end's height ($u)", h2, Modifier.weight(1f)) { h2 = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("Antenna gain (dBi)", g1, Modifier.weight(1f)) { g1 = it }; Num("Other end's gain (dBi)", g2, Modifier.weight(1f)) { g2 = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Num("Transmit power (dBm)", tx, Modifier.weight(1f)) { tx = it }; Num("Cable loss (dB)", loss, Modifier.weight(1f)) { loss = it }
            }
        } }
        item { SectionLabel(tr("LoRa settings")) }
        item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(Triple("LongFast", 11, 250_000.0), Triple("MediumFast", 9, 250_000.0), Triple("ShortFast", 7, 250_000.0),
                    Triple("LongSlow", 12, 125_000.0), Triple("MeshCore US", 7, 62_500.0), Triple("MeshCore EU", 8, 62_500.0))) { (n, s, b) ->
                    FilterChip(sf == s && bw == b, { sf = s; bw = b }, { Text(n) })
                }
            }
            Text("Spreading factor $sf · bandwidth ${(bw / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} kHz", fontSize = 13.sp)
            Slider(sf.toFloat(), { sf = it.toInt() }, valueRange = 7f..12f, steps = 4)
            Num("Frequency (MHz)", freq, Modifier.fillMaxWidth()) { freq = it }
        } }
        item { SectionLabel(tr("Ground between you")) }
        items(LinkBudget.Terrain.entries) { t ->
            Row(Modifier.fillMaxWidth().padding(16.dp, 2.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(terrain == t, { terrain = t }); Text(t.label)
            }
        }
        item { org.highbridge.app.ui.Gap(24) }
    }
}

@Composable
private fun Num(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }.take(7)) }, modifier, label = { Text(label, fontSize = 12.sp) },
        singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
}

// ---------------------------------------------------------------------------------------------- AR view

private data class ArThing(val name: String, val lat: Double, val lon: Double, val altM: Double?, val color: Color, val kind: String)

/**
 * Camera view with nodes, waypoints and (optionally) OpenStreetMap landmarks drawn where they are.
 * Hold the phone upright. Drop a pin straight ahead at a chosen distance.
 */
@Composable
fun ArScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    LaunchedEffect(Unit) { if (!camOk) camPerm.launch(Manifest.permission.CAMERA) }
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val waypoints by remember { app.db.dao().waypoints(System.currentTimeMillis()) }.collectAsState(emptyList())
    val me by app.radios.myLocation.collectAsState()
    val myAlt by app.radios.myAltitudeM.collectAsState()
    val metric by app.prefs.metric.collectAsState()
    var azimuth by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var showLandmarks by remember { mutableStateOf(false) }
    var landmarks by remember { mutableStateOf<List<ArThing>>(emptyList()) }
    var dropDist by remember { mutableFloatStateOf(200f) }
    var dropAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val lifecycle = LocalLifecycleOwner.current

    // Orientation from the rotation-vector sensor, remapped for a phone held upright like a camera.
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val remap = FloatArray(9); val o = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
                SensorManager.getOrientation(remap, o)
                val az = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
                // Smooth across the 0/360 seam.
                var diff = az - azimuth; if (diff > 180) diff -= 360; if (diff < -180) diff += 360
                azimuth = ((azimuth + diff * 0.2f) + 360) % 360
                pitch = pitch + (Math.toDegrees(o[1].toDouble()).toFloat() * -1f - pitch) * 0.2f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    LaunchedEffect(showLandmarks, me) {
        val p = me ?: return@LaunchedEffect
        if (showLandmarks) landmarks = withContext(Dispatchers.IO) { Landmarks.near(ctx, p.first, p.second) }.getOrElse { ctx.toast("Couldn't load landmarks: ${it.message}"); emptyList() }
    }

    val things = remember(nodes, waypoints, landmarks, showLandmarks) {
        nodes.filter { it.lat != null && it.lon != null && !it.ignored }.map { ArThing(it.longName, it.lat!!, it.lon!!, it.altM?.toDouble(), org.highbridge.app.ui.theme.protocolColorOf(it.protocol), "node") } +
            waypoints.map { ArThing(it.name.ifBlank { "Pin" }, it.lat, it.lon, null, org.highbridge.app.ui.theme.StPaulMeshPalette.SchmidtBrick, "pin") } +
            (if (showLandmarks) landmarks else emptyList())
    }
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (camOk) AndroidView({ c ->
            PreviewView(c).also { pv ->
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = pv.surfaceProvider }
                    runCatching { provider.unbindAll(); provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview) }
                }, ContextCompat.getMainExecutor(c))
            }
        }, Modifier.fillMaxSize()) else Text(tr("AR view needs the camera. Allow it to see nodes and pins over the live view."), Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)

        Canvas(Modifier.fillMaxSize()) {
            val p = me ?: return@Canvas
            val hfov = 60f; val vfov = 45f
            val visible = things.mapNotNull { t ->
                val dist = Geo.distanceM(p.first, p.second, t.lat, t.lon)
                if (dist < 5 || dist > 60_000) return@mapNotNull null
                var rel = (Geo.bearingDeg(p.first, p.second, t.lat, t.lon) - azimuth).toFloat()
                if (rel > 180) rel -= 360; if (rel < -180) rel += 360
                if (abs(rel) > hfov / 2) return@mapNotNull null
                val elev = if (t.altM != null && myAlt != null) Math.toDegrees(kotlin.math.atan2(t.altM - myAlt!!, dist)).toFloat() else 0f
                Triple(t, dist, Offset(size.width / 2 + rel / (hfov / 2) * size.width / 2, size.height / 2 - (elev - pitch) / (vfov / 2) * size.height / 2))
            }.sortedByDescending { it.second }
            visible.forEach { (t, dist, at) ->
                val r = (28f - (dist / 2000f).toFloat()).coerceIn(10f, 28f)
                drawCircle(t.color, r, at); drawCircle(Color.White, r, at, style = Stroke(3f))
                val label = "${t.name}\n${Geo.formatDistance(dist, metric)}"
                val layout = measurer.measure(label, TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, background = Color(0x99000000)))
                drawText(layout, topLeft = Offset(at.x - layout.size.width / 2, at.y + r + 4))
            }
            // Crosshair for dropping a pin ahead.
            drawLine(Color.White, Offset(size.width / 2 - 20, size.height / 2), Offset(size.width / 2 + 20, size.height / 2), 2f)
            drawLine(Color.White, Offset(size.width / 2, size.height / 2 - 20), Offset(size.width / 2, size.height / 2 + 20), 2f)
        }

        Column(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { nav.popBackStack() }) { Text(tr("‹ Back"), color = Color.White) }
                Text("%.0f° %s".format(azimuth, Geo.compassPoint(azimuth.toDouble())), Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                FilterChip(showLandmarks, { showLandmarks = !showLandmarks }, { Text(tr("Landmarks"), color = Color.White) })
            }
            if (me == null) Text(tr("Waiting for your location…"), color = Color.White, fontSize = 12.sp)
            if (showLandmarks) Text(tr("Landmarks © OpenStreetMap contributors (your location is sent rounded to about 1 km)"), color = Color.White, fontSize = 10.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(12.dp)) {
            Text("Drop a pin ${Geo.formatDistance(dropDist.toDouble(), metric)} straight ahead", color = Color.White)
            Slider(dropDist, { dropDist = it }, valueRange = 20f..3000f)
            Button(onClick = { me?.let { dropAt = Geo.destination(it.first, it.second, azimuth.toDouble(), dropDist.toDouble()) } }, enabled = me != null) { Text(tr("Drop pin here")) }
        }
    }
    dropAt?.let { (lat, lon) -> DropWaypointDialog(lat, lon) { dropAt = null } }
}

/** Nearby peaks, towers and viewpoints from OpenStreetMap's Overpass API, kept per ~1 km square so they still work offline later. */
private object Landmarks {
    suspend fun near(ctx: Context, lat: Double, lon: Double): Result<List<ArThing>> = runCatching {
        val la = "%.2f".format(java.util.Locale.US, lat); val lo = "%.2f".format(java.util.Locale.US, lon)
        val cache = File(File(ctx.cacheDir, "landmarks").apply { mkdirs() }, "$la,$lo.json")
        val json = if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 86_400_000) cache.readText() else {
            val q = """[out:json][timeout:25];(node["natural"="peak"](around:20000,$la,$lo);node["man_made"~"^(tower|mast|water_tower|communications_tower|lighthouse)$"](around:10000,$la,$lo);node["tourism"="viewpoint"](around:8000,$la,$lo);node["amenity"="place_of_worship"]["name"](around:3000,$la,$lo););out 200;"""
            val url = java.net.URL("https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(q, "UTF-8"))
            val c = (url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "Highbridge (Android mesh radio app)") }
            // Offline or the server is busy: use what was saved here before, however old.
            runCatching { c.inputStream.bufferedReader().use { it.readText() }.also { cache.writeText(it) } }
                .getOrElse { e -> if (cache.exists()) cache.readText() else throw e }
        }
        val els = org.json.JSONObject(json).optJSONArray("elements") ?: org.json.JSONArray()
        (0 until els.length()).mapNotNull { i ->
            val e = els.getJSONObject(i); val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            val kind = tags.optString("natural").ifBlank { tags.optString("man_made").ifBlank { tags.optString("tourism").ifBlank { "landmark" } } }
            val name = tags.optString("name").ifBlank { kind.replace('_', ' ').replaceFirstChar { it.uppercase() } }
            ArThing(name, e.getDouble("lat"), e.getDouble("lon"), tags.optString("ele").toDoubleOrNull(), Color(0xFF6BA6D9), kind)
        }
    }
}
