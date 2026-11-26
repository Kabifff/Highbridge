// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.rns

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads the Reticulum (RNS) and LXMF Python packages from PyPI the first time the user turns
 * Reticulum on.
 *
 * Why they aren't bundled: both use the "Reticulum License", which adds use restrictions (no
 * systems meant to harm people, no AI training data). The GPL forbids adding restrictions, so
 * shipping them inside Highbridge's GPL-3.0 app would break one license or the other. The user
 * fetches them on their own device instead, after reading their license.
 *
 * The files are pinned by SHA-256 so a tampered download is refused.
 */
object RnsInstaller {
    data class Pkg(val name: String, val version: String, val url: String, val sha256: String)

    val PACKAGES = listOf(
        Pkg("rns", "1.6.1", "https://files.pythonhosted.org/packages/b1/df/c8ad51a9b2b7af893290fa06b8de272507800642808120fb8d5c3e4c3fa2/rns-1.6.1-0-py3-none-any.whl",
            "f276cb7d818df5cfd6ecc59a8a4f3344e47d46acdfdb48e75537400309547959"),
        Pkg("lxmf", "1.3.1", "https://files.pythonhosted.org/packages/3f/09/c16d278a4e30f6af867d04ad0d6c501dcec3d9762f5d60cc44b3b6c3c22a/lxmf-1.3.1-py3-none-any.whl",
            "42795725445177c78522154c234bc0aabcdb93b7f85883a27921a37ec86d9416"),
    )
    const val LICENSE_URL = "https://github.com/markqvist/Reticulum/blob/master/LICENSE"
    const val DOWNLOAD_KB = 770

    fun dir(ctx: Context) = File(ctx.filesDir, "python-ext")
    private fun marker(ctx: Context) = File(dir(ctx), ".installed")
    private val expected = PACKAGES.joinToString(",") { "${it.name}==${it.version}" }

    fun isInstalled(ctx: Context): Boolean = marker(ctx).takeIf { it.exists() }?.readText() == expected

    suspend fun install(ctx: Context, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val target = dir(ctx)
        val tmp = File(ctx.cacheDir, "python-ext-tmp").apply { deleteRecursively(); mkdirs() }
        PACKAGES.forEachIndexed { i, p ->
            val bytes = fetch(p.url)
            val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(got == p.sha256) { "${p.name} download didn't match its expected checksum; not installed" }
            unzip(bytes, tmp)
            onProgress((i + 1f) / PACKAGES.size)
        }
        target.deleteRecursively()
        check(tmp.renameTo(target)) { "Couldn't move the packages into place" }
        marker(ctx).writeText(expected)
    }

    fun uninstall(ctx: Context) { dir(ctx).deleteRecursively() }

    private fun fetch(url: String): ByteArray {
        var u = URL(url); var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 20_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Highbridge-Android")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { u = URL(u, c.getHeaderField("Location")); continue }
            check(code == 200) { "Download failed (HTTP $code)" }
            return c.inputStream.use { it.readBytes() }
        }
    }

    private fun unzip(bytes: ByteArray, into: File) {
        val root = into.canonicalPath + File.separator
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(into, e.name)
                check(out.canonicalPath.startsWith(root)) { "Bad path in package" }
                if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
    }
}
