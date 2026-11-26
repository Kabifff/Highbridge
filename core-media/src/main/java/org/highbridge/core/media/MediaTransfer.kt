// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshPayload
import org.highbridge.core.protocol.MeshTransport
import org.highbridge.core.protocol.ProtocolType
import kotlin.random.Random

/**
 * Sends photos and voice notes as numbered chunks, reassembles incoming ones, and asks for missing
 * chunks (NACK) after a quiet spell. The sender keeps its chunks for 15 minutes to answer NACKs.
 */
class MediaTransferManager(
    private val transports: () -> Map<ProtocolType, MeshTransport>,
    private val chunkIntervalMs: () -> Long = { 3_000L },
) {
    data class Incoming(val protocol: ProtocolType, val senderId: String, val senderName: String, val channelIndex: Int, val toNodeId: String?, val mediaType: MediaType, val data: ByteArray)
    data class Progress(val transferId: Int, val done: Int, val total: Int, val outgoing: Boolean)

    private class Sent(val protocol: ProtocolType, val channel: Int, val to: String?, val chunks: List<ByteArray>, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reassembler = MediaReassembler()
    private val sent = HashMap<Int, Sent>()
    private val _completed = MutableSharedFlow<Incoming>(extraBufferCapacity = 32)
    val completed = _completed.asSharedFlow()
    private val _progress = MutableSharedFlow<Progress>(extraBufferCapacity = 64)
    val progress = _progress.asSharedFlow()
    private val origin = HashMap<String, MeshPacket>()

    init {
        scope.launch {
            while (isActive) {
                delay(15_000)
                for ((sender, id) in reassembler.stalledTransfers(20_000)) {
                    val info = reassembler.transferInfo(sender, id) ?: continue
                    val missing = reassembler.missingChunks(sender, id)
                    val pkt = origin["$sender#$id"] ?: continue
                    val t = transports()[pkt.protocol] ?: continue
                    reassembler.touch(sender, id)
                    runCatching { t.sendBinary(pkt.channelIndex, MediaFrame.PRIVATE_PORT_ID, MediaFrame.nack(info.first, id, info.second, missing), pkt.fromNodeId.takeIf { !it.startsWith("name:") && !it.startsWith("channel-") }) }
                }
                val cutoff = System.currentTimeMillis() - 15 * 60_000
                synchronized(sent) { sent.entries.removeIf { it.value.at < cutoff } }
            }
        }
    }

    fun chunkPayloadFor(p: ProtocolType): Int = when (p) {
        ProtocolType.MESHTASTIC -> 140
        ProtocolType.MESHCORE -> 110
        ProtocolType.RETICULUM -> 140
    }

    /** Splits and sends. Returns the transfer id. */
    fun send(protocol: ProtocolType, channelIndex: Int, toNodeId: String?, type: MediaType, data: ByteArray): Int {
        val transport = transports()[protocol] ?: error("That radio isn't connected")
        val id = Random.nextInt(1, Int.MAX_VALUE)
        val chunks = MediaChunker.split(data, type, chunkPayloadFor(protocol), id)
        synchronized(sent) { sent[id] = Sent(protocol, channelIndex, toNodeId, chunks, System.currentTimeMillis()) }
        scope.launch {
            chunks.forEachIndexed { i, c ->
                transport.sendBinary(channelIndex, MediaFrame.PRIVATE_PORT_ID, c, toNodeId)
                _progress.emit(Progress(id, i + 1, chunks.size, true))
                if (i < chunks.lastIndex) delay(chunkIntervalMs())
            }
        }
        return id
    }

    /** Feed every incoming packet here; non-media packets are ignored. */
    fun onPacket(p: MeshPacket) {
        val bin = p.payload as? MeshPayload.Binary ?: return
        if (bin.portId != MediaFrame.PRIVATE_PORT_ID) return
        when (val ev = reassembler.onFrameReceived(p.fromNodeId, bin.data)) {
            is ReassemblyEvent.Progress -> {
                origin["${ev.senderId}#${ev.transferId}"] = p
                _progress.tryEmit(Progress(ev.transferId, ev.receivedChunks, ev.totalChunks, false))
            }
            is ReassemblyEvent.Completed -> {
                origin.remove("${ev.senderId}#${ev.transferId}")
                _completed.tryEmit(Incoming(p.protocol, p.fromNodeId, p.fromShortName, p.channelIndex, p.toNodeId, ev.mediaType, ev.data))
            }
            is ReassemblyEvent.NackReceived -> resend(ev.transferId, ev.missingChunks)
            null -> {}
        }
    }

    private fun resend(transferId: Int, missing: List<Int>) {
        val s = synchronized(sent) { sent[transferId] } ?: return
        val t = transports()[s.protocol] ?: return
        scope.launch {
            for (i in missing) {
                s.chunks.getOrNull(i)?.let { t.sendBinary(s.channel, MediaFrame.PRIVATE_PORT_ID, it, s.to) }
                delay(chunkIntervalMs())
            }
        }
    }
}
