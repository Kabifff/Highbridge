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
    data class Release(val project: String, val tag: String, val name: String, val prerelease: Boolean, val publishedAt: String, val notes: String, val assets: List<Asset>)

    val PROJECTS = mapOf(
        "Meshtastic" to "meshtastic/firmware",
        "MeshCore" to "meshcore-dev/MeshCore",
        "RNode" to "markqvist/RNode_Firmware",
    )

    suspend fun releases(project: String, includePre: Boolean = false, count: Int = 5): List<Release> = withContext(Dispatchers.IO) {
        val repo = PROJECTS[project] ?: error("Unknown project")
        val c = URL("https://api.github.com/repos/$repo/releases?per_page=$count").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Highbridge-Android")
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        val body = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val assets = o.getJSONArray("assets").let { a -> (0 until a.length()).map { j -> a.getJSONObject(j).let { Asset(it.getString("name"), it.getString("browser_download_url"), it.getLong("size")) } } }
            Release(project, o.getString("tag_name"), o.optString("name"), o.getBoolean("prerelease"), o.optString("published_at"), o.optString("body").take(2000), assets)
        }.filter { includePre || !it.prerelease }
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
