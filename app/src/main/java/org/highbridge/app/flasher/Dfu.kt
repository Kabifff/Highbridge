// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.flasher

import android.app.Activity
import android.content.Context
import android.net.Uri
import no.nordicsemi.android.dfu.DfuBaseService
import no.nordicsemi.android.dfu.DfuServiceInitiator
import org.highbridge.app.MainActivity

/** Nordic Bluetooth DFU for nRF52 radios (RAK4631, T-Echo, T114) using a firmware .zip. */
class HighbridgeDfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java
    override fun isDebug(): Boolean = false
}

object BleDfu {
    fun start(ctx: Context, address: String, name: String?, zip: Uri) {
        DfuServiceInitiator.createDfuNotificationChannel(ctx)
        DfuServiceInitiator(address)
            .setDeviceName(name)
            .setKeepBond(false)
            .setForeground(true)
            .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
            .setPrepareDataObjectDelay(300L)
            .setZip(zip)
            .start(ctx, HighbridgeDfuService::class.java)
    }
}
