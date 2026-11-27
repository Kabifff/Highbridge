// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.flasher

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.highbridge.core.protocol.link.GattClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

/**
 * Meshtastic's ESP32 "unified OTA": the radio reboots into its OTA loader (after an admin ota_request
 * carrying the image's SHA-256), then takes the app image over Wi-Fi (TCP 3232) or Bluetooth.
 * Text commands end in a newline: "VERSION" → "OK …"; "OTA <size> <sha256>" → "ERASING" … "OK";
 * then the raw image in chunks, each answered with "ACK"; finally "OK" once the hash checks out.
 * Needs firmware 2.7.18 or later with the OTA loader installed (the web flasher's Update does that).
 * Use the board's app image (firmware-<board>-<version>.bin), not the -factory or -ota files.
 */
object Esp32Ota {
    const val TCP_PORT = 3232
    val SERVICE: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val WRITE: UUID = UUID.fromString("62ec0272-3ec5-11eb-b378-0242ac130005")
    val NOTIFY: UUID = UUID.fromString("62ec0272-3ec5-11eb-b378-0242ac130003")

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun checkImage(name: String, image: ByteArray) {
        val n = name.lowercase()
        require(n.endsWith(".bin")) { "Choose the board's .bin app image" }
        require(!n.contains("factory") && !n.contains("-ota") && !n.contains("littlefs")) { "Use firmware-<board>-<version>.bin, not the factory, OTA-loader or filesystem image" }
        require(image.size > 100_000 && image[0] == 0xE9.toByte()) { "That file isn't an ESP32 app image" }
    }

    /** Over Wi-Fi: [host] is the radio's address on your network. */
    suspend fun updateWifi(host: String, image: ByteArray, log: (String) -> Unit, progress: (Float) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            log("Waiting for the OTA loader on $host…")
            val s = withTimeoutOrNull(60_000) {
                var sock: Socket? = null
                while (sock == null) { sock = runCatching { Socket().apply { connect(InetSocketAddress(host, TCP_PORT), 3_000) } }.getOrNull(); if (sock == null) delay(2_000) }
                sock
            } ?: error("The radio didn't open its update port. Is it on firmware 2.7.18 or newer with the OTA loader?")
            s.use { sock ->
                sock.soTimeout = 180_000
                val inp = BufferedReader(InputStreamReader(sock.getInputStream()))
                val out = sock.getOutputStream()
                fun send(line: String) { out.write((line + "\n").toByteArray()); out.flush() }
                send("VERSION"); log("Loader: " + (inp.readLine() ?: "no answer"))
                send("OTA ${image.size} ${hex(sha256(image))}")
                while (true) {
                    val l = inp.readLine() ?: error("Connection closed")
                    log(l)
                    if (l.startsWith("ERR")) error(l)
                    if (l.startsWith("OK")) break
                }
                var sent = 0
                while (sent < image.size) {
                    val n = minOf(1024, image.size - sent)
                    out.write(image, sent, n); out.flush(); sent += n
                    val ack = inp.readLine() ?: error("Connection closed at $sent bytes")
                    if (ack.startsWith("ERR")) error(ack)
                    progress(sent / image.size.toFloat())
                }
                val done = inp.readLine() ?: ""
                log(done)
                check(done.startsWith("OK")) { "The radio rejected the image: $done" }
                runCatching { send("REBOOT") }
            }
            log("Done. The radio restarts on the new firmware.")
        }
    }

    /** Over Bluetooth: the loader advertises the OTA service on the same radio. */
    suspend fun updateBle(ctx: Context, device: BluetoothDevice, image: ByteArray, log: (String) -> Unit, progress: (Float) -> Unit): Result<Unit> = runCatching {
        log("Waiting for the OTA loader over Bluetooth…")
        delay(8_000) // reboot into the loader
        val g = GattClient(ctx, device)
        try {
            withTimeout(45_000) { var ok = false; while (!ok) { ok = runCatching { g.connect(517) }.isSuccess && g.hasService(SERVICE); if (!ok) { g.close(); delay(3_000) } } }
            g.enableNotifications(SERVICE, NOTIFY)
            val lines = g.notifications.filter { it.uuid == NOTIFY }.map { it.value.decodeToString().trim() }
            suspend fun cmd(line: String, until: (String) -> Boolean, timeout: Long): String =
                withTimeout(timeout) { g.write(SERVICE, WRITE, (line + "\n").toByteArray()); lines.first { log(it); it.startsWith("ERR") || until(it) } }
                    .also { if (it.startsWith("ERR")) error(it) }
            cmd("VERSION", { it.startsWith("OK") }, 15_000)
            cmd("OTA ${image.size} ${hex(sha256(image))}", { it.startsWith("OK") }, 120_000)
            val chunk = (g.mtu - 3).coerceIn(20, 512)
            var sent = 0
            while (sent < image.size) {
                val part = image.copyOfRange(sent, minOf(image.size, sent + chunk))
                withTimeout(15_000) { g.write(SERVICE, WRITE, part, withResponse = false); lines.first { it.startsWith("ACK") || it.startsWith("ERR") || it.startsWith("OK") } }
                    .also { if (it.startsWith("ERR")) error(it) }
                sent += part.size
                progress(sent / image.size.toFloat())
            }
            withTimeout(180_000) { lines.first { log(it); it.startsWith("OK") || it.startsWith("ERR") } }.also { if (it.startsWith("ERR")) error(it) }
            log("Done. The radio restarts on the new firmware.")
        } finally { g.close() }
    }
}
