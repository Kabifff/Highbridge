// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app

import android.app.Application
import org.highbridge.app.data.AppDatabase
import org.highbridge.app.data.Prefs
import org.highbridge.app.location.LocationScheduler
import org.highbridge.app.location.PowerProfileManager
import org.highbridge.app.radio.RadioManager
import org.osmdroid.config.Configuration
import java.io.File

class HighbridgeApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var prefs: Prefs; private set
    lateinit var radios: RadioManager; private set
    lateinit var location: LocationScheduler; private set
    lateinit var power: PowerProfileManager; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.build(this)
        prefs = Prefs(this)
        radios = RadioManager(this, db.dao(), prefs)
        location = LocationScheduler(this, prefs, radios)
        power = PowerProfileManager(this, prefs).also { it.start() }
        Configuration.getInstance().apply {
            userAgentValue = "Highbridge/${BuildConfig.VERSION_NAME} (Android)"
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "tiles")
        }
    }

    /** Duress PIN: erase messages, nodes, keys, settings and media, then close the app. It reopens looking new. */
    fun wipeEverything() {
        runCatching { db.close() }
        deleteDatabase(AppDatabase.NAME)
        prefs.wipe()
        File(filesDir, "media").deleteRecursively()
        File(filesDir, "reticulum").deleteRecursively()
        getSharedPreferences("bridge", MODE_PRIVATE).edit().clear().commit()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object { lateinit var instance: HighbridgeApp; private set }
}
