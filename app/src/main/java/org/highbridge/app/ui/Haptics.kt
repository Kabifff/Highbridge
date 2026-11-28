// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import org.highbridge.app.HighbridgeApp

/** Short vibrations for taps, at the strength chosen in Settings (or none). */
object Haptics {
    val LEVELS = listOf("OFF" to "Off", "LIGHT" to "Light", "MEDIUM" to "Medium", "STRONG" to "Strong")

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }.getOrNull()?.takeIf { it.hasVibrator() }

    /** A tap on anything tappable. */
    fun tap(ctx: Context, level: String = HighbridgeApp.instance.prefs.haptics.value) = play(ctx, level, strong = false)

    /** Something finished well (joined, saved, sent): one step stronger than a tap. */
    fun confirm(ctx: Context, level: String = HighbridgeApp.instance.prefs.haptics.value) = play(ctx, level, strong = true)

    private fun play(ctx: Context, level: String, strong: Boolean) {
        if (level == "OFF") return
        val v = vibrator(ctx) ?: return
        val step = (when (level) { "LIGHT" -> 0; "MEDIUM" -> 1; else -> 2 } + if (strong) 1 else 0).coerceAtMost(2)
        runCatching {
            v.vibrate(if (Build.VERSION.SDK_INT >= 29)
                VibrationEffect.createPredefined(when (step) { 0 -> VibrationEffect.EFFECT_TICK; 1 -> VibrationEffect.EFFECT_CLICK; else -> VibrationEffect.EFFECT_HEAVY_CLICK })
            else VibrationEffect.createOneShot(longArrayOf(10L, 20L, 35L)[step], intArrayOf(60, 140, 255)[step]))
        }
    }
}

/**
 * Vibrates when a tap inside lands on something tappable. It only listens: after the buttons, switches, chips
 * and rows inside have handled the touch, a tap that one of them used (and that didn't turn into a scroll) buzzes.
 */
fun Modifier.hapticTaps(): Modifier = composed {
    val ctx = LocalContext.current
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (!c.pressed) { if (!moved && c.isConsumed) Haptics.tap(ctx); break }
            }
        }
    }
}

/** Opens the side menu from any screen's header; null before setup is finished. */
val LocalOpenDrawer = staticCompositionLocalOf<(() -> Unit)?> { null }

/** AlertDialog whose buttons and content buzz on tap like the rest of the app (dialogs are separate windows). */
@Composable
fun HbAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) = AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = { Box(Modifier.hapticTaps()) { confirmButton() } },
    modifier = modifier,
    dismissButton = dismissButton?.let { d -> { Box(Modifier.hapticTaps()) { d() } } },
    icon = icon, title = title,
    text = text?.let { t -> { Box(Modifier.hapticTaps()) { t() } } },
)

/** DropdownMenu whose items buzz on tap (menus are separate windows too). */
@Composable
fun HbDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    DropdownMenu(expanded, onDismissRequest, modifier) { Column(Modifier.width(IntrinsicSize.Max).hapticTaps()) { content() } }
