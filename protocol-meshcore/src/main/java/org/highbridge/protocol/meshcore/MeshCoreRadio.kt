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
    private val channelInfo = sortedMapOf<Int, MC.ChannelInfo>() // guarded by itself
    private val ackToPacket = LinkedHashMap<Int, String>()
    private val syncLock = Mutex()
    private var presetForAirtime: LoRaAirtime.Params? = null

    companion object {
        const val WARDRIVE_CHANNEL = "#wardriving"
        val wardriveChannelHash: Int by lazy { MC.channelHash(EncryptedChannel.meshCoreHashtag(WARDRIVE_CHANNEL).rawKeyBytes()) }
    }
    @Volatile private var maxChannels = 8

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
            MC.RESP_DEVICE_INFO -> MC.parseDeviceInfo(f)?.let { _device.value = "MeshCore ${it.version} · ${it.manufacturer}".trim(); maxChannels = it.maxChannels.coerceIn(1, 64) }
            MC.RESP_CONTACTS_START -> contactBuffer = mutableMapOf()
            MC.RESP_CONTACT, MC.PUSH_NEW_ADVERT -> MC.parseContact(f)?.let { c ->
                if (code == MC.RESP_CONTACT) contactBuffer[c.prefixHex] = c else { noteAdvert(c.prefixHex, c); _contacts.value = _contacts.value + (c.prefixHex to c); publishNodes() }
            }
            MC.RESP_END_OF_CONTACTS -> {
                _contacts.value = contactBuffer.toMap(); savedPrefixes = contactBuffer.keys.toSet(); publishNodes()
                // Fill in names for adverts that arrived as a bare key.
                _discovered.value = _discovered.value.mapValues { (k, d) -> if (d.contact == null) d.copy(contact = contactBuffer[k]) else d }
            }
            MC.RESP_CONTACT_MSG_RECV, MC.RESP_CONTACT_MSG_RECV_V3, MC.RESP_CHANNEL_MSG_RECV, MC.RESP_CHANNEL_MSG_RECV_V3 -> MC.parseMessage(f)?.let { onMessage(it) }
            MC.RESP_CHANNEL_DATA_RECV -> MC.parseChannelData(f)?.let { d ->
                if (d.dataType == MC.DATA_TYPE_HIGHBRIDGE)
                    _incoming.emit(MeshPacket("mc-d-${System.nanoTime()}", protocol, "channel-${d.channelIdx}", "MC", null, d.channelIdx, MeshPayload.Binary(256, d.data)))
            }
            MC.RESP_CHANNEL_INFO -> {} // read by refreshChannels() from the command reply
            MC.PUSH_TELEMETRY_RESPONSE -> MC.parseTelemetry(f)?.let { (prefix, t) ->
                val id = _contacts.value.keys.firstOrNull { it.startsWith(prefix) } ?: prefix
                _events.emit(RadioEvent.Telemetry(protocol, id, System.currentTimeMillis(), voltage = t.voltage, temperatureC = t.temperatureC,
                    humidityPct = t.humidityPct, pressureHpa = t.pressureHpa))
                _events.emit(RadioEvent.AdminReply(protocol, id, "Telemetry: " + listOfNotNull(t.voltage?.let { "%.2f V".format(it) },
                    t.temperatureC?.let { "%.1f °C".format(it) }, t.humidityPct?.let { "%.0f%% RH".format(it) }, t.pressureHpa?.let { "%.1f hPa".format(it) }).joinToString(" · ").ifEmpty { "no sensors" }))
            }
            MC.PUSH_LOG_RX_DATA -> MC.parseRxLog(f)?.let {
                _events.emit(RadioEvent.RxLog(protocol, it.snr, it.rssi, it.payloadType, it.hops, it.lastHop, it.channelHash))
                it.lastHop?.let { hop -> _events.emit(RadioEvent.Heard(protocol, "rpt:$hop", it.snr, it.rssi, 0)) }
            }
            MC.RESP_BATT_AND_STORAGE -> if (f.size >= 3) {
                val mv = ByteBuffer.wrap(f, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                _myId.value?.let { _events.emit(RadioEvent.Telemetry(protocol, it, System.currentTimeMillis(), voltage = mv / 1000f)) }
            }
            MC.PUSH_MSG_WAITING -> scope.launch { syncMessages() }
            MC.PUSH_SEND_CONFIRMED -> if (f.size >= 5) {
                val ack = ByteBuffer.wrap(f, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                synchronized(ackToPacket) { ackToPacket.remove(ack) }?.let { _events.emit(RadioEvent.Ack(protocol, it, true)) }
            }
            MC.PUSH_ADVERT, MC.PUSH_PATH_UPDATED -> {
                if (code == MC.PUSH_ADVERT && f.size >= 7) noteAdvert(MC.hex(f.copyOfRange(1, 7)), null)
                scope.launch { runCatching { refreshContacts() } }
            }
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
                else -> {
                    // Room server posts are signed with the author's 4-byte key prefix: show who wrote them.
                    val author = m.authorPrefixHex?.let { a -> _contacts.value.values.firstOrNull { MC.hex(it.publicKey).startsWith(a) }?.name ?: "@$a" }
                    val name = if (author != null) "$author · ${contact?.name ?: "room"}" else contact?.name ?: prefix.take(4)
                    _incoming.emit(MeshPacket("mc-$prefix-${m.timestamp}-${m.authorPrefixHex ?: ""}", protocol, prefix, name, _myId.value ?: "me", 0, MeshPayload.Text(m.text), now))
                }
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
        val found = sortedMapOf<Int, MC.ChannelInfo>()
        for (i in 0 until maxChannels) {
            val r = runCatching { command(MC.getChannel(i), 3_000) }.getOrNull() ?: break
            if (r[0].toInt() != MC.RESP_CHANNEL_INFO) break // past the last slot this firmware has
            MC.parseChannelInfo(r)?.let { found[it.idx] = it }
        }
        synchronized(channelInfo) { channelInfo.clear(); channelInfo.putAll(found) }
        _channels.value = found.values.filter { it.name.isNotBlank() }.map { MeshChannel(it.idx, it.name, protocol, isEncrypted = it.idx != 0) }
            .ifEmpty { listOf(MeshChannel(0, "Public", protocol)) }
    }

    /**
     * Wardriving ping: "lat, lon" on the #wardriving hashtag channel (the convention MeshCore coverage
     * maps use), joining that channel in a free slot if needed. Repeats show up as [RadioEvent.RxLog]
     * events with [wardriveChannelHash].
     */
    override suspend fun sendWardrivePing(lat: Double, lon: Double): Result<String> = runCatching {
        val ch = EncryptedChannel.meshCoreHashtag(WARDRIVE_CHANNEL)
        val slot = synchronized(channelInfo) { channelInfo.values.firstOrNull { it.name.equals(WARDRIVE_CHANNEL, true) }?.idx }
            ?: (freeChannelSlot() ?: error("No free channel slot for $WARDRIVE_CHANNEL. Leave a channel first.")).also { s ->
                applyChannel(ch.copy(slotIndex = s)).getOrThrow()
            }
        sendText(slot, "%.5f, %.5f".format(java.util.Locale.US, lat, lon), null).getOrThrow()
    }

    /** Lowest empty channel slot (slot 0 is the Public channel), or null when all are in use. */
    fun freeChannelSlot(): Int? {
        val used = synchronized(channelInfo) { channelInfo.filterValues { it.name.isNotBlank() }.keys.toSet() }
        return (1 until maxChannels).firstOrNull { it !in used }
    }

    /** Empties a channel slot on the radio. */
    suspend fun removeChannel(idx: Int): Result<Unit> = runCatching {
        require(idx > 0) { "The Public channel can't be removed" }
        val r = command(MC.setChannel(idx, "", ByteArray(16)))
        check(r[0].toInt() == MC.RESP_OK) { "Radio refused" }
        refreshChannels()
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
            // Firmware answers OK or SENT depending on version.
            check(r[0].toInt() == MC.RESP_OK || r[0].toInt() == MC.RESP_SENT) { "Radio refused the channel message" }
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
        check(r[0].toInt() == MC.RESP_OK || r[0].toInt() == MC.RESP_SENT) { "Radio refused the data packet (needs MeshCore firmware with channel data)" }
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
        require(channel.slotIndex in 0 until maxChannels) { "This radio has channel slots 0–${maxChannels - 1}" }
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

    /** Announce yourself: [flood] = across the mesh; false = only to radios in direct range (zero-hop). */
    suspend fun advertise(flood: Boolean = true) = runCatching { command(MC.selfAdvert(flood)) }

    /** Asks a contact (repeater, sensor or companion that allows it) for its telemetry; replies arrive as Telemetry events. */
    suspend fun requestTelemetry(id: String): Result<Unit> = runCatching {
        val c = contact(id) ?: error("Unknown contact")
        val r = command(MC.telemetryRequest(c.publicKey))
        check(r[0].toInt() == MC.RESP_SENT || r[0].toInt() == MC.RESP_OK) { "Radio refused" }
    }

    /**
     * Region scope (firmware 1.10+): flood packets you send carry this region's transport code, so repeaters
     * configured with regions keep them inside it. The key is the first 16 bytes of SHA-256 of "#" + name.
     * Null or blank clears it (classic, unscoped flooding).
     */
    suspend fun setRegionScope(region: String?): Result<Unit> = runCatching {
        val bare = region?.trim()?.removePrefix("#").orEmpty()
        val frame = if (bare.isEmpty()) byteArrayOf(MC.CMD_SET_FLOOD_SCOPE.toByte(), 0)
            else byteArrayOf(MC.CMD_SET_FLOOD_SCOPE.toByte(), 0) + MC.regionKey(bare)
        val r = command(frame)
        check(r[0].toInt() == MC.RESP_OK) { "This radio's firmware doesn't support regions yet (needs 1.10 or newer)" }
    }

    // ------------------------------------------------------------------ discover

    /** An advert heard recently, whether or not the radio has saved it as a contact. */
    data class Discovered(val prefixHex: String, val contact: MC.Contact?, val heardMs: Long)
    private val _discovered = MutableStateFlow<Map<String, Discovered>>(emptyMap())
    /** Adverts heard since connecting, newest first when sorted by heardMs (the Discover screen). */
    val discovered: StateFlow<Map<String, Discovered>> = _discovered

    private fun noteAdvert(prefix: String, c: MC.Contact?) {
        val old = _discovered.value[prefix]
        _discovered.value = (_discovered.value + (prefix to Discovered(prefix, c ?: old?.contact ?: _contacts.value[prefix], System.currentTimeMillis())))
            .entries.sortedByDescending { it.value.heardMs }.take(200).associate { it.key to it.value }
    }

    /** True if the radio has this advert saved as a contact (from the last contact sync). */
    fun isSaved(prefix: String) = savedPrefixes.contains(prefix)
    @Volatile private var savedPrefixes: Set<String> = emptySet()

    /** Saves a discovered advert to the radio's contacts. */
    suspend fun addDiscovered(prefix: String): Result<String> = runCatching {
        val c = _discovered.value[prefix]?.contact ?: _contacts.value[prefix] ?: error("Wait for its next advert; only its key prefix is known so far")
        val r = command(MC.updateContactPath(c, c.directPath))
        check(r[0].toInt() == MC.RESP_OK) { "Radio refused the contact (its contact list may be full)" }
        refreshContacts()
        c.name
    }

    // ------------------------------------------------------------------ config backup

    /** Name, radio settings, power and channels as JSON, to back up or copy to another radio. [region] is the app's flood scope. */
    fun exportConfig(region: String?): String {
        val me = selfInfo.value ?: error("Not connected yet")
        val o = org.json.JSONObject().put("format", "highbridge-meshcore-config").put("v", 1)
            .put("name", me.name).put("freq_khz", me.freqKhz).put("bw_hz", me.bwHz).put("sf", me.sf).put("cr", me.cr).put("tx_power_dbm", me.txPower)
        if (me.lat != 0.0 || me.lon != 0.0) o.put("lat", me.lat).put("lon", me.lon)
        if (!region.isNullOrBlank()) o.put("region", region)
        val chans = org.json.JSONArray()
        synchronized(channelInfo) { channelInfo.values.filter { it.name.isNotBlank() } }.forEach { c ->
            chans.put(org.json.JSONObject().put("idx", c.idx).put("name", c.name).put("secret_hex", MC.hex(c.secret)))
        }
        return o.put("channels", chans).toString(2)
    }

    /** Applies a config made by [exportConfig]. Returns the region it named, if any, so the app can apply that too. */
    suspend fun importConfig(json: String, includePosition: Boolean = false): Result<String?> = runCatching {
        val o = org.json.JSONObject(json)
        require(o.optString("format") == "highbridge-meshcore-config") { "That isn't a Highbridge MeshCore config file" }
        o.optString("name").takeIf { it.isNotBlank() }?.let { command(MC.setAdvertName(it)) }
        if (o.has("freq_khz")) {
            val r = command(MC.setRadio(o.getInt("freq_khz"), o.getInt("bw_hz"), o.getInt("sf"), o.getInt("cr")))
            check(r[0].toInt() == MC.RESP_OK) { "Radio refused the radio settings" }
        }
        if (o.has("tx_power_dbm")) command(MC.setTxPower(o.getInt("tx_power_dbm")))
        if (includePosition && o.has("lat")) command(MC.setLatLon(o.getDouble("lat"), o.getDouble("lon")))
        val chans = o.optJSONArray("channels") ?: org.json.JSONArray()
        for (i in 0 until chans.length()) {
            val c = chans.getJSONObject(i)
            val idx = c.getInt("idx"); if (idx >= maxChannels) continue
            command(MC.setChannel(idx, c.getString("name"), MC.unhex(c.getString("secret_hex"))))
        }
        refreshChannels()
        o.optString("region").takeIf { it.isNotBlank() }
    }

    /** Your own contact as a link the MeshCore app and Highbridge can import. */
    fun myContactUri(): String? = selfInfo.value?.let { MC.contactUri(it.name, it.publicKey, 1) }

    /** Adds a contact from a meshcore://contact/add link (it floods until a path is learned). */
    suspend fun importContact(uri: String): Result<String> = runCatching {
        val c = MC.parseContactUri(uri) ?: error("That isn't a MeshCore contact code")
        val r = command(MC.updateContactPath(c, null))
        check(r[0].toInt() == MC.RESP_OK) { "Radio refused the contact" }
        refreshContacts()
        c.name
    }
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
