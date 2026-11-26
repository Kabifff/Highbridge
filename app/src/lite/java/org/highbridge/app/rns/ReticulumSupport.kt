// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.rns

import android.content.Context
import org.highbridge.protocol.reticulum.RnsEngine

/**
 * Store edition (Google Play, F-Droid): Meshtastic and MeshCore only. Reticulum needs a Python
 * runtime plus code downloaded at run time, which app stores don't allow, so it is left out.
 */
object ReticulumSupport {
    const val AVAILABLE = false
    const val DOWNLOAD_KB = 0
    const val LICENSE_URL = "https://github.com/markqvist/Reticulum/blob/master/LICENSE"
    fun isInstalled(ctx: Context) = false
    suspend fun install(ctx: Context): Unit = error(UNAVAILABLE)
    fun engine(ctx: Context): RnsEngine = error(UNAVAILABLE)
    private const val UNAVAILABLE = "This edition of Highbridge doesn't include Reticulum."
}
