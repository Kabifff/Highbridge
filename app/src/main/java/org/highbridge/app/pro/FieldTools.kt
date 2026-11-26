// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.highbridge.core.protocol.MeshNode
import org.highbridge.core.protocol.MeshtasticRegion
import org.highbridge.core.protocol.ProtocolType
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Escapes text for XML (names like  Bob "K0ABC" & Sons  used to break the XML or inject tags). */
internal fun xmlEscape(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&quot;"); '\'' -> append("&apos;")
        else -> if (c.code >= 0x20 || c == '\t' || c == '\n') append(c)
    }
}

/** java.time is thread-safe; the old shared SimpleDateFormat was not. */
private val ISO_UTC: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
private fun iso(ms: Long) = ISO_UTC.format(Instant.ofEpochMilli(ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

/** Sends mesh node positions to ATAK / iTAK / WinTAK as Cursor-on-Target XML over UDP multicast. */
object AtakCotBridge {
    fun buildCotXml(node: MeshNode, staleMinutes: Int = 10, nowMs: Long = System.currentTimeMillis()): String? {
        val lat = node.latitude ?: return null
        val lon = node.longitude ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val uid = xmlEscape("HIGHBRIDGE-${node.protocol.name}-${node.id}")
        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="$uid" type="a-f-G-U-C" time="${iso(nowMs)}" start="${iso(nowMs)}" stale="${iso(nowMs + staleMinutes * 60_000L)}" how="m-g">
  <point lat="$lat" lon="$lon" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <contact callsign="${xmlEscape("${node.shortName} (${node.protocol.shortTag})")}"/>
    <remarks>${xmlEscape("${node.longName} | RSSI:${node.rssi ?: "N/A"} | SNR:${node.snr ?: "N/A"}")}</remarks>
  </detail>
</event>"""
    }

    /** 239.2.3.1:6969 is ATAK's default situational-awareness multicast group. Needs Wi-Fi on the same network. */
    suspend fun sendUdp(xml: String, host: String = "239.2.3.1", port: Int = 6969): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            MulticastSocket().use { sock ->
                sock.timeToLive = 1 // stay on the local network
                val bytes = xml.toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), port))
            }
        }
    }
}

data class TrackPoint(val lat: Double, val lon: Double, val eleM: Double?, val timeMs: Long)

/** Records a GPS track and exports GPX 1.1. Safe to call from the location thread and the UI at once. */
class GpxTrackRecorder(private val clock: () -> Long = System::currentTimeMillis) {
    private val points = mutableListOf<TrackPoint>()

    @Synchronized fun record(lat: Double, lon: Double, eleM: Double? = null) { points.add(TrackPoint(lat, lon, eleM, clock())) }
    /** Adds a saved point with its original time (used when exporting a stored track). */
    @Synchronized fun add(p: TrackPoint) { points.add(p) }
    @Synchronized fun clear() = points.clear()
    @Synchronized fun count(): Int = points.size

    @Synchronized
    fun exportGpx(trackName: String = "Highbridge Track"): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="Highbridge" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("  <trk><name>${xmlEscape(trackName)}</name><trkseg>")
        points.forEach { p ->
            val ele = p.eleM?.let { "<ele>$it</ele>" } ?: ""
            appendLine("""    <trkpt lat="${p.lat}" lon="${p.lon}">$ele<time>${iso(p.timeMs)}</time></trkpt>""")
        }
        appendLine("  </trkseg></trk>")
        append("</gpx>")
    }
}

data class TracerouteHop(val nodeId: String, val snrDb: Float)
data class TracerouteResult(val targetId: String, val protocol: ProtocolType, val hops: List<TracerouteHop>, val timestampMs: Long = System.currentTimeMillis())
data class RangeTestSample(val lat: Double, val lon: Double, val rssi: Int, val snr: Float, val protocol: ProtocolType)
data class TelemetrySample(val nodeId: String, val batteryPct: Int?, val voltage: Float?, val tempC: Float?, val humidityPct: Float?, val timestampMs: Long = System.currentTimeMillis())

/**
 * Rolling 1-hour transmit-time meter. Uses a monotonic clock (pass SystemClock::elapsedRealtime
 * on Android) so changing the phone's time can't fake the numbers, and knows each region's limit
 * (10% in EU 433/868 and Thailand, 1% in Ukraine 868, none in the US).
 */
class AirtimeMonitor(
    var region: MeshtasticRegion = MeshtasticRegion.UNSET,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val txLog = ArrayDeque<Pair<Long, Long>>() // (time, durationMs)

    @Synchronized fun recordTx(durationMs: Long) {
        val now = monotonicMs(); txLog.addLast(now to durationMs); prune(now)
    }

    @Synchronized fun dutyCyclePercent(): Float {
        prune(monotonicMs())
        return txLog.sumOf { it.second } / 36_000f // ms per hour / 100
    }

    /** Fraction of the region's allowance used (1.0 = at the legal limit). */
    fun allowanceUsed(): Float = dutyCyclePercent() / region.dutyCyclePercent
    fun shouldWarn(): Boolean = region.hasDutyCycleLimit && allowanceUsed() >= 0.8f
    fun mustHold(): Boolean = region.hasDutyCycleLimit && allowanceUsed() >= 1.0f

    private fun prune(now: Long) {
        while (txLog.isNotEmpty() && now - txLog.first().first > 3_600_000L) txLog.removeFirst()
    }
}
