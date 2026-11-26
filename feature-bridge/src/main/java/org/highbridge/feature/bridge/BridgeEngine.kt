// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.feature.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshPayload
import org.highbridge.core.protocol.MeshTransport
import org.highbridge.core.protocol.ProtocolType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

enum class BridgeMode(val label: String, val activeProtocols: Set<ProtocolType>) {
    MESHTASTIC_MESHCORE("Meshtastic ↔ MeshCore", setOf(ProtocolType.MESHTASTIC, ProtocolType.MESHCORE)),
    MESHTASTIC_RETICULUM("Meshtastic ↔ Reticulum", setOf(ProtocolType.MESHTASTIC, ProtocolType.RETICULUM)),
    MESHCORE_RETICULUM("MeshCore ↔ Reticulum", setOf(ProtocolType.MESHCORE, ProtocolType.RETICULUM)),
    ALL_THREE("All three", setOf(ProtocolType.MESHTASTIC, ProtocolType.MESHCORE, ProtocolType.RETICULUM)),
}

data class BridgeRule(
    val id: String, val protocolA: ProtocolType, val channelA: Int,
    val protocolB: ProtocolType, val channelB: Int,
    val bidirectional: Boolean = true, val forwardMedia: Boolean = true,
    val prefixSenderTag: Boolean = true, val enabled: Boolean = true,
)

data class BridgeStats(
    val isRunning: Boolean = false, val activeMode: BridgeMode = BridgeMode.MESHTASTIC_MESHCORE,
    val forwardedCount: Int = 0, val loopsBlocked: Int = 0, val failedCount: Int = 0,
    val lastEvent: String = "Idle",
)

/** Remembers recently bridged messages so they aren't bridged back and forth forever. */
class DeduplicationCache(
    private val maxEntries: Int = 500,
    ttlMs: Long = 120_000L,
    private val nanoClock: () -> Long = System::nanoTime, // monotonic: immune to clock changes
) {
    private val ttlNanos = ttlMs * 1_000_000L
    private val map = object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxEntries
    }

    @Synchronized
    fun isDuplicateOrRecord(fingerprint: String): Boolean {
        val now = nanoClock()
        val existing = map[fingerprint]
        if (existing != null && (now - existing) < ttlNanos) return true
        map[fingerprint] = now
        return false
    }

    companion object {
        private val TAG_CHAIN = Regex("^(?:\\[(?:MT|MC|RNS):([^\\]]{1,40})]\\s*)+")
        private val ONE_TAG = Regex("\\[(?:MT|MC|RNS):([^\\]]{1,40})]")

        /** Splits "[MC:bridge] [MT:alice] hi" into origin "alice" and body "hi". */
        fun parse(text: String, fallbackSender: String): Pair<String, String> {
            val chain = TAG_CHAIN.find(text) ?: return fallbackSender to text.trim()
            val origin = ONE_TAG.findAll(chain.value).lastOrNull()?.groupValues?.get(1) ?: fallbackSender
            return origin to text.substring(chain.value.length).trim()
        }

        fun hasTag(text: String): Boolean = TAG_CHAIN.containsMatchIn(text.trimStart())

        /** Same text from two different people is NOT a duplicate; the same person's text echoed back is. */
        fun fingerprint(origin: String, body: String, pairKey: String): String = sha("$pairKey|$origin|$body")

        fun fingerprintBytes(portId: Int, data: ByteArray, pairKey: String): String =
            sha("$pairKey|BIN:$portId|" + data.joinToString("") { "%02x".format(it) })

        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
    }
}

class MeshBridgeEngine(
    private val transports: Map<ProtocolType, MeshTransport>,
    private val scope: CoroutineScope,
) {
    private val dedup = DeduplicationCache()
    /** Packet ids this bridge sent, so its own messages are never re-bridged. */
    private val sentIds: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    private val _mode = MutableStateFlow(BridgeMode.MESHTASTIC_MESHCORE)
    val mode: StateFlow<BridgeMode> = _mode.asStateFlow()

    private val _rules = MutableStateFlow(listOf(
        BridgeRule("mt-mc", ProtocolType.MESHTASTIC, 0, ProtocolType.MESHCORE, 0),
        BridgeRule("mt-rns", ProtocolType.MESHTASTIC, 0, ProtocolType.RETICULUM, 0),
        BridgeRule("mc-rns", ProtocolType.MESHCORE, 0, ProtocolType.RETICULUM, 0),
    ))
    val rules: StateFlow<List<BridgeRule>> = _rules.asStateFlow()

    private val _stats = MutableStateFlow(BridgeStats())
    val stats: StateFlow<BridgeStats> = _stats.asStateFlow()
    private val jobs = mutableListOf<Job>()

    @Synchronized
    fun setBridgeMode(newMode: BridgeMode) {
        val wasRunning = _stats.value.isRunning
        if (wasRunning) stop()
        _mode.value = newMode
        _stats.update { it.copy(activeMode = newMode, lastEvent = "Mode: ${newMode.label}") }
        if (wasRunning) start()
    }

    fun updateRules(newRules: List<BridgeRule>) { _rules.value = newRules }

    @Synchronized
    fun start() {
        if (_stats.value.isRunning) return
        _stats.update { it.copy(isRunning = true, activeMode = _mode.value, lastEvent = "Active: ${_mode.value.label}") }
        _mode.value.activeProtocols.forEach { proto ->
            val transport = transports[proto] ?: return@forEach
            jobs += scope.launch {
                // If a radio disconnects or its flow fails, keep retrying instead of silently dying.
                while (isActive) {
                    try {
                        transport.incomingPackets.collect { handleIncoming(it, transport) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _stats.update { it.copy(lastEvent = "${proto.shortTag} error: ${e.message ?: e::class.simpleName}") }
                    }
                    delay(5_000)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        _stats.update { it.copy(isRunning = false, lastEvent = "Bridge stopped") }
    }

    /** Public so unit tests can feed packets straight in. */
    suspend fun handleIncoming(packet: MeshPacket, source: MeshTransport) {
        val allowed = _mode.value.activeProtocols
        if (source.protocol !in allowed) return
        if (!packet.isBroadcast) return                 // never forward private direct messages
        if (packet.packetId in sentIds) return          // our own message echoed back

        for (rule in _rules.value) {
            if (!rule.enabled) continue
            val (targetProto, targetChannel) = when {
                rule.protocolA == source.protocol && rule.channelA == packet.channelIndex -> rule.protocolB to rule.channelB
                rule.bidirectional && rule.protocolB == source.protocol && rule.channelB == packet.channelIndex -> rule.protocolA to rule.channelA
                else -> continue
            }
            if (targetProto !in allowed || targetProto == source.protocol) continue
            val target = transports[targetProto] ?: continue
            val pairKey = listOf("${source.protocol}:${packet.channelIndex}", "$targetProto:$targetChannel").sorted().joinToString("<->")

            when (val payload = packet.payload) {
                is MeshPayload.Text -> {
                    val (origin, body) = DeduplicationCache.parse(payload.body, packet.fromShortName)
                    if (body.isEmpty()) continue
                    if (dedup.isDuplicateOrRecord(DeduplicationCache.fingerprint(origin, body, pairKey))) {
                        _stats.update { it.copy(loopsBlocked = it.loopsBlocked + 1) }; continue
                    }
                    val alreadyTagged = DeduplicationCache.hasTag(payload.body)
                    val outText = when {
                        alreadyTagged -> payload.body.trim()                          // don't stack tags
                        rule.prefixSenderTag -> "[${source.protocol.shortTag}:${packet.fromShortName}] $body"
                        else -> body
                    }
                    record(targetProto, target.sendText(targetChannel, truncateUtf8(outText, target.maxPayloadBytes)), "${source.protocol.shortTag} → ${targetProto.shortTag}")
                }
                is MeshPayload.Binary -> {
                    if (!rule.forwardMedia) continue
                    if (payload.data.size > target.maxPayloadBytes) { _stats.update { it.copy(failedCount = it.failedCount + 1, lastEvent = "Media too large for ${targetProto.shortTag}") }; continue }
                    if (dedup.isDuplicateOrRecord(DeduplicationCache.fingerprintBytes(payload.portId, payload.data, pairKey))) {
                        _stats.update { it.copy(loopsBlocked = it.loopsBlocked + 1) }; continue
                    }
                    record(targetProto, target.sendBinary(targetChannel, payload.portId, payload.data), "Media → ${targetProto.shortTag}")
                }
            }
        }
    }

    private fun record(target: ProtocolType, result: Result<String>, event: String) {
        result.onSuccess { id ->
            sentIds += id
            synchronized(sentIds) { while (sentIds.size > 1000) sentIds.remove(sentIds.first()) }
            _stats.update { it.copy(forwardedCount = it.forwardedCount + 1, lastEvent = event) }
        }.onFailure { e ->
            _stats.update { it.copy(failedCount = it.failedCount + 1, lastEvent = "Failed to ${target.shortTag}: ${e.message}") }
        }
    }

    companion object {
        /** Cuts text to fit [maxBytes] of UTF-8 without breaking a character in half. */
        fun truncateUtf8(text: String, maxBytes: Int): String {
            if (text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return text
            val sb = StringBuilder(); var used = 0; val ell = "…"; val ellBytes = 3
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i); val ch = String(Character.toChars(cp))
                val b = ch.toByteArray(StandardCharsets.UTF_8).size
                if (used + b + ellBytes > maxBytes) break
                sb.append(ch); used += b; i += Character.charCount(cp)
            }
            return sb.append(ell).toString()
        }
    }
}
