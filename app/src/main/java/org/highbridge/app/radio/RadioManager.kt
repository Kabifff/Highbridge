// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.radio

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.highbridge.app.MainActivity
import org.highbridge.app.R
import org.highbridge.app.data.ChannelEntity
import org.highbridge.app.data.GeofenceEntity
import org.highbridge.app.data.MeshDao
import org.highbridge.app.data.MessageEntity
import org.highbridge.app.data.NodeEntity
import org.highbridge.app.data.Prefs
import org.highbridge.app.data.RangeTestEntity
import org.highbridge.app.data.RouteEntity
import org.highbridge.app.data.TelemetryEntity
import org.highbridge.app.data.TrackPointEntity
import org.highbridge.app.data.WaypointEntity
import org.highbridge.app.pro.AirtimeMonitor
import org.highbridge.app.pro.AtakCotBridge
import org.highbridge.app.pro.GeofenceRules
import org.highbridge.app.pro.Geo
import org.highbridge.app.rns.ReticulumSupport
import org.highbridge.core.media.LoRaImageCompressor
import org.highbridge.core.media.MediaTransferManager
import org.highbridge.core.media.MediaType
import org.highbridge.core.media.NativeCodec2
import org.highbridge.core.media.VoiceIO
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.EncryptedChannel
import org.highbridge.core.protocol.MeshNode
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshPayload
import org.highbridge.core.protocol.MeshRadio
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.RadioEvent
import org.highbridge.core.protocol.link.BleNusStream
import org.highbridge.core.protocol.link.StreamLink
import org.highbridge.core.protocol.link.UsbDevices
import org.highbridge.core.protocol.link.UsbSerialLink
import org.highbridge.feature.bridge.BridgePrefs
import org.highbridge.feature.bridge.MeshBridgeService
import org.highbridge.feature.bridge.TransportRegistry
import org.highbridge.protocol.meshcore.MeshCoreLink
import org.highbridge.protocol.meshcore.MeshCoreRadio
import org.highbridge.protocol.meshtastic.MeshtasticBleLink
import org.highbridge.protocol.meshtastic.MeshtasticRadio
import org.highbridge.protocol.meshtastic.MeshtasticSerialLink
import org.highbridge.protocol.reticulum.RNodeController
import org.highbridge.protocol.reticulum.RNodeParams
import org.highbridge.protocol.reticulum.ReticulumRadio
import org.highbridge.protocol.reticulum.RnsEngine
import java.io.File
import java.util.Base64

/** Owns every connected radio, stores what they hear, and sends on the user's behalf. */
class RadioManager(private val ctx: Context, private val dao: MeshDao, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _radios = MutableStateFlow<Map<ProtocolType, MeshRadio>>(emptyMap())
    val radios: StateFlow<Map<ProtocolType, MeshRadio>> = _radios
    private val jobs = java.util.concurrent.ConcurrentHashMap<ProtocolType, List<Job>>()
    private val usbLinks = java.util.concurrent.ConcurrentHashMap<ProtocolType, UsbSerialLink>()
    private val reconnectLock = kotlinx.coroutines.sync.Mutex()
    private val reconnectJobs = java.util.concurrent.ConcurrentHashMap<ProtocolType, Job>()

    private val _log = MutableSharedFlow<Pair<ProtocolType, String>>(replay = 200, extraBufferCapacity = 500)
    /** Debug log lines and admin replies from every radio, for the terminal screen. */
    val log = _log.asSharedFlow()
    private val _admin = MutableSharedFlow<RadioEvent.AdminReply>(extraBufferCapacity = 64)
    val adminReplies = _admin.asSharedFlow()
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts = _toast.asSharedFlow()

    val airtime = AirtimeMonitor(prefs.region.value) { SystemClock.elapsedRealtime() }
    private val _airtimePct = MutableStateFlow(0f)
    val airtimePercent: StateFlow<Float> = _airtimePct

    val myLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    /** Your GPS altitude in metres, when the phone reports one. */
    val myAltitudeM = MutableStateFlow<Double?>(null)
    @Volatile var foreground = false
    @Volatile var openConversation: String? = null

    val media = MediaTransferManager({ _radios.value }, { if (prefs.powerProfile.value.allowBridgeMedia) 3_000L else 6_000L })
    private val rnsEngine: RnsEngine by lazy { ReticulumSupport.engine(ctx) }

    @Volatile var recordingTrackId: Long? = null
        private set

    init {
        // APRS is ham radio: never offered to the bridge, so unlicensed mesh traffic can't reach it that way.
        TransportRegistry.provider = { _radios.value - ProtocolType.APRS }
        TransportRegistry.hasConnectedRadios = { (_radios.value - ProtocolType.APRS).isNotEmpty() }
        TransportRegistry.reconnect = { reconnectRemembered() }
        createNotificationChannels()
        scope.launch { media.completed.collect { onMediaComplete(it) } }
        scope.launch { prefs.region.collect { airtime.region = it } }
        scope.launch { stationIdLoop() }
        // Keep the home-screen widget current.
        scope.launch {
            kotlinx.coroutines.flow.combine(_radios, dao.unreadCount()) { r, u -> r.keys.map { it.shortTag } to u }
                .collect { (names, unread) -> org.highbridge.app.widget.StatusWidget.update(ctx, names, unread) }
        }
    }

    fun radio(p: ProtocolType) = _radios.value[p]

    /** Coverage recording for the stpaulmesh.com wardriving maps. */
    val wardriver by lazy { Wardriver(ctx, dao, prefs, scope, { radio(it) }, { !airtime.mustHold() }) }
    val meshtastic get() = radio(ProtocolType.MESHTASTIC) as? MeshtasticRadio
    val meshcore get() = radio(ProtocolType.MESHCORE) as? MeshCoreRadio
    val reticulum get() = radio(ProtocolType.RETICULUM) as? ReticulumRadio
    val aprs get() = radio(ProtocolType.APRS) as? org.highbridge.app.aprs.AprsRadio
    val aprsGateway = org.highbridge.app.aprs.AprsGateway({ prefs.aprsGatewayRegs.value }, { prefs.setAprsGatewayRegs(it); aprs?.refreshFilter() })

    // ------------------------------------------------------------------ connecting

    @SuppressLint("MissingPermission")
    suspend fun connectBle(device: BluetoothDevice, protocol: ProtocolType): Result<Unit> {
        disconnect(protocol)
        val radio: MeshRadio = runCatching { when (protocol) {
            ProtocolType.MESHTASTIC -> MeshtasticRadio(MeshtasticBleLink(ctx, device))
            ProtocolType.MESHCORE -> MeshCoreRadio(MeshCoreLink(BleNusStream(ctx, device), isSerial = false))
            ProtocolType.RETICULUM -> reticulumRadio(BleNusStream(ctx, device))
            ProtocolType.APRS -> aprsRadio(org.highbridge.core.protocol.link.BleKissStream(ctx, device), device.name ?: device.address)
        } }.getOrElse { return Result.failure(it) }
        return attach(radio).onSuccess { prefs.rememberDevice("BLE|${protocol.name}|${device.address}") }
    }

    /** Opens a USB radio, works out which firmware it runs, and connects with the right protocol. */
    suspend fun connectUsb(driver: UsbSerialDriver): Result<ProtocolType> = runCatching {
        val usb = ctx.getSystemService(UsbManager::class.java)
        val kind = UsbProbe.detect(usb, driver) ?: error("No Meshtastic, MeshCore or RNode firmware answered on ${UsbDevices.describe(driver.device)}")
        disconnect(kind)
        val link = UsbSerialLink(usb, driver)
        val radio: MeshRadio = when (kind) {
            ProtocolType.MESHTASTIC -> MeshtasticRadio(MeshtasticSerialLink(link))
            ProtocolType.MESHCORE -> MeshCoreRadio(MeshCoreLink(link, isSerial = true))
            ProtocolType.RETICULUM -> reticulumRadio(link)
            ProtocolType.APRS -> error("APRS TNCs are connected with Connect as APRS TNC")
        }
        usbLinks[kind] = link
        attach(radio).getOrThrow()
        prefs.rememberDevice("USB|${kind.name}|%04x:%04x".format(driver.device.vendorId, driver.device.productId))
        kind
    }

    // ------------------------------------------------------------------ APRS

    private fun aprsConfig() = org.highbridge.app.aprs.AprsRadio.Config(
        prefs.aprsCall.value.trim().uppercase(), prefs.aprsSymbol.value, prefs.aprsComment.value,
        prefs.aprsPath.value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() },
        prefs.aprsIs.value, prefs.aprsIsHost.value, prefs.aprsRadiusKm.value, { myLocation.value },
        { if (prefs.aprsGateway.value) aprsGateway.calls() else emptySet() })

    private fun aprsRadio(link: StreamLink?, name: String?): org.highbridge.app.aprs.AprsRadio {
        require(org.highbridge.app.aprs.AprsFormat.isCallsign(prefs.aprsCall.value.trim())) { "Set your amateur radio call sign in APRS settings first" }
        return org.highbridge.app.aprs.AprsRadio({ aprsConfig() }, link, name)
    }

    /** APRS over the internet only (APRS-IS): see and message stations without a TNC. */
    suspend fun connectAprsInternet(): Result<Unit> = runCatching {
        disconnect(ProtocolType.APRS)
        prefs.setAprsIs(true)
        attach(aprsRadio(null, null)).getOrThrow()
        prefs.rememberDevice("IS|APRS|-")
    }

    /** A KISS TNC paired over classic Bluetooth (serial port profile). */
    @SuppressLint("MissingPermission")
    suspend fun connectAprsSpp(device: BluetoothDevice): Result<Unit> = runCatching {
        disconnect(ProtocolType.APRS)
        attach(aprsRadio(org.highbridge.core.protocol.link.BluetoothSppStream(device), device.name ?: device.address)).getOrThrow()
        prefs.rememberDevice("SPP|APRS|${device.address}")
    }

    /** A KISS TNC on USB (NinoTNC, Mobilinkd, a LoRa APRS iGate) at [baud]. */
    suspend fun connectAprsUsb(driver: UsbSerialDriver, baud: Int): Result<Unit> = runCatching {
        disconnect(ProtocolType.APRS)
        val link = UsbSerialLink(ctx.getSystemService(UsbManager::class.java), driver, baud)
        attach(aprsRadio(link, UsbDevices.describe(driver.device))).getOrThrow()
        usbLinks[ProtocolType.APRS] = link
        prefs.rememberDevice("USBK|APRS|%04x:%04x:%d".format(driver.device.vendorId, driver.device.productId, baud))
    }

    /** Reticulum without a radio: Wi-Fi (AutoInterface) and any TCP hosts from settings. */
    suspend fun startReticulumNetworkOnly(): Result<Unit> {
        disconnect(ProtocolType.RETICULUM)
        return attach(runCatching { reticulumRadio(null) }.getOrElse { return Result.failure(it) })
    }

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private fun reticulumRadio(rnodeLink: StreamLink?): ReticulumRadio {
        check(ReticulumSupport.AVAILABLE) { "This edition of Highbridge doesn't include Reticulum." }
        check(ReticulumSupport.isInstalled(ctx)) {
            "Reticulum needs a one-time download first. Open Connect a radio › Reticulum."
        }
        if (prefs.rnsAuto.value && multicastLock == null) runCatching {
            // AutoInterface finds peers with IPv6 multicast; Android drops multicast without this lock.
            multicastLock = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                .createMulticastLock("highbridge-rns").apply { setReferenceCounted(false); acquire() }
        }
        val opts = RnsEngine.StartOptions(File(ctx.filesDir, "reticulum").absolutePath, prefs.rnsAuto.value,
            prefs.rnsTcpHosts.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }, rnodeLink != null, 1200,
            prefs.displayName.value.ifBlank { "Highbridge user" })
        val params = rnodeLink?.let { rnodeParamsForRegion() }
        return ReticulumRadio(rnsEngine, opts, rnodeLink?.let { RNodeController(it) }, params)
    }

    /** Sensible RNode settings inside the chosen region's band (125 kHz, SF8, CR5, 17 dBm). */
    fun rnodeParamsForRegion(): RNodeParams {
        val r = prefs.region.value
        val centerMHz = ((r.startMHz + r.endMHz) / 2.0)
        return RNodeParams((centerMHz * 1_000_000).toLong(), 125_000, minOf(17, r.powerLimitDbm), 8, 5)
    }

    /** A radio on your Wi-Fi network (Meshtastic port 4403, MeshCore Wi-Fi companion port 5000). */
    suspend fun connectTcp(host: String, protocol: ProtocolType): Result<Unit> = runCatching {
        require(protocol != ProtocolType.RETICULUM) { "For Reticulum over the network, add a TCP host in Settings" }
        val (h, p) = host.trim().let { if (':' in it) it.substringBeforeLast(':') to it.substringAfterLast(':').toInt()
            else it to when (protocol) { ProtocolType.MESHCORE -> org.highbridge.core.protocol.link.TcpStreamLink.MESHCORE_PORT; ProtocolType.APRS -> 8001
                else -> org.highbridge.core.protocol.link.TcpStreamLink.MESHTASTIC_PORT } }
        disconnect(protocol)
        val link = org.highbridge.core.protocol.link.TcpStreamLink(h, p)
        val radio: MeshRadio = when (protocol) {
            ProtocolType.MESHCORE -> MeshCoreRadio(MeshCoreLink(link, isSerial = true))
            ProtocolType.APRS -> aprsRadio(link, "$h:$p")   // Direwolf and other network KISS TNCs
            else -> MeshtasticRadio(MeshtasticSerialLink(link))
        }
        attach(radio).getOrThrow()
        tcpHosts[protocol] = h
        prefs.rememberDevice("TCP|${protocol.name}|$h:$p")
    }

    /** Host of a radio connected over the network, for Wi-Fi firmware updates. */
    val tcpHosts = java.util.concurrent.ConcurrentHashMap<ProtocolType, String>()

    private suspend fun attach(radio: MeshRadio): Result<Unit> {
        val r = radio.connect("")
        if (r.isFailure) return r
        if (radio is MeshCoreRadio && prefs.mcRegion.value.isNotBlank()) scope.launch { radio.setRegionScope(prefs.mcRegion.value) }
        _radios.value = _radios.value + (radio.protocol to radio)
        jobs[radio.protocol] = listOf(
            scope.launch { radio.incomingPackets.collect { runCatching { onPacket(it) } } },
            scope.launch { radio.events.collect { runCatching { onEvent(it) } } },
            scope.launch {
                radio.connectionState.collect { s ->
                    if (s is ConnectionState.Error || s is ConnectionState.Disconnected) {
                        if (_radios.value[radio.protocol] === radio) {
                            _radios.value = _radios.value - radio.protocol; refreshService()
                            if (s is ConnectionState.Error) {
                                _toast.emit("${radio.protocol.shortTag}: ${s.message}. Trying to reconnect…")
                                scheduleReconnect(radio.protocol)
                            }
                        }
                    }
                }
            },
        )
        if (radio is ReticulumRadio) rejoinGroups(radio)
        if (radio is org.highbridge.app.aprs.AprsRadio) jobs[radio.protocol] = jobs[radio.protocol].orEmpty() +
            scope.launch { radio.heardMessages.collect { runCatching { onAprsHeard(it) } } }
        refreshService()
        return r
    }

    suspend fun disconnect(protocol: ProtocolType) {
        val r = _radios.value[protocol] ?: return
        jobs.remove(protocol)?.forEach { it.cancel() }
        _radios.value = _radios.value - protocol
        runCatching { r.disconnect() }
        usbLinks.remove(protocol)
        refreshService()
    }

    suspend fun disconnectAll() { ProtocolType.entries.forEach { disconnect(it) } }

    private fun refreshService() {
        if (_radios.value.isNotEmpty() || BridgePrefs.isEnabled(ctx)) runCatching { MeshBridgeService.start(ctx) }
    }

    /** Up to 6 tries, 20 s apart; one loop per protocol however many times the link drops. */
    private fun scheduleReconnect(protocol: ProtocolType) {
        if (reconnectJobs[protocol]?.isActive == true) return
        reconnectJobs[protocol] = scope.launch {
            repeat(6) {
                delay(20_000)
                if (_radios.value.containsKey(protocol)) return@launch
                runCatching { reconnectRemembered() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun reconnectRemembered() = reconnectLock.withLock {
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val usb = ctx.getSystemService(UsbManager::class.java)
        for (entry in prefs.lastDevices.value) {
            val parts = entry.split('|')
            if (parts.size < 3) continue
            val proto = runCatching { ProtocolType.valueOf(parts[1]) }.getOrNull() ?: continue
            if (_radios.value.containsKey(proto)) continue
            when (parts[0]) {
                "BLE" -> bt?.let { runCatching { connectBle(it.getRemoteDevice(parts[2]), proto) } }
                "TCP" -> runCatching { connectTcp(parts[2], proto) }
                "USB" -> UsbDevices.drivers(usb).firstOrNull { "%04x:%04x".format(it.device.vendorId, it.device.productId) == parts[2] && usb.hasPermission(it.device) }
                    ?.let { runCatching { connectUsb(it) } }
                "USBK" -> UsbDevices.drivers(usb).firstOrNull { "%04x:%04x".format(it.device.vendorId, it.device.productId) == parts[2].substringBeforeLast(':') && usb.hasPermission(it.device) }
                    ?.let { runCatching { connectAprsUsb(it, parts[2].substringAfterLast(':').toIntOrNull() ?: 9600) } }
                "SPP" -> bt?.let { runCatching { connectAprsSpp(it.getRemoteDevice(parts[2])) } }
                "IS" -> runCatching { connectAprsInternet() }
            }
        }
    }

    private fun conversationKey(protocol: ProtocolType, channel: Int, toNodeId: String?, fromId: String) =
        if (toNodeId == null) "${protocol.name}:ch:$channel" else "${protocol.name}:dm:$fromId"

    // ------------------------------------------------------------------ incoming

    private fun conversationOf(p: MeshPacket): String = conversationKey(p.protocol, p.channelIndex, p.toNodeId, p.fromNodeId)

    private suspend fun onPacket(p: MeshPacket) {
        media.onPacket(p)
        // APRS gateway: direct messages to this node from Meshtastic or MeshCore.
        if (prefs.aprsGateway.value && p.toNodeId != null && p.protocol != ProtocolType.APRS && p.protocol != ProtocolType.RETICULUM) {
            (p.payload as? MeshPayload.Text)?.let { t -> handleGateway(p, t.body) }
        }
        val tp = p.payload as? MeshPayload.Text ?: return
        val text = tp.body
        if (dao.countPacket(p.packetId, p.protocol.name) > 0) return
        if (dao.node("${p.protocol.name}:${p.fromNodeId}")?.ignored == true) return
        val conv = conversationOf(p)
        val bridged = Regex("^\\[(MT|MC|RNS)]").containsMatchIn(text)
        dao.insertMessage(MessageEntity(packetId = p.packetId, protocol = p.protocol.name, conversation = conv, fromId = p.fromNodeId,
            fromName = p.fromShortName, text = text, timeMs = p.timestampMs, outgoing = false, status = 4, viaBridge = bridged,
            read = tp.emoji || (conv == openConversation && foreground), replyTo = tp.replyTo, emoji = tp.emoji))
        if (!tp.emoji && !(foreground && conv == openConversation)) notifyMessage(conv, p.fromShortName, text)
    }

    private suspend fun onEvent(e: RadioEvent) {
        if (e is RadioEvent.Ack && e.protocol == ProtocolType.APRS) gatewayPending.remove(e.packetId)?.let { key ->
            val proto = runCatching { ProtocolType.valueOf(key.substringBefore(':')) }.getOrNull()
            if (proto != null) radio(proto)?.sendText(0, if (e.delivered) "APRS: delivered ✓" else "APRS: not acknowledged (${e.reason ?: "no reply"})", key.substringAfter(':'))
            return
        }
        val proto = e.protocol.name
        wardriver.onEvent(e)
        when (e) {
            is RadioEvent.Waypoint -> {
                val key = "$proto:${e.id}"
                if (e.deleted) dao.deleteWaypoint(key) else {
                    val isNew = dao.waypointNow(key) == null
                    dao.upsertWaypoint(WaypointEntity(key, proto, e.id, e.name, e.description, e.lat, e.lon, e.icon, e.expireMs, e.fromId, System.currentTimeMillis()))
                    if (isNew) notify("Waypoint: ${e.name.ifBlank { "pin" }}", "Shared by ${dao.node("$proto:${e.fromId}")?.shortName ?: e.fromId}", "wp$key".hashCode())
                }
            }
            is RadioEvent.RxLog -> {}
            is RadioEvent.NodeUpdated -> saveNode(e.node)
            is RadioEvent.Position -> {
                val key = "$proto:${e.nodeId}"
                val old = dao.node(key)
                dao.upsertNode((old ?: NodeEntity(key, proto, e.nodeId, e.nodeId.takeLast(4), e.nodeId)).copy(lat = e.lat, lon = e.lon, altM = e.altitudeM ?: old?.altM, lastHeardMs = System.currentTimeMillis()))
                checkGeofences(key, old?.shortName ?: e.nodeId, e.lat, e.lon)
                if (prefs.atakEnabled.value) dao.node(key)?.let { n -> AtakCotBridge.buildCotXml(n.toMeshNode())?.let { AtakCotBridge.sendUdp(it) } }
            }
            is RadioEvent.Telemetry -> dao.insertTelemetry(TelemetryEntity(nodeKey = "$proto:${e.nodeId}", timeMs = e.timeMs, battery = e.batteryPct,
                voltage = e.voltage, channelUtil = e.channelUtil, airUtilTx = e.airUtilTx, temperatureC = e.temperatureC, humidityPct = e.humidityPct, pressureHpa = e.pressureHpa))
            is RadioEvent.Route -> dao.insertRoute(RouteEntity(protocol = proto, destId = e.destId, hops = e.hops.joinToString(","), snrs = e.snrTowards.joinToString(","),
                hopsBack = e.hopsBack.joinToString(","), snrsBack = e.snrBack.joinToString(","), timeMs = System.currentTimeMillis()))
            is RadioEvent.RangeTest -> dao.insertRange(RangeTestEntity(protocol = proto, fromId = e.fromId, text = e.text, snr = e.snr, rssi = e.rssi,
                lat = myLocation.value?.first, lon = myLocation.value?.second, timeMs = e.timeMs))
            is RadioEvent.Ack -> dao.setStatus(e.packetId, if (e.delivered) 2 else 3)
            is RadioEvent.Heard -> {
                if (e.fromId.startsWith("name:")) return
                val key = "$proto:${e.fromId}"
                dao.node(key)?.let { dao.upsertNode(it.copy(snr = e.snr ?: it.snr, rssi = e.rssi ?: it.rssi, hops = e.hops ?: it.hops, lastHeardMs = System.currentTimeMillis())) }
            }
            is RadioEvent.AdminReply -> { _admin.emit(e); _log.emit(e.protocol to "← ${e.fromId}: ${e.text}") }
            is RadioEvent.Log -> _log.emit(e.protocol to e.line)
            is RadioEvent.Airtime -> {
                if (e.protocol == ProtocolType.MESHTASTIC) lastMtTx = System.currentTimeMillis()
                airtime.recordTx(e.txMs)
                _airtimePct.value = airtime.dutyCyclePercent()
                if (airtime.shouldWarn()) _toast.emit("Airtime is at ${"%.1f".format(airtime.dutyCyclePercent())}% of the hour. ${prefs.region.value.label} allows ${prefs.region.value.dutyCyclePercent}%.")
            }
        }
    }

    private suspend fun saveNode(n: MeshNode) {
        val key = "${n.protocol.name}:${n.id}"
        val old = dao.node(key)
        dao.upsertNode(NodeEntity(key, n.protocol.name, n.id, n.shortName, n.longName, n.latitude ?: old?.lat, n.longitude ?: old?.lon,
            n.batteryPct ?: old?.battery, n.voltage ?: old?.voltage, n.snr ?: old?.snr, n.rssi ?: old?.rssi,
            n.hopsAway.takeIf { it >= 0 } ?: old?.hops, maxOf(n.lastHeardMs, old?.lastHeardMs ?: 0), old?.favorite ?: false,
            ignored = old?.ignored ?: false, altM = old?.altM))
    }

    private val fenceState = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private suspend fun checkGeofences(nodeKey: String, name: String, lat: Double, lon: Double) {
        for (g in dao.activeGeofences()) {
            if (g.nodeKey != null && g.nodeKey != nodeKey) continue
            val kind = runCatching { GeofenceRules.Kind.valueOf(g.kind) }.getOrNull() ?: continue
            val inside = GeofenceRules.isInside(kind, Geo.parsePoints(g.points), g.radiusM, lat, lon)
            val k = "${g.rowId}|$nodeKey"
            val was = fenceState.put(k, inside)
            if (was != null && was != inside && (inside || g.alertOnExit))
                notify("Geofence: ${g.name}", "$name ${if (inside) "entered" else if (kind == GeofenceRules.Kind.TRAIL) "drifted off" else "left"} ${g.name}", "fence$k".hashCode())
        }
    }

    private val livePlayer by lazy { VoiceIO.LivePlayer() }
    private val liveIn = java.util.concurrent.ConcurrentHashMap<String, MutableList<Short>>()

    /** A live push-to-talk block: play it now, and keep the whole session as one voice message. */
    private suspend fun onLiveVoice(m: MediaTransferManager.Incoming) {
        if (m.data.size < 4) return
        val mode = m.data[0].toInt(); val session = m.data[1].toInt() and 0xFF
        val pcm = NativeCodec2(mode).use { it.decodeToPcm8k(m.data.copyOfRange(3, m.data.size)) }
        val conv = conversationKey(m.protocol, m.channelIndex, m.toNodeId, m.senderId)
        if (prefs.notifyLevel(conv) != "NONE") livePlayer.enqueue(pcm)
        val key = "live-${m.senderId}-$session"
        val all = liveIn.getOrPut(key) { mutableListOf() }
        val first = all.isEmpty()
        all.addAll(pcm.toList())
        val f = File(File(ctx.filesDir, "media").apply { mkdirs() }, "$key.wav").apply { writeBytes(VoiceIO.toWav(all.toShortArray())) }
        val label = "Live voice · ${all.size / VoiceIO.SAMPLE_RATE}s"
        if (first) {
            dao.insertMessage(MessageEntity(packetId = key, protocol = m.protocol.name, conversation = conv, fromId = m.senderId, fromName = m.senderName,
                text = label, timeMs = System.currentTimeMillis(), outgoing = false, mediaType = MediaType.VOICE_CODEC2.name, mediaPath = f.absolutePath))
            notifyMessage(conv, m.senderName, "Talking live…")
        } else dao.updateMedia(key, label, f.absolutePath, System.currentTimeMillis())
        if (liveIn.size > 20) liveIn.keys.take(liveIn.size - 20).forEach { liveIn.remove(it) }
    }

    /** Sends one second of push-to-talk audio. [session] groups the blocks of one transmission. */
    suspend fun sendLiveBlock(conversation: String, session: Int, seq: Int, pcm: ShortArray): Result<Unit> = runCatching {
        airtimeHold()?.let { throw it }
        val t = parse(conversation)
        val bits = NativeCodec2(NativeCodec2.MODE_700C).use { it.encodePcm8k(pcm) }
        media.send(t.protocol, t.channel, t.toNodeId, MediaType.VOICE_LIVE, byteArrayOf(NativeCodec2.MODE_700C.toByte(), session.toByte(), seq.toByte()) + bits)
    }

    /** Keeps your side of a live transmission in the chat once you let go. */
    suspend fun saveLiveSent(conversation: String, pcm: ShortArray) {
        if (pcm.isEmpty()) return
        val t = parse(conversation)
        val f = File(File(ctx.filesDir, "media").apply { mkdirs() }, "live_sent_${System.currentTimeMillis()}.wav").apply { writeBytes(VoiceIO.toWav(pcm)) }
        dao.insertMessage(MessageEntity(packetId = "media-live-${System.nanoTime()}", protocol = t.protocol.name, conversation = conversation, fromId = "me", fromName = "You",
            text = "Live voice · ${pcm.size / VoiceIO.SAMPLE_RATE}s", timeMs = System.currentTimeMillis(), outgoing = true, status = 1,
            mediaType = MediaType.VOICE_CODEC2.name, mediaPath = f.absolutePath))
    }

    private suspend fun onMediaComplete(m: MediaTransferManager.Incoming) {
        val dir = File(ctx.filesDir, "media").apply { mkdirs() }
        if (m.mediaType == MediaType.VOICE_LIVE) { onLiveVoice(m); return }
        val (file, label) = when (m.mediaType) {
            MediaType.IMAGE_WEBP -> File(dir, "img_${System.currentTimeMillis()}.webp").also { it.writeBytes(m.data) } to "Photo"
            MediaType.VOICE_CODEC2 -> {
                val (mode, bits) = VoiceIO.unpack(m.data)
                val pcm = NativeCodec2(mode).use { it.decodeToPcm8k(bits) }
                File(dir, "voice_${System.currentTimeMillis()}.wav").also { it.writeBytes(VoiceIO.toWav(pcm)) } to "Voice message · ${pcm.size / VoiceIO.SAMPLE_RATE}s"
            }
            MediaType.BINARY_FILE -> File(dir, "file_${System.currentTimeMillis()}.bin").also { it.writeBytes(m.data) } to "File"
            MediaType.VOICE_LIVE -> return
        }
        val conv = conversationKey(m.protocol, m.channelIndex, m.toNodeId, m.senderId)
        dao.insertMessage(MessageEntity(packetId = "media-${file.name}", protocol = m.protocol.name, conversation = conv, fromId = m.senderId,
            fromName = m.senderName, text = label, timeMs = System.currentTimeMillis(), outgoing = false, mediaType = m.mediaType.name, mediaPath = file.absolutePath))
        notifyMessage(conv, m.senderName, label)
    }

    // ------------------------------------------------------------------ APRS gateway and station ID

    private var aprsFilterAt: Pair<Double, Double>? = null

    private suspend fun handleGateway(p: MeshPacket, text: String) {
        val key = "${p.protocol.name}:${p.fromNodeId}"
        when (val a = aprsGateway.onMeshDirect(key, text)) {
            is org.highbridge.app.aprs.AprsGateway.Action.Reply -> radio(p.protocol)?.sendText(0, a.text, p.fromNodeId)
            is org.highbridge.app.aprs.AprsGateway.Action.SendAprs -> {
                val ap = aprs
                val r = when {
                    ap == null || !ap.canTransmit -> Result.failure(IllegalStateException("The APRS gateway is offline right now"))
                    else -> ap.sendMessageAs(a.fromCall, a.toCall, a.text)
                }
                r.onSuccess { id -> gatewayPending[id] = key }
                radio(p.protocol)?.sendText(0, r.fold({ "Sent to ${a.toCall} on APRS as ${a.fromCall}" }, { "Not sent: ${it.message}" }), p.fromNodeId)
            }
            org.highbridge.app.aprs.AprsGateway.Action.None -> {}
        }
    }

    /** APRS message id -> mesh user it came from, to tell them when it's acknowledged. */
    private val gatewayPending = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** APRS messages for gateway users go back to them over the mesh, acknowledged on their behalf. */
    private suspend fun onAprsHeard(h: org.highbridge.app.aprs.AprsRadio.Heard) {
        if (!prefs.aprsGateway.value) return
        val key = aprsGateway.meshKeyFor(h.to) ?: return
        val proto = runCatching { ProtocolType.valueOf(key.substringBefore(':')) }.getOrNull() ?: return
        val r = radio(proto) ?: return
        r.sendText(0, "APRS ${h.from}: ${h.text}".take(r.maxPayloadBytes), key.substringAfter(':')).onSuccess {
            h.msgNo?.let { no -> aprs?.sendAck(h.to, h.from, no) }   // only once it's on its way over the mesh
        }
    }

    private var lastMtTx = 0L
    private var lastStationId = 0L

    /**
     * FCC 97.119: identify at least every 10 minutes while transmitting and at the end. In licensed mode the
     * node's user record carries the call sign, so re-broadcast it after a quiet spell or every 10 minutes of activity.
     */
    private suspend fun stationIdLoop() {
        while (true) {
            delay(30_000)
            val mt = meshtastic ?: continue
            if (!prefs.autoStationId.value || !mt.isLicensed) continue
            val now = System.currentTimeMillis()
            if (lastMtTx <= lastStationId + 5_000) continue        // nothing sent since the last ID
            if (now - lastStationId >= 10 * 60_000L || now - lastMtTx >= 3 * 60_000L) {
                lastStationId = now
                mt.sendStationId()
            }
        }
    }

    // ------------------------------------------------------------------ sending

    data class Target(val protocol: ProtocolType, val channel: Int, val toNodeId: String?)

    fun parse(conversation: String): Target {
        val parts = conversation.split(':', limit = 3)
        val proto = ProtocolType.valueOf(parts[0])
        return if (parts[1] == "ch") Target(proto, parts[2].toInt(), null) else Target(proto, 0, parts[2])
    }

    /** [replyTo] = packet id being answered; [emoji] = send [text] (one emoji) as a reaction to [replyTo]. */
    suspend fun sendText(conversation: String, text: String, replyTo: String? = null, emoji: Boolean = false): Result<String> {
        val t = parse(conversation)
        val radio = radio(t.protocol) ?: return Result.failure(IllegalStateException("Connect a ${t.protocol.shortTag} radio first"))
        airtimeHold()?.let { return Result.failure(it) }
        val row = dao.insertMessage(MessageEntity(packetId = "pending-${System.nanoTime()}", protocol = t.protocol.name, conversation = conversation,
            fromId = radio.myNodeId.value ?: "me", fromName = "You", text = text, timeMs = System.currentTimeMillis(), outgoing = true, status = 0,
            replyTo = replyTo, emoji = emoji))
        // MeshCore has no reply or reaction fields: send a reaction as "+1 to <name>" style text instead.
        val r = if (replyTo != null || emoji) radio.sendText(t.channel, text, t.toNodeId, replyTo, emoji) else radio.sendText(t.channel, text, t.toNodeId)
        r.onSuccess { dao.setSent(row, it, 1) }.onFailure { dao.setSent(row, "failed-$row", 3) }
        return r
    }

    /** Drops a map pin and shares it on the channel (Meshtastic). */
    suspend fun shareWaypoint(protocol: ProtocolType, name: String, description: String, lat: Double, lon: Double, icon: Int, expireHours: Int): Result<Unit> = runCatching {
        val r = radio(protocol) ?: error("Connect a ${protocol.shortTag} radio first")
        airtimeHold()?.let { throw it }
        val expire = if (expireHours <= 0) 0L else System.currentTimeMillis() + expireHours * 3_600_000L
        val id = r.sendWaypoint(0, 0, name, description, lat, lon, icon, expire).getOrThrow()
        dao.upsertWaypoint(WaypointEntity("${protocol.name}:$id", protocol.name, id, name, description, lat, lon, icon, expire, "me", System.currentTimeMillis()))
    }

    /** Removes a pin; if you made it, tells the mesh to remove it too. */
    suspend fun deleteWaypoint(w: WaypointEntity): Result<Unit> = runCatching {
        if (w.fromId == "me") radio(ProtocolType.valueOf(w.protocol))?.sendWaypoint(0, w.wid, w.name, w.description, w.lat, w.lon, w.icon, 1000L)
        dao.deleteWaypoint(w.key)
    }

    private fun airtimeHold(): Throwable? = if (airtime.mustHold())
        IllegalStateException("You've used this hour's airtime allowance for ${prefs.region.value.label}. Wait a few minutes.") else null

    suspend fun sendPhoto(conversation: String, bitmap: android.graphics.Bitmap): Result<Int> = runCatching {
        airtimeHold()?.let { throw it }
        val t = parse(conversation)
        val bytes = LoRaImageCompressor.compressForLoRa(bitmap)
        val id = media.send(t.protocol, t.channel, t.toNodeId, MediaType.IMAGE_WEBP, bytes)
        val f = File(File(ctx.filesDir, "media").apply { mkdirs() }, "sent_$id.webp").apply { writeBytes(bytes) }
        dao.insertMessage(MessageEntity(packetId = "media-out-$id", protocol = t.protocol.name, conversation = conversation, fromId = "me", fromName = "You",
            text = "Photo · %.1f KB".format(bytes.size / 1024.0), timeMs = System.currentTimeMillis(), outgoing = true, status = 1, mediaType = MediaType.IMAGE_WEBP.name, mediaPath = f.absolutePath))
        id
    }

    suspend fun sendVoice(conversation: String, pcm: ShortArray, mode: Int = NativeCodec2.MODE_1200): Result<Int> = runCatching {
        airtimeHold()?.let { throw it }
        val t = parse(conversation)
        val bits = NativeCodec2(mode).use { it.encodePcm8k(pcm) }
        val id = media.send(t.protocol, t.channel, t.toNodeId, MediaType.VOICE_CODEC2, VoiceIO.pack(mode, bits))
        val f = File(File(ctx.filesDir, "media").apply { mkdirs() }, "sent_$id.wav").apply { writeBytes(VoiceIO.toWav(NativeCodec2(mode).use { c -> c.decodeToPcm8k(bits) })) }
        dao.insertMessage(MessageEntity(packetId = "media-out-$id", protocol = t.protocol.name, conversation = conversation, fromId = "me", fromName = "You",
            text = "Voice message · ${pcm.size / VoiceIO.SAMPLE_RATE}s", timeMs = System.currentTimeMillis(), outgoing = true, status = 1,
            mediaType = MediaType.VOICE_CODEC2.name, mediaPath = f.absolutePath))
        id
    }

    /** One-tap check-in or SOS on channel 0 of every connected radio, with location if known. */
    suspend fun broadcastStatus(sos: Boolean): List<String> {
        val loc = myLocation.value
        val where = loc?.let { "%.5f,%.5f".format(it.first, it.second) } ?: "location unknown"
        val text = if (sos) "SOS! Need help at $where" else "Check-in: OK at $where"
        val sent = mutableListOf<String>()
        for ((p, r) in _radios.value) {
            if (p == ProtocolType.APRS) continue   // APRS has no group channel; use a beacon or status there
            // SOS goes out even past the airtime allowance; check-ins respect it.
            if (!sos && airtime.mustHold()) continue
            val ok = r.sendText(0, text, null).isSuccess
            if (ok) sent += p.shortTag
            if (loc != null) runCatching { r.sendPosition(loc.first, loc.second) }
            dao.insertMessage(MessageEntity(packetId = "status-${System.nanoTime()}", protocol = p.name, conversation = "${p.name}:ch:0", fromId = "me",
                fromName = "You", text = text, timeMs = System.currentTimeMillis(), outgoing = true, status = if (ok) 1 else 3))
        }
        return sent
    }

    /**
     * Saves a channel and loads it on the radio. A channel with [EncryptedChannel.AUTO_SLOT] goes in the
     * next free slot. Returns the channel as loaded (with its real slot).
     */
    suspend fun applyChannel(c: EncryptedChannel): Result<EncryptedChannel> = runCatching {
        val radio = radio(c.protocol)
        val placed = if (c.slotIndex != EncryptedChannel.AUTO_SLOT) c else {
            radio ?: error("Connect a ${c.protocol.shortTag} radio first, so Highbridge can pick a free channel slot.")
            // Already on the radio under the same name and key? Use that slot.
            val existing = dao.channelsNow().firstOrNull { it.protocol == c.protocol.name && it.name == c.name && it.keyB64 == c.keyBase64Url }
                ?.slot?.takeIf { s -> radio.channels.value.any { it.index == s && it.name == c.name } }
            c.copy(slotIndex = existing ?: nextFreeSlot(c.protocol) ?: error("All ${c.protocol.shortTag} channel slots are in use. Leave one first."))
        }
        dao.upsertChannel(ChannelEntity(placed.id, placed.protocol.name, placed.slotIndex, placed.name, placed.keyBase64Url, System.currentTimeMillis()))
        (radio ?: error("Saved. Connect a ${placed.protocol.shortTag} radio to load it.")).applyChannel(placed).getOrThrow()
        placed
    }

    /** First empty channel slot on the connected radio (never slot 0, the primary/public channel). */
    suspend fun nextFreeSlot(p: ProtocolType): Int? {
        val r = radio(p) ?: return null
        val used = r.channels.value.map { it.index }.toSet()
        return when (p) {
            ProtocolType.MESHTASTIC -> (1..EncryptedChannel.maxSlot(p)).firstOrNull { it !in used }
            ProtocolType.MESHCORE -> (r as? MeshCoreRadio)?.freeChannelSlot()
            ProtocolType.RETICULUM -> maxOf(used.maxOrNull() ?: 0, dao.channelsNow().filter { it.protocol == p.name }.maxOfOrNull { it.slot } ?: 0) + 1
            ProtocolType.APRS -> null
        }
    }

    /** Takes a channel off the radio and forgets its saved key. Slot 0 stays. */
    suspend fun leaveChannel(p: ProtocolType, slot: Int): Result<Unit> = runCatching {
        require(slot > 0) { "The primary channel can't be removed" }
        when (p) {
            ProtocolType.MESHCORE -> (radio(p) as? MeshCoreRadio)?.removeChannel(slot)?.getOrThrow()
            ProtocolType.MESHTASTIC -> meshtastic?.removeChannel(slot)?.getOrThrow()
            ProtocolType.RETICULUM, ProtocolType.APRS -> {}
        }
        dao.channelsNow().filter { it.protocol == p.name && it.slot == slot }.forEach { dao.deleteChannel(it.id) }
    }

    private suspend fun rejoinGroups(r: ReticulumRadio) {
        for (c in dao.channelsNow().filter { it.protocol == ProtocolType.RETICULUM.name }) {
            runCatching { r.applyChannel(EncryptedChannel(c.id, ProtocolType.RETICULUM, c.slot, c.name, c.keyB64)) }
        }
    }

    // ------------------------------------------------------------------ location, tracks

    private var lastSharedAt = 0L
    private var lastShared: Pair<Double, Double>? = null

    fun onLocation(lat: Double, lon: Double, alt: Double?) {
        myLocation.value = lat to lon
        if (alt != null) myAltitudeM.value = alt
        scope.launch {
            recordingTrackId?.let { dao.insertTrackPoint(TrackPointEntity(trackId = it, lat = lat, lon = lon, altM = alt, timeMs = System.currentTimeMillis())) }
            if ((prefs.shareLocation.value || prefs.aprsBeacon.value) && shouldShare(lat, lon)) for (r in _radios.value.values) {
                // APRS beacons follow their own switch; the meshes follow "share my location".
                val wanted = if (r.protocol == ProtocolType.APRS) prefs.aprsBeacon.value else prefs.shareLocation.value
                if (wanted) runCatching { r.sendPosition(lat, lon, alt?.toInt()) }
            }
            aprs?.let { a -> val last = aprsFilterAt; if (last == null || Geo.distanceM(last.first, last.second, lat, lon) > 5_000) { aprsFilterAt = lat to lon; a.refreshFilter() } }
        }
    }

    /**
     * GPS fixes arrive every 35 s, far too often to put on the air. Share at most every 15 minutes,
     * or every 2 minutes once you've moved 200 m, and never past the airtime allowance.
     */
    @Synchronized
    private fun shouldShare(lat: Double, lon: Double): Boolean {
        val now = SystemClock.elapsedRealtime()
        val since = now - lastSharedAt
        val moved = lastShared?.let { Geo.distanceM(it.first, it.second, lat, lon) } ?: Double.MAX_VALUE
        val due = lastSharedAt == 0L || since >= POSITION_EVERY_MS || (since >= POSITION_MIN_MS && moved >= POSITION_MOVE_M)
        if (!due || airtime.shouldWarn()) return false
        lastSharedAt = now; lastShared = lat to lon
        return true
    }

    fun startTrack(): Long { val id = System.currentTimeMillis(); recordingTrackId = id; return id }
    fun stopTrack() { recordingTrackId = null }

    /** Periodic range-test pings on Meshtastic. */
    private var rangeJob: Job? = null
    val rangeTestRunning = MutableStateFlow(false)
    fun setRangeTest(on: Boolean, intervalSec: Int = 60) {
        rangeJob?.cancel(); rangeTestRunning.value = on
        if (!on) return
        rangeJob = scope.launch {
            var seq = 1
            while (isActive) { if (!airtime.mustHold()) meshtastic?.sendRangeTest(seq++); delay(intervalSec * 1000L) }
        }
    }

    fun addGeofence(g: GeofenceEntity) = scope.launch { dao.upsertGeofence(g) }

    // ------------------------------------------------------------------ notifications

    private fun createNotificationChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alerts (geofence, SOS)", NotificationManager.IMPORTANCE_HIGH))
    }

    /** True if [text] mentions you: "@[Your name]" (MeshCore style) or "@yourname". */
    fun mentionsMe(text: String): Boolean {
        val names = (listOf(prefs.displayName.value) + _radios.value.values.mapNotNull { r -> (r as? MeshCoreRadio)?.selfInfo?.value?.name })
            .filter { it.isNotBlank() }
        return names.any { n -> text.contains("@[$n]", true) || Regex("@" + Regex.escape(n) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }
    }

    private val recentByConv = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    private fun notifyMessage(conv: String, from: String, text: String) {
        val sos = SOS.containsMatchIn(text)
        val level = prefs.notifyLevel(conv)
        if (!sos && (level == "NONE" || (level == "MENTIONS" && !mentionsMe(text)))) return
        if (sos) { notify("SOS from $from", text, conv.hashCode(), CH_ALERTS, conv); return }
        // Messaging-style notification with Reply and Mark as read, which phones, watches and Android Auto all understand.
        val lines = recentByConv.getOrPut(conv) { mutableListOf() }.apply { add(from to text); while (size > 6) removeAt(0) }
        MessageNotifier.show(ctx, conv, lines.toList())
    }

    /** Called when the person reads or answers a conversation. */
    fun clearNotification(conv: String) { recentByConv.remove(conv); NotificationManagerCompat.from(ctx).cancel(conv.hashCode()) }

    @SuppressLint("MissingPermission")
    private fun notify(title: String, text: String, id: Int, channel: String = CH_ALERTS, conversation: String? = null) {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            conversation?.let { putExtra("conversation", it) }
        }
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat_highbridge).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_ALERTS = "alerts"
        private const val POSITION_EVERY_MS = 15 * 60_000L
        private const val POSITION_MIN_MS = 2 * 60_000L
        private const val POSITION_MOVE_M = 200.0
        /** "SOS!" at the start, or after a bridge tag like "[MT:bob] ". */
        private val SOS = Regex("""^(\[(MT|MC|RNS):[^\]]*]\s*)*SOS\b""")
    }
}

fun NodeEntity.toMeshNode() = MeshNode(id, shortName, longName, ProtocolType.valueOf(protocol), rssi, snr, hops ?: 0, lat, lon, battery, voltage, lastHeardMs = lastHeardMs)

fun EncryptedChannel.Companion.fromEntity(c: ChannelEntity) = EncryptedChannel(c.id, ProtocolType.valueOf(c.protocol), c.slot, c.name, c.keyB64)

