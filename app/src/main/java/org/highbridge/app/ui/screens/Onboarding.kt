// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui.screens

import org.highbridge.app.ui.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.LocalNav
import org.highbridge.app.ui.theme.DisplayFont
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.core.protocol.MeshtasticRegion

/** The first screen: the High Bridge over the river, the Cathedral dome and the Schmidt tower on the West 7th bluff. */
@Composable
fun WelcomeScreen() {
    val nav = LocalNav.current
    val app = HighbridgeApp.instance
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        HeroBridge(Modifier.statusBarsPadding().fillMaxWidth().height(300.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(StPaulMeshPalette.Gold))
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("WEST 7TH · SAINT PAUL", color = StPaulMeshPalette.SchmidtBrick, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.6.sp)
            Text("Highbridge", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(tr("One bridge for every mesh."), fontFamily = DisplayFont, fontStyle = FontStyle.Italic, fontSize = 20.sp)
            Text("${if (org.highbridge.app.rns.ReticulumSupport.AVAILABLE) "Meshtastic, MeshCore and Reticulum" else "Meshtastic and MeshCore"} in one app. Made on Fort Road in Saint Paul, and it works on any LoRa band in the world.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { app.prefs.setOnboarded(true); nav.navigate("radio") { popUpTo("welcome") { inclusive = true } } },
                Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Text(tr("Get started"), fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = {
                app.prefs.setRegion(MeshtasticRegion.US); app.prefs.setOnboarded(true)
                nav.navigate("connect") { popUpTo("welcome") { inclusive = true } }
            }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp)) { Text(tr("Join Saint Paul Mesh"), fontWeight = FontWeight.Bold) }
            Text(tr("No internet, cell service or account needed."), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HeroBridge(modifier: Modifier = Modifier) {
    val dark = org.highbridge.app.ui.isDarkUi
    Canvas(modifier.background(MaterialTheme.colorScheme.background)) {
        val s = size.width / 390f
        val yOff = (size.height - 330 * s).coerceAtLeast(0f)
        fun o(x: Float, y: Float) = Offset(x * s, y * s + yOff)
        val ink = if (dark) StPaulMeshPalette.NightFg else StPaulMeshPalette.Ink
        val moss = if (dark) StPaulMeshPalette.NightLine else StPaulMeshPalette.Moss
        drawCircle(StPaulMeshPalette.Gold.copy(alpha = .22f), 26 * s, o(300f, 70f)); drawCircle(StPaulMeshPalette.Gold, 14 * s, o(300f, 70f))
        val dash = PathEffect.dashPathEffect(floatArrayOf(4 * s, 5 * s))
        drawPath(Path().apply { moveTo(206 * s, 150 * s + yOff); cubicTo(230 * s, 112 * s + yOff, 268 * s, 92 * s + yOff, 286 * s, 80 * s + yOff) }, StPaulMeshPalette.Gold, style = Stroke(2 * s, pathEffect = dash))
        drawPath(Path().apply { moveTo(78 * s, 116 * s + yOff); cubicTo(110 * s, 92 * s + yOff, 160 * s, 92 * s + yOff, 194 * s, 150 * s + yOff) }, StPaulMeshPalette.Gold, style = Stroke(2 * s, pathEffect = dash))
        drawPath(Path().apply { moveTo(0f, 330 * s + yOff); lineTo(0f, 168 * s + yOff); quadraticTo(45 * s, 158 * s + yOff, 96 * s, 166 * s + yOff); lineTo(132 * s, 178 * s + yOff); lineTo(140 * s, 330 * s + yOff); close() }, StPaulMeshPalette.Green)
        drawPath(Path().apply { moveTo(390 * s, 330 * s + yOff); lineTo(390 * s, 162 * s + yOff); quadraticTo(348 * s, 154 * s + yOff, 302 * s, 164 * s + yOff); lineTo(270 * s, 178 * s + yOff); lineTo(258 * s, 330 * s + yOff); close() }, StPaulMeshPalette.Wave)
        drawRect(StPaulMeshPalette.River, o(0f, 288f), Size(390 * s, 42 * s))
        // Cathedral dome
        drawRect(moss, o(20f, 138f), Size(40 * s, 30 * s)); drawArc(moss, 180f, 180f, true, o(20f, 98f), Size(40 * s, 82 * s)); drawRect(moss, o(36f, 94f), Size(8 * s, 12 * s))
        // Schmidt Brewery tower
        drawRect(StPaulMeshPalette.SchmidtBrick, o(66f, 124f), Size(24 * s, 44 * s))
        listOf(66f, 75.5f, 85f).forEach { drawRect(StPaulMeshPalette.SchmidtBrick, o(it, 118f), Size(5 * s, 7 * s)) }
        drawCircle(StPaulMeshPalette.Gold, 4 * s, o(78f, 112f))
        // Cherokee Heights houses
        drawRect(moss, o(318f, 146f), Size(22 * s, 18 * s)); drawRect(moss, o(348f, 150f), Size(18 * s, 14 * s))
        // The bridge
        drawRect(ink, o(94f, 169f), Size(214 * s, 7 * s))
        drawPath(Path().apply { moveTo(135 * s, 292 * s + yOff); quadraticTo(200 * s, 64 * s + yOff, 265 * s, 292 * s + yOff) }, ink, style = Stroke(6 * s))
        listOf(150f to 245f, 165f to 211f, 180f to 189f, 220f to 189f, 235f to 211f, 250f to 245f, 112f to 186f, 288f to 186f).forEach { (x, y) ->
            drawLine(ink, o(x, 176f), o(x, y), 3 * s)
        }
        drawRect(ink, o(128f, 284f), Size(14 * s, 10 * s)); drawRect(ink, o(258f, 284f), Size(14 * s, 10 * s))
        drawCircle(StPaulMeshPalette.Gold, 6 * s, o(200f, 160f)); drawCircle(StPaulMeshPalette.Gold.copy(alpha = .6f), 13 * s, o(200f, 160f), style = Stroke(2 * s))
    }
}
