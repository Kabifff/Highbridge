// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.flasher

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Writes firmware to ESP32 boards through the chip's ROM bootloader, the same SLIP protocol
 * esptool uses (no stub). Works with USB-UART bridges (CP210x, CH340) and native USB (ESP32-S3).
 */
class EspRomFlasher(private val port: UsbSerialPort, private val nativeUsb: Boolean, private val log: (String) -> Unit) {
    private val rx = ByteArrayOutputStream()
    private var chip = "ESP32"
    private var statusLen = 4

    private fun slip(data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); o.write(0xC0)
        for (b in data) when (b.toInt() and 0xFF) { 0xC0 -> { o.write(0xDB); o.write(0xDC) }; 0xDB -> { o.write(0xDB); o.write(0xDD) }; else -> o.write(b.toInt()) }
        o.write(0xC0); return o.toByteArray()
    }

    private fun readPacket(timeoutMs: Long): ByteArray? {
        val end = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1024)
        val pkt = ByteArrayOutputStream(); var inPkt = false; var esc = false
        while (System.currentTimeMillis() < end) {
            val n = runCatching { port.read(buf, 100) }.getOrDefault(0)
            for (i in 0 until n) {
                val v = buf[i].toInt() and 0xFF
                if (v == 0xC0) { if (inPkt && pkt.size() > 0) return pkt.toByteArray(); inPkt = true; pkt.reset(); continue }
                if (!inPkt) continue
                if (esc) { pkt.write(if (v == 0xDC) 0xC0 else if (v == 0xDD) 0xDB else v); esc = false }
                else if (v == 0xDB) esc = true else pkt.write(v)
            }
        }
        return null
    }

    private fun command(op: Int, data: ByteArray, checksum: Int = 0, timeoutMs: Long = 3_000): Pair<Int, ByteArray> {
        val b = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0).put(op.toByte()).putShort(data.size.toShort()).putInt(checksum).put(data)
        port.write(slip(b.array()), 2_000)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val p = readPacket(end - System.currentTimeMillis()) ?: break
            if (p.size < 8 || p[0].toInt() != 1 || (p[1].toInt() and 0xFF) != op) continue
            val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            val value = bb.getInt(4)
            val body = p.copyOfRange(8, p.size)
            if (body.size >= statusLen && body[body.size - statusLen].toInt() != 0)
                error("Bootloader error 0x%02x on command 0x%02x".format(body[body.size - statusLen + 1], op))
            return value to body
        }
        error("No reply from bootloader (command 0x%02x)".format(op))
    }

    private fun le(vararg v: Int): ByteArray = ByteBuffer.allocate(4 * v.size).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putInt(it) } }.array()

    /** Pulses EN / IO0 through RTS / DTR to restart into the bootloader. */
    private suspend fun enterBootloader() {
        if (nativeUsb) {
            port.rts = false; port.dtr = false; delay(100)
            port.dtr = true; port.rts = false; delay(100)
            port.rts = true; port.dtr = false; port.rts = true; delay(100)
            port.dtr = false; port.rts = false
        } else {
            port.dtr = false; port.rts = true; delay(100)
            port.dtr = true; port.rts = false; delay(50)
            port.dtr = false
        }
        delay(300)
        runCatching { port.purgeHwBuffers(true, true) }
    }

    suspend fun connect(): String = withContext(Dispatchers.IO) {
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        repeat(4) { attempt ->
            enterBootloader()
            val sync = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
            repeat(6) {
                if (runCatching { command(0x08, sync, timeoutMs = 300) }.isSuccess) {
                    repeat(7) { readPacket(50) } // drain the extra sync replies
                    val magic = runCatching { command(0x0A, le(0x40001000)).first }.getOrDefault(0)
                    chip = when (magic) {
                        0x00f01d83 -> "ESP32"; 0x000007c6 -> "ESP32-S2"; 0x9 -> "ESP32-S3"
                        0x6921506f, 0x1b31506f, 0x4881606F, 0x4361606f -> "ESP32-C3"; 0x2CE0806F -> "ESP32-C6"
                        else -> "ESP32 family (0x%08x)".format(magic)
                    }
                    log("Connected to $chip ROM bootloader")
                    command(0x0D, ByteArray(8))                                                  // SPI_ATTACH
                    command(0x0B, le(0, 16 * 1024 * 1024, 0x10000, 0x1000, 0x100, 0xFFFF))       // SPI_SET_PARAMS
                    return@withContext chip
                }
            }
            log("Sync attempt ${attempt + 1} failed, retrying…")
        }
        error("Couldn't reach the ESP32 bootloader. Hold BOOT while plugging in, then try again.")
    }

    /** Faster transfers after connecting (ROM CHANGE_BAUDRATE). Skipped for native USB, where baud doesn't matter. */
    suspend fun speedUp(baud: Int = 460_800) = withContext(Dispatchers.IO) {
        if (nativeUsb) return@withContext
        command(0x0F, le(baud, 0)); delay(50)
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); delay(50)
        log("Switched to $baud baud")
    }

    suspend fun flash(image: ByteArray, offset: Int, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val block = 0x400
        val blocks = (image.size + block - 1) / block
        val begin = if (chip == "ESP32") le(image.size, blocks, block, offset) else le(image.size, blocks, block, offset, 0)
        log("Erasing ${image.size / 1024} KB at 0x%x…".format(offset))
        command(0x02, begin, timeoutMs = 10_000L + image.size / 40)
        for (seq in 0 until blocks) {
            val chunk = image.copyOfRange(seq * block, minOf(image.size, (seq + 1) * block)).let { if (it.size < block) it + ByteArray(block - it.size) { 0xFF.toByte() } else it }
            var cs = 0xEF; chunk.forEach { cs = cs xor (it.toInt() and 0xFF) }
            var tries = 0
            while (true) {
                val ok = runCatching { command(0x03, le(chunk.size, seq, 0, 0) + chunk, cs, timeoutMs = 5_000) }.isSuccess
                if (ok) break
                if (++tries >= 3) error("Write failed at block $seq")
            }
            onProgress((seq + 1) / blocks.toFloat())
        }
        log("Verifying…")
        val (_, md5Body) = command(0x13, le(offset, image.size, 0, 0), timeoutMs = 15_000L + image.size / 20)
        val got = String(md5Body.copyOfRange(0, minOf(32, md5Body.size))).lowercase()
        val want = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
        if (got.length == 32 && got != want) error("Verify failed: flash doesn't match the file")
        log("Verified. Restarting the radio…")
        runCatching { command(0x04, le(0), timeoutMs = 1_000) }
        port.rts = true; delay(100); port.rts = false
    }

    companion object {
        /** Meshtastic and MeshCore "factory"/"merged" images start at 0; app-only updates at 0x10000. */
        fun offsetFor(fileName: String): Int =
            if (fileName.contains("factory", true) || fileName.contains("merged", true)) 0 else 0x10000
    }
}
