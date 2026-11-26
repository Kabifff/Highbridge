// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.highbridge.app.ui.theme.StPaulMeshPalette
import org.highbridge.app.ui.theme.protocolColor
import org.highbridge.core.protocol.ProtocolType

val isDarkUi: Boolean @Composable get() = MaterialTheme.colorScheme.background == StPaulMeshPalette.NightBg

/** Screen header: italic serif title, subtitle, optional West 7th skyline strip, gold rule. */
@Composable
fun HbHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, skyline: Boolean = false, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
        if (skyline) Skyline(Modifier.fillMaxWidth().height(34.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(StPaulMeshPalette.Gold))
    }
}

/** Cathedral dome, Schmidt Brewery tower, the High Bridge and the river, drawn small. */
@Composable
fun Skyline(modifier: Modifier = Modifier) {
    val dark = isDarkUi
    Canvas(modifier.background(if (dark) StPaulMeshPalette.NightBg else StPaulMeshPalette.Parchment)) {
        val w = size.width; val h = size.height; val sx = w / 390f; val sy = h / 34f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        val bluff = if (dark) StPaulMeshPalette.NightCard else StPaulMeshPalette.GreenSoft
        val ink = if (dark) StPaulMeshPalette.NightLine else Color(0xFFA9B79F)
        drawPath(Path().apply { moveTo(0f, h); lineTo(0f, 20 * sy); quadraticTo(30 * sx, 16 * sy, 70 * sx, 19 * sy); lineTo(96 * sx, 24 * sy); lineTo(100 * sx, h); close() }, bluff)
        drawPath(Path().apply { moveTo(w, h); lineTo(w, 18 * sy); quadraticTo(360 * sx, 14 * sy, 320 * sx, 19 * sy); lineTo(292 * sx, 24 * sy); lineTo(288 * sx, h); close() }, bluff)
        drawRect(if (dark) StPaulMeshPalette.MeshCore.copy(alpha = .5f) else StPaulMeshPalette.River, p(0f, 30f), Size(w, 4 * sy))
        drawRect(if (dark) StPaulMeshPalette.NightLine else StPaulMeshPalette.Stone, p(18f, 10f), Size(14 * sx, 10 * sy))
        drawArc(if (dark) StPaulMeshPalette.NightLine else StPaulMeshPalette.Stone, 180f, 180f, true, p(18f, 4f), Size(14 * sx, 13 * sy))
        drawRect(if (dark) Color(0xFF4A2A24) else StPaulMeshPalette.BrickLight, p(42f, 6f), Size(9 * sx, 14 * sy))
        if (dark) drawRect(StPaulMeshPalette.NightAmber, p(45f, 9f), Size(3 * sx, 3 * sy))
        drawRect(ink, p(96f, 20f), Size(198 * sx, 3 * sy))
        drawPath(Path().apply { moveTo(130 * sx, 31 * sy); quadraticTo(195 * sx, 2 * sy, 260 * sx, 31 * sy) }, ink, style = Stroke(2.5f * sx))
        drawCircle(StPaulMeshPalette.Gold, 3 * sx, p(195f, 16f))
    }
}

@Composable
fun ProtocolBadge(p: ProtocolType) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(protocolColor(p)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(p.shortTag, color = if (isDarkUi) StPaulMeshPalette.NightBg else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun HbCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) { Box(Modifier.size(size.dp).clip(CircleShape).background(color)) }

@Composable
fun EmptyState(title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BridgeGlyph(Modifier.size(72.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp)); action()
    }
}

/** The app icon's arch, used for empty states. */
@Composable
fun BridgeGlyph(modifier: Modifier = Modifier) {
    val green = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val s = size.width / 160f
        drawRoundRect(green, cornerRadius = androidx.compose.ui.geometry.CornerRadius(36 * s))
        val cream = StPaulMeshPalette.Cream
        drawRect(cream, Offset(22 * s, 56 * s), Size(116 * s, 10 * s))
        drawPath(Path().apply { moveTo(40 * s, 128 * s); quadraticTo(80 * s, -4 * s, 120 * s, 128 * s) }, cream, style = Stroke(10 * s))
        drawLine(cream, Offset(54 * s, 66 * s), Offset(54 * s, 90 * s), 6 * s); drawLine(cream, Offset(106 * s, 66 * s), Offset(106 * s, 90 * s), 6 * s)
        drawRoundRect(StPaulMeshPalette.MeshCoreDark, Offset(22 * s, 126 * s), Size(116 * s, 10 * s), androidx.compose.ui.geometry.CornerRadius(5 * s))
        drawCircle(StPaulMeshPalette.Gold, 11 * s, Offset(80 * s, 34 * s))
    }
}

/** The Bridge screen's diagram: each pair of networks is an arch; solid when bridged, dashed when not. */
@Composable
fun BridgeSpans(active: Set<Pair<ProtocolType, ProtocolType>>, modifier: Modifier = Modifier) {
    val mt = protocolColor(ProtocolType.MESHTASTIC); val mc = protocolColor(ProtocolType.MESHCORE); val rns = protocolColor(ProtocolType.RETICULUM)
    val ink = MaterialTheme.colorScheme.onSurface; val off = StPaulMeshPalette.Stone; val on = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width; val s = w / 328f
        drawRect(StPaulMeshPalette.River, Offset(0f, 84 * s), Size(w, 12 * s))
        drawRect(ink, Offset(14 * s, 38 * s), Size(300 * s, 5 * s))
        fun span(x0: Float, x1: Float, isOn: Boolean) {
            val path = Path().apply { moveTo(x0 * s, 86 * s); quadraticTo((x0 + x1) / 2 * s, -6 * s, x1 * s, 86 * s) }
            drawPath(path, if (isOn) on else off, style = Stroke(if (isOn) 5 * s else 4 * s, cap = StrokeCap.Butt,
                pathEffect = if (isOn) null else PathEffect.dashPathEffect(floatArrayOf(7 * s, 6 * s))))
        }
        fun has(a: ProtocolType, b: ProtocolType) = (a to b) in active || (b to a) in active
        span(40f, 164f, has(ProtocolType.MESHTASTIC, ProtocolType.MESHCORE))
        span(164f, 288f, has(ProtocolType.MESHCORE, ProtocolType.RETICULUM))
        if (has(ProtocolType.MESHTASTIC, ProtocolType.RETICULUM)) drawLine(on, Offset(40 * s, 30 * s), Offset(288 * s, 30 * s), 3 * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * s, 4 * s)))
        drawCircle(mt, 17 * s, Offset(40 * s, 30 * s)); drawCircle(mc, 17 * s, Offset(164 * s, 30 * s)); drawCircle(rns, 17 * s, Offset(288 * s, 30 * s))
    }
}

@Composable fun HeroLock() = BridgeGlyph(Modifier.size(96.dp))

fun timeAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "never"
    val s = (now - ms) / 1000
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
}

@Composable fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
@Composable fun HGap(w: Int) = Spacer(Modifier.width(w.dp))
