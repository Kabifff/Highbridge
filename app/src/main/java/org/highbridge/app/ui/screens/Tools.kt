package org.highbridge.app.ui.screens

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.data.GeofenceEntity
import org.highbridge.app.pro.AtakCotBridge
import org.highbridge.app.pro.CwKeyer
import org.highbridge.app.pro.CwTimingDecoder
import org.highbridge.app.pro.Geo
import org.highbridge.app.pro.GpxTrackRecorder
import org.highbridge.app.pro.MorseCodec
import org.highbridge.app.pro.TopologyBuilder
import org.highbridge.app.pro.TrackPoint
import org.highbridge.app.pro.Zambretti
import org.highbridge.app.radio.toMeshNode
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.app.ui.timeAgo
import org.highbridge.core.protocol.ProtocolType
import java.io.File
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ToolsScreen() {
    val nav = LocalNav.current
    val tools = listOf(
        "checkin" to ("Check-in & SOS" to "One tap to tell everyone you're OK, or that you need help"),
        "topology" to ("Topology graph" to "Who hears whom, by hops and signal, without GPS"),
        "geofences" to ("Geofences" to "Alerts when a node leaves an area or drifts off a trail"),
        "weather" to ("Barometer forecast" to "Offline Zambretti forecast from pressure"),
        "morse" to ("Morse & CW key" to "Encode, decode and key Morse at 700 Hz"),
        "rangetest" to ("Range test" to "Ping, log signal by location, see a heatmap"),
        "tracks" to ("GPX tracks" to "Record where you went and export GPX 1.1"),
        "atak" to ("ATAK bridge" to "Send node positions to ATAK as Cursor on Target"),
        "airtime" to ("Airtime meter" to "How much of the legal duty cycle you've used"),
        "micron" to ("NomadNet browser" to "Read Micron pages from Reticulum nodes"),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("Field tools", "Work offline, anywhere", onBack = { nav.popBackStack() }) }
        items(tools) { (route, t) ->
            Column(Modifier.fillMaxWidth().clickable { nav.navigate(route) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(t.first, fontWeight = FontWeight.Bold); Text(t.second, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun CheckInScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val radios by app.radios.radios.collectAsState()
    var confirmSos by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        HbHeader("Check-in & SOS", "Sends on channel 0 of every connected radio", onBack = { nav.popBackStack() })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (radios.isEmpty()) "Connect a radio first." else "Will send through ${radios.keys.joinToString { it.shortTag }}, with your location if the phone has one.")
            Button(onClick = { scope.launch { val s = app.radios.broadcastStatus(false); Toast.makeText(ctx, if (s.isEmpty()) "Nothing sent" else "Check-in sent on ${s.joinToString()}", Toast.LENGTH_LONG).show() } },
                Modifier.fillMaxWidth().height(64.dp), enabled = radios.isNotEmpty()) { Text("I'm OK — check in", fontSize = 18.sp) }
            Button(onClick = { if (confirmSos) { confirmSos = false; scope.launch { val s = app.radios.broadcastStatus(true); Toast.makeText(ctx, "SOS sent on ${s.joinToString()}", Toast.LENGTH_LONG).show() } } else confirmSos = true },
                Modifier.fillMaxWidth().height(64.dp), enabled = radios.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = StPaulMeshPalette.Danger)) {
                Text(if (confirmSos) "Tap again to send SOS" else "SOS — I need help", fontSize = 18.sp)
            }
            Text("An SOS is a message to everyone on the mesh. It does not call emergency services. Call 911 (or your local number) if you can.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TopologyScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val routes by app.db.dao().routes().collectAsState(emptyList())
    val radios by app.radios.radios.collectAsState()
    val stale by app.prefs.staleMinutes.collectAsState()
    val myIds = radios.mapNotNull { (p, r) -> r.myNodeId.value?.let { p.name to it } }.toMap()
    val graph = remember(nodes, routes, myIds) { TopologyBuilder.build(myIds, nodes, routes, stale * 60_000L) }
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val ringColor = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxSize()) {
        HbHeader("Topology", "Rings are hops from you; lines are traceroutes, labelled with SNR", onBack = { nav.popBackStack() })
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val maxHop = (graph.nodes.maxOfOrNull { it.hops } ?: 1).coerceIn(1, 7)
            val step = size.minDimension / 2 / (maxHop + 0.5f)
            for (h in 1..maxHop) drawCircle(ringColor, step * h, c, style = Stroke(1.5f))
            val pos = HashMap<String, Offset>()
            graph.nodes.groupBy { it.hops }.forEach { (hop, ns) ->
                ns.forEachIndexed { i, n ->
                    val a = 2 * Math.PI * i / ns.size + hop * 0.6
                    pos[n.key] = if (hop == 0) c + Offset((i - (ns.size - 1) / 2f) * 30f, 0f) else c + Offset((cos(a) * step * hop).toFloat(), (sin(a) * step * hop).toFloat())
                }
            }
            for (e in graph.edges) {
                val a = pos[e.from] ?: continue; val b = pos[e.to] ?: continue
                val col = when { e.snr == null -> StPaulMeshPalette.Stone; e.snr >= 0 -> StPaulMeshPalette.Wave; e.snr >= -10 -> StPaulMeshPalette.Gold; else -> StPaulMeshPalette.SchmidtBrick }
                drawLine(col, a, b, 4f)
                e.snr?.let { drawText(measurer, "%.0f".format(it), (a + b) / 2f, TextStyle(fontSize = 10.sp, color = ink)) }
            }
            for (n in graph.nodes) {
                val p = pos[n.key] ?: continue
                val col = when (n.protocol) { "MESHTASTIC" -> StPaulMeshPalette.Meshtastic; "MESHCORE" -> StPaulMeshPalette.MeshCore; else -> StPaulMeshPalette.Reticulum }
                drawCircle(if (n.hops == 0) StPaulMeshPalette.Gold else col, if (n.hops == 0) 14f else 10f, p)
                drawText(measurer, n.label, p + Offset(12f, -8f), TextStyle(fontSize = 10.sp, color = ink))
            }
        }
        Text("${graph.nodes.size - myIds.size} nodes · ${graph.edges.size} links. Run traceroutes from a node's page to add links.", Modifier.padding(20.dp), fontSize = 12.sp)
    }
}

@Composable
fun CompassScreen(key: String) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val me by app.radios.myLocation.collectAsState()
    val metric by app.prefs.metric.collectAsState()
    val n = nodes.firstOrNull { it.key == key }
    var heading by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rot = FloatArray(9); val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { SensorManager.getRotationMatrixFromVector(rot, e.values); SensorManager.getOrientation(rot, ori); heading = Math.toDegrees(ori[0].toDouble()).toFloat() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(l) }
    }
    val bearing = if (me != null && n?.lat != null && n.lon != null) Geo.bearingDeg(me!!.first, me!!.second, n.lat, n.lon).toFloat() else null
    val dist = if (me != null && n?.lat != null && n.lon != null) Geo.distanceM(me!!.first, me!!.second, n.lat, n.lon) else null
    val green = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        HbHeader("Compass", n?.longName ?: "", onBack = { nav.popBackStack() })
        if (bearing == null) Text("Waiting for your location and the node's position…", Modifier.padding(20.dp))
        else {
            Box(Modifier.padding(24.dp).size(280.dp).rotate(bearing - heading), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(StPaulMeshPalette.Line, size.minDimension / 2, style = Stroke(4f))
                    val c = Offset(size.width / 2, size.height / 2)
                    drawPath(Path().apply { moveTo(c.x, 20f); lineTo(c.x - 40f, c.y); lineTo(c.x + 40f, c.y); close() }, green)
                    drawCircle(StPaulMeshPalette.Gold, 12f, c)
                }
            }
            Text(Geo.formatDistance(dist!!, metric), style = MaterialTheme.typography.headlineMedium, color = green)
            Text("Bearing %.0f° %s".format(bearing, Geo.compassPoint(bearing.toDouble())))
            Text("Hold the phone flat and point the arrow ahead of you.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GeofenceScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val fences by app.db.dao().geofences().collectAsState(emptyList())
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val me by app.radios.myLocation.collectAsState()
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("CIRCLE") }
    var radius by remember { mutableStateOf("300") }
    var points by remember { mutableStateOf("") }
    var nodeKey by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("Geofences", "Get an alert when a node leaves an area", onBack = { nav.popBackStack() }) }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HbCard {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name, like \"Harriet Island\"") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CIRCLE" to "Circle", "POLYGON" to "Area", "TRAIL" to "Trail drift").forEach { (k, l) -> FilterChip(kind == k, { kind = k }, { Text(l) }) }
                    }
                    OutlinedTextField(points, { points = it }, Modifier.fillMaxWidth(), label = { Text(if (kind == "CIRCLE") "Center: lat,lon" else "Points: lat,lon; lat,lon; …") })
                    if (kind == "CIRCLE") TextButton(onClick = { me?.let { points = "%.6f,%.6f".format(it.first, it.second) } }, enabled = me != null) { Text("Use my location") }
                    if (kind != "POLYGON") OutlinedTextField(radius, { radius = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), label = { Text(if (kind == "TRAIL") "Allowed drift (m)" else "Radius (m)") })
                    Text("Watch: ${nodes.firstOrNull { it.key == nodeKey }?.longName ?: "every node"}", fontSize = 13.sp)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(nodeKey == null, { nodeKey = null }, { Text("All") }) }
                        items(nodes.filter { it.lat != null }.take(30)) { n -> FilterChip(nodeKey == n.key, { nodeKey = n.key }, { Text(n.shortName) }) }
                    }
                    Button(enabled = name.isNotBlank() && Geo.parsePoints(points).isNotEmpty(), onClick = {
                        scope.launch { app.db.dao().upsertGeofence(GeofenceEntity(name = name, kind = kind, points = points, radiusM = radius.toDoubleOrNull() ?: 300.0, nodeKey = nodeKey)) }
                        name = ""; points = ""
                    }) { Text("Add geofence") }
                }
            }
        }
        items(fences, key = { it.rowId }) { g ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(g.name, fontWeight = FontWeight.Bold); Text("${g.kind.lowercase()} · ${if (g.kind == "POLYGON") "" else "${g.radiusM.toInt()} m · "}${nodes.firstOrNull { it.key == g.nodeKey }?.longName ?: "all nodes"}", fontSize = 12.sp) }
                Switch(g.active, { on -> scope.launch { app.db.dao().upsertGeofence(g.copy(active = on)) } })
                TextButton(onClick = { scope.launch { app.db.dao().deleteGeofence(g.rowId) } }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun WeatherScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    var phoneHpa by remember { mutableStateOf<Float?>(null) }
    var history by remember { mutableStateOf<List<org.highbridge.app.data.TelemetryEntity>>(emptyList()) }
    var altitude by remember { mutableStateOf("214") } // Saint Paul is about 214 m above sea level
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener { override fun onSensorChanged(e: SensorEvent) { phoneHpa = e.values[0] }; override fun onAccuracyChanged(s: Sensor?, a: Int) {} }
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) }
        onDispose { sm.unregisterListener(l) }
    }
    LaunchedEffect(Unit) { history = app.db.dao().pressureSince(System.currentTimeMillis() - 4 * 3600_000L) }
    val now = history.lastOrNull()?.pressureHpa ?: phoneHpa
    val threeHoursAgo = history.firstOrNull { it.timeMs <= System.currentTimeMillis() - 2.5 * 3600_000 }?.pressureHpa
    val alt = altitude.toDoubleOrNull() ?: 0.0
    val month = Calendar.getInstance().get(Calendar.MONTH)
    val north = (app.radios.myLocation.value?.first ?: 45.0) >= 0
    val summer = if (north) month in 3..8 else month !in 3..8
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HbHeader("Barometer forecast", "Zambretti, offline", onBack = { nav.popBackStack() })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (now == null) Text("This phone has no barometer and no node has sent pressure yet. Nodes with a BME280 or BMP280 sensor report it in telemetry.")
            else {
                val sl = Zambretti.seaLevel(now.toDouble(), alt)
                val trend = threeHoursAgo?.let { Zambretti.trendOf(now - it.toDouble()) } ?: Zambretti.Trend.STEADY
                HbCard {
                    Text(Zambretti.forecast(sl, trend, summer), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("%.1f hPa at sea level · %s".format(sl, trend.name.lowercase()))
                    if (threeHoursAgo == null) Text("Trend needs about 3 hours of readings; assuming steady for now.", fontSize = 12.sp)
                }
            }
            OutlinedTextField(altitude, { altitude = it }, Modifier.fillMaxWidth(), label = { Text("Your altitude (m)") }, singleLine = true)
            Text("Zambretti is a rule of thumb from 1915, right roughly three times in four. Check an official forecast when you can.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MorseScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var morse by remember { mutableStateOf("") }
    val keyer = remember { CwKeyer() }
    val decoder = remember { CwTimingDecoder() }
    var keyed by remember { mutableStateOf("") }
    var downAt by remember { mutableLongStateOf(0L) }
    var upAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(Unit) { onDispose { keyer.release() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HbHeader("Morse & CW", "International Morse, 700 Hz sidetone", onBack = { nav.popBackStack() })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(text, { text = it; morse = MorseCodec.encode(it) }, Modifier.fillMaxWidth(), label = { Text("Text") })
            OutlinedTextField(morse, { morse = it; text = MorseCodec.decode(it) }, Modifier.fillMaxWidth(), label = { Text("Morse (. - and / between words)") }, textStyle = TextStyle(fontFamily = FontFamily.Monospace))
            OutlinedButton(onClick = { scope.launch { keyer.playText(text) } }, enabled = text.isNotBlank()) { Text("Play it") }
            SectionLabel("Straight key", Modifier.padding(0.dp))
            Box(Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)).pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val now = System.currentTimeMillis()
                    if (upAt > 0) decoder.onGap(now - upAt)
                    downAt = now; keyer.keyDown()
                    tryAwaitRelease()
                    keyer.keyUp(); upAt = System.currentTimeMillis(); decoder.onTone(upAt - downAt); keyed = decoder.morse
                })
            }, contentAlignment = Alignment.Center) { Text("Press and hold to key", color = MaterialTheme.colorScheme.onPrimary) }
            Text(keyed, fontFamily = FontFamily.Monospace)
            Text(MorseCodec.decode(keyed), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { decoder.clear(); keyed = ""; upAt = 0 }) { Text("Clear") }
        }
    }
}

@Composable
fun RangeTestScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by app.radios.rangeTestRunning.collectAsState()
    val results by app.db.dao().rangeTests().collectAsState(emptyList())
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("Range test", "Meshtastic range-test pings and a signal heatmap", onBack = { nav.popBackStack() }) }
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Turn on the Range Test module on both radios. One sends a numbered ping each minute; the other logs signal and location. Open the map and turn on \"Range test\" to see the heatmap.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { app.radios.setRangeTest(!running) }, enabled = app.radios.meshtastic != null) { Text(if (running) "Stop sending pings" else "Send pings every minute") }
                    OutlinedButton(onClick = {
                        val f = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "range-test.csv")
                        f.writeText("time,protocol,from,text,snr,rssi,lat,lon\n" + results.joinToString("\n") { "${it.timeMs},${it.protocol},${it.fromId},\"${it.text}\",${it.snr},${it.rssi},${it.lat},${it.lon}" })
                        shareFile(ctx, f, "text/csv")
                    }, enabled = results.isNotEmpty()) { Text("Export CSV") }
                }
                TextButton(onClick = { scope.launch { app.db.dao().clearRange() } }) { Text("Clear log") }
            }
        }
        items(results.take(200)) { r ->
            Text("${timeAgo(r.timeMs)} · ${r.fromId} · ${r.text} · SNR ${r.snr ?: "?"} · RSSI ${r.rssi ?: "?"}${if (r.lat != null) " · %.4f,%.4f".format(r.lat, r.lon) else ""}", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 12.sp)
        }
    }
}

@Composable
fun TracksScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ids by app.db.dao().trackIds().collectAsState(emptyList())
    var recording by remember { mutableStateOf(app.radios.recordingTrackId != null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader("GPX tracks", "Record and export GPX 1.1", onBack = { nav.popBackStack() }) }
        item {
            Column(Modifier.padding(20.dp)) {
                Button(onClick = { if (recording) app.radios.stopTrack() else app.radios.startTrack(); recording = !recording }) { Text(if (recording) "Stop recording" else "Start recording") }
                Text("Points are saved at your GPS interval (Settings › Location).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(ids) { id ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(id)), Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val rec = GpxTrackRecorder()
                        app.db.dao().track(id).forEach { rec.add(TrackPoint(it.lat, it.lon, it.altM, it.timeMs)) }
                        val f = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "highbridge-track-$id.gpx").apply { writeText(rec.exportGpx("Highbridge track")) }
                        shareFile(ctx, f, "application/gpx+xml")
                    }
                }) { Text("Export") }
                TextButton(onClick = { scope.launch { app.db.dao().deleteTrack(id) } }) { Text("Delete") }
            }
        }
    }
}

@Composable
fun AtakScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val on by app.prefs.atakEnabled.collectAsState()
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    Column(Modifier.fillMaxSize()) {
        HbHeader("ATAK bridge", "Cursor on Target over UDP multicast", onBack = { nav.popBackStack() })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Send node positions to ATAK", Modifier.weight(1f)); Switch(on, { app.prefs.setAtak(it) }) }
            Text("Positions go to 239.2.3.1:6969 on this phone's network, where ATAK, iTAK and WinTAK listen by default.", fontSize = 13.sp)
            OutlinedButton(onClick = {
                scope.launch {
                    var n = 0
                    nodes.filter { it.lat != null }.forEach { e -> AtakCotBridge.buildCotXml(e.toMeshNode())?.let { if (AtakCotBridge.sendUdp(it).isSuccess) n++ } }
                    Toast.makeText(ctx, "Sent $n positions", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Send all positions now") }
        }
    }
}

@Composable
fun AirtimeScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val pct by app.radios.airtimePercent.collectAsState()
    val region by app.prefs.region.collectAsState()
    Column(Modifier.fillMaxSize()) {
        HbHeader("Airtime meter", region.label, onBack = { nav.popBackStack() })
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("%.2f%% of the last hour".format(pct), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            if (region.hasDutyCycleLimit) {
                androidx.compose.material3.LinearProgressIndicator(progress = { (pct / region.dutyCyclePercent).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(10.dp))
                Text("${region.label} allows ${region.dutyCyclePercent}% duty cycle. Highbridge warns at 80% and holds messages at 100%.")
            } else Text("${region.label} has no duty-cycle limit, but less airtime means a healthier mesh for everyone.")
            Text("This counts what this phone sent, estimated from packet size and radio settings.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
}

