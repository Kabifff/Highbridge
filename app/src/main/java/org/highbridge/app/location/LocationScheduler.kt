// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.highbridge.app.data.Prefs
import org.highbridge.app.radio.RadioManager
import org.highbridge.core.protocol.PowerProfile

/**
 * GPS without Google Play Services: Fast (35 s, recommended) or Battery Saving (5 min), with the
 * distance filter from the power profile. Uses Android's own fused provider on Android 12+.
 */
class LocationScheduler(private val ctx: Context, private val prefs: Prefs, private val radios: RadioManager) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private val listener = LocationListener { l: Location -> radios.onLocation(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else null) }

    private var started = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(prefs.locationMode, prefs.powerProfile) { m, p -> m to p }.collect { (mode, profile) ->
                stopUpdates()
                val provider = when {
                    Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    lm.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> LocationManager.NETWORK_PROVIDER
                }
                val interval = if (profile == PowerProfile.ULTRA_LOW_POWER) maxOf(mode.intervalMs, profile.locationMode.intervalMs) else mode.intervalMs
                runCatching {
                    lm.requestLocationUpdates(provider, interval, profile.minDistanceMeters, listener, Looper.getMainLooper())
                    lm.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
                    running = true
                }
            }
        }
    }

    private fun stopUpdates() { if (running) runCatching { lm.removeUpdates(listener) }; running = false }
}

/** Switches to Ultra-Low Power below 20% battery (if allowed) and back once charging or above 30%. */
class PowerProfileManager(private val ctx: Context, private val prefs: Prefs) {
    val batteryPct = MutableStateFlow<Int?>(null)
    private var previous: PowerProfile? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val charging = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
            if (level < 0) return
            val pct = level * 100 / scale
            batteryPct.value = pct
            if (!prefs.autoLowBattery.value) return
            if (pct < 20 && !charging && prefs.powerProfile.value != PowerProfile.ULTRA_LOW_POWER) {
                previous = prefs.powerProfile.value; prefs.setPowerProfile(PowerProfile.ULTRA_LOW_POWER)
            } else if ((charging || pct > 30) && previous != null) {
                prefs.setPowerProfile(previous!!); previous = null
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var started = false

    fun start() {
        if (started) return
        started = true
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Bluetooth link speed follows the power profile.
        scope.launch { prefs.powerProfile.collect { org.highbridge.core.protocol.link.GattClient.setPriorityForAll(it.blePriority.gattValue) } }
    }
}
