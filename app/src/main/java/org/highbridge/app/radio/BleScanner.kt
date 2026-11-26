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
            val item = FoundRadio(r.device, name, r.rssi, guess)
            _found.value = (_found.value.filter { it.device.address != r.device.address } + item).sortedByDescending { it.rssi }
        }
    }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val s = adapter?.bluetoothLeScanner ?: return
        _found.value = emptyList()
        s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scanning.value = true
    }

    fun stop() { runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }; scanning.value = false }
}
