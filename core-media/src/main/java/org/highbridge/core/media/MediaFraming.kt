// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.random.Random

/*
 * 156-byte LoRa media frames: 16-byte header + up to 140 bytes of payload.
 * Pure Kotlin (no Android imports), so it can be unit-tested on a computer.
 *
 * Header (big-endian):
 *   0  'O' 'M' magic (2)        4  transferId (4)      12 CRC32 (4) of bytes 0..11 + payload
 *   2  frameType (1)            8  chunkIndex (2)
 *   3  mediaType (1)           10  totalChunks (2)
 */

enum class MediaType(val code: Byte) {
    IMAGE_WEBP(0x01), VOICE_CODEC2(0x02), BINARY_FILE(0x03);
    companion object { fun fromCode(code: Byte) = entries.firstOrNull { it.code == code } }
}

enum class FrameType(val code: Byte) {
    DATA(0x01), NACK(0x02), ACK(0x03);
    companion object { fun fromCode(code: Byte) = entries.firstOrNull { it.code == code } }
}

class MediaFrame(
    val frameType: FrameType, val mediaType: MediaType, val transferId: Int,
    val chunkIndex: Int, val totalChunks: Int, val payload: ByteArray,
) {
    companion object {
        const val MAGIC_0: Byte = 0x4F; const val MAGIC_1: Byte = 0x4D
        const val HEADER_SIZE = 16
        const val DEFAULT_MAX_PAYLOAD = 140
        /** Meshtastic PortNum.PRIVATE_APP. */
        const val PRIVATE_PORT_ID = 256
        /** 256 x 140 B = 35 KB. Anything larger would take hours on LoRa and is refused. */
        const val MAX_CHUNKS = 256

        private fun crcOf(header12: ByteArray, payload: ByteArray): Int =
            CRC32().apply { update(header12, 0, 12); update(payload) }.value.toInt()

        fun encode(frame: MediaFrame): ByteArray {
            require(frame.totalChunks in 1..MAX_CHUNKS) { "totalChunks must be 1..$MAX_CHUNKS" }
            require(frame.chunkIndex in 0 until frame.totalChunks || frame.frameType != FrameType.DATA) { "chunkIndex out of range" }
            require(frame.payload.size <= DEFAULT_MAX_PAYLOAD) { "payload too large for one LoRa frame" }
            val buf = ByteBuffer.allocate(HEADER_SIZE + frame.payload.size).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC_0).put(MAGIC_1).put(frame.frameType.code).put(frame.mediaType.code)
                .putInt(frame.transferId).putShort(frame.chunkIndex.toShort()).putShort(frame.totalChunks.toShort())
            val header = buf.array().copyOfRange(0, 12)
            return buf.putInt(crcOf(header, frame.payload)).put(frame.payload).array()
        }

        fun decode(bytes: ByteArray): MediaFrame? {
            if (bytes.size < HEADER_SIZE || bytes.size > HEADER_SIZE + DEFAULT_MAX_PAYLOAD) return null
            if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply { position(2) }
            val frameType = FrameType.fromCode(buf.get()) ?: return null
            val mediaType = MediaType.fromCode(buf.get()) ?: return null
            val transferId = buf.int
            val chunkIndex = buf.short.toInt() and 0xFFFF
            val totalChunks = buf.short.toInt() and 0xFFFF
            val expectedCrc = buf.int
            val payload = ByteArray(bytes.size - HEADER_SIZE).also { buf.get(it) }
            if (crcOf(bytes.copyOfRange(0, 12), payload) != expectedCrc) return null
            return MediaFrame(frameType, mediaType, transferId, chunkIndex, totalChunks, payload)
        }

        /** NACK listing missing chunk numbers (2 bytes each; up to 70 per frame). */
        fun nack(mediaType: MediaType, transferId: Int, totalChunks: Int, missing: List<Int>): ByteArray {
            val list = missing.take(DEFAULT_MAX_PAYLOAD / 2)
            val p = ByteBuffer.allocate(list.size * 2).order(ByteOrder.BIG_ENDIAN).apply { list.forEach { putShort(it.toShort()) } }.array()
            return encode(MediaFrame(FrameType.NACK, mediaType, transferId, 0, totalChunks, p))
        }
    }
}

object MediaChunker {
    fun split(
        data: ByteArray, mediaType: MediaType,
        maxChunkPayload: Int = MediaFrame.DEFAULT_MAX_PAYLOAD, transferId: Int = Random.nextInt(),
    ): List<ByteArray> {
        require(maxChunkPayload in 1..MediaFrame.DEFAULT_MAX_PAYLOAD) { "chunk size must be 1..${MediaFrame.DEFAULT_MAX_PAYLOAD}" }
        val totalChunks = ((data.size + maxChunkPayload - 1) / maxChunkPayload).coerceAtLeast(1)
        require(totalChunks <= MediaFrame.MAX_CHUNKS) { "Too big for LoRa: ${data.size} bytes (max ${MediaFrame.MAX_CHUNKS * maxChunkPayload})" }
        return (0 until totalChunks).map { index ->
            val start = index * maxChunkPayload
            val slice = data.copyOfRange(start, minOf(start + maxChunkPayload, data.size))
            MediaFrame.encode(MediaFrame(FrameType.DATA, mediaType, transferId, index, totalChunks, slice))
        }
    }
}

sealed interface ReassemblyEvent {
    data class Progress(val senderId: String, val transferId: Int, val mediaType: MediaType, val receivedChunks: Int, val totalChunks: Int) : ReassemblyEvent
    class Completed(val senderId: String, val transferId: Int, val mediaType: MediaType, val data: ByteArray) : ReassemblyEvent
    data class NackReceived(val senderId: String, val transferId: Int, val missingChunks: List<Int>) : ReassemblyEvent
}

/**
 * Rebuilds media from frames. Transfers are keyed by sender + transferId so two people sending
 * at once can't mix chunks. Memory is capped so a broken or hostile sender can't fill the phone.
 */
class MediaReassembler(
    private val transferTimeoutMs: Long = 10 * 60 * 1000L,
    private val maxActiveTransfers: Int = 16,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class ActiveTransfer(val mediaType: MediaType, val totalChunks: Int, var lastUpdatedMs: Long) {
        val chunks = HashMap<Int, ByteArray>()
    }
    private val transfers = HashMap<String, ActiveTransfer>()
    private fun key(sender: String, id: Int) = "$sender#$id"

    @Synchronized
    fun onFrameReceived(senderId: String, rawBytes: ByteArray): ReassemblyEvent? {
        val now = clock()
        transfers.entries.removeIf { now - it.value.lastUpdatedMs > transferTimeoutMs }
        val frame = MediaFrame.decode(rawBytes) ?: return null

        if (frame.frameType == FrameType.NACK) {
            val buf = ByteBuffer.wrap(frame.payload).order(ByteOrder.BIG_ENDIAN)
            val missing = buildList { while (buf.remaining() >= 2) add(buf.short.toInt() and 0xFFFF) }
            return ReassemblyEvent.NackReceived(senderId, frame.transferId, missing)
        }
        if (frame.frameType != FrameType.DATA) return null
        if (frame.totalChunks !in 1..MediaFrame.MAX_CHUNKS || frame.chunkIndex >= frame.totalChunks) return null

        val k = key(senderId, frame.transferId)
        var active = transfers[k]
        if (active != null && (active.totalChunks != frame.totalChunks || active.mediaType != frame.mediaType)) {
            transfers.remove(k); active = null // same id reused for a different file: start over
        }
        if (active == null) {
            if (transfers.size >= maxActiveTransfers) transfers.minByOrNull { it.value.lastUpdatedMs }?.let { transfers.remove(it.key) }
            active = ActiveTransfer(frame.mediaType, frame.totalChunks, now).also { transfers[k] = it }
        }
        active.chunks[frame.chunkIndex] = frame.payload
        active.lastUpdatedMs = now

        if (active.chunks.size == active.totalChunks) {
            val out = ByteArrayOutputStream()
            for (i in 0 until active.totalChunks) out.write(active.chunks.getValue(i))
            transfers.remove(k)
            return ReassemblyEvent.Completed(senderId, frame.transferId, active.mediaType, out.toByteArray())
        }
        return ReassemblyEvent.Progress(senderId, frame.transferId, active.mediaType, active.chunks.size, active.totalChunks)
    }

    /** Chunks still missing for a transfer, to put in a NACK after a quiet period. */
    @Synchronized
    fun missingChunks(senderId: String, transferId: Int): List<Int> {
        val t = transfers[key(senderId, transferId)] ?: return emptyList()
        return (0 until t.totalChunks).filter { it !in t.chunks }
    }

    /** Media type and chunk count of an unfinished transfer, for building a NACK. */
    @Synchronized
    fun transferInfo(senderId: String, transferId: Int): Pair<MediaType, Int>? =
        transfers[key(senderId, transferId)]?.let { it.mediaType to it.totalChunks }

    /** Forgets an unfinished transfer (the sender went away). */
    @Synchronized
    fun drop(senderId: String, transferId: Int) { transfers.remove(key(senderId, transferId)) }

    /** Marks a transfer as just-touched so a NACK isn't sent again immediately. */
    @Synchronized
    fun touch(senderId: String, transferId: Int) { transfers[key(senderId, transferId)]?.lastUpdatedMs = clock() }

    /** Transfers that have gone quiet for [quietMs] and are still incomplete. */
    @Synchronized
    fun stalledTransfers(quietMs: Long): List<Pair<String, Int>> {
        val now = clock()
        return transfers.filter { now - it.value.lastUpdatedMs >= quietMs }.keys.map {
            val i = it.lastIndexOf('#'); it.substring(0, i) to it.substring(i + 1).toInt()
        }
    }
}
