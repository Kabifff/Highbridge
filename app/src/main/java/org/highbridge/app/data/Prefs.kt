// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.highbridge.core.protocol.LocationUpdateMode
import org.highbridge.core.protocol.MeshtasticRegion
import org.highbridge.core.protocol.PowerProfile

/** Small settings store with observable values. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("highbridge", Context.MODE_PRIVATE)
    private fun <T> state(v: T) = MutableStateFlow(v)

    val onboarded = state(sp.getBoolean("onboarded", false))
    val displayName = state(sp.getString("displayName", "") ?: "")
    val region = state(MeshtasticRegion.fromCode(sp.getInt("region", 0)) ?: MeshtasticRegion.UNSET)
    val locationMode = state(runCatching { LocationUpdateMode.valueOf(sp.getString("locationMode", "FAST")!!) }.getOrDefault(LocationUpdateMode.FAST))
    val shareLocation = state(sp.getBoolean("shareLocation", false))
    val powerProfile = state(runCatching { PowerProfile.valueOf(sp.getString("power", "BALANCED")!!) }.getOrDefault(PowerProfile.BALANCED))
    val autoLowBattery = state(sp.getBoolean("autoLowBattery", true))
    val mapStyle = state(sp.getString("mapStyle", "STANDARD") ?: "STANDARD")
    val staleMinutes = state(sp.getInt("staleMinutes", 120))
    val atakEnabled = state(sp.getBoolean("atak", false))
    val metric = state(sp.getBoolean("metric", false))
    val rnsAuto = state(sp.getBoolean("rnsAuto", true))
    val rnsTcpHosts = state(sp.getString("rnsTcp", "") ?: "")
    val lastDevices = state(sp.getStringSet("lastDevices", emptySet())!!.toSet())
    val pinRecord = state(sp.getString("pin", null))
    val duressRecord = state(sp.getString("duress", null))
    val darkMode = state(sp.getString("dark", "SYSTEM") ?: "SYSTEM")
    /** Wardriving: share coverage with the stpaulmesh.com map; ping actively; skip a circle around home. */
    val wardriveUpload = state(sp.getBoolean("wdUpload", true))
    val wardrivePing = state(sp.getBoolean("wdPing", true))
    val wardriveHome = state(sp.getString("wdHome", "") ?: "")
    val wardriveHideM = state(sp.getInt("wdHideM", 400))
    /** Asked once, at the first Start, whether to share coverage. Until then nothing is uploaded. */
    val wardriveAsked = state(sp.getBoolean("wdAsked", false))
    /** MeshCore region scope applied on every connect ("" = unscoped). */
    val mcRegion = state(sp.getString("mcRegion", "") ?: "")
    /** Per-conversation notifications: "ALL", "MENTIONS" or "NONE", stored as conv=level lines. */
    val notifyLevels = state(sp.getString("notifyLevels", "") ?: "")

    private fun save(block: SharedPreferences.Editor.() -> Unit) = sp.edit().apply(block).apply()

    fun setOnboarded(v: Boolean) { onboarded.value = v; save { putBoolean("onboarded", v) } }
    fun setDisplayName(v: String) { displayName.value = v; save { putString("displayName", v) } }
    fun setRegion(v: MeshtasticRegion) { region.value = v; save { putInt("region", v.code) } }
    fun setLocationMode(v: LocationUpdateMode) { locationMode.value = v; save { putString("locationMode", v.name) } }
    fun setShareLocation(v: Boolean) { shareLocation.value = v; save { putBoolean("shareLocation", v) } }
    fun setPowerProfile(v: PowerProfile) { powerProfile.value = v; save { putString("power", v.name) } }
    fun setAutoLowBattery(v: Boolean) { autoLowBattery.value = v; save { putBoolean("autoLowBattery", v) } }
    fun setMapStyle(v: String) { mapStyle.value = v; save { putString("mapStyle", v) } }
    fun setStaleMinutes(v: Int) { staleMinutes.value = v; save { putInt("staleMinutes", v) } }
    fun setAtak(v: Boolean) { atakEnabled.value = v; save { putBoolean("atak", v) } }
    fun setMetric(v: Boolean) { metric.value = v; save { putBoolean("metric", v) } }
    fun setRnsAuto(v: Boolean) { rnsAuto.value = v; save { putBoolean("rnsAuto", v) } }
    fun setRnsTcpHosts(v: String) { rnsTcpHosts.value = v; save { putString("rnsTcp", v) } }
    fun setDarkMode(v: String) { darkMode.value = v; save { putString("dark", v) } }
    fun setWardriveUpload(v: Boolean) { wardriveUpload.value = v; save { putBoolean("wdUpload", v) } }
    fun setWardrivePing(v: Boolean) { wardrivePing.value = v; save { putBoolean("wdPing", v) } }
    fun setWardriveHome(v: String) { wardriveHome.value = v; save { putString("wdHome", v) } }
    fun setWardriveHideM(v: Int) { wardriveHideM.value = v; save { putInt("wdHideM", v) } }
    fun setWardriveAsked(v: Boolean) { wardriveAsked.value = v; save { putBoolean("wdAsked", v) } }
    fun setMcRegion(v: String) { mcRegion.value = v; save { putString("mcRegion", v) } }
    fun notifyLevel(conv: String): String = notifyLevels.value.lineSequence().firstOrNull { it.startsWith("$conv=") }?.substringAfter('=') ?: "ALL"
    fun setNotifyLevel(conv: String, level: String) {
        val v = (notifyLevels.value.lineSequence().filter { it.isNotBlank() && !it.startsWith("$conv=") } + if (level == "ALL") emptySequence() else sequenceOf("$conv=$level")).joinToString("\n")
        notifyLevels.value = v; save { putString("notifyLevels", v) }
    }
    /** "BLE|MESHTASTIC|AA:BB:..." or "USB|AUTO|vid:pid", used to reconnect after a reboot. */
    fun rememberDevice(entry: String) {
        val kind = entry.substringBefore('|') + "|" + entry.split('|').getOrNull(1)
        val set = lastDevices.value.filterNot { it.startsWith(kind) }.toSet() + entry
        lastDevices.value = set; save { putStringSet("lastDevices", set) }
    }
    fun forgetDevices() { lastDevices.value = emptySet(); save { remove("lastDevices") } }
    fun setPin(record: String?) { pinRecord.value = record; save { if (record == null) remove("pin") else putString("pin", record) } }
    fun setDuress(record: String?) { duressRecord.value = record; save { if (record == null) remove("duress") else putString("duress", record) } }
    fun wipe() { sp.edit().clear().commit() }
}
