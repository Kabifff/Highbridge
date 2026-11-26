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
import org.highbridge.app.pro.AirtimeMonitor
import org.highbridge.app.pro.AtakCotBridge
import org.highbridge.app.pro.GeofenceRules
import org.highbridge.app.pro.Geo
import org.highbridge.app.rns.ChaquopyRnsEngine
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
    private val jobs = mutableMapOf<ProtocolType, List<Job>>()
    private val usbLinks = mutableMapOf<ProtocolType, UsbSerialLink>()

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
    @Volatile var foreground = false
    @Volatile var openConversation: String? = null

    val media = MediaTransferManager({ _radios.value }, { if (prefs.powerProfile.value.allowBridgeMedia) 3_000L else 6_000L })
    private val rnsEngine: RnsEngine by lazy { ChaquopyRnsEngine() }

    @Volatile var recordingTrackId: Long? = null
        private set

    init {
        TransportRegistry.provider = { _radios.value }
        TransportRegistry.hasConnectedRadios = { _radios.value.isNotEmpty() }
        TransportRegistry.reconnect = { reconnectRemembered() }
        createNotificationChannels()
        scope.launch { media.completed.collect { onMediaComplete(it) } }
        scope.launch { prefs.region.collect { airtime.region = it } }
    }

    fun radio(p: ProtocolType) = _radios.value[p]
    val meshtastic get() = radio(ProtocolType.MESHTASTIC) as? MeshtasticRadio
    val meshcore get() = radio(ProtocolType.MESHCORE) as? MeshCoreRadio
    val reticulum get() = radio(ProtocolType.RETICULUM) as? ReticulumRadio

    // ------------------------------------------------------------------ connecting

    @SuppressLint("MissingPermission")
    suspend fun connectBle(device: BluetoothDevice, protocol: ProtocolType): Result<Unit> {
        disconnect(protocol)
        val radio: MeshRadio = runCatching { when (protocol) {
            ProtocolType.MESHTASTIC -> MeshtasticRadio(MeshtasticBleLink(ctx, device))
            ProtocolType.MESHCORE -> MeshCoreRadio(MeshCoreLink(BleNusStream(ctx, device), isSerial = false))
            ProtocolType.RETICULUM -> reticulumRadio(BleNusStream(ctx, device))
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
        }
        usbLinks[kind] = link
        attach(radio).getOrThrow()
        prefs.rememberDevice("USB|${kind.name}|%04x:%04x".format(driver.device.vendorId, driver.device.productId))
        kind
    }

    /** Reticulum without a radio: Wi-Fi (AutoInterface) and any TCP hosts from settings. */
    suspend fun startReticulumNetworkOnly(): Result<Unit> {
        disconnect(ProtocolType.RETICULUM)
        return attach(runCatching { reticulumRadio(null) }.getOrElse { return Result.failure(it) })
    }

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private fun reticulumRadio(rnodeLink: StreamLink?): ReticulumRadio {
        check(org.highbridge.app.rns.RnsInstaller.isInstalled(ctx)) {
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

    private suspend fun attach(radio: MeshRadio): Result<Unit> {
        val r = radio.connect("")
        if (r.isFailure) return r
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
                                scope.launch { repeat(6) { delay(20_000); if (_radios.value.containsKey(radio.protocol)) return@launch; runCatching { reconnectRemembered() } } }
                            }
                        }
                    }
                }
            },
        )
        if (radio is ReticulumRadio) rejoinGroups(radio)
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

    @SuppressLint("MissingPermission")
    suspend fun reconnectRemembered() {
        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val usb = ctx.getSystemService(UsbManager::class.java)
        for (entry in prefs.lastDevices.value) {
            val parts = entry.split('|')
            if (parts.size < 3) continue
            val proto = runCatching { ProtocolType.valueOf(parts[1]) }.getOrNull() ?: continue
            if (_radios.value.containsKey(proto)) continue
            when (parts[0]) {
                "BLE" -> bt?.let { runCatching { connectBle(it.getRemoteDevice(parts[2]), proto) } }
                "USB" -> UsbDevices.drivers(usb).firstOrNull { "%04x:%04x".format(it.device.vendorId, it.device.productId) == parts[2] && usb.hasPermission(it.device) }
                    ?.let { runCatching { connectUsb(it) } }
            }
        }
    }

    // ------------------------------------------------------------------ incoming

    private fun conversationOf(p: MeshPacket): String =
        if (p.toNodeId == null) "${p.protocol.name}:ch:${p.channelIndex}" else "${p.protocol.name}:dm:${p.fromNodeId}"

    private suspend fun onPacket(p: MeshPacket) {
        media.onPacket(p)
        val text = (p.payload as? MeshPayload.Text)?.body ?: return
        if (dao.countPacket(p.packetId, p.protocol.name) > 0) return
        val conv = conversationOf(p)
        val bridged = Regex("^\\[(MT|MC|RNS)]").containsMatchIn(text)
        dao.insertMessage(MessageEntity(packetId = p.packetId, protocol = p.protocol.name, conversation = conv, fromId = p.fromNodeId,
            fromName = p.fromShortName, text = text, timeMs = p.timestampMs, outgoing = false, status = 4, viaBridge = bridged,
            read = conv == openConversation && foreground))
        if (!(foreground && conv == openConversation)) notifyMessage(conv, p.fromShortName, text)
    }

    private suspend fun onEvent(e: RadioEvent) {
        val proto = e.protocol.name
        when (e) {
            is RadioEvent.NodeUpdated -> saveNode(e.node)
            is RadioEvent.Position -> {
                val key = "$proto:${e.nodeId}"
                val old = dao.node(key)
                dao.upsertNode((old ?: NodeEntity(key, proto, e.nodeId, e.nodeId.takeLast(4), e.nodeId)).copy(lat = e.lat, lon = e.lon, lastHeardMs = System.currentTimeMillis()))
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
            n.hopsAway.takeIf { it >= 0 } ?: old?.hops, maxOf(n.lastHeardMs, old?.lastHeardMs ?: 0), old?.favorite ?: false))
    }

    private val fenceState = mutableMapOf<String, Boolean>()
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

    private suspend fun onMediaComplete(m: MediaTransferManager.Incoming) {
        val dir = File(ctx.filesDir, "media").apply { mkdirs() }
        val (file, label) = when (m.mediaType) {
            MediaType.IMAGE_WEBP -> File(dir, "img_${System.currentTimeMillis()}.webp").also { it.writeBytes(m.data) } to "Photo"
            MediaType.VOICE_CODEC2 -> {
                val (mode, bits) = VoiceIO.unpack(m.data)
                val pcm = NativeCodec2(mode).use { it.decodeToPcm8k(bits) }
                File(dir, "voice_${System.currentTimeMillis()}.wav").also { it.writeBytes(VoiceIO.toWav(pcm)) } to "Voice message · ${pcm.size / VoiceIO.SAMPLE_RATE}s"
            }
            MediaType.BINARY_FILE -> File(dir, "file_${System.currentTimeMillis()}.bin").also { it.writeBytes(m.data) } to "File"
        }
        val conv = if (m.toNodeId == null) "${m.protocol.name}:ch:${m.channelIndex}" else "${m.protocol.name}:dm:${m.senderId}"
        dao.insertMessage(MessageEntity(packetId = "media-${file.name}", protocol = m.protocol.name, conversation = conv, fromId = m.senderId,
            fromName = m.senderName, text = label, timeMs = System.currentTimeMillis(), outgoing = false, mediaType = m.mediaType.name, mediaPath = file.absolutePath))
        notifyMessage(conv, m.senderName, label)
    }

    // ------------------------------------------------------------------ sending

    data class Target(val protocol: ProtocolType, val channel: Int, val toNodeId: String?)

    fun parse(conversation: String): Target {
        val parts = conversation.split(':', limit = 3)
        val proto = ProtocolType.valueOf(parts[0])
        return if (parts[1] == "ch") Target(proto, parts[2].toInt(), null) else Target(proto, 0, parts[2])
    }

    suspend fun sendText(conversation: String, text: String): Result<String> {
        val t = parse(conversation)
        val radio = radio(t.protocol) ?: return Result.failure(IllegalStateException("Connect a ${t.protocol.shortTag} radio first"))
        if (airtime.mustHold()) return Result.failure(IllegalStateException("You've used this hour's airtime allowance for ${prefs.region.value.label}. Wait a few minutes."))
        val row = dao.insertMessage(MessageEntity(packetId = "pending-${System.nanoTime()}", protocol = t.protocol.name, conversation = conversation,
            fromId = radio.myNodeId.value ?: "me", fromName = "You", text = text, timeMs = System.currentTimeMillis(), outgoing = true, status = 0))
        val r = radio.sendText(t.channel, text, t.toNodeId)
        r.onSuccess { dao.setSent(row, it, 1) }.onFailure { dao.setSent(row, "failed-$row", 3) }
        return r
    }

    suspend fun sendPhoto(conversation: String, bitmap: android.graphics.Bitmap): Result<Int> = runCatching {
        val t = parse(conversation)
        val bytes = LoRaImageCompressor.compressForLoRa(bitmap)
        val id = media.send(t.protocol, t.channel, t.toNodeId, MediaType.IMAGE_WEBP, bytes)
        val f = File(File(ctx.filesDir, "media").apply { mkdirs() }, "sent_$id.webp").apply { writeBytes(bytes) }
        dao.insertMessage(MessageEntity(packetId = "media-out-$id", protocol = t.protocol.name, conversation = conversation, fromId = "me", fromName = "You",
            text = "Photo · %.1f KB".format(bytes.size / 1024.0), timeMs = System.currentTimeMillis(), outgoing = true, status = 1, mediaType = MediaType.IMAGE_WEBP.name, mediaPath = f.absolutePath))
        id
    }

    suspend fun sendVoice(conversation: String, pcm: ShortArray, mode: Int = NativeCodec2.MODE_1200): Result<Int> = runCatching {
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
            if (r.sendText(0, text, null).isSuccess) sent += p.shortTag
            if (loc != null) r.sendPosition(loc.first, loc.second)
            dao.insertMessage(MessageEntity(packetId = "status-${System.nanoTime()}", protocol = p.name, conversation = "${p.name}:ch:0", fromId = "me",
                fromName = "You", text = text, timeMs = System.currentTimeMillis(), outgoing = true, status = 1))
        }
        return sent
    }

    suspend fun applyChannel(c: EncryptedChannel): Result<Unit> = runCatching {
        dao.upsertChannel(ChannelEntity(c.id, c.protocol.name, c.slotIndex, c.name, c.keyBase64Url, System.currentTimeMillis()))
        radio(c.protocol)?.applyChannel(c)?.getOrThrow() ?: error("Saved. Connect a ${c.protocol.shortTag} radio to load it.")
    }

    private suspend fun rejoinGroups(r: ReticulumRadio) {
        for (c in dao.channelsNow().filter { it.protocol == ProtocolType.RETICULUM.name }) {
            runCatching { r.applyChannel(EncryptedChannel(c.id, ProtocolType.RETICULUM, c.slot, c.name, c.keyB64)) }
        }
    }

    // ------------------------------------------------------------------ location, tracks

    fun onLocation(lat: Double, lon: Double, alt: Double?) {
        myLocation.value = lat to lon
        scope.launch {
            recordingTrackId?.let { dao.insertTrackPoint(TrackPointEntity(trackId = it, lat = lat, lon = lon, altM = alt, timeMs = System.currentTimeMillis())) }
            if (prefs.shareLocation.value) for (r in _radios.value.values) runCatching { r.sendPosition(lat, lon, alt?.toInt()) }
        }
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
            while (isActive) { meshtastic?.sendRangeTest(seq++); delay(intervalSec * 1000L) }
        }
    }

    fun addGeofence(g: GeofenceEntity) = scope.launch { dao.upsertGeofence(g) }

    // ------------------------------------------------------------------ notifications

    private fun createNotificationChannels() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alerts (geofence, SOS)", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notifyMessage(conv: String, from: String, text: String) {
        val sos = text.startsWith("SOS")
        notify(if (sos) "SOS from $from" else from, text, conv.hashCode(), if (sos) CH_ALERTS else CH_MESSAGES, conv)
    }

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
    }
}

fun NodeEntity.toMeshNode() = MeshNode(id, shortName, longName, ProtocolType.valueOf(protocol), rssi, snr, hops ?: 0, lat, lon, battery, voltage, lastHeardMs = lastHeardMs)

fun EncryptedChannel.Companion.fromEntity(c: ChannelEntity) = EncryptedChannel(c.id, ProtocolType.valueOf(c.protocol), c.slot, c.name, c.keyB64)

