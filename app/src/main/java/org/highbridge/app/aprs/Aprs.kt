// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.aprs

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** KISS framing (the byte format every APRS TNC speaks to the phone). */
object Kiss {
    const val FEND = 0xC0; const val FESC = 0xDB; const val TFEND = 0xDC; const val TFESC = 0xDD

    /** Wraps one AX.25 frame as a KISS data frame on [port]. */
    fun encode(frame: ByteArray, port: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(frame.size + 4)
        out.write(FEND); out.write((port and 0x0F) shl 4)
        for (b in frame) when (b.toInt() and 0xFF) {
            FEND -> { out.write(FESC); out.write(TFEND) }
            FESC -> { out.write(FESC); out.write(TFESC) }
            else -> out.write(b.toInt())
        }
        out.write(FEND)
        return out.toByteArray()
    }

    /** Reassembles KISS data frames from a byte stream that may split or join them anywhere. */
    class Decoder {
        private val buf = ByteArrayOutputStream()
        private var escaped = false
        fun feed(bytes: ByteArray): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            for (raw in bytes) {
                val b = raw.toInt() and 0xFF
                when {
                    b == FEND -> {
                        val f = buf.toByteArray(); buf.reset(); escaped = false
                        // Low nibble 0 = data frame; anything else is a TNC command echo.
                        if (f.size > 1 && (f[0].toInt() and 0x0F) == 0) frames += f.copyOfRange(1, f.size)
                    }
                    escaped -> { buf.write(if (b == TFEND) FEND else if (b == TFESC) FESC else b); escaped = false }
                    b == FESC -> escaped = true
                    else -> buf.write(b)
                }
            }
            return frames
        }
    }
}

/** One APRS packet in the plain-text "TNC2" form: SOURCE>DEST,PATH1,PATH2:info */
data class AprsPacket(val source: String, val dest: String, val path: List<String>, val info: String) {
    fun tnc2(): String = buildString { append(source).append('>').append(dest); path.forEach { append(',').append(it) }; append(':').append(info) }

    companion object {
        fun parseTnc2(line: String): AprsPacket? {
            val colon = line.indexOf(':'); val gt = line.indexOf('>')
            if (colon < 0 || gt < 1 || gt > colon) return null
            val head = line.substring(gt + 1, colon).split(',')
            return AprsPacket(line.substring(0, gt).trim(), head.first(), head.drop(1), line.substring(colon + 1).trimEnd('\r', '\n'))
        }
    }
}

/** AX.25 UI frames, the over-the-air form of an APRS packet. */
object Ax25 {
    private fun address(call: String, last: Boolean, isDest: Boolean, repeated: Boolean = false): ByteArray {
        val c = call.trimEnd('*').uppercase()
        val base = c.substringBefore('-'); val ssid = c.substringAfter('-', "0").toIntOrNull()?.coerceIn(0, 15) ?: 0
        val out = ByteArray(7)
        for (i in 0 until 6) out[i] = ((base.getOrElse(i) { ' ' }.code shl 1) and 0xFE).toByte()
        var s = 0x60 or (ssid shl 1)
        if (isDest) s = s or 0x80              // command bit on the destination
        if (repeated) s = s or 0x80            // H bit: this digipeater already passed it on
        if (last) s = s or 0x01
        out[6] = s.toByte()
        return out
    }

    fun encode(p: AprsPacket): ByteArray {
        val out = ByteArrayOutputStream()
        val path = p.path.take(8)
        out.write(address(p.dest, false, true))
        out.write(address(p.source, path.isEmpty(), false))
        path.forEachIndexed { i, d -> out.write(address(d, i == path.lastIndex, false, d.endsWith("*"))) }
        out.write(0x03); out.write(0xF0)
        out.write(p.info.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    fun decode(f: ByteArray): AprsPacket? {
        val calls = mutableListOf<String>()
        var i = 0
        while (i + 7 <= f.size) {
            val base = (0 until 6).map { ((f[i + it].toInt() and 0xFF) shr 1).toChar() }.joinToString("").trimEnd()
            val s = f[i + 6].toInt() and 0xFF
            val ssid = (s shr 1) and 0x0F
            val repeated = calls.size >= 2 && (s and 0x80) != 0
            calls += base + (if (ssid != 0) "-$ssid" else "") + (if (repeated) "*" else "")
            i += 7
            if (s and 0x01 != 0) break
        }
        if (calls.size < 2 || i + 2 > f.size) return null
        if ((f[i].toInt() and 0xFF) != 0x03 || (f[i + 1].toInt() and 0xFF) != 0xF0) return null // only UI frames carry APRS
        val info = String(f, i + 2, f.size - i - 2, Charsets.UTF_8)
        return AprsPacket(calls[1], calls[0], calls.drop(2), info)
    }
}

/** What an APRS packet says, once parsed. */
sealed interface AprsData {
    data class Position(val lat: Double, val lon: Double, val symbol: String, val comment: String, val altitudeFt: Int? = null,
                        val courseDeg: Int? = null, val speedKnots: Int? = null, val messaging: Boolean = false, val objectName: String? = null) : AprsData
    data class Message(val addressee: String, val text: String, val msgNo: String?) : AprsData
    data class Ack(val addressee: String, val msgNo: String, val rejected: Boolean = false) : AprsData
    data class Status(val text: String) : AprsData
    data class Other(val info: String) : AprsData
}

object AprsParser {
    /** The packet actually meant, unwrapping third-party ("}") packets gated from the internet. */
    fun unwrap(p: AprsPacket): AprsPacket = if (p.info.startsWith("}")) AprsPacket.parseTnc2(p.info.substring(1))?.let(::unwrap) ?: p else p

    fun parse(p: AprsPacket): AprsData = runCatching { parseInner(p) }.getOrNull() ?: AprsData.Other(p.info)

    private fun parseInner(p: AprsPacket): AprsData? {
        val info = p.info
        if (info.isEmpty()) return null
        return when (info[0]) {
            '!', '=' -> position(info.substring(1), messaging = info[0] == '=')
            '/', '@' -> if (info.length > 8) position(info.substring(8), messaging = info[0] == '@') else null
            ':' -> message(info)
            '>' -> AprsData.Status(info.substring(1).trim())
            '`', '\'', '\u001c', '\u001d' -> micE(p.dest, info)
            ';' -> if (info.length >= 18) (position(info.substring(18)) as? AprsData.Position)?.copy(objectName = info.substring(1, 10).trim()) else null
            ')' -> {
                val end = info.indexOfAny(charArrayOf('!', '_'), 1).takeIf { it in 4..10 } ?: return null
                (position(info.substring(end + 1)) as? AprsData.Position)?.copy(objectName = info.substring(1, end))
            }
            else -> null
        }
    }

    private fun message(info: String): AprsData? {
        if (info.length < 11 || info[10] != ':') return null
        val to = info.substring(1, 10).trim().uppercase()
        val body = info.substring(11)
        Regex("^(ack|rej)([A-Za-z0-9]{1,5})}?").find(body)?.let { return AprsData.Ack(to, it.groupValues[2], rejected = it.groupValues[1] == "rej") }
        val brace = body.lastIndexOf('{')
        return if (brace >= 0) AprsData.Message(to, body.substring(0, brace).trim(), body.substring(brace + 1).substringBefore('}').trim().ifEmpty { null })
        else AprsData.Message(to, body.trim(), null)
    }

    private fun position(s: String, messaging: Boolean = false): AprsData? {
        if (s.isEmpty()) return null
        return if (s[0].isDigit() || s[0] == ' ') uncompressed(s, messaging) else compressed(s, messaging)
    }

    private fun uncompressed(s: String, messaging: Boolean): AprsData.Position? {
        if (s.length < 19) return null
        val latS = s.substring(0, 8).replace(' ', '0'); val lonS = s.substring(9, 18).replace(' ', '0')
        val lat = (latS.substring(0, 2).toInt() + latS.substring(2, 7).toDouble() / 60.0) * if (latS[7].uppercaseChar() == 'S') -1 else 1
        val lon = (lonS.substring(0, 3).toInt() + lonS.substring(3, 8).toDouble() / 60.0) * if (lonS[8].uppercaseChar() == 'W') -1 else 1
        if (abs(lat) > 90 || abs(lon) > 180) return null
        var comment = s.substring(19)
        var course: Int? = null; var speed: Int? = null
        Regex("^(\\d{3})/(\\d{3})").find(comment)?.let { course = it.groupValues[1].toInt(); speed = it.groupValues[2].toInt(); comment = comment.substring(7) }
        val (alt, rest) = altitude(comment)
        return AprsData.Position(lat, lon, "${s[8]}${s[18]}", rest.trim(), alt, course, speed, messaging)
    }

    private fun base91(s: String): Int = s.fold(0) { acc, c -> acc * 91 + (c.code - 33) }

    private fun compressed(s: String, messaging: Boolean): AprsData.Position? {
        if (s.length < 13) return null
        val lat = 90 - base91(s.substring(1, 5)) / 380926.0
        val lon = -180 + base91(s.substring(5, 9)) / 190463.0
        var course: Int? = null; var speed: Int? = null; var alt: Int? = null
        val c = s[10]; val sp = s[11]; val t = s[12].code - 33
        if (c != ' ') when {
            (t shr 3) and 0x03 == 2 -> alt = (1.002.pow((c.code - 33) * 91 + (sp.code - 33))).roundToInt() // GGA: altitude
            c.code - 33 in 0..89 -> { course = (c.code - 33) * 4; speed = (1.08.pow(sp.code - 33) - 1).roundToInt() }
        }
        val (a2, rest) = altitude(s.substring(13))
        return AprsData.Position(lat, lon, "${s[0]}${s[9]}", rest.trim(), alt ?: a2, course, speed, messaging)
    }

    private fun altitude(comment: String): Pair<Int?, String> {
        val m = Regex("/A=(-?\\d{5,6})").find(comment) ?: return null to comment
        return m.groupValues[1].toInt() to comment.removeRange(m.range)
    }

    /** Mic-E: Kenwood and Yaesu radios hide the latitude in the destination callsign. */
    private fun micE(dest: String, info: String): AprsData.Position? {
        val d = dest.substringBefore('-')
        if (d.length < 6 || info.length < 9) return null
        val digits = IntArray(6)
        for (i in 0 until 6) {
            val c = d[i]
            digits[i] = when (c) {
                in '0'..'9' -> c - '0'; in 'A'..'J' -> c - 'A'; in 'P'..'Y' -> c - 'P'
                'K', 'L', 'Z' -> 0
                else -> return null
            }
        }
        val north = d[3] in 'P'..'Z'; val lonOffset = d[4] in 'P'..'Z'; val west = d[5] in 'P'..'Z'
        val lat = (digits[0] * 10 + digits[1] + (digits[2] * 10 + digits[3] + (digits[4] * 10 + digits[5]) / 100.0) / 60.0) * if (north) 1 else -1
        var lonDeg = info[1].code - 28
        if (lonOffset) lonDeg += 100
        if (lonDeg in 180..189) lonDeg -= 80 else if (lonDeg in 190..199) lonDeg -= 190
        var lonMin = info[2].code - 28
        if (lonMin >= 60) lonMin -= 60
        val lonHun = info[3].code - 28
        val lon = (lonDeg + (lonMin + lonHun / 100.0) / 60.0) * if (west) -1 else 1
        val sp = info[4].code - 28; val dc = info[5].code - 28; val se = info[6].code - 28
        var speed = sp * 10 + dc / 10; if (speed >= 800) speed -= 800
        var course = (dc % 10) * 100 + se; if (course >= 400) course -= 400
        val rest = info.substring(9)
        val (alt, comment) = rest.let { r ->
            // Mic-E altitude: 3 base-91 chars then "}", metres above -10 km.
            val m = Regex("(.{3})}").find(r)
            if (m != null && m.range.first <= 1) (((base91(m.groupValues[1]) - 10000) * 3.281).roundToInt()) to r.removeRange(m.range) else null to r
        }
        return AprsData.Position(lat, lon, "${info[8]}${info[7]}", comment.trim().trimStart('>', ']', '`', '\''), alt, course.takeIf { it in 1..360 }, speed, messaging = true)
    }
}

/** Builds the APRS packets Highbridge sends. */
object AprsFormat {
    /** "Experimental" tocall prefix, allowed without registration. */
    const val TOCALL = "APZHBG"

    private fun latStr(lat: Double): String {
        val a = abs(lat); var d = floor(a).toInt(); var m = (a - d) * 60
        if (String.format(Locale.US, "%05.2f", m) == "60.00") { d += 1; m = 0.0 }
        return String.format(Locale.US, "%02d%05.2f%c", d, m, if (lat < 0) 'S' else 'N')
    }
    private fun lonStr(lon: Double): String {
        val a = abs(lon); var d = floor(a).toInt(); var m = (a - d) * 60
        if (String.format(Locale.US, "%05.2f", m) == "60.00") { d += 1; m = 0.0 }
        return String.format(Locale.US, "%03d%05.2f%c", d, m, if (lon < 0) 'W' else 'E')
    }

    /** A position report ("=" = this station can receive messages). [symbol] is table + code, e.g. "/>" for a car. */
    fun position(lat: Double, lon: Double, symbol: String, comment: String, altitudeM: Double? = null): String {
        val sym = symbol.padEnd(2, '-')
        val alt = altitudeM?.let { String.format(Locale.US, "/A=%06d", (it * 3.281).roundToInt().coerceIn(-99999, 999999)) } ?: ""
        return "=" + latStr(lat) + sym[0] + lonStr(lon) + sym[1] + clean(comment).take(43) + alt
    }

    /** APRS message text can't contain these, and stops at 67 characters. */
    fun clean(text: String) = text.replace(Regex("[|~{\\r\\n]"), " ").trim()

    fun message(to: String, text: String, msgNo: String?): String =
        ":" + to.uppercase().padEnd(9).take(9) + ":" + clean(text).take(67) + (msgNo?.let { "{$it" } ?: "")
    fun ack(to: String, msgNo: String) = ":" + to.uppercase().padEnd(9).take(9) + ":ack$msgNo"

    /** The APRS-IS login code for a call sign (the well-known public hash; it only proves you read the docs). */
    fun passcode(callsign: String): Int {
        val c = callsign.uppercase().substringBefore('-')
        var hash = 0x73E2
        var i = 0
        while (i < c.length) {
            hash = hash xor (c[i].code shl 8)
            if (i + 1 < c.length) hash = hash xor c[i + 1].code
            i += 2
        }
        return hash and 0x7FFF
    }

    val CALLSIGN = Regex("^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,4}(-([0-9]|1[0-5]))?$")
    fun isCallsign(s: String) = CALLSIGN.matches(s.uppercase())

    /** Common symbols people pick for themselves, table + code. */
    val SYMBOLS = linkedMapOf("/-" to "House", "/>" to "Car", "/[" to "Person", "/b" to "Bicycle", "/<" to "Motorcycle", "/k" to "Truck",
        "/j" to "Jeep", "/v" to "Van", "/R" to "RV", "/s" to "Boat", "/Y" to "Sailboat", "/_" to "Weather station", "/y" to "Antenna at home",
        "/r" to "Antenna", "/'" to "Aircraft", "/O" to "Balloon", "/X" to "Helicopter", "/#" to "Digipeater", "/&" to "Gateway", "\\&" to "Gateway",
        "/u" to "Semi truck", "/U" to "Bus", "/f" to "Fire truck", "/a" to "Ambulance", "/!" to "Police", "/+" to "Red Cross", "/h" to "Hospital",
        "/;" to "Campground", "/=" to "Train", "\\#" to "Digipeater", "/$" to "Phone", "/n" to "Node", "/E" to "Eyeball (event)")
    fun symbolName(s: String) = SYMBOLS[s] ?: SYMBOLS["/" + s.getOrElse(1) { ' ' }]?.takeIf { s.firstOrNull() != '/' && s.firstOrNull() != '\\' }?.let { "$it (overlay ${s[0]})" } ?: "Symbol $s"
}
