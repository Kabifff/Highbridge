// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import kotlin.math.tan

/** Distance by tilt angle, the way a surveyor's clinometer works. Angles in degrees, positive above level. */
object Rangefinder {
    private fun t(deg: Double) = tan(Math.toRadians(deg))

    /** Level ground: looking down at the foot of something from eye height [eyeM]. Null unless aimed below level. */
    fun fromEyeHeight(eyeM: Double, pitchDeg: Double): Double? =
        if (pitchDeg > -0.3) null else (eyeM / t(-pitchDeg)).takeIf { it in 0.5..5_000.0 }

    /** Something [heightM] tall, sighted at its foot then its top. Null if the top isn't above the foot. */
    fun fromKnownHeight(heightM: Double, footDeg: Double, topDeg: Double): Double? {
        val diff = t(topDeg) - t(footDeg)
        return if (heightM <= 0 || diff < 0.002) null else (heightM / diff).takeIf { it in 0.5..20_000.0 }
    }

    /** Height of something [distM] away, sighted at its foot then its top. */
    fun heightAt(distM: Double, footDeg: Double, topDeg: Double): Double = distM * (t(topDeg) - t(footDeg))
}
