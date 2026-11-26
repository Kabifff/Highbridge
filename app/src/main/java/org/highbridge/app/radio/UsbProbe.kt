// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.radio

import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.link.UsbSerialLink
import org.highbridge.protocol.meshcore.MC
import org.highbridge.protocol.meshtastic.MeshtasticSerialLink
import org.highbridge.protocol.reticulum.Kiss
import org.meshtastic.proto.ToRadio

/**
 * Works out which firmware a USB radio runs by asking politely in each protocol:
 * RNode (KISS detect), then MeshCore (APP_START), then Meshtastic (want_config).
 * Each question is harmless to the other firmwares.
 */
object UsbProbe {
    suspend fun detect(usb: UsbManager, driver: UsbSerialDriver): ProtocolType? {
        val link = UsbSerialLink(usb, driver)
        link.open().getOrThrow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val seen = java.io.ByteArrayOutputStream()
            scope.launch { link.incoming.collect { synchronized(seen) { seen.write(it) } } }
            suspend fun ask(bytes: ByteArray, waitMs: Long, test: (ByteArray) -> Boolean): Boolean {
                synchronized(seen) { seen.reset() }
                link.write(bytes)
                val done = CompletableDeferred<Boolean>()
                val poll = scope.launch {
                    while (!done.isCompleted) { if (test(synchronized(seen) { seen.toByteArray() })) done.complete(true); delay(50) }
                }
                val ok = withTimeoutOrNull(waitMs) { done.await() } ?: false
                poll.cancel()
                return ok
            }
            delay(300)
            if (ask(Kiss.frame(Kiss.CMD_DETECT, byteArrayOf(Kiss.DETECT_REQ.toByte())), 1_500) { b -> contains(b, byteArrayOf(Kiss.FEND.toByte(), Kiss.CMD_DETECT.toByte(), Kiss.DETECT_RESP.toByte())) })
                return ProtocolType.RETICULUM
            if (ask(MC.serialFrame(MC.appStart()), 1_500) { b -> b.indexOf('>'.code.toByte()).let { it >= 0 && it + 3 < b.size && b[it + 3].toInt() == MC.RESP_SELF_INFO } })
                return ProtocolType.MESHCORE
            val wake = ByteArray(32) { 0xC3.toByte() }
            if (ask(wake + MeshtasticSerialLink.frame(ToRadio(want_config_id = 0x48420001).encode()), 3_000) { b -> contains(b, byteArrayOf(0x94.toByte(), 0xC3.toByte())) })
                return ProtocolType.MESHTASTIC
            return null
        } finally {
            scope.cancel(); link.close(); delay(300)
        }
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..hay.size - needle.size) { for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer; return true }
        return false
    }
}
