// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.aprs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.MeshChannel
import org.highbridge.core.protocol.MeshNode
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshPayload
import org.highbridge.core.protocol.MeshRadio
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.RadioEvent
import org.highbridge.core.protocol.link.StreamLink
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * APRS as a "radio": a KISS TNC (Bluetooth, USB or TCP to Direwolf) for RF, and/or APRS-IS over the internet.
 * Stations become nodes, APRS messages to your call sign become direct messages, and positions go on the map.
 * There is no group chat on APRS, so it has no channels and is never bridged.
 */
class AprsRadio(
    val config: () -> Config,
    private val tnc: StreamLink?,
    private val tncName: String?,
) : MeshRadio {
    data class Config(val callsign: String, val symbol: String, val comment: String, val path: List<String>,
                      val useIs: Boolean, val isHost: String, val isFilterKm: Int, val location: () -> Pair<Double, Double>?,
                      val extraCalls: () -> Set<String> = { emptySet() })

    data class Station(val call: String, val lat: Double?, val lon: Double?, val symbol: String, val comment: String, val path: String,
                       val viaInternet: Boolean, val heardMs: Long, val altitudeFt: Int?, val status: String = "")

    /** Every message heard, to anyone (the gateway uses this). */
    data class Heard(val from: String, val to: String, val text: String, val msgNo: String?, val viaInternet: Boolean)

    override val protocol = ProtocolType.APRS
    override val maxPayloadBytes = 67

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val discoveredNodes: StateFlow<List<MeshNode>> = _nodes
    override val channels: StateFlow<List<MeshChannel>> = MutableStateFlow(emptyList())
    private val _incoming = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    override val incomingPackets: Flow<MeshPacket> = _incoming
    private val _events = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 128)
    override val events: Flow<RadioEvent> = _events
    private val _myId = MutableStateFlow<String?>(null)
    override val myNodeId: StateFlow<String?> = _myId
    private val _device = MutableStateFlow<String?>(null)
    override val deviceInfo: StateFlow<String?> = _device

    private val _stations = MutableStateFlow<Map<String, Station>>(emptyMap())
    val stations: StateFlow<Map<String, Station>> = _stations
    private val _heard = MutableSharedFlow<Heard>(extraBufferCapacity = 64)
    val heardMessages: SharedFlow<Heard> = _heard
    private val _raw = MutableSharedFlow<String>(extraBufferCapacity = 256)
    /** Every packet as text, newest last, for the APRS log. */
    val rawLog: SharedFlow<String> = _raw

    /** True once APRS-IS accepted the passcode, so sending over the internet works. */
    val isVerified = MutableStateFlow(false)
    val isConnected = MutableStateFlow(false)
    val tncConnected = MutableStateFlow(false)
    val canTransmit: Boolean get() = tncConnected.value || isVerified.value

    private val kiss = Kiss.Decoder()
    private var textMode: Boolean? = null           // some LoRa trackers speak TNC2 text lines instead of KISS
    private val textBuf = StringBuilder()
    private var isSocket: Socket? = null
    private var isJob: Job? = null
    private val sendLock = Mutex()

    // ------------------------------------------------------------------ connecting

    override suspend fun connect(address: String): Result<Unit> = runCatching {
        val c = config()
        require(c.callsign.isNotBlank()) { "Set your call sign in APRS settings first" }
        _myId.value = c.callsign.uppercase()
        _state.value = ConnectionState.Connecting(tncName ?: "APRS-IS")
        if (tnc != null) {
            tnc.open().getOrThrow()
            tncConnected.value = true
            scope.launch { tnc.incoming.collect { onTncBytes(it) } }
            scope.launch { tnc.isOpen.collect { if (!it && tncConnected.value) { tncConnected.value = false; updateState() } } }
        }
        if (c.useIs) startIs()
        updateState()
        scope.launch { retryLoop() }
    }

    private fun updateState() {
        val parts = listOfNotNull(tncName?.takeIf { tncConnected.value }?.let { "TNC $it" },
            if (isConnected.value) "APRS-IS" + if (isVerified.value) "" else " (listen only)" else null)
        _device.value = "APRS · ${config().callsign.uppercase()}" + if (parts.isNotEmpty()) " · " + parts.joinToString(" + ") else ""
        _state.value = if (tncConnected.value || isConnected.value || (tnc == null && config().useIs))
            ConnectionState.Connected(_device.value ?: "APRS", config().callsign.uppercase())
        else ConnectionState.Error("APRS connection lost")
    }

    override suspend fun disconnect() {
        scope.cancel()
        runCatching { isSocket?.close() }
        runCatching { tnc?.close() }
        _state.value = ConnectionState.Disconnected
    }

    // ------------------------------------------------------------------ APRS-IS

    private fun filterString(): String {
        val c = config()
        val calls = (setOf(c.callsign.uppercase().substringBefore('-')) + c.extraCalls().map { it.substringBefore('-') }).joinToString("/")
        val here = c.location()
        val area = if (here != null) "r/%.2f/%.2f/%d".format(java.util.Locale.US, here.first, here.second, c.isFilterKm) else "m/${c.isFilterKm}"
        return "$area g/$calls*"
    }

    private fun startIs() {
        isJob?.cancel()
        isJob = scope.launch {
            var backoff = 5_000L
            while (isActive) {
                runCatching {
                    val c = config()
                    val host = c.isHost.substringBefore(':').ifBlank { "rotate.aprs2.net" }
                    val port = c.isHost.substringAfter(':', "14580").toIntOrNull() ?: 14580
                    val s = Socket().apply { connect(InetSocketAddress(host, port), 15_000); soTimeout = 90_000 }
                    isSocket = s
                    val out = s.getOutputStream()
                    val call = c.callsign.uppercase()
                    val pass = if (c.callsign.isNotBlank()) AprsFormat.passcode(call) else -1
                    out.write("user $call pass $pass vers Highbridge 0.5.3 filter ${filterString()}\r\n".toByteArray()); out.flush()
                    isConnected.value = true; updateState(); backoff = 5_000L
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("#")) {
                            if (line.contains("logresp") && line.contains(" verified")) { isVerified.value = !line.contains("unverified"); updateState() }
                            continue
                        }
                        AprsPacket.parseTnc2(line)?.let { onPacket(it, viaInternet = true) }
                    }
                }
                isConnected.value = false; isVerified.value = false; runCatching { isSocket?.close() }; isSocket = null
                if (!isActive) break
                updateState()
                delay(backoff); backoff = (backoff * 2).coerceAtMost(300_000)
            }
        }
    }

    /** Re-centres the APRS-IS area filter when you move, and picks up new gateway call signs. */
    fun refreshFilter() {
        val s = isSocket ?: return
        scope.launch { runCatching { s.getOutputStream().apply { write("#filter ${filterString()}\r\n".toByteArray()); flush() } } }
    }

    // ------------------------------------------------------------------ receiving

    private suspend fun onTncBytes(bytes: ByteArray) {
        if (textMode == null) textMode = bytes.none { (it.toInt() and 0xFF) == Kiss.FEND } && bytes.any { it == '>'.code.toByte() }
        if (textMode == true) {
            textBuf.append(String(bytes, Charsets.UTF_8))
            while (true) {
                val nl = textBuf.indexOfFirst { it == '\n' || it == '\r' }
                if (nl < 0) break
                val line = textBuf.substring(0, nl).trim(); textBuf.delete(0, nl + 1)
                if (line.isNotEmpty()) AprsPacket.parseTnc2(line)?.let { onPacket(it, viaInternet = false) }
            }
        } else for (f in kiss.feed(bytes)) Ax25.decode(f)?.let { onPacket(it, viaInternet = false) }
    }

    private val seen = LinkedHashMap<String, Long>()

    suspend fun onPacket(raw: AprsPacket, viaInternet: Boolean) {
        _raw.tryEmit((if (viaInternet) "IS  " else "RF  ") + raw.tnc2())
        val p = AprsParser.unwrap(raw)
        val now = System.currentTimeMillis()
        val path = p.path.joinToString(",")
        when (val d = AprsParser.parse(p)) {
            is AprsData.Position -> {
                val call = (d.objectName ?: p.source).trim()
                val old = _stations.value[call]
                val st = Station(call, d.lat, d.lon, d.symbol, d.comment, path, viaInternet, now, d.altitudeFt, old?.status ?: "")
                _stations.value = _stations.value + (call to st)
                publish(st)
                _events.emit(RadioEvent.Position(protocol, call, d.lat, d.lon, d.altitudeFt?.let { (it / 3.281).toInt() }, now))
            }
            is AprsData.Status -> {
                val old = _stations.value[p.source]
                val st = old?.copy(status = d.text, heardMs = now) ?: Station(p.source, null, null, "/[", "", path, viaInternet, now, null, d.text)
                _stations.value = _stations.value + (p.source to st)
                publish(st)
            }
            is AprsData.Message -> {
                val key = "${p.source}|${d.addressee}|${d.msgNo ?: d.text.hashCode()}"
                synchronized(seen) {
                    if (seen.containsKey(key) && now - seen[key]!! < 30 * 60_000) { // A retry of something already handled: just acknowledge again (for you or a gateway user).
                        if (d.msgNo != null && (d.addressee == me() || d.addressee in config().extraCalls())) scope.launch { sendAck(d.addressee, p.source, d.msgNo) }
                        return
                    }
                    seen[key] = now; while (seen.size > 500) seen.remove(seen.keys.first())
                }
                _heard.emit(Heard(p.source, d.addressee, d.text, d.msgNo, viaInternet))
                if (d.addressee == me()) {
                    d.msgNo?.let { sendAck(me(), p.source, it) }
                    if (!_stations.value.containsKey(p.source)) {
                        val st = Station(p.source, null, null, "/[", "", path, viaInternet, now, null)
                        _stations.value = _stations.value + (p.source to st); publish(st)
                    }
                    _incoming.emit(MeshPacket("aprs-in-${p.source}-${d.msgNo ?: now}", protocol, p.source, p.source, me(), 0, MeshPayload.Text(d.text)))
                }
            }
            is AprsData.Ack -> pending.values.firstOrNull { it.from == d.addressee && it.to == p.source && it.msgNo == d.msgNo }?.let { m ->
                pending.remove(m.packetId)
                _events.emit(RadioEvent.Ack(protocol, m.packetId, !d.rejected, if (d.rejected) "Rejected by ${p.source}" else null))
            }
            is AprsData.Other -> {}
        }
    }

    private fun me() = config().callsign.uppercase()

    private fun publish(st: Station) {
        val n = MeshNode(st.call, st.call.take(6), st.call, protocol, latitude = st.lat, longitude = st.lon,
            hopsAway = if (st.viaInternet) -1 else st.path.split(',').count { it.endsWith("*") }, lastHeardMs = st.heardMs)
        _nodes.value = (_nodes.value.filter { it.id != st.call } + n).sortedByDescending { it.lastHeardMs }.take(1000)
        _events.tryEmit(RadioEvent.NodeUpdated(protocol, n))
    }

    // ------------------------------------------------------------------ sending

    /** Sends [info] from [source] over RF if a TNC is connected, otherwise over APRS-IS. */
    suspend fun transmit(info: String, source: String = me()): Result<Unit> = sendLock.withLock {
        runCatching {
            val c = config()
            val pkt = AprsPacket(source.uppercase(), AprsFormat.TOCALL, if (tncConnected.value) c.path else listOf("TCPIP*"), info)
            when {
                tncConnected.value && tnc != null ->
                    tnc.write(if (textMode == true) (pkt.tnc2() + "\r\n").toByteArray() else Kiss.encode(Ax25.encode(pkt))).getOrThrow()
                isVerified.value -> withContext(Dispatchers.IO) { (isSocket ?: error("APRS-IS not connected")).getOutputStream().apply { write((pkt.tnc2() + "\r\n").toByteArray()); flush() } }
                isConnected.value -> error("APRS-IS didn't accept your call sign, so this is listen-only")
                else -> error("APRS isn't connected")
            }
            _raw.tryEmit("TX  " + pkt.tnc2())
            Unit
        }
    }

    private data class Pending(val packetId: String, val from: String, val to: String, val text: String, val msgNo: String, var tries: Int, var nextAt: Long)
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private var msgCounter = (System.currentTimeMillis() / 1000 % 900).toInt() + 1

    private fun nextMsgNo(): String { msgCounter = msgCounter % 99_999 + 1; return msgCounter.toString() }

    override suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?): Result<String> {
        val to = toNodeId ?: return Result.failure(IllegalStateException("APRS has no group chat. Message a call sign instead."))
        return sendMessageAs(me(), to, text)
    }

    /** An APRS message from [from] (your call, or a gateway user's), retried until acknowledged. */
    suspend fun sendMessageAs(from: String, to: String, text: String): Result<String> = runCatching {
        require(AprsFormat.isCallsign(from)) { "Set a valid call sign first" }
        val no = nextMsgNo()
        val id = "aprs-out-${from.uppercase()}-${to.uppercase()}-$no"
        transmit(AprsFormat.message(to, text, no), from).getOrThrow()
        pending[id] = Pending(id, from.uppercase(), to.uppercase(), text, no, 1, System.currentTimeMillis() + 30_000)
        id
    }

    /** Acknowledges a message; [asCall] is who it was for (you, or a gateway user). */
    suspend fun sendAck(asCall: String, to: String, msgNo: String) { transmit(AprsFormat.ack(to, msgNo), asCall) }

    private suspend fun retryLoop() {
        while (scope.isActive) {
            delay(5_000)
            val now = System.currentTimeMillis()
            for (m in pending.values.toList()) if (now >= m.nextAt) {
                if (m.tries >= 5) { pending.remove(m.packetId); _events.emit(RadioEvent.Ack(protocol, m.packetId, false, "No acknowledgement")); continue }
                m.tries++; m.nextAt = now + 30_000L * (1 shl (m.tries - 1)).coerceAtMost(8)
                transmit(AprsFormat.message(m.to, m.text, m.msgNo), m.from)
            }
        }
    }

    override suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String?): Result<String> =
        Result.failure(UnsupportedOperationException("APRS carries text only"))

    override suspend fun sendPosition(lat: Double, lon: Double, altitudeM: Int?): Result<Unit> {
        val c = config()
        return transmit(AprsFormat.position(lat, lon, c.symbol, c.comment, altitudeM?.toDouble()))
    }

    /** A status line others see under your call sign. */
    suspend fun sendStatus(text: String) = transmit(">" + AprsFormat.clean(text).take(62))

    companion object {
        /** True when [p] came from APRS and so is ham-radio traffic. */
        fun isAprs(p: ProtocolType) = p == ProtocolType.APRS
    }
}
