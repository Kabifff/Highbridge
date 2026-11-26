// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.rns

import android.content.Context
import org.highbridge.protocol.reticulum.RnsEngine
import org.highbridge.rns.ChaquopyRnsEngine
import org.highbridge.rns.RnsInstaller

/** Full edition (GitHub releases): Reticulum through Python, downloaded after the user agrees to its license. */
object ReticulumSupport {
    const val AVAILABLE = true
    const val DOWNLOAD_KB = RnsInstaller.DOWNLOAD_KB
    const val LICENSE_URL = RnsInstaller.LICENSE_URL
    fun isInstalled(ctx: Context) = RnsInstaller.isInstalled(ctx)
    suspend fun install(ctx: Context) { RnsInstaller.install(ctx) }
    fun engine(ctx: Context): RnsEngine = ChaquopyRnsEngine(ctx)
}
