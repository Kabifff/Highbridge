// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.sqrt

/**
 * Line of sight between two points: ground heights along the path (Open-Meteo elevation API), the earth's curve
 * (4/3 radius, for normal radio refraction), the straight radio path between the antennas, and the first Fresnel zone.
 */
object LineOfSight {
    data class Point(val distM: Double, val groundM: Double, val losM: Double, val fresnelM: Double)
    data class Profile(val points: List<Point>, val distanceM: Double, val clear: Boolean,
                       /** Smallest (path height above ground) / (Fresnel radius) along the way; 0.6 or more is good. */
                       val fresnelClearance: Double, val worstAtM: Double, val worstBlockM: Double)

    private const val EFFECTIVE_EARTH_M = 6_371_000.0 * 4 / 3

    /** Builds the profile from ground heights already known ([elevations] evenly spaced from start to end). */
    fun compute(elevations: List<Double>, distanceM: Double, h1M: Double, h2M: Double, freqMhz: Double): Profile {
        val n = elevations.size
        require(n >= 2) { "Need at least two heights" }
        val lambda = 299_792_458.0 / (freqMhz * 1e6)
        val start = elevations.first() + h1M; val end = elevations.last() + h2M
        var clearance = Double.MAX_VALUE; var worstAt = 0.0; var worstBlock = Double.NEGATIVE_INFINITY
        val pts = elevations.mapIndexed { i, e ->
            val d = distanceM * i / (n - 1)
            val bulge = d * (distanceM - d) / (2 * EFFECTIVE_EARTH_M)
            val ground = e + bulge
            val los = start + (end - start) * d / distanceM
            val fresnel = sqrt(lambda * d * (distanceM - d) / distanceM)
            if (i in 1 until n - 1) {
                if (fresnel > 0) clearance = minOf(clearance, (los - ground) / fresnel)
                if (ground - los > worstBlock) { worstBlock = ground - los; worstAt = d }
            }
            Point(d, ground, los, fresnel)
        }
        if (clearance == Double.MAX_VALUE) clearance = 10.0
        return Profile(pts, distanceM, worstBlock <= 0, clearance, worstAt, worstBlock)
    }

    /** Fetches ground heights for [samples] points along the path and computes the profile. */
    suspend fun profile(lat1: Double, lon1: Double, lat2: Double, lon2: Double, h1M: Double, h2M: Double, freqMhz: Double,
                        samples: Int = 100): Result<Profile> = withContext(Dispatchers.IO) {
        runCatching {
            val dist = Geo.distanceM(lat1, lon1, lat2, lon2)
            require(dist > 20) { "The two points are too close together" }
            require(dist < 200_000) { "That's more than 200 km away" }
            val bearing = Geo.bearingDeg(lat1, lon1, lat2, lon2)
            val pts = (0 until samples).map { i -> if (i == 0) lat1 to lon1 else Geo.destination(lat1, lon1, bearing, dist * i / (samples - 1)) }
            val lats = pts.joinToString(",") { String.format(Locale.US, "%.5f", it.first) }
            val lons = pts.joinToString(",") { String.format(Locale.US, "%.5f", it.second) }
            val c = (URL("https://api.open-meteo.com/v1/elevation?latitude=$lats&longitude=$lons").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000; setRequestProperty("User-Agent", "Highbridge (Android mesh radio app)")
            }
            val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("elevation")
            compute((0 until arr.length()).map { arr.getDouble(it) }, dist, h1M, h2M, freqMhz)
        }
    }
}
