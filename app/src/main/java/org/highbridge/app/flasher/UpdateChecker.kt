// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.flasher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for new Meshtastic, MeshCore and RNode firmware, and downloads assets the user picks. */
object UpdateChecker {
    data class Asset(val name: String, val url: String, val sizeBytes: Long)
    data class Release(val project: String, val id: Long, val tag: String, val name: String, val prerelease: Boolean, val publishedAt: String,
                       val notes: String, val assets: List<Asset>)

    val PROJECTS = mapOf(
        "Meshtastic" to "meshtastic/firmware",
        "MeshCore" to "meshcore-dev/MeshCore",
        "RNode" to "markqvist/RNode_Firmware",
    )

    /** Each project's own web flasher, for boards or files the list here can't handle. */
    val WEB_FLASHERS = mapOf(
        "Meshtastic" to "https://flasher.meshtastic.org",
        "MeshCore" to "https://flasher.meshcore.io",
    )

    /** Files a person might flash: firmware images, not checksums, manifests or debug symbols. */
    fun isFlashable(name: String): Boolean {
        val n = name.lowercase()
        return (n.endsWith(".zip") || n.endsWith(".bin") || n.endsWith(".uf2") || n.endsWith(".hex")) &&
            !n.contains("debug") && !n.endsWith(".elf") && !n.contains("littlefs") && !n.contains("-ota")
    }

    /**
     * File-name fragment for the connected board: "Meshtastic 2.7.15 · HELTEC_V4" → "heltec-v4",
     * "MeshCore 1.9 · Heltec V3" → "heltec_v3". Used to pre-fill the file search.
     */
    fun boardHint(deviceInfo: String?): String = deviceInfo?.substringAfter("· ", "")?.trim().orEmpty().let { hw ->
        when {
            hw.isEmpty() -> ""
            deviceInfo!!.startsWith("Meshtastic") -> hw.lowercase().replace('_', '-')
            else -> hw.lowercase().replace(' ', '_')
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Highbridge-Android")
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        when (val code = c.responseCode) {
            200 -> {}
            403, 429 -> error("GitHub's hourly limit for checks from this network is used up. Try again in an hour, or use the web flasher.")
            else -> error("GitHub answered HTTP $code")
        }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parseAssets(a: JSONArray) = (0 until a.length()).map { j ->
        a.getJSONObject(j).let { Asset(it.getString("name"), it.getString("browser_download_url"), it.optLong("size")) }
    }

    /**
     * Newest releases. Meshtastic marks most builds as pre-releases (alpha) and only some as beta,
     * so we look through the last 40 and keep [count] of the kind asked for.
     */
    suspend fun releases(project: String, includePre: Boolean = false, count: Int = 6): List<Release> = withContext(Dispatchers.IO) {
        val repo = PROJECTS[project] ?: error("Unknown project")
        val arr = JSONArray(get("https://api.github.com/repos/$repo/releases?per_page=40"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Release(project, o.getLong("id"), o.getString("tag_name"), o.optString("name"), o.optBoolean("prerelease"), o.optString("published_at"),
                o.optString("body").take(2000), parseAssets(o.optJSONArray("assets") ?: JSONArray()))
        }.filter { includePre || !it.prerelease }.take(count)
    }

    /** Every file attached to a release (Meshtastic attaches hundreds, more than the list call returns). */
    suspend fun allAssets(project: String, releaseId: Long): List<Asset> = withContext(Dispatchers.IO) {
        val repo = PROJECTS[project] ?: error("Unknown project")
        buildList {
            for (page in 1..10) {
                val a = JSONArray(get("https://api.github.com/repos/$repo/releases/$releaseId/assets?per_page=100&page=$page"))
                addAll(parseAssets(a))
                if (a.length() < 100) break
            }
        }
    }

    /** True if [latestTag] is newer than [installed] (compares the numbers in "2.7.11.abc" style versions). */
    fun isNewer(latestTag: String, installed: String?): Boolean {
        if (installed.isNullOrBlank()) return true
        fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toInt() }.take(3).toList()
        val a = nums(latestTag); val b = nums(installed)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    suspend fun download(asset: Asset, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val out = File(dir, asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        var url = URL(asset.url)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Highbridge-Android")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); continue }
            check(code == 200) { "Download failed (HTTP $code)" }
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.sizeBytes
        c.inputStream.use { input -> out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024); var done = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; onProgress(done / total.toFloat()) }
        } }
        out
    }
}
