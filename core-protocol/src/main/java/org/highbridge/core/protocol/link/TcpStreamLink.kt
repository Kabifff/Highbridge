// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.protocol.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A radio on the network: Meshtastic's TCP API (port 4403) and MeshCore's Wi-Fi companion (port 5000)
 * use the same byte framing as their USB serial links, so this plugs into the same parsers.
 */
class TcpStreamLink(private val host: String, private val port: Int) : StreamLink {
    override val name: String get() = "$host:$port"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND)
    override val incoming: SharedFlow<ByteArray> = _incoming
    private val _open = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _open
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Mutex()

    override suspend fun open(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = Socket().apply { tcpNoDelay = true; keepAlive = true; connect(InetSocketAddress(host, port), 8_000) }
            socket = s; out = s.getOutputStream()
            _open.value = true
            val input: InputStream = s.getInputStream()
            scope.launch {
                val buf = ByteArray(4096)
                try {
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) _incoming.emit(buf.copyOf(n))
                    }
                } catch (_: Exception) { }
                _open.value = false
            }
            Unit
        }
    }

    override suspend fun write(data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { writeLock.withLock { (out ?: error("Not connected")).apply { write(data); flush() } }; Unit }
    }

    override fun close() {
        _open.value = false
        runCatching { socket?.close() }
        scope.cancel()
    }

    companion object {
        const val MESHTASTIC_PORT = 4403
        const val MESHCORE_PORT = 5000
    }
}
