// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.protocol.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Coroutine wrapper around BluetoothGatt. Android allows only one GATT operation in flight,
 * so every operation takes [opLock] and waits for its callback before the next one starts.
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, private val device: BluetoothDevice) {
    data class Notification(val uuid: UUID, val value: ByteArray)

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected
    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)
    val notifications: SharedFlow<Notification> = _notifications
    var mtu: Int = 23; private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                complete(true)
            } else {
                _connected.value = false
                pending?.completeExceptionally(IllegalStateException("Bluetooth disconnected (status $status)"))
                if (newState == BluetoothProfile.STATE_DISCONNECTED) runCatching { g.close() }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = completeStatus(status, true)
        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu; complete(newMtu) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = completeStatus(status, true)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = completeStatus(status, true)
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = completeStatus(status, value)
        @Deprecated("Pre-Android 13 path")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") completeStatus(status, c.value ?: ByteArray(0))
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(Notification(c.uuid, value))
        }
        @Deprecated("Pre-Android 13 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") _notifications.tryEmit(Notification(c.uuid, c.value?.copyOf() ?: ByteArray(0)))
        }
    }

    private fun complete(v: Any?) { pending?.complete(v) }
    private fun completeStatus(status: Int, v: Any?) {
        if (status == BluetoothGatt.GATT_SUCCESS) pending?.complete(v)
        else pending?.completeExceptionally(IllegalStateException("GATT error $status"))
    }

    private suspend fun <T> op(timeoutMs: Long = 10_000, start: (BluetoothGatt?) -> Boolean): T = opLock.withLock {
        val d = CompletableDeferred<Any?>()
        pending = d
        try {
            check(start(gatt)) { "Bluetooth operation could not start" }
            @Suppress("UNCHECKED_CAST")
            withTimeout(timeoutMs) { d.await() } as T
        } finally { pending = null }
    }

    suspend fun connect(requestMtu: Int = 512) {
        op<Any?>(20_000) {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        op<Any?>(15_000) { it!!.discoverServices() }
        runCatching { op<Int>(5_000) { it!!.requestMtu(requestMtu) } }
    }

    /**
     * Meshtastic and MeshCore radios use a Bluetooth PIN (often 123456 or shown on the screen).
     * Pair first so Android shows its PIN dialog, instead of failing the first encrypted read.
     */
    suspend fun ensureBonded(timeoutMs: Long = 60_000) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        if (device.bondState == BluetoothDevice.BOND_NONE) device.createBond()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> return
                BluetoothDevice.BOND_NONE -> if (System.currentTimeMillis() > end - timeoutMs + 3_000) error("Pairing was cancelled or the PIN was wrong")
            }
            kotlinx.coroutines.delay(250)
        }
        error("Pairing timed out")
    }

    fun hasService(uuid: UUID) = gatt?.getService(uuid) != null

    private fun char(service: UUID, uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(uuid) ?: error("Characteristic $uuid not found")

    suspend fun enableNotifications(service: UUID, uuid: UUID) {
        val c = char(service, uuid)
        check(gatt!!.setCharacteristicNotification(c, true))
        val cccd = c.getDescriptor(CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Any?> { g ->
            if (Build.VERSION.SDK_INT >= 33) g!!.writeDescriptor(cccd, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") run { cccd.value = value; g!!.writeDescriptor(cccd) }
        }
    }

    suspend fun write(service: UUID, uuid: UUID, data: ByteArray, withResponse: Boolean = true) {
        val c = char(service, uuid)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op<Any?> { g -> g!!.writeCharacteristicCompat(c, data, type) }
    }

    suspend fun read(service: UUID, uuid: UUID): ByteArray {
        val c = char(service, uuid)
        return op { g -> g!!.readCharacteristic(c) }
    }

    fun setConnectionPriority(priority: Int) { gatt?.requestConnectionPriority(priority) }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        _connected.value = false
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
