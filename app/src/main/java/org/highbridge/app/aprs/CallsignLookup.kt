// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.aprs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up an amateur radio call sign: callook.info for US calls (FCC data), HamDB for others.
 * Shows only name, license class, town, grid and expiry, never a street address.
 */
object CallsignLookup {
    data class Info(val call: String, val name: String, val licenseClass: String?, val place: String?, val grid: String?, val expires: String?,
                    val lat: Double?, val lon: Double?, val source: String)

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("User-Agent", "Highbridge (Android mesh radio app)")
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    suspend fun lookup(callsign: String): Result<Info> = withContext(Dispatchers.IO) {
        runCatching {
            val call = callsign.uppercase().substringBefore('-').trim()
            require(AprsFormat.isCallsign(call)) { "That doesn't look like a call sign" }
            callook(call) ?: hamdb(call) ?: error("No license found for $call")
        }
    }

    private fun callook(call: String): Info? = runCatching {
        val j = JSONObject(get("https://callook.info/${URLEncoder.encode(call, "UTF-8")}/json"))
        if (j.optString("status") != "VALID") return null
        val loc = j.optJSONObject("location"); val addr = j.optJSONObject("address")
        Info(call, j.optString("name").trim(), j.optJSONObject("current")?.optString("operClass")?.ifBlank { null },
            addr?.optString("line2")?.replace(Regex("\\s+\\d{5}(-\\d{4})?$"), "")?.ifBlank { null },   // town and state, no ZIP
            loc?.optString("gridsquare")?.ifBlank { null }, j.optJSONObject("otherInfo")?.optString("expiryDate")?.ifBlank { null },
            loc?.optString("latitude")?.toDoubleOrNull(), loc?.optString("longitude")?.toDoubleOrNull(), "callook.info (FCC)")
    }.getOrNull()

    private fun hamdb(call: String): Info? = runCatching {
        val c = JSONObject(get("https://api.hamdb.org/${URLEncoder.encode(call, "UTF-8")}/json/Highbridge")).getJSONObject("hamdb")
        if (c.optJSONObject("messages")?.optString("status") != "OK") return null
        val d = c.getJSONObject("callsign")
        val name = listOf(d.optString("fname"), d.optString("name")).filter { it.isNotBlank() && it != "NOT_FOUND" }.joinToString(" ")
        val place = listOf(d.optString("addr2"), d.optString("state"), d.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
        Info(call, name, d.optString("class").ifBlank { null }, place.ifBlank { null }, d.optString("grid").ifBlank { null },
            d.optString("expires").ifBlank { null }, d.optString("lat").toDoubleOrNull(), d.optString("lon").toDoubleOrNull(), "HamDB")
    }.getOrNull()

    fun className(c: String?): String? = when (c?.uppercase()) {
        "T", "TECHNICIAN" -> "Technician"; "G", "GENERAL" -> "General"; "E", "EXTRA", "AMATEUR EXTRA" -> "Amateur Extra"
        "A", "ADVANCED" -> "Advanced"; "N", "NOVICE" -> "Novice"; null, "" -> null; else -> c
    }
}
