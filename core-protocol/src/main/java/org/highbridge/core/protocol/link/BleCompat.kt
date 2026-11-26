// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.protocol.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.os.Build

/** BLE write that works on Android 8–12 and 13+. (No changes needed; this one was correct.) */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun BluetoothGatt.writeCharacteristicCompat(
    char: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    writeCharacteristic(char, value, writeType) == BluetoothStatusCodes.SUCCESS
} else {
    char.writeType = writeType
    char.value = value
    writeCharacteristic(char)
}
