// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** [APRS] is amateur-radio APRS (a TNC or APRS-IS), never bridged to the meshes. */
enum class ProtocolType(val shortTag: String) { MESHTASTIC("MT"), MESHCORE("MC"), RETICULUM("RNS"), APRS("APRS") }

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val deviceAddress: String) : ConnectionState
    data class Connected(val deviceName: String, val nodeId: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class MeshNode(
    val id: String,
    val shortName: String,
    val longName: String,
    val protocol: ProtocolType,
    val rssi: Int? = null,
    val snr: Float? = null,
    val hopsAway: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val batteryPct: Int? = null,
    val voltage: Float? = null,
    val isApproved: Boolean = false,
    val lastHeardMs: Long = System.currentTimeMillis(),
)

data class MeshChannel(
    val index: Int,
    val name: String,
    val protocol: ProtocolType,
    val isEncrypted: Boolean = true,
)

sealed interface MeshPayload {
    /** [replyTo] = packet id this answers; [emoji] = a tapback reaction (the body is one emoji). */
    data class Text(val body: String, val replyTo: String? = null, val emoji: Boolean = false) : MeshPayload
    data class Binary(val portId: Int, val data: ByteArray) : MeshPayload {
        override fun equals(other: Any?): Boolean =
            other is Binary && portId == other.portId && data.contentEquals(other.data)
        override fun hashCode(): Int = 31 * portId + data.contentHashCode()
    }
}

data class MeshPacket(
    val packetId: String,
    val protocol: ProtocolType,
    val fromNodeId: String,
    val fromShortName: String,
    /** null = sent to the whole channel; a value = a private direct message. */
    val toNodeId: String?,
    val channelIndex: Int,
    val payload: MeshPayload,
    val timestampMs: Long = System.currentTimeMillis(),
    val isBridged: Boolean = false,
) {
    val isBroadcast: Boolean get() = toNodeId == null
}

interface MeshTransport {
    val protocol: ProtocolType
    /** Largest text/binary payload one send can carry, in bytes. */
    val maxPayloadBytes: Int
    val connectionState: StateFlow<ConnectionState>
    val discoveredNodes: StateFlow<List<MeshNode>>
    val channels: StateFlow<List<MeshChannel>>
    val incomingPackets: Flow<MeshPacket>

    suspend fun connect(address: String): Result<Unit>
    suspend fun disconnect()
    suspend fun sendText(channelIndex: Int, text: String, toNodeId: String? = null): Result<String>
    suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String? = null): Result<String>
}

/** Everything a radio reports besides chat text: telemetry, positions, routes, admin replies and log lines. */
sealed interface RadioEvent {
    val protocol: ProtocolType
    data class NodeUpdated(override val protocol: ProtocolType, val node: MeshNode) : RadioEvent
    data class Position(override val protocol: ProtocolType, val nodeId: String, val lat: Double, val lon: Double, val altitudeM: Int?, val timeMs: Long) : RadioEvent
    data class Telemetry(
        override val protocol: ProtocolType, val nodeId: String, val timeMs: Long,
        val batteryPct: Int? = null, val voltage: Float? = null, val channelUtil: Float? = null, val airUtilTx: Float? = null,
        val temperatureC: Float? = null, val humidityPct: Float? = null, val pressureHpa: Float? = null,
    ) : RadioEvent
    data class Route(override val protocol: ProtocolType, val destId: String, val hops: List<String>, val snrTowards: List<Float>, val hopsBack: List<String> = emptyList(), val snrBack: List<Float> = emptyList()) : RadioEvent
    data class RangeTest(override val protocol: ProtocolType, val fromId: String, val text: String, val snr: Float?, val rssi: Int?, val timeMs: Long) : RadioEvent
    data class Ack(override val protocol: ProtocolType, val packetId: String, val delivered: Boolean, val reason: String? = null) : RadioEvent
    data class Heard(override val protocol: ProtocolType, val fromId: String, val snr: Float?, val rssi: Int?, val hops: Int?) : RadioEvent
    data class AdminReply(override val protocol: ProtocolType, val fromId: String, val text: String) : RadioEvent
    data class Log(override val protocol: ProtocolType, val line: String) : RadioEvent
    data class Airtime(override val protocol: ProtocolType, val txMs: Long) : RadioEvent
    /** A map pin someone shared (Meshtastic waypoints). [expireMs] 0 = never; [deleted] = the sender removed it. */
    data class Waypoint(override val protocol: ProtocolType, val id: Int, val name: String, val description: String, val lat: Double, val lon: Double,
                        val icon: Int, val expireMs: Long, val fromId: String, val deleted: Boolean = false) : RadioEvent
    /**
     * One packet the radio heard on air, before decryption (MeshCore RX log). [lastHop] is the hash prefix of
     * the repeater that sent it to us, null if it came straight from its origin. [channelHash] is set for group messages.
     */
    data class RxLog(override val protocol: ProtocolType, val snr: Float, val rssi: Int, val payloadType: Int, val pathLen: Int,
                     val lastHop: String?, val channelHash: Int?, val timeMs: Long = System.currentTimeMillis()) : RadioEvent
}

/** A connected radio with the richer features the app's screens use. The bridge only needs [MeshTransport]. */
interface MeshRadio : MeshTransport {
    val events: Flow<RadioEvent>
    val myNodeId: StateFlow<String?>
    /** Human-readable firmware / device summary once connected. */
    val deviceInfo: StateFlow<String?>
    suspend fun sendPosition(lat: Double, lon: Double, altitudeM: Int? = null): Result<Unit> = Result.failure(UnsupportedOperationException("Not supported"))
    suspend fun requestTraceroute(nodeId: String): Result<Unit> = Result.failure(UnsupportedOperationException("Not supported"))
    suspend fun applyChannel(channel: EncryptedChannel): Result<Unit> = Result.failure(UnsupportedOperationException("Not supported"))
    suspend fun setOwnerName(longName: String, shortName: String): Result<Unit> = Result.failure(UnsupportedOperationException("Not supported"))
    /** Text with a reply link or as a reaction. Radios without these send plain text. */
    suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?, replyTo: String?, emoji: Boolean): Result<String> = sendText(channelIndex, text, toNodeId)
    /** Shares a map pin with the channel. [id] 0 = new; delete by sending [expireMs] 1. */
    suspend fun sendWaypoint(channelIndex: Int, id: Int, name: String, description: String, lat: Double, lon: Double, icon: Int, expireMs: Long): Result<Int> =
        Result.failure(UnsupportedOperationException("This radio doesn't support shared waypoints"))
    /**
     * A wardriving ping from here. Meshtastic: a position broadcast that counts as heard when a nearby
     * node rebroadcasts it (an Ack event for the returned id). MeshCore: a message on the #wardriving channel.
     */
    suspend fun sendWardrivePing(lat: Double, lon: Double): Result<String> = Result.failure(UnsupportedOperationException("Not supported"))
}
