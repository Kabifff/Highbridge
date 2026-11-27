// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.link.BleNusStream
import org.highbridge.protocol.meshtastic.MeshtasticBleLink

data class FoundRadio(val device: BluetoothDevice, val name: String, val rssi: Int, val guess: ProtocolType?)

/** Finds nearby Meshtastic, MeshCore and RNode radios by their advertised services and names. */
@SuppressLint("MissingPermission")
class BleScanner(ctx: Context) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val _found = MutableStateFlow<List<FoundRadio>>(emptyList())
    val found: StateFlow<List<FoundRadio>> = _found
    val scanning = MutableStateFlow(false)

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val uuids = r.scanRecord?.serviceUuids.orEmpty()
            val name = r.scanRecord?.deviceName ?: r.device.name ?: return
            val guess = when {
                ParcelUuid(MeshtasticBleLink.SERVICE) in uuids -> ProtocolType.MESHTASTIC
                name.contains("rnode", true) -> ProtocolType.RETICULUM
                ParcelUuid(BleNusStream.SERVICE) in uuids || name.startsWith("MeshCore", true) -> ProtocolType.MESHCORE
                else -> null
            }
            if (guess == null && !name.contains("mesh", true)) return
            update(r.device, name, r.rssi, guess)
        }
    }

    private val smoothed = HashMap<String, Double>()
    private val shownAt = HashMap<String, Long>()

    /**
     * Radios keep the order they were first found in, so rows never jump around while you're
     * trying to tap one. Signal is smoothed and the row refreshes at most once a second.
     */
    @Synchronized
    private fun update(device: BluetoothDevice, name: String, rssi: Int, guess: ProtocolType?) {
        val addr = device.address
        val avg = smoothed[addr]?.let { it * 0.7 + rssi * 0.3 } ?: rssi.toDouble()
        smoothed[addr] = avg
        val list = _found.value
        val i = list.indexOfFirst { it.device.address == addr }
        val now = android.os.SystemClock.elapsedRealtime()
        if (i >= 0 && now - (shownAt[addr] ?: 0L) < 1_000 && list[i].name == name) return
        shownAt[addr] = now
        val item = FoundRadio(device, name, avg.toInt(), guess ?: list.getOrNull(i)?.guess)
        _found.value = if (i >= 0) list.toMutableList().also { it[i] = item } else list + item
    }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val s = adapter?.bluetoothLeScanner ?: return
        synchronized(this) { _found.value = emptyList(); smoothed.clear(); shownAt.clear() }
        s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scanning.value = true
    }

    fun stop() { runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }; scanning.value = false }
}
