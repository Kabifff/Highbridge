// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.wear

import org.highbridge.app.HighbridgeApp

/** Builds without Google Play services (F-Droid, GitHub) have no watch link. */
object WearLink {
    fun start(app: HighbridgeApp) = Unit
}
