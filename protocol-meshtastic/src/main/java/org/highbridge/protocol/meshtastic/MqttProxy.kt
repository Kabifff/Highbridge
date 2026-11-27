// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.protocol.meshtastic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * A small MQTT 3.1.1 client (QoS 0) for Meshtastic's "client proxy": a radio without its own internet
 * hands MQTT messages to the phone, which talks to the broker for it.
 */
class MqttProxy(
    private val scope: CoroutineScope,
    private val toRadio: suspend (topic: String, payload: ByteArray, retained: Boolean) -> Unit,
    private val log: (String) -> Unit,
) {
    data class Config(val host: String, val port: Int, val tls: Boolean, val user: String, val pass: String, val clientId: String, val topics: List<String>)

    private val _status = MutableStateFlow("Off")
    val status: StateFlow<String> = _status
    private var job: Job? = null
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val lock = Mutex()

    fun start(c: Config) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var backoff = 5_000L
            while (isActive) {
                runCatching { session(c) }.onFailure { _status.value = "Disconnected: ${it.message}"; log("MQTT: ${it.message}") }
                delay(backoff); backoff = minOf(backoff * 2, 120_000L)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { socket?.close() }
        socket = null; out = null
        _status.value = "Off"
    }

    /** A message the radio wants published. */
    suspend fun publish(topic: String, payload: ByteArray, retained: Boolean) {
        val o = out ?: return
        val t = utf8(topic)
        lock.withLock { runCatching { o.write(packet(0x30 or (if (retained) 1 else 0), t + payload)); o.flush() } }
    }

    private suspend fun session(c: Config) {
        _status.value = "Connecting to ${c.host}"
        val s = if (c.tls) SSLSocketFactory.getDefault().createSocket(c.host, c.port) as Socket
            else Socket().apply { connect(InetSocketAddress(c.host, c.port), 10_000) }
        s.soTimeout = 0
        socket = s; out = s.getOutputStream()
        val input = DataInputStream(s.getInputStream())
        // CONNECT: protocol "MQTT" level 4, clean session, username/password if set, keep-alive 60 s.
        var flags = 0x02
        if (c.user.isNotEmpty()) flags = flags or 0x80
        if (c.pass.isNotEmpty()) flags = flags or 0x40
        var body = utf8("MQTT") + byteArrayOf(4, flags.toByte(), 0, 60) + utf8(c.clientId)
        if (c.user.isNotEmpty()) body += utf8(c.user)
        if (c.pass.isNotEmpty()) body += utf8(c.pass)
        lock.withLock { out!!.write(packet(0x10, body)); out!!.flush() }
        val (type, ack) = read(input)
        check(type shr 4 == 2 && ack.size >= 2 && ack[1].toInt() == 0) { "Broker refused the login (code ${ack.getOrNull(1)})" }
        // SUBSCRIBE to every topic, QoS 0.
        var sub = byteArrayOf(0, 1)
        c.topics.forEach { sub += utf8(it) + byteArrayOf(0) }
        lock.withLock { out!!.write(packet(0x82, sub)); out!!.flush() }
        _status.value = "Connected to ${c.host}"
        log("MQTT proxy connected to ${c.host}, ${c.topics.size} topics")
        val ping = scope.launch(Dispatchers.IO) { while (isActive) { delay(45_000); lock.withLock { runCatching { out?.write(byteArrayOf(0xC0.toByte(), 0)); out?.flush() } } } }
        try {
            while (true) {
                val (t, p) = read(input)
                if (t shr 4 == 3) { // PUBLISH
                    val tl = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
                    val topic = String(p, 2, tl)
                    var i = 2 + tl
                    if ((t shr 1) and 3 > 0) i += 2 // packet id for QoS 1/2 (we subscribe at 0, but be safe)
                    toRadio(topic, p.copyOfRange(i, p.size), t and 1 == 1)
                }
            }
        } finally { ping.cancel(); runCatching { s.close() } }
    }

    private fun read(input: DataInputStream): Pair<Int, ByteArray> {
        val type = input.readUnsignedByte()
        var mult = 1; var len = 0
        while (true) { val b = input.readUnsignedByte(); len += (b and 0x7F) * mult; if (b and 0x80 == 0) break; mult *= 128; check(mult < 128 * 128 * 128 * 128) }
        val p = ByteArray(len); input.readFully(p)
        return type to p
    }

    companion object {
        /** Meshtastic's public broker and its published shared login. */
        const val DEFAULT_HOST = "mqtt.meshtastic.org"
        const val DEFAULT_USER = "meshdev"
        const val DEFAULT_PASS = "large4cats"

        fun utf8(s: String): ByteArray { val b = s.toByteArray(); return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b }
        fun packet(header: Int, body: ByteArray): ByteArray {
            var len = body.size
            val lenBytes = mutableListOf<Byte>()
            do { var d = len % 128; len /= 128; if (len > 0) d = d or 0x80; lenBytes += d.toByte() } while (len > 0)
            return byteArrayOf(header.toByte()) + lenBytes.toByteArray() + body
        }
    }
}
