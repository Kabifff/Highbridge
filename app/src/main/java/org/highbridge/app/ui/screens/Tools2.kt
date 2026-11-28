@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.pro.Geo
import org.highbridge.app.pro.LineOfSight
import org.highbridge.app.pro.Rangefinder
import org.highbridge.app.pro.Rtttl
import org.highbridge.app.ui.HbCard
import org.highbridge.app.ui.HbHeader
import org.highbridge.app.ui.SectionLabel
import org.highbridge.app.ui.toast
import org.highbridge.app.ui.tr
import kotlin.math.abs

// ---------------------------------------------------------------------------------------------- Ringtones

/** Tunes for the radio's buzzer and the phone's message alerts, in RTTTL. */
@Composable
fun RingtonesScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tune by app.prefs.messageTune.collectAsState()
    val radios by app.radios.radios.collectAsState()
    val hasMt = radios.containsKey(org.highbridge.core.protocol.ProtocolType.MESHTASTIC)
    var custom by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<String?>(null) }
    fun preview(t: String) { playing = t; scope.launch { Rtttl.play(t).onFailure { ctx.toast(it.message, long = true) }; playing = null } }
    fun toRadio(t: String) = scope.launch {
        val mt = app.radios.meshtastic ?: return@launch ctx.toast(tr("Connect your Meshtastic radio first."))
        mt.setRingtone(t).fold({ mt.playRingtone(); ctx.toast(tr("Sent to your radio. It plays this for alerts when its buzzer is set up in External notification.")) }, { ctx.toast(it.message, long = true) })
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { HbHeader(tr("Ringtones"), tr("Tunes for your radio's buzzer and this phone"), onBack = { nav.popBackStack() }) }
        item {
            Column(Modifier.padding(16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Message alert on this phone") + ": " + (Rtttl.LIBRARY.firstOrNull { it.second == tune }?.first ?: if (tune.isBlank()) tr("normal notification sound") else tr("your own tune")), fontSize = 13.sp)
                if (tune.isNotBlank()) TextButton(onClick = { app.prefs.setMessageTune("") }) { Text(tr("Use the normal notification sound")) }
                Text(tr("Tunes only play when your ringer is on, not on silent or vibrate."), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(Rtttl.LIBRARY, key = { it.first }) { (name, t) ->
            Column(Modifier.fillMaxWidth().padding(16.dp, 6.dp)) {
                Text(name + if (t == tune) "  ✓" else "", fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { preview(t) }, enabled = playing == null) { Text(if (playing == t) tr("Playing…") else "▶ " + tr("Play")) }
                    TextButton(onClick = { app.prefs.setMessageTune(t); ctx.toast(tr("Message alert set")) }) { Text(tr("Use for messages")) }
                    if (hasMt) TextButton(onClick = { toRadio(t) }) { Text(tr("Send to radio")) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        item { SectionLabel(tr("Your own tune")) }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(custom, { custom = it.take(230) }, Modifier.fillMaxWidth(), label = { Text("RTTTL") },
                    placeholder = { Text("MyTune:d=4,o=5,b=120:c,e,g,c6") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                val parsed = remember(custom) { if (custom.isBlank()) null else Rtttl.parse(custom) }
                parsed?.onFailure { Text(it.message ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    ?.onSuccess { Text("${it.notes.size} notes · %.1f s".format(it.lengthMs / 1000.0), fontSize = 12.sp) }
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { preview(custom) }, enabled = parsed?.isSuccess == true && playing == null) { Text("▶ " + tr("Play")) }
                    OutlinedButton(onClick = { app.prefs.setMessageTune(custom) }, enabled = parsed?.isSuccess == true) { Text(tr("Use for messages")) }
                    if (hasMt) OutlinedButton(onClick = { toRadio(custom) }, enabled = parsed?.isSuccess == true) { Text(tr("Send to radio")) }
                }
                Text(tr("The built-in tunes are public-domain melodies and simple alerts."), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { org.highbridge.app.ui.Gap(24) }
    }
}

// ---------------------------------------------------------------------------------------------- Rangefinder

/**
 * Rangefinder: uses the phone's tilt. Aim the crosshair at the foot of something on level ground and the distance
 * follows from your eye height; aim at the foot then the top of something of known height for a second method.
 */
@Composable
fun RangefinderScreen() {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val metric by app.prefs.metric.collectAsState()
    val me by app.radios.myLocation.collectAsState()
    var camOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    LaunchedEffect(Unit) { if (!camOk) camPerm.launch(Manifest.permission.CAMERA) }
    var pitch by remember { mutableFloatStateOf(0f) }        // degrees above (+) or below (-) level
    var azimuth by remember { mutableFloatStateOf(0f) }
    var eyeM by remember { mutableFloatStateOf(1.6f) }
    var knownH by remember { mutableStateOf(if (metric) "10" else "30") }
    var mode by remember { mutableStateOf(0) }               // 0 = from eye height, 1 = known height
    var baseAngle by remember { mutableStateOf<Float?>(null) }
    var result by remember { mutableStateOf<Double?>(null) }   // metres
    var heightResult by remember { mutableStateOf<Double?>(null) }
    var dropAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val ft = if (metric) 1.0 else 3.281

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val remap = FloatArray(9); val o = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
                SensorManager.getOrientation(remap, o)
                pitch += (-Math.toDegrees(o[1].toDouble()).toFloat() - pitch) * 0.15f
                azimuth = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(l) }
    }
    fun dist(m: Double) = Geo.formatDistance(m, metric)
    fun len(m: Double) = if (metric) "%.1f m".format(m) else "%.0f ft".format(m * 3.281)

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
        }, Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Color.White, 36f, c, style = Stroke(3f))
            drawLine(Color.White, Offset(c.x - 70, c.y), Offset(c.x + 70, c.y), 3f)
            drawLine(Color.White, Offset(c.x, c.y - 70), Offset(c.x, c.y + 70), 3f)
        }
        Column(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { nav.popBackStack() }) { Text(tr("‹ Back"), color = Color.White) }
                Text(tr("Rangefinder"), Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                org.highbridge.app.ui.LocalOpenDrawer.current?.let { open ->
                    androidx.compose.material3.IconButton(onClick = open) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Menu, "Menu", tint = Color.White) } }
            }
            Text("%s %.1f° · %.0f° %s".format(tr("Tilt"), pitch, azimuth, Geo.compassPoint(azimuth.toDouble())), color = Color.White, fontSize = 13.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(mode == 0, { mode = 0; baseAngle = null; result = null; heightResult = null }, { Text(tr("Level ground"), color = Color.White) })
                FilterChip(mode == 1, { mode = 1; baseAngle = null; result = null; heightResult = null }, { Text(tr("Known height"), color = Color.White) })
            }
            if (mode == 0) {
                Text(tr("Your eye height") + ": " + len(eyeM.toDouble()), color = Color.White, fontSize = 13.sp)
                Slider(eyeM, { eyeM = it }, valueRange = 1.0f..2.2f)
                Text(tr("Aim at the point where the object meets the ground, then tap Measure. Then aim at its top for its height."), color = Color.White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        result = Rangefinder.fromEyeHeight(eyeM.toDouble(), pitch.toDouble()); baseAngle = pitch; heightResult = null
                        if (result == null) ctx.toast(tr("Aim below level at the foot of the object"))
                    }) { Text(tr("Measure")) }
                    OutlinedButton(onClick = { val d = result; val b = baseAngle
                        if (d != null && b != null) heightResult = Rangefinder.heightAt(d, b.toDouble(), pitch.toDouble()) }, enabled = result != null) { Text(tr("Top")) }
                }
            } else {
                OutlinedTextField(knownH, { knownH = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, singleLine = true,
                    label = { Text(tr("Its height") + if (metric) " (m)" else " (ft)") }, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White))
                Text(tr("Aim at its foot and tap Foot, then at its top and tap Top. Works for a tower, a pole or a person."), color = Color.White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { baseAngle = pitch; result = null }) { Text(tr("Foot") + if (baseAngle != null) " ✓" else "") }
                    Button(onClick = {
                        val b = baseAngle ?: return@Button ctx.toast(tr("Tap Foot first"))
                        val h = (knownH.toDoubleOrNull() ?: 0.0) / ft
                        result = Rangefinder.fromKnownHeight(h, b.toDouble(), pitch.toDouble())
                        if (result == null) ctx.toast(tr("Aim higher for the top than for the foot"))
                    }, enabled = baseAngle != null) { Text(tr("Top")) }
                }
            }
            result?.let { d ->
                Text(tr("Distance") + ": " + dist(d), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                heightResult?.let { Text(tr("Height") + ": " + len(it), color = Color.White) }
                Text(tr("Accuracy drops for far objects and near-level aim. It assumes level ground for the first method."), color = Color(0xFFCCCCCC), fontSize = 11.sp)
                Button(onClick = { me?.let { dropAt = Geo.destination(it.first, it.second, azimuth.toDouble(), d) } ?: ctx.toast(tr("Waiting for your location")) }) { Text(tr("Drop a pin there")) }
            }
        }
    }
    dropAt?.let { (lat, lon) -> DropWaypointDialog(lat, lon) { dropAt = null } }
}

// ---------------------------------------------------------------------------------------------- Line of sight

/** Terrain profile between you and a node: is there a clear radio path? */
@Composable
fun LineOfSightScreen(nodeKey: String) {
    val app = HighbridgeApp.instance
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val metric by app.prefs.metric.collectAsState()
    val me by app.radios.myLocation.collectAsState()
    val nodes by app.db.dao().nodes().collectAsState(emptyList())
    val withPos = nodes.filter { it.lat != null && it.lon != null }
    var target by remember(nodeKey, withPos.size) { mutableStateOf(withPos.firstOrNull { it.key == nodeKey }) }
    var myMast by remember { mutableFloatStateOf(2f) }
    var theirMast by remember { mutableFloatStateOf(5f) }
    var result by remember { mutableStateOf<Result<LineOfSight.Profile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    val freq = 915.0
    fun run() {
        val a = me ?: return ctx.toast(tr("Waiting for your location"))
        val t = target ?: return
        busy = true
        scope.launch {
            result = LineOfSight.profile(a.first, a.second, t.lat!!, t.lon!!, myMast.toDouble(), theirMast.toDouble(), freq); busy = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HbHeader(tr("Line of sight"), tr("Hills and the curve of the earth between you and a node"), onBack = { nav.popBackStack() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("To") + ": ", fontWeight = FontWeight.Bold)
                TextButton(onClick = { pick = true }) { Text(target?.longName ?: tr("Choose a node with a position")) }
                org.highbridge.app.ui.HbDropdownMenu(pick, { pick = false }) {
                    withPos.sortedBy { it.longName }.take(60).forEach { n ->
                        androidx.compose.material3.DropdownMenuItem({ Text(n.longName) }, { target = n; pick = false; result = null })
                    }
                }
            }
            val u = { m: Float -> if (metric) "%.0f m".format(m) else "%.0f ft".format(m * 3.281) }
            Text(tr("Your antenna above the ground") + ": " + u(myMast), fontSize = 13.sp)
            Slider(myMast, { myMast = it }, valueRange = 1f..40f)
            Text(tr("Their antenna above the ground") + ": " + u(theirMast), fontSize = 13.sp)
            Slider(theirMast, { theirMast = it }, valueRange = 1f..40f)
            Button(onClick = { run() }, enabled = target != null && !busy) { Text(if (busy) tr("Getting elevations…") else tr("Check the path")) }
            result?.onFailure { Text(tr("Couldn't get elevations") + ": ${it.message}", color = MaterialTheme.colorScheme.error) }
            result?.onSuccess { p -> LosChart(p, metric) }
            Text(tr("Ground heights come from Open-Meteo (Copernicus 90 m elevation data) and need internet: the points along the path are sent there. Buildings and trees aren't included."),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LosChart(p: LineOfSight.Profile, metric: Boolean) {
    val ok = p.clear; val fresnelOk = p.fresnelClearance >= 0.6
    HbCard { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when {
            ok && fresnelOk -> tr("Clear path")
            ok -> tr("Clear, but close to the ground: expect a weaker signal")
            else -> tr("Blocked by the terrain")
        }, fontWeight = FontWeight.Bold, color = if (ok && fresnelOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text(Geo.formatDistance(p.distanceM, metric) + " · " + tr("Fresnel zone") + " %d%% ".format((p.fresnelClearance.coerceIn(0.0, 9.99) * 100).toInt()) + tr("clear"), fontSize = 13.sp)
        if (!ok) Text(tr("Worst spot") + ": " + Geo.formatDistance(p.worstAtM, metric) + " " + tr("from you") + ", " +
            (if (metric) "%.0f m".format(p.worstBlockM) else "%.0f ft".format(p.worstBlockM * 3.281)) + " " + tr("above the line"), fontSize = 13.sp)
        val ground = MaterialTheme.colorScheme.secondary; val line = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val minE = p.points.minOf { minOf(it.groundM, it.losM - it.fresnelM) } - 5
            val maxE = p.points.maxOf { maxOf(it.groundM, it.losM + it.fresnelM) } + 5
            fun x(d: Double) = (d / p.distanceM * size.width).toFloat()
            fun y(e: Double) = (size.height - (e - minE) / (maxE - minE) * size.height).toFloat()
            val g = Path().apply { moveTo(0f, size.height); p.points.forEach { lineTo(x(it.distM), y(it.groundM)) }; lineTo(size.width, size.height); close() }
            drawPath(g, ground.copy(alpha = 0.55f))
            val top = Path(); val bot = Path()
            p.points.forEachIndexed { i, pt -> if (i == 0) { top.moveTo(x(pt.distM), y(pt.losM + pt.fresnelM)); bot.moveTo(x(pt.distM), y(pt.losM - pt.fresnelM)) }
                else { top.lineTo(x(pt.distM), y(pt.losM + pt.fresnelM)); bot.lineTo(x(pt.distM), y(pt.losM - pt.fresnelM)) } }
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            drawPath(top, line.copy(alpha = 0.6f), style = Stroke(2f, pathEffect = dash)); drawPath(bot, line.copy(alpha = 0.6f), style = Stroke(2f, pathEffect = dash))
            drawLine(line, Offset(0f, y(p.points.first().losM)), Offset(size.width, y(p.points.last().losM)), 4f)
        }
        Text(tr("Solid line: the radio path. Dashed: the Fresnel zone, which should be at least 60% clear."), fontSize = 11.sp)
    } }
}
