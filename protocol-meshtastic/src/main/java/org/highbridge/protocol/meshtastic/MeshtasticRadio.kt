// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.protocol.meshtastic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.EncryptedChannel
import org.highbridge.core.protocol.LoRaAirtime
import org.highbridge.core.protocol.MeshChannel
import org.highbridge.core.protocol.MeshNode
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshPayload
import org.highbridge.core.protocol.MeshRadio
import org.highbridge.core.protocol.MeshtasticLoRaConfig
import org.highbridge.core.protocol.MeshtasticModemPreset
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.RadioEvent
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Waypoint
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import java.security.SecureRandom
import org.meshtastic.proto.MeshPacket as MtPacket

/**
 * Meshtastic client over BLE or USB serial.
 * Handshake: send want_config_id, receive my_info / node_info / channel / config until config_complete_id.
 */
class MeshtasticRadio(private val link: MeshtasticLink) : MeshRadio {
    override val protocol = ProtocolType.MESHTASTIC
    /** Meshtastic Data payload limit (DATA_PAYLOAD_LEN = 233). */
    override val maxPayloadBytes: Int = 228

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rng = SecureRandom()
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state
    private val nodes = linkedMapOf<Int, MeshNode>()
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

    private var myNum = 0
    private var configId = 0
    private val configDone = MutableStateFlow(false)
    private val channelSlots = sortedMapOf<Int, Channel>()
    private var loraConfig: Config.LoRaConfig? = null
    private var heartbeat: Job? = null

    /** Current LoRa settings read from the radio, if known. */
    val currentLora: Config.LoRaConfig? get() = loraConfig

    override suspend fun connect(address: String): Result<Unit> = runCatching {
        _state.value = ConnectionState.Connecting(link.name)
        link.open()
        scope.launch { link.frames.collect { runCatching { handle(FromRadio.ADAPTER.decode(it)) } } }
        scope.launch { link.logs.collect { _events.emit(RadioEvent.Log(protocol, it)) } }
        configId = rng.nextInt(Int.MAX_VALUE - 1) + 1
        link.send(ToRadio(want_config_id = configId).encode())
        link.afterWantConfig()
        val ok = withTimeoutOrNull(45_000) { configDone.first { it } }
        if (ok == null) error("The radio didn't finish sending its settings. Is it a Meshtastic radio?")
        _state.value = ConnectionState.Connected(link.name, _myId.value ?: "?")
        scope.launch {
            link.connected.first { !it }
            _state.value = ConnectionState.Error("${link.name} disconnected")
            link.close()
        }
        heartbeat = scope.launch {
            while (isActive) { delay(5 * 60_000L); runCatching { link.send(ToRadio(heartbeat = Heartbeat()).encode()) } }
        }
    }.onFailure { _state.value = ConnectionState.Error(it.message ?: "Connection failed"); link.close() }

    override suspend fun disconnect() {
        heartbeat?.cancel()
        runCatching { link.send(ToRadio(disconnect = true).encode()) }
        scope.cancel() // first, so the drop watcher doesn't report a user disconnect as an error
        link.close()
        _state.value = ConnectionState.Disconnected
    }

    private suspend fun handle(f: FromRadio) {
        f.my_info?.let {
            myNum = it.my_node_num
            _myId.value = idOf(myNum)
        }
        f.metadata?.let { _device.value = "Meshtastic ${it.firmware_version} · ${it.hw_model.name}" }
        f.node_info?.let { upsertNode(it) }
        f.channel?.let { ch ->
            channelSlots[ch.index] = ch
            _channels.value = channelSlots.values.filter { it.role != Channel.Role.DISABLED }.map {
                MeshChannel(it.index, it.settings?.name?.ifBlank { if (it.index == 0) "Primary" else "Channel ${it.index}" } ?: "Channel ${it.index}",
                    protocol, isEncrypted = (it.settings?.psk?.size ?: 0) > 1)
            }
        }
        f.config?.lora?.let { loraConfig = it }
        f.config?.let { c ->
            val cur = _settings.value
            _settings.value = cur.copy(device = c.device ?: cur.device, position = c.position ?: cur.position, power = c.power ?: cur.power,
                lora = c.lora ?: cur.lora, security = c.security ?: cur.security, network = c.network ?: cur.network, display = c.display ?: cur.display)
        }
        f.moduleConfig?.let { m ->
            val cur = _settings.value
            _settings.value = cur.copy(mqtt = m.mqtt ?: cur.mqtt, telemetry = m.telemetry ?: cur.telemetry, neighborInfo = m.neighbor_info ?: cur.neighborInfo,
                storeForward = m.store_forward ?: cur.storeForward, rangeTest = m.range_test ?: cur.rangeTest, externalNotification = m.external_notification ?: cur.externalNotification,
                cannedMessage = m.canned_message ?: cur.cannedMessage, detectionSensor = m.detection_sensor ?: cur.detectionSensor, paxcounter = m.paxcounter ?: cur.paxcounter)
        }
        f.log_record?.let { _events.emit(RadioEvent.Log(protocol, "${it.source}: ${it.message}")) }
        if (f.config_complete_id != null && f.config_complete_id == configId) configDone.value = true
        if (f.rebooted == true) { configDone.value = false; runCatching { link.send(ToRadio(want_config_id = configId).encode()) } }
        f.packet?.let { handlePacket(it) }
    }

    private fun upsertNode(n: NodeInfo) {
        val old = nodes[n.num]
        val pos = n.position
        val node = MeshNode(
            id = idOf(n.num),
            shortName = n.user?.short_name?.ifBlank { null } ?: old?.shortName ?: idOf(n.num).takeLast(4),
            longName = n.user?.long_name?.ifBlank { null } ?: old?.longName ?: idOf(n.num),
            protocol = protocol,
            snr = n.snr.takeIf { it != 0f } ?: old?.snr,
            hopsAway = n.hops_away ?: old?.hopsAway ?: 0,
            latitude = pos?.latitude_i?.takeIf { it != 0 }?.let { it * 1e-7 } ?: old?.latitude,
            longitude = pos?.longitude_i?.takeIf { it != 0 }?.let { it * 1e-7 } ?: old?.longitude,
            batteryPct = n.device_metrics?.battery_level ?: old?.batteryPct,
            voltage = n.device_metrics?.voltage ?: old?.voltage,
            lastHeardMs = if (n.last_heard > 0) n.last_heard * 1000L else old?.lastHeardMs ?: 0L,
        )
        nodes[n.num] = node
        publishNodes(node)
    }

    private fun publishNodes(changed: MeshNode) {
        _nodes.value = nodes.values.filter { it.id != _myId.value }.sortedByDescending { it.lastHeardMs }
        _events.tryEmit(RadioEvent.NodeUpdated(protocol, changed))
    }

    private fun touch(num: Int, snr: Float?, rssi: Int?, hops: Int?, edit: (MeshNode) -> MeshNode = { it }) {
        if (num == 0 || num == myNum) return
        val old = nodes[num] ?: MeshNode(idOf(num), idOf(num).takeLast(4), idOf(num), protocol)
        val n = edit(old.copy(snr = snr ?: old.snr, rssi = rssi ?: old.rssi, hopsAway = hops ?: old.hopsAway, lastHeardMs = System.currentTimeMillis()))
        nodes[num] = n
        publishNodes(n)
    }

    private suspend fun handlePacket(p: MtPacket) {
        val d = p.decoded ?: return
        val from = idOf(p.from)
        val hops = if (p.hop_start > 0) (p.hop_start - p.hop_limit).coerceAtLeast(0) else null
        val snr = p.rx_snr.takeIf { it != 0f }
        val rssi = p.rx_rssi?.takeIf { it != 0 }
        touch(p.from, snr, rssi, hops)
        if (p.from != myNum) _events.emit(RadioEvent.Heard(protocol, from, snr, rssi, hops))
        val toId = if (p.to == BROADCAST) null else idOf(p.to)
        val bytes = d.payload.toByteArray()
        when (d.portnum) {
            PortNum.TEXT_MESSAGE_APP -> _incoming.emit(MeshPacket(idOf(p.id), protocol, from, nodes[p.from]?.shortName ?: from.takeLast(4),
                toId, p.channel, MeshPayload.Text(bytes.decodeToString(), replyTo = d.reply_id.takeIf { it != 0 }?.let { idOf(it) }, emoji = d.emoji != 0)))
            PortNum.WAYPOINT_APP -> runCatching { Waypoint.ADAPTER.decode(bytes) }.getOrNull()?.let { w ->
                val lat = w.latitude_i ?: return@let
                val lon = w.longitude_i ?: return@let
                val expire = w.expire.toLong() and 0xFFFFFFFFL
                _events.emit(RadioEvent.Waypoint(protocol, w.id, w.name, w.description, lat * 1e-7, lon * 1e-7, w.icon, expire * 1000,
                    from, deleted = expire in 1 until System.currentTimeMillis() / 1000))
            }
            PortNum.POSITION_APP -> runCatching { Position.ADAPTER.decode(bytes) }.getOrNull()?.let { pos ->
                val lat = pos.latitude_i ?: return@let
                val lon = pos.longitude_i ?: return@let
                touch(p.from, null, null, null) { it.copy(latitude = lat * 1e-7, longitude = lon * 1e-7) }
                _events.emit(RadioEvent.Position(protocol, from, lat * 1e-7, lon * 1e-7, pos.altitude, if (pos.time > 0) pos.time * 1000L else System.currentTimeMillis()))
            }
            PortNum.NODEINFO_APP -> runCatching { User.ADAPTER.decode(bytes) }.getOrNull()?.let { u ->
                touch(p.from, null, null, null) { it.copy(shortName = u.short_name.ifBlank { it.shortName }, longName = u.long_name.ifBlank { it.longName }) }
            }
            PortNum.TELEMETRY_APP -> runCatching { Telemetry.ADAPTER.decode(bytes) }.getOrNull()?.let { t ->
                val dm = t.device_metrics; val em = t.environment_metrics
                if (dm != null) touch(p.from, null, null, null) { it.copy(batteryPct = dm.battery_level ?: it.batteryPct, voltage = dm.voltage ?: it.voltage) }
                _events.emit(RadioEvent.Telemetry(protocol, from, if (t.time > 0) t.time * 1000L else System.currentTimeMillis(),
                    batteryPct = dm?.battery_level, voltage = dm?.voltage ?: em?.voltage, channelUtil = dm?.channel_utilization, airUtilTx = dm?.air_util_tx,
                    temperatureC = em?.temperature, humidityPct = em?.relative_humidity, pressureHpa = em?.barometric_pressure))
            }
            PortNum.ROUTING_APP -> runCatching { Routing.ADAPTER.decode(bytes) }.getOrNull()?.let { r ->
                if (d.request_id != 0) {
                    val err = r.error_reason
                    val ok = err == null || err == Routing.Error.NONE
                    _events.emit(RadioEvent.Ack(protocol, idOf(d.request_id), ok, if (ok) null else err?.name))
                }
                r.route_reply?.let { emitRoute(p.from, it) }
            }
            PortNum.TRACEROUTE_APP -> if (d.request_id != 0) runCatching { RouteDiscovery.ADAPTER.decode(bytes) }.getOrNull()?.let { emitRoute(p.from, it) }
            PortNum.RANGE_TEST_APP -> _events.emit(RadioEvent.RangeTest(protocol, from, bytes.decodeToString(), snr, rssi, System.currentTimeMillis()))
            PortNum.ADMIN_APP -> runCatching { AdminMessage.ADAPTER.decode(bytes) }.getOrNull()?.let { a ->
                a.get_config_response?.lora?.let { loraConfig = it }
                a.get_device_metadata_response?.let { _device.value = "Meshtastic ${it.firmware_version} · ${it.hw_model.name}" }
                _events.emit(RadioEvent.AdminReply(protocol, from, a.toString().take(400)))
            }
            PortNum.PRIVATE_APP -> _incoming.emit(MeshPacket(idOf(p.id), protocol, from, nodes[p.from]?.shortName ?: from.takeLast(4),
                toId, p.channel, MeshPayload.Binary(PortNum.PRIVATE_APP.value, bytes)))
            else -> {}
        }
    }

    private suspend fun emitRoute(destNum: Int, r: RouteDiscovery) {
        _events.emit(RadioEvent.Route(protocol, idOf(destNum), r.route.map { idOf(it) }, r.snr_towards.map { it / 4f },
            r.route_back.map { idOf(it) }, r.snr_back.map { it / 4f }))
    }

    private fun newPacketId(): Int = rng.nextInt(Int.MAX_VALUE - 1) + 1

    private suspend fun sendData(channelIndex: Int, to: Int, port: PortNum, payload: ByteArray, wantAck: Boolean, wantResponse: Boolean = false,
                                 replyId: Int = 0, emoji: Boolean = false): String {
        require(payload.size <= 233) { "Too large for one Meshtastic packet" }
        val id = newPacketId()
        val pkt = MtPacket(to = to, channel = channelIndex, id = id, want_ack = wantAck, hop_limit = loraConfig?.hop_limit?.takeIf { it > 0 } ?: 3,
            decoded = Data(portnum = port, payload = payload.toByteString(), want_response = wantResponse, reply_id = replyId, emoji = if (emoji) 1 else 0))
        link.send(ToRadio(packet = pkt).encode())
        val preset = loraConfig?.modem_preset?.value?.let { MeshtasticModemPreset.fromCode(it) } ?: MeshtasticModemPreset.LONG_FAST
        _events.tryEmit(RadioEvent.Airtime(protocol, LoRaAirtime.timeOnAirMs(payload.size + 32, LoRaAirtime.forPreset(preset)).toLong()))
        return idOf(id)
    }

    private fun numOf(id: String?): Int = if (id == null) BROADCAST else id.removePrefix("!").toLong(16).toInt()

    override suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?): Result<String> = runCatching {
        sendData(channelIndex, numOf(toNodeId), PortNum.TEXT_MESSAGE_APP, text.toByteArray(), wantAck = true)
    }

    override suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?, replyTo: String?, emoji: Boolean): Result<String> = runCatching {
        sendData(channelIndex, numOf(toNodeId), PortNum.TEXT_MESSAGE_APP, text.toByteArray(), wantAck = true,
            replyId = replyTo?.let { runCatching { numOf(it) }.getOrDefault(0) } ?: 0, emoji = emoji)
    }

    override suspend fun sendWaypoint(channelIndex: Int, id: Int, name: String, description: String, lat: Double, lon: Double, icon: Int, expireMs: Long): Result<Int> = runCatching {
        val wid = if (id != 0) id else (newPacketId() and 0x7FFFFFFF).coerceAtLeast(1)
        val w = Waypoint(id = wid, latitude_i = (lat * 1e7).toInt(), longitude_i = (lon * 1e7).toInt(), expire = (expireMs / 1000).toInt(),
            name = name.take(29), description = description.take(99), icon = icon)
        sendData(channelIndex, BROADCAST, PortNum.WAYPOINT_APP, w.encode(), wantAck = false)
        wid
    }

    /** Position broadcast with want_ack: the radio reports an (implicit) ack once a neighbour rebroadcasts it. */
    override suspend fun sendWardrivePing(lat: Double, lon: Double): Result<String> = runCatching {
        val pos = Position(latitude_i = (lat * 1e7).toInt(), longitude_i = (lon * 1e7).toInt(), time = (System.currentTimeMillis() / 1000).toInt())
        sendData(0, BROADCAST, PortNum.POSITION_APP, pos.encode(), wantAck = true)
    }

    override suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String?): Result<String> = runCatching {
        val port = PortNum.fromValue(portId) ?: PortNum.PRIVATE_APP
        sendData(channelIndex, numOf(toNodeId), port, data, wantAck = toNodeId != null)
    }

    override suspend fun sendPosition(lat: Double, lon: Double, altitudeM: Int?): Result<Unit> = runCatching {
        val pos = Position(latitude_i = (lat * 1e7).toInt(), longitude_i = (lon * 1e7).toInt(), altitude = altitudeM,
            time = (System.currentTimeMillis() / 1000).toInt())
        sendData(0, BROADCAST, PortNum.POSITION_APP, pos.encode(), wantAck = false)
    }

    override suspend fun requestTraceroute(nodeId: String): Result<Unit> = runCatching {
        sendData(0, numOf(nodeId), PortNum.TRACEROUTE_APP, RouteDiscovery().encode(), wantAck = false, wantResponse = true)
    }

    suspend fun sendRangeTest(seq: Int): Result<String> = runCatching {
        sendData(0, BROADCAST, PortNum.RANGE_TEST_APP, "seq $seq".toByteArray(), wantAck = false)
    }

    /** Everything the radio reported about its settings during the handshake, kept current as you change it. */
    data class Settings(
        val device: Config.DeviceConfig? = null, val position: Config.PositionConfig? = null, val power: Config.PowerConfig? = null,
        val lora: Config.LoRaConfig? = null, val security: Config.SecurityConfig? = null, val network: Config.NetworkConfig? = null,
        val display: Config.DisplayConfig? = null,
        val mqtt: org.meshtastic.proto.ModuleConfig.MQTTConfig? = null, val telemetry: org.meshtastic.proto.ModuleConfig.TelemetryConfig? = null,
        val neighborInfo: org.meshtastic.proto.ModuleConfig.NeighborInfoConfig? = null, val storeForward: org.meshtastic.proto.ModuleConfig.StoreForwardConfig? = null,
        val rangeTest: org.meshtastic.proto.ModuleConfig.RangeTestConfig? = null, val externalNotification: org.meshtastic.proto.ModuleConfig.ExternalNotificationConfig? = null,
        val cannedMessage: org.meshtastic.proto.ModuleConfig.CannedMessageConfig? = null, val detectionSensor: org.meshtastic.proto.ModuleConfig.DetectionSensorConfig? = null,
        val paxcounter: org.meshtastic.proto.ModuleConfig.PaxcounterConfig? = null,
    )
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    /** Writes one config section (device, position, power, network, display, security…) in an edit transaction. */
    suspend fun setConfig(c: Config): Result<Unit> = runCatching {
        admin(AdminMessage(begin_edit_settings = true)); admin(AdminMessage(set_config = c)); admin(AdminMessage(commit_edit_settings = true))
        val cur = _settings.value
        _settings.value = cur.copy(device = c.device ?: cur.device, position = c.position ?: cur.position, power = c.power ?: cur.power,
            lora = c.lora ?: cur.lora, security = c.security ?: cur.security, network = c.network ?: cur.network, display = c.display ?: cur.display)
    }

    /** Writes one module section (MQTT, telemetry, neighbour info, store & forward, range test…). */
    suspend fun setModule(m: org.meshtastic.proto.ModuleConfig): Result<Unit> = runCatching {
        admin(AdminMessage(begin_edit_settings = true)); admin(AdminMessage(set_module_config = m)); admin(AdminMessage(commit_edit_settings = true))
        val cur = _settings.value
        _settings.value = cur.copy(mqtt = m.mqtt ?: cur.mqtt, telemetry = m.telemetry ?: cur.telemetry, neighborInfo = m.neighbor_info ?: cur.neighborInfo,
            storeForward = m.store_forward ?: cur.storeForward, rangeTest = m.range_test ?: cur.rangeTest, externalNotification = m.external_notification ?: cur.externalNotification,
            cannedMessage = m.canned_message ?: cur.cannedMessage, detectionSensor = m.detection_sensor ?: cur.detectionSensor, paxcounter = m.paxcounter ?: cur.paxcounter)
    }

    /** Favourite / ignore on the radio itself, so it also affects what the radio shows and relays to you. */
    suspend fun setFavoriteOnRadio(nodeId: String, favorite: Boolean): Result<Unit> = runCatching {
        val n = numOf(nodeId); admin(if (favorite) AdminMessage(set_favorite_node = n) else AdminMessage(remove_favorite_node = n))
    }
    suspend fun setIgnoredOnRadio(nodeId: String, ignored: Boolean): Result<Unit> = runCatching {
        val n = numOf(nodeId); admin(if (ignored) AdminMessage(set_ignored_node = n) else AdminMessage(remove_ignored_node = n))
    }
    suspend fun shutdown(): Result<Unit> = runCatching { admin(AdminMessage(shutdown_seconds = 2)) }
    /**
     * Licensed (ham) mode: your call sign becomes the node name, encryption is turned off as FCC Part 97
     * requires, and power limits follow your license instead of Part 15. [frequencyMHz] 0 = keep the current one.
     */
    suspend fun setHamMode(callSign: String, txPowerDbm: Int, frequencyMHz: Float = 0f): Result<Unit> = runCatching {
        val cs = callSign.trim().uppercase()
        require(Regex("^[A-Z0-9]{1,3}[0-9][A-Z0-9]{0,4}$").matches(cs)) { "That doesn't look like a call sign" }
        admin(AdminMessage(set_ham_mode = org.meshtastic.proto.HamParameters(call_sign = cs, tx_power = txPowerDbm, frequency = frequencyMHz,
            short_name = cs.takeLast(4), long_name = cs)))
    }
    suspend fun nodeDbReset(): Result<Unit> = runCatching { admin(AdminMessage(nodedb_reset = true)) }
    /** Erases all settings (keeps nothing but the firmware). [device] = also erase keys and the node database. */
    suspend fun factoryReset(device: Boolean): Result<Unit> = runCatching { admin(if (device) AdminMessage(factory_reset_device = 1) else AdminMessage(factory_reset_config = 1)) }
    suspend fun setCannedMessages(messages: String): Result<Unit> = runCatching { admin(AdminMessage(set_canned_message_module_messages = messages.take(200))) }

    private suspend fun admin(msg: AdminMessage) {
        sendData(0, myNum, PortNum.ADMIN_APP, msg.encode(), wantAck = true, wantResponse = true)
    }

    override suspend fun applyChannel(channel: EncryptedChannel): Result<Unit> = runCatching {
        require(channel.protocol == ProtocolType.MESHTASTIC)
        val settings = ChannelSettings(name = channel.name, psk = channel.rawKeyBytes().toByteString())
        val ch = Channel(index = channel.slotIndex, settings = settings,
            role = if (channel.slotIndex == 0) Channel.Role.PRIMARY else Channel.Role.SECONDARY)
        admin(AdminMessage(set_channel = ch))
        channelSlots[ch.index] = ch
        _channels.value = (_channels.value.filter { it.index != ch.index } + MeshChannel(ch.index, channel.name, protocol, isEncrypted = true)).sortedBy { it.index }
    }

    /** Disables a secondary channel slot. */
    suspend fun removeChannel(index: Int): Result<Unit> = runCatching {
        require(index in 1..7) { "Only secondary channels (1–7) can be removed" }
        admin(AdminMessage(set_channel = Channel(index = index, role = Channel.Role.DISABLED)))
        channelSlots.remove(index)
        _channels.value = _channels.value.filter { it.index != index }
    }

        /** Writes region, preset, hop limit and power. Power 0 means "region maximum". */
    suspend fun applyLoRa(cfg: MeshtasticLoRaConfig): Result<Unit> = runCatching {
        val base = loraConfig ?: Config.LoRaConfig()
        val region = Config.LoRaConfig.RegionCode.fromValue(cfg.region.code) ?: error("Firmware doesn't know region ${cfg.region.label}")
        val preset = Config.LoRaConfig.ModemPreset.fromValue(cfg.preset.code) ?: error("Unknown preset")
        val lora = base.copy(use_preset = true, region = region, modem_preset = preset, hop_limit = cfg.hopLimit,
            tx_enabled = true, tx_power = cfg.effectiveTxPowerDbm, channel_num = cfg.frequencySlot)
        admin(AdminMessage(set_config = Config(lora = lora)))
        loraConfig = lora
    }

    override suspend fun setOwnerName(longName: String, shortName: String): Result<Unit> = runCatching {
        admin(AdminMessage(set_owner = User(id = _myId.value ?: "", long_name = longName.take(39), short_name = shortName.take(4))))
    }

    suspend fun requestMetadata(): Result<Unit> = runCatching { admin(AdminMessage(get_device_metadata_request = true)) }

    /** Reboot into the nRF52 / ESP32 bootloader for firmware updates. */
    suspend fun enterDfuMode(): Result<Unit> = runCatching { admin(AdminMessage(enter_dfu_mode_request = true)) }
    suspend fun reboot(seconds: Int = 2): Result<Unit> = runCatching { admin(AdminMessage(reboot_seconds = seconds)) }

    companion object {
        const val BROADCAST = -1 // 0xFFFFFFFF
        fun idOf(num: Int): String = "!%08x".format(num)
    }
}
