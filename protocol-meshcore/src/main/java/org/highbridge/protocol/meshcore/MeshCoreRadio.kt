// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.protocol.meshcore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.EncryptedChannel
import org.highbridge.core.protocol.LoRaAirtime
import org.highbridge.core.protocol.MeshChannel
import org.highbridge.core.protocol.MeshCoreRadioPreset
import org.highbridge.core.protocol.MeshNode
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshPayload
import org.highbridge.core.protocol.MeshRadio
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.RadioEvent
import org.highbridge.core.protocol.link.StreamLink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frames to and from a MeshCore companion radio. BLE: one notification per frame. USB: '<' / '>' length-prefixed. */
class MeshCoreLink(private val stream: StreamLink, private val isSerial: Boolean) {
    val name get() = stream.name
    val connected get() = stream.isOpen
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND)
    val frames = _frames.asSharedFlow()
    private val deframer = MC.SerialDeframer { f -> scope.launch { _frames.emit(f) } }

    suspend fun open() {
        if (!stream.isOpen.value) stream.open().getOrThrow()
        scope.launch {
            stream.incoming.collect { chunk -> if (isSerial) chunk.forEach { deframer.feed(it) } else _frames.emit(chunk) }
        }
    }
    suspend fun send(frame: ByteArray) { stream.write(if (isSerial) MC.serialFrame(frame) else frame).getOrThrow() }
    fun close() { scope.cancel(); stream.close() }
}

class MeshCoreRadio(private val link: MeshCoreLink) : MeshRadio {
    override val protocol = ProtocolType.MESHCORE
    override val maxPayloadBytes: Int = MC.MAX_TEXT_LEN

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val discoveredNodes: StateFlow<List<MeshNode>> = _nodes
    private val _channels = MutableStateFlow<List<MeshChannel>>(emptyList())
    override val channels: StateFlow<List<MeshChannel>> = _channels
    private val _incoming = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incomingPackets: Flow<MeshPacket> = _incoming.asSharedFlow()
    private val _events = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<RadioEvent> = _events.asSharedFlow()
    private val _myId = MutableStateFlow<String?>(null)
    override val myNodeId: StateFlow<String?> = _myId
    private val _device = MutableStateFlow<String?>(null)
    override val deviceInfo: StateFlow<String?> = _device

    private val _contacts = MutableStateFlow<Map<String, MC.Contact>>(emptyMap())
    /** Contacts keyed by 6-byte public-key prefix (hex). */
    val contacts: StateFlow<Map<String, MC.Contact>> = _contacts
    private val _self = MutableStateFlow<MC.SelfInfo?>(null)
    val selfInfo: StateFlow<MC.SelfInfo?> = _self
    private val _repeaterStatus = MutableSharedFlow<Pair<String, MC.RepeaterStatus>>(extraBufferCapacity = 16)
    val repeaterStatus = _repeaterStatus.asSharedFlow()
    private val _loginResults = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 16)
    val loginResults = _loginResults.asSharedFlow()

    private val cmdLock = Mutex()
    @Volatile private var pendingReply: CompletableDeferred<ByteArray>? = null
    private var contactBuffer = mutableMapOf<String, MC.Contact>()
    private val channelInfo = sortedMapOf<Int, MC.ChannelInfo>()
    private val ackToPacket = LinkedHashMap<Int, String>()
    private val syncLock = Mutex()
    private var presetForAirtime: LoRaAirtime.Params? = null

    override suspend fun connect(address: String): Result<Unit> = runCatching {
        _state.value = ConnectionState.Connecting(link.name)
        link.open()
        scope.launch { link.frames.collect { runCatching { onFrame(it) } } }
        command(MC.appStart()).let { if (it[0].toInt() != MC.RESP_SELF_INFO) error("Not a MeshCore companion radio") }
        runCatching { command(MC.deviceQuery()) }
        runCatching { command(MC.setTime()) }
        refreshContacts()
        refreshChannels()
        syncMessages()
        _state.value = ConnectionState.Connected(link.name, _myId.value ?: "?")
        scope.launch { while (isActive) { delay(60_000); runCatching { command(MC.battery()) } } }
        scope.launch {
            link.connected.first { !it }
            _state.value = ConnectionState.Error("${link.name} disconnected")
            link.close()
        }
        Unit
    }.onFailure { _state.value = ConnectionState.Error(it.message ?: "Connection failed"); link.close() }

    override suspend fun disconnect() { scope.cancel(); link.close(); _state.value = ConnectionState.Disconnected }

    /** Sends one command and waits for its direct reply (codes below 0x80). Pushes are handled separately. */
    suspend fun command(frame: ByteArray, timeoutMs: Long = 8_000): ByteArray = cmdLock.withLock {
        val d = CompletableDeferred<ByteArray>()
        pendingReply = d
        try {
            link.send(frame)
            withTimeout(timeoutMs) { d.await() }
        } finally { pendingReply = null }
    }

    private suspend fun onFrame(f: ByteArray) {
        if (f.isEmpty()) return
        val code = f[0].toInt() and 0xFF
        when (code) {
            MC.RESP_SELF_INFO -> MC.parseSelfInfo(f)?.let { s ->
                _self.value = s
                _myId.value = MC.hex(s.publicKey, 6)
                presetForAirtime = LoRaAirtime.Params(s.sf, s.bwHz.toDouble(), s.cr, preambleSymbols = 8)
            }
            MC.RESP_DEVICE_INFO -> MC.parseDeviceInfo(f)?.let { _device.value = "MeshCore ${it.version} · ${it.manufacturer}".trim() }
            MC.RESP_CONTACTS_START -> contactBuffer = mutableMapOf()
            MC.RESP_CONTACT, MC.PUSH_NEW_ADVERT -> MC.parseContact(f)?.let { c ->
                if (code == MC.RESP_CONTACT) contactBuffer[c.prefixHex] = c else { _contacts.value = _contacts.value + (c.prefixHex to c); publishNodes() }
            }
            MC.RESP_END_OF_CONTACTS -> { _contacts.value = contactBuffer.toMap(); publishNodes() }
            MC.RESP_CONTACT_MSG_RECV, MC.RESP_CONTACT_MSG_RECV_V3, MC.RESP_CHANNEL_MSG_RECV, MC.RESP_CHANNEL_MSG_RECV_V3 -> MC.parseMessage(f)?.let { onMessage(it) }
            MC.RESP_CHANNEL_DATA_RECV -> MC.parseChannelData(f)?.let { d ->
                if (d.dataType == MC.DATA_TYPE_HIGHBRIDGE)
                    _incoming.emit(MeshPacket("mc-d-${System.nanoTime()}", protocol, "channel-${d.channelIdx}", "MC", null, d.channelIdx, MeshPayload.Binary(256, d.data)))
            }
            MC.RESP_CHANNEL_INFO -> MC.parseChannelInfo(f)?.let { channelInfo[it.idx] = it }
            MC.RESP_BATT_AND_STORAGE -> if (f.size >= 3) {
                val mv = ByteBuffer.wrap(f, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                _myId.value?.let { _events.emit(RadioEvent.Telemetry(protocol, it, System.currentTimeMillis(), voltage = mv / 1000f)) }
            }
            MC.PUSH_MSG_WAITING -> scope.launch { syncMessages() }
            MC.PUSH_SEND_CONFIRMED -> if (f.size >= 5) {
                val ack = ByteBuffer.wrap(f, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                synchronized(ackToPacket) { ackToPacket.remove(ack) }?.let { _events.emit(RadioEvent.Ack(protocol, it, true)) }
            }
            MC.PUSH_ADVERT, MC.PUSH_PATH_UPDATED -> scope.launch { runCatching { refreshContacts() } }
            MC.PUSH_LOGIN_SUCCESS, MC.PUSH_LOGIN_FAIL -> if (f.size >= 8) {
                val who = MC.hex(f.copyOfRange(2, 8))
                val ok = code == MC.PUSH_LOGIN_SUCCESS
                _loginResults.emit(who to ok)
                _events.emit(RadioEvent.AdminReply(protocol, who, if (ok) "Logged in${if (f[1].toInt() and 1 == 1) " as admin" else ""}" else "Login failed"))
            }
            MC.PUSH_STATUS_RESPONSE -> if (f.size >= 8) {
                val who = MC.hex(f.copyOfRange(2, 8))
                MC.parseStatus(f)?.let { s ->
                    _repeaterStatus.emit(who to s)
                    _events.emit(RadioEvent.AdminReply(protocol, who, "Battery ${s.battMv / 1000.0} V · noise ${s.noiseFloor} dBm · up ${s.upTimeSec / 3600} h · rx ${s.recv} / tx ${s.sent}"))
                    _events.emit(RadioEvent.Telemetry(protocol, who, System.currentTimeMillis(), voltage = s.battMv / 1000f))
                }
            }
            MC.PUSH_TRACE_DATA -> MC.parseTrace(f)?.let { t ->
                val names = t.pathHashes.map { h -> _contacts.value.values.firstOrNull { it.publicKey[0] == h }?.name ?: "%02x".format(h) }
                _events.emit(RadioEvent.Route(protocol, names.lastOrNull() ?: "?", names, t.snrs))
            }
        }
        if (code < 0x80 && code != MC.RESP_CONTACT && code != MC.RESP_CONTACTS_START) pendingReply?.complete(f)
    }

    private suspend fun onMessage(m: MC.Message) {
        val now = System.currentTimeMillis()
        if (m.isChannel) {
            // Channel text arrives as "SenderName: message".
            val sep = m.text.indexOf(": ")
            val sender = if (sep in 1..32) m.text.substring(0, sep) else "?"
            val body = if (sep in 1..32) m.text.substring(sep + 2) else m.text
            _incoming.emit(MeshPacket("mc-${m.channelIdx}-${m.timestamp}-${sender.hashCode()}", protocol, "name:$sender", sender, null, m.channelIdx, MeshPayload.Text(body), now))
            if (m.pathLen != 0xFF || m.snr != null) _events.emit(RadioEvent.Heard(protocol, "name:$sender", m.snr, null, m.pathLen.takeIf { it != 0xFF }))
        } else {
            val prefix = m.senderPrefixHex ?: return
            val contact = _contacts.value[prefix]
            when (m.txtType) {
                MC.TXT_CLI_DATA -> _events.emit(RadioEvent.AdminReply(protocol, prefix, m.text))
                else -> _incoming.emit(MeshPacket("mc-$prefix-${m.timestamp}", protocol, prefix, contact?.name ?: prefix.take(4),
                    _myId.value ?: "me", 0, MeshPayload.Text(m.text), now))
            }
            _events.emit(RadioEvent.Heard(protocol, prefix, m.snr, null, m.pathLen.takeIf { it != 0xFF }))
        }
    }

    private fun publishNodes() {
        _nodes.value = _contacts.value.values.map { c ->
            MeshNode(c.prefixHex, c.name.take(4), c.name + when { c.isRepeater -> " (repeater)"; c.isRoom -> " (room)"; else -> "" }, protocol,
                hopsAway = c.directPath?.size ?: -1,
                latitude = c.lat.takeIf { it != 0.0 }, longitude = c.lon.takeIf { it != 0.0 },
                lastHeardMs = c.lastAdvert * 1000L)
        }.sortedByDescending { it.lastHeardMs }
        _nodes.value.forEach { _events.tryEmit(RadioEvent.NodeUpdated(protocol, it)) }
    }

    suspend fun refreshContacts() { command(MC.getContacts(), 20_000) }

    suspend fun refreshChannels() {
        for (i in 0 until 8) runCatching { command(MC.getChannel(i), 3_000) }.getOrNull()?.let { if (it[0].toInt() != MC.RESP_CHANNEL_INFO) return@let }
        _channels.value = channelInfo.values.filter { it.name.isNotBlank() }.map { MeshChannel(it.idx, it.name, protocol) }
            .ifEmpty { listOf(MeshChannel(0, "Public", protocol)) }
    }

    private suspend fun syncMessages() = syncLock.withLock {
        repeat(100) {
            val r = runCatching { command(MC.syncNext()) }.getOrNull() ?: return@withLock
            if (r[0].toInt() == MC.RESP_NO_MORE_MESSAGES) return@withLock
        }
    }

    private fun prefixOf(id: String): ByteArray {
        val c = _contacts.value[id] ?: _contacts.value.values.firstOrNull { it.name == id.removePrefix("name:") }
        return c?.publicKey ?: MC.unhex(id.take(12))
    }
    fun contact(id: String): MC.Contact? = _contacts.value[id]

    private fun airtime(bytes: Int) { presetForAirtime?.let { _events.tryEmit(RadioEvent.Airtime(protocol, LoRaAirtime.timeOnAirMs(bytes + 20, it).toLong())) } }

    override suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?): Result<String> = runCatching {
        if (toNodeId == null) {
            val r = command(MC.sendChannelText(channelIndex, text))
            check(r[0].toInt() == MC.RESP_OK) { "Radio refused the channel message" }
            airtime(text.toByteArray().size + 16)
            "mc-out-${System.nanoTime()}"
        } else {
            val r = command(MC.sendText(prefixOf(toNodeId), text))
            check(r[0].toInt() == MC.RESP_SENT) { if (r[0].toInt() == MC.RESP_ERR) "Contact not found" else "Send failed" }
            val ack = ByteBuffer.wrap(r, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val id = "mc-out-${System.nanoTime()}"
            synchronized(ackToPacket) {
                ackToPacket[ack] = id
                while (ackToPacket.size > 200) ackToPacket.remove(ackToPacket.keys.first())
            }
            airtime(text.toByteArray().size + 16)
            id
        }
    }

    override suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String?): Result<String> = runCatching {
        val r = command(MC.sendChannelData(channelIndex, data))
        check(r[0].toInt() == MC.RESP_OK) { "Radio refused the data packet (needs MeshCore firmware with channel data)" }
        airtime(data.size)
        "mc-bin-${System.nanoTime()}"
    }

    override suspend fun sendPosition(lat: Double, lon: Double, altitudeM: Int?): Result<Unit> = runCatching {
        command(MC.setLatLon(lat, lon)); command(MC.selfAdvert(true))
    }

    override suspend fun requestTraceroute(nodeId: String): Result<Unit> = runCatching {
        val c = contact(nodeId) ?: error("Unknown contact")
        val path = c.directPath ?: error("No known path to ${c.name} yet. Send it a message first.")
        command(MC.tracePath((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), path + c.publicKey[0]))
    }

    override suspend fun applyChannel(channel: EncryptedChannel): Result<Unit> = runCatching {
        require(channel.protocol == ProtocolType.MESHCORE)
        val r = command(MC.setChannel(channel.slotIndex, channel.name, channel.rawKeyBytes()))
        check(r[0].toInt() == MC.RESP_OK) { "Radio refused the channel" }
        refreshChannels()
    }

    override suspend fun setOwnerName(longName: String, shortName: String): Result<Unit> = runCatching { command(MC.setAdvertName(longName)) }

    suspend fun applyPreset(p: MeshCoreRadioPreset, txPowerDbm: Int): Result<Unit> = runCatching {
        val r = command(MC.setRadio(p.frequencyKhz, (p.bandwidthKhz * 1000).toInt(), p.spreadingFactor, p.codingRate))
        check(r[0].toInt() == MC.RESP_OK) { "Radio refused those settings" }
        command(MC.setTxPower(txPowerDbm))
        presetForAirtime = LoRaAirtime.forMeshCore(p)
    }

    suspend fun advertise() = runCatching { command(MC.selfAdvert(true)) }
    suspend fun login(id: String, password: String) = runCatching { command(MC.login(prefixOf(id), password)) }
    suspend fun requestStatus(id: String) = runCatching { command(MC.statusRequest(prefixOf(id))) }
    suspend fun remoteCli(id: String, cmd: String) = runCatching { command(MC.remoteCli(prefixOf(id).copyOf(6), cmd)) }
    suspend fun localCli(cmd: String): Result<String> = runCatching {
        val r = command(MC.localCli(cmd))
        if (r[0].toInt() == MC.RESP_CLI_REPLY) String(r, 1, r.size - 1) else "Not supported by this firmware"
    }
    suspend fun resetPath(id: String) = runCatching { command(MC.resetPath(prefixOf(id))); refreshContacts() }
    suspend fun setPath(id: String, path: ByteArray?) = runCatching {
        val c = contact(id) ?: error("Unknown contact")
        val r = command(MC.updateContactPath(c, path))
        check(r[0].toInt() == MC.RESP_OK) { "Radio refused the path" }
        refreshContacts()
    }
    suspend fun removeContact(id: String) = runCatching { command(MC.removeContact(prefixOf(id))); refreshContacts() }
    suspend fun reboot() = runCatching { link.send(MC.reboot()) }
}
