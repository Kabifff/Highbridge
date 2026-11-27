// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances, bearings and geofence tests. Pure Kotlin so it can be unit-tested. */
object Geo {
    private const val R = 6_371_000.0
    private fun rad(d: Double) = Math.toRadians(d)

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1); val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(rad(lon2 - lon1)) * cos(rad(lat2))
        val x = cos(rad(lat1)) * sin(rad(lat2)) - sin(rad(lat1)) * cos(rad(lat2)) * cos(rad(lon2 - lon1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The point [distM] metres from (lat, lon) along [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val d = distM / 6_371_000.0; val b = rad(bearingDeg); val p1 = rad(lat); val l1 = rad(lon)
        val p2 = kotlin.math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }

    fun compassPoint(deg: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

    fun formatDistance(m: Double, metric: Boolean): String = if (metric) {
        if (m < 1000) "%.0f m".format(m) else "%.1f km".format(m / 1000)
    } else {
        val ft = m * 3.28084
        if (ft < 1000) "%.0f ft".format(ft) else "%.1f mi".format(m / 1609.344)
    }

    /** Ray casting point-in-polygon; polygon as (lat, lon) pairs. */
    fun inPolygon(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val (yi, xi) = poly[i]; val (yj, xj) = poly[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Shortest distance from a point to a polyline (a "trail"), in metres. Flat-earth approximation per segment. */
    fun distanceToTrailM(lat: Double, lon: Double, trail: List<Pair<Double, Double>>): Double {
        if (trail.isEmpty()) return Double.MAX_VALUE
        if (trail.size == 1) return distanceM(lat, lon, trail[0].first, trail[0].second)
        val kLat = 111_320.0; val kLon = 111_320.0 * cos(rad(lat))
        var best = Double.MAX_VALUE
        for (i in 0 until trail.lastIndex) {
            val ax = (trail[i].second - lon) * kLon; val ay = (trail[i].first - lat) * kLat
            val bx = (trail[i + 1].second - lon) * kLon; val by = (trail[i + 1].first - lat) * kLat
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            best = minOf(best, sqrt(px * px + py * py))
        }
        return best
    }

    fun parsePoints(s: String): List<Pair<Double, Double>> = s.split(';').mapNotNull { p ->
        val parts = p.split(',')
        if (parts.size == 2) parts[0].trim().toDoubleOrNull()?.let { a -> parts[1].trim().toDoubleOrNull()?.let { b -> a to b } } else null
    }
}

/** Decides whether a node is inside a geofence. */
object GeofenceRules {
    enum class Kind { CIRCLE, POLYGON, TRAIL }
    fun isInside(kind: Kind, points: List<Pair<Double, Double>>, radiusM: Double, lat: Double, lon: Double): Boolean = when (kind) {
        Kind.CIRCLE -> points.firstOrNull()?.let { Geo.distanceM(it.first, it.second, lat, lon) <= radiusM } ?: false
        Kind.POLYGON -> points.size >= 3 && Geo.inPolygon(lat, lon, points)
        Kind.TRAIL -> Geo.distanceToTrailM(lat, lon, points) <= radiusM
    }
}
