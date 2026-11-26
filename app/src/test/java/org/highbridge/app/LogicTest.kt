// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app

import org.highbridge.core.protocol.*
import org.highbridge.core.media.*
import org.highbridge.feature.bridge.*
import org.highbridge.app.pro.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.highbridge.app.pro.TopologyBuilder
import org.highbridge.app.pro.Geo
import org.highbridge.app.pro.GeofenceRules
import org.highbridge.app.pro.Zambretti
import org.highbridge.app.pro.CwTimingDecoder

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) fails++ }

class FakeTransport(override val protocol: ProtocolType, override val maxPayloadBytes: Int) : MeshTransport {
    val sent = mutableListOf<String>(); var n = 0
    override val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val discoveredNodes = MutableStateFlow(emptyList<MeshNode>())
    override val channels = MutableStateFlow(emptyList<MeshChannel>())
    override val incomingPackets = MutableSharedFlow<MeshPacket>()
    override suspend fun connect(address: String) = Result.success(Unit)
    override suspend fun disconnect() {}
    override suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?): Result<String> { sent += text; return Result.success("${protocol.shortTag}-${n++}") }
    override suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String?): Result<String> { sent += "BIN${data.size}"; return Result.success("${protocol.shortTag}-${n++}") }
}
fun pkt(p: ProtocolType, from: String, text: String, to: String? = null, id: String = java.util.UUID.randomUUID().toString()) =
    MeshPacket(id, p, from, from, to, 0, MeshPayload.Text(text))

class LogicTest {
@org.junit.Test
fun everything() = runBlocking {
    fails = 0
    // Regions
    check("27 Meshtastic regions (0-26)", MeshtasticRegion.entries.size == 27 && MeshtasticRegion.entries.map { it.code } == (0..26).toList())
    check("Unknown future region code doesn't crash", MeshtasticRegion.fromCode(33) == null)
    check("Suggest: Germany -> EU_868", MeshtasticRegion.suggestFor("de") == MeshtasticRegion.EU_868)
    check("Suggest: Japan -> JP; Brazil -> BR_902; Unknown -> null", MeshtasticRegion.suggestFor("JP") == MeshtasticRegion.JP && MeshtasticRegion.suggestFor("BR") == MeshtasticRegion.BR_902 && MeshtasticRegion.suggestFor("ZZ") == null)
    check("No country in two regions", MeshtasticRegion.entries.flatMap { it.countries }.let { it.size == it.toSet().size })
    check("Every MeshCore preset is legal for its band", MeshCorePresets.ALL.all { MeshCorePresets.validate(it).isEmpty() })
    check("Illegal MeshCore freq caught", MeshCorePresets.validate(MeshCorePresets.ALL[0].copy(frequencyKhz = 869_618)).isNotEmpty())
    check("Default region is UNSET, power = region default", MeshtasticLoRaConfig().region == MeshtasticRegion.UNSET && MeshtasticLoRaConfig().txPowerDbm == 0)
    check("TX power capped by region (JP 13 dBm)", MeshtasticLoRaConfig(region = MeshtasticRegion.JP, txPowerDbm = 30).effectiveTxPowerDbm == 13)
    check("St. Paul quick-join = US + Medium Fast", StPaulQuickJoin.meshtastic.preset == MeshtasticModemPreset.MEDIUM_FAST && StPaulQuickJoin.meshtastic.region == MeshtasticRegion.US)
    // Channels
    val ch = EncryptedChannel.createRandom(ProtocolType.MESHCORE, 1, "West Seventh")
    val back = EncryptedChannel.fromShareUri(ch.toShareUri())
    check("Share link round-trips (with space in name)", back != null && back.name == "West Seventh" && back.rawKeyBytes().size == 16)
    check("Meshtastic name >11 bytes rejected", runCatching { EncryptedChannel.createRandom(ProtocolType.MESHTASTIC, 0, "WestSeventhSt") }.isFailure)
    check("Meshtastic slot 8 rejected", EncryptedChannel.fromShareUri(EncryptedChannel.createRandom(ProtocolType.MESHTASTIC, 7, "a").toShareUri().replace("i=7", "i=8")) == null)
    // Media
    val data = ByteArray(1000) { it.toByte() }
    val frames = MediaChunker.split(data, MediaType.IMAGE_WEBP, transferId = 42)
    check("All frames <= 156 bytes", frames.all { it.size <= 156 })
    val r = MediaReassembler()
    frames.reversed().forEach { r.onFrameReceived("bob", it) }.let {}
    val r2 = MediaReassembler(); var done: ReassemblyEvent? = null
    for (f in frames.shuffled()) done = r2.onFrameReceived("bob", f)
    check("Reassembles out-of-order", done is ReassemblyEvent.Completed && (done as ReassemblyEvent.Completed).data.contentEquals(data))
    val bad = frames[0].copyOf(); bad[5] = (bad[5] + 1).toByte()
    check("Corrupted header (transferId) now rejected", MediaFrame.decode(bad) == null)
    val r3 = MediaReassembler(); r3.onFrameReceived("alice", frames[0]); r3.onFrameReceived("bob", frames[1])
    check("Two senders with same transferId don't mix", r3.missingChunks("alice", 42).size == frames.size - 1)
    val nack = MediaFrame.nack(MediaType.IMAGE_WEBP, 42, frames.size, listOf(2, 5))
    check("NACK builds and parses", (r3.onFrameReceived("bob", nack) as? ReassemblyEvent.NackReceived)?.missingChunks == listOf(2, 5))
    check("Oversize media refused", runCatching { MediaChunker.split(ByteArray(40_000), MediaType.BINARY_FILE) }.isFailure)
    // Bridge
    val mt = FakeTransport(ProtocolType.MESHTASTIC, 200); val mc = FakeTransport(ProtocolType.MESHCORE, 40)
    val eng = MeshBridgeEngine(mapOf(ProtocolType.MESHTASTIC to mt, ProtocolType.MESHCORE to mc), this)
    eng.handleIncoming(pkt(ProtocolType.MESHTASTIC, "alice", "ok"), mt)
    eng.handleIncoming(pkt(ProtocolType.MESHTASTIC, "bob", "ok"), mt)
    check("Same text from two people both bridged", mc.sent.size == 2)
    eng.handleIncoming(pkt(ProtocolType.MESHCORE, "bridge2", "[MT:alice] ok"), mc)
    check("Echo of alice's message blocked", mt.sent.isEmpty())
    eng.handleIncoming(pkt(ProtocolType.MESHTASTIC, "carol", "secret", to = "dave"), mt)
    check("Direct messages never bridged", mc.sent.size == 2)
    eng.handleIncoming(pkt(ProtocolType.MESHTASTIC, "erin", "x".repeat(100)), mt)
    check("Long text truncated to target limit", mc.sent.last().toByteArray().size <= 40)
    eng.handleIncoming(pkt(ProtocolType.MESHCORE, "frank", "hello"), mc)
    eng.handleIncoming(pkt(ProtocolType.MESHTASTIC, "x", "y", id = "MT-0"), mt)
    check("Own sent packet id not re-bridged", mt.sent.size == 1 && mc.sent.size == 3)
    eng.handleIncoming(pkt(ProtocolType.MESHCORE, "relay", "[MT:gina] hi there"), mc)
    check("No tag stacking", mt.sent.last() == "[MT:gina] hi there")
    check("UTF-8 truncation keeps whole characters", MeshBridgeEngine.truncateUtf8("héllo wörld ✓✓✓", 10).toByteArray().size <= 10)
    // Field tools
    val xml = AtakCotBridge.buildCotXml(MeshNode("1", "Bob \"<x>\" & co", "L", ProtocolType.MESHTASTIC, latitude = 44.9, longitude = -93.1))!!
    check("CoT XML escapes names", "<x>" !in xml && "&lt;x&gt;" in xml)
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).also { check("CoT XML is well-formed", true) }
    val g = GpxTrackRecorder(); g.record(44.9, -93.1); val gpx = g.exportGpx("A & B <trip>")
    javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream()); check("GPX well-formed with odd name", true)
    var t = 0L; val am = AirtimeMonitor(MeshtasticRegion.EU_868) { t }
    am.recordTx(300_000); check("EU 868: 5 min/hr = 8.3%, warns at 80% of 10%", am.shouldWarn() && !am.mustHold())
    check("US has no duty limit", !AirtimeMonitor(MeshtasticRegion.US) { 0 }.apply { recordTx(3_000_000) }.mustHold())
    check("MeshCore reset path = 13 + 32-byte key", org.highbridge.protocol.meshcore.MC.resetPath(ByteArray(32)).let { it[0] == 13.toByte() && it.size == 33 })
    val cli = org.highbridge.protocol.meshcore.MC.remoteCli(ByteArray(6) { 7 }, "neighbors")
    check("MeshCore CLI frame: type 2, txt_type 1, len 13+text", cli[0] == 2.toByte() && cli[1] == 1.toByte() && cli.size == 13 + 9)
    // Security
    val blob = EncryptedBackupManager.encrypt("keys".toByteArray(), "correct horse".toCharArray())
    check("Backup round-trips", EncryptedBackupManager.decrypt(blob, "correct horse".toCharArray()).decodeToString() == "keys")
    check("Wrong backup password fails", runCatching { EncryptedBackupManager.decrypt(blob, "wrong pass".toCharArray()) }.isFailure)
    var now = 0L
    val sec = AppSecurityManager(AppSecurityManager.create("1234", 1000), AppSecurityManager.create("9999", 1000)) { now }
    check("PIN unlock / duress", sec.verify("1234") == PinResult.UNLOCKED && sec.verify("9999") == PinResult.DURESS_WIPE)
    repeat(5) { sec.verify("0000") }; check("Lockout after 5 wrong PINs", sec.verify("1234") == PinResult.LOCKED_OUT)
    now += 31_000; check("Unlocks after waiting", sec.verify("1234") == PinResult.UNLOCKED)
    check("Morse round-trip", MorseCodec.decode(MorseCodec.encode("SOS at 7th & Grand!")) == "SOS AT 7TH & GRAND!")

    // ---------------- added with the full app ----------------
    // MeshCore codec
    val c = org.highbridge.protocol.meshcore.MC
    val st = c.sendText(ByteArray(32) { 7 }, "héllo")
    check("MC send text frame: code 2, plain, 6-byte prefix", st[0].toInt() == 2 && st[1].toInt() == 0 && st.copyOfRange(7, 13).all { it.toInt() == 7 } && String(st, 13, st.size - 13) == "héllo")
    check("MC text capped at 160 bytes on a UTF-8 boundary", c.utf8Cap("é".repeat(100), 160).size == 160 && String(c.utf8Cap("é".repeat(100), 160)).length == 80)
    check("MC set channel needs 16-byte secret", runCatching { c.setChannel(1, "x", ByteArray(32)) }.isFailure && c.setChannel(1, "Fort Road", ByteArray(16)).size == 2 + 32 + 16)
    check("MC radio params validated", runCatching { c.setRadio(910_525, 62_500, 7, 5) }.isSuccess && runCatching { c.setRadio(910_525, 62_500, 13, 5) }.isFailure)
    val contactFrame = byteArrayOf(3) + ByteArray(32) { it.toByte() } + byteArrayOf(2, 0, 2) + byteArrayOf(0x11, 0x22) + ByteArray(62) + "Schmidt Rptr".toByteArray().copyOf(32) +
        java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1_700_000_000).putInt(44_925_000).putInt(-93_125_000).array()
    val pc = c.parseContact(contactFrame)
    check("MC contact parses (name, repeater, path, position)", pc != null && pc.name == "Schmidt Rptr" && pc.isRepeater && pc.directPath!!.contentEquals(byteArrayOf(0x11, 0x22)) && kotlin.math.abs(pc.lat - 44.925) < 1e-6)
    val upd = c.updateContactPath(pc!!, byteArrayOf(0x33))
    check("MC path editor frame", upd[0].toInt() == 9 && upd[35].toInt() == 1 && upd[36].toInt() == 0x33 && c.parseContact(byteArrayOf(3) + upd.copyOfRange(1, upd.size))?.name == "Schmidt Rptr")
    val chMsg = byteArrayOf(17, 8, 0, 0, 1, 2, 0) + java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).array() + "Fran: hi there".toByteArray()
    val pm = c.parseMessage(chMsg)
    check("MC v3 channel message parses with SNR", pm != null && pm.isChannel && pm.channelIdx == 1 && pm.snr == 2f && pm.text == "Fran: hi there")
    var got: ByteArray? = null
    val de = org.highbridge.protocol.meshcore.MC.SerialDeframer { got = it }
    (byteArrayOf(0x41, '>'.code.toByte(), 3, 0, 5, 6, 7)).forEach { de.feed(it) }
    check("MC USB deframer skips junk and reads '>' frames", got?.contentEquals(byteArrayOf(5, 6, 7)) == true)
    check("MC USB frame header", c.serialFrame(byteArrayOf(1, 2)).contentEquals(byteArrayOf('<'.code.toByte(), 2, 0, 1, 2)))
    // KISS
    val k = org.highbridge.protocol.reticulum.Kiss
    val payload = byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0x02)
    var kd: Pair<Int, ByteArray>? = null
    val dec = org.highbridge.protocol.reticulum.Kiss.Decoder { cmd, d -> kd = cmd to d }
    k.frame(k.CMD_DATA, payload).forEach { dec.feed(it) }
    check("KISS escape round-trip", kd?.first == 0 && kd?.second?.contentEquals(payload) == true)
    check("RNode bitrate estimate (SF8, 125 kHz, CR5) ~3125 bps", org.highbridge.protocol.reticulum.RNodeParams(915_000_000, 125_000, 17, 8, 5).bitrate in 3000..3200)
    // Meshtastic serial deframer
    val frames2 = mutableListOf<ByteArray>(); val logs = mutableListOf<String>()
    val sd = org.highbridge.protocol.meshtastic.StreamDeframer({ frames2 += it }, { logs += it })
    ("INFO boot\n".toByteArray() + org.highbridge.protocol.meshtastic.MeshtasticSerialLink.frame(byteArrayOf(8, 1))).forEach { sd.feed(it) }
    check("Meshtastic deframer splits log text and frames", frames2.size == 1 && frames2[0].contentEquals(byteArrayOf(8, 1)) && logs == listOf("INFO boot"))
    // Airtime
    val ms = LoRaAirtime.timeOnAirMs(50, LoRaAirtime.forPreset(MeshtasticModemPreset.LONG_FAST))
    check("LongFast 50-byte airtime ~640 ms (Semtech formula, 16-symbol preamble)", ms in 600.0..680.0)
    check("Slower presets take longer", LoRaAirtime.timeOnAirMs(50, LoRaAirtime.forPreset(MeshtasticModemPreset.LONG_SLOW)) > ms)
    // Geo
    val hb = 44.9331 to -93.1044; val schmidt = 44.9220 to -93.1260
    val d = Geo.distanceM(hb.first, hb.second, schmidt.first, schmidt.second)
    check("High Bridge to Schmidt Brewery ~2 km", d in 1800.0..2300.0)
    check("Bearing from the bridge to the brewery is south-west", Geo.compassPoint(Geo.bearingDeg(hb.first, hb.second, schmidt.first, schmidt.second)) == "SW")
    check("Point in polygon", Geo.inPolygon(1.0, 1.0, listOf(0.0 to 0.0, 0.0 to 2.0, 2.0 to 2.0, 2.0 to 0.0)) && !Geo.inPolygon(3.0, 3.0, listOf(0.0 to 0.0, 0.0 to 2.0, 2.0 to 2.0, 2.0 to 0.0)))
    check("Trail drift distance", Geo.distanceToTrailM(44.9300, -93.1000, listOf(44.9300 to -93.1100, 44.9300 to -93.0900)) < 5 &&
        Geo.distanceToTrailM(44.9400, -93.1000, listOf(44.9300 to -93.1100, 44.9300 to -93.0900)) in 1000.0..1200.0)
    check("Geofence circle", GeofenceRules.isInside(GeofenceRules.Kind.CIRCLE, listOf(hb), 100.0, hb.first, hb.second) && !GeofenceRules.isInside(GeofenceRules.Kind.CIRCLE, listOf(hb), 100.0, schmidt.first, schmidt.second))
    // Weather
    check("Zambretti: high and rising is fine", Zambretti.forecast(1035.0, Zambretti.Trend.RISING, true).contains("fine", true))
    check("Zambretti: low and falling is stormy or rainy", Zambretti.forecast(960.0, Zambretti.Trend.FALLING, false).let { it.contains("storm", true) || it.contains("rain", true) })
    check("Sea-level correction raises pressure at altitude", Zambretti.seaLevel(990.0, 214.0) > 1010.0)
    // UF2
    val blk = java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x0A324655).putInt(0x9E5D5157.toInt()).putInt(0x2000)
        .putInt(0).putInt(256).putInt(0).putInt(1).putInt(0xADA52840.toInt()).also { it.putInt(508, 0x0AB16F30) }.array()
    check("UF2 validates and reads nRF52840 family", org.highbridge.app.flasher.Uf2Flasher.validate(blk).let { org.highbridge.app.flasher.Uf2Flasher.familyName(it.familyId) == "nRF52840" })
    check("UF2 rejects random data", runCatching { org.highbridge.app.flasher.Uf2Flasher.validate(ByteArray(512)) }.isFailure)
    check("ESP32 offsets: factory at 0, app update at 0x10000", org.highbridge.app.flasher.EspRomFlasher.offsetFor("firmware-heltec-v4-2.7.11.factory.bin") == 0 &&
        org.highbridge.app.flasher.EspRomFlasher.offsetFor("firmware-heltec-v4-2.7.11.bin") == 0x10000)
    // Update checker
    val uc = org.highbridge.app.flasher.UpdateChecker
    check("Version compare", uc.isNewer("v2.7.12.abcd", "2.7.11") && !uc.isNewer("v2.7.11", "2.7.11.5b2") && uc.isNewer("v1.12.0", null))
    // CW
    val cw = CwTimingDecoder()
    listOf(100L, 300L).forEach { cw.onTone(it); cw.onGap(100) } // .-  = A
    cw.onGap(400)
    listOf(300L, 100L, 100L, 100L).forEach { cw.onTone(it); cw.onGap(100) } // -... = B
    check("CW timing decoder reads AB", cw.text.replace(" ", "") == "AB")
    // Topology
    val tn = listOf(org.highbridge.app.data.NodeEntity("MESHTASTIC:!a", "MESHTASTIC", "!a", "A", "Node A", hops = 0, snr = 5f, lastHeardMs = System.currentTimeMillis()),
        org.highbridge.app.data.NodeEntity("MESHTASTIC:!b", "MESHTASTIC", "!b", "B", "Node B", hops = 1, lastHeardMs = System.currentTimeMillis()))
    val tr = listOf(org.highbridge.app.data.RouteEntity(protocol = "MESHTASTIC", destId = "!b", hops = "!a", snrs = "5.0,-3.0", hopsBack = "", snrsBack = "", timeMs = 1))
    val topo = TopologyBuilder.build(mapOf("MESHTASTIC" to "!me"), tn, tr, 3_600_000)
    check("Topology: me -> A -> B with SNR", topo.edges.any { it.from == "MESHTASTIC:!me" && it.to == "MESHTASTIC:!a" && it.snr == 5f } && topo.edges.any { it.from == "MESHTASTIC:!a" && it.to == "MESHTASTIC:!b" && it.snr == -3f })
    // Media manager NACK path
    val sentBins = mutableListOf<ByteArray>()
    val fake = object : MeshTransport by FakeTransport(ProtocolType.MESHTASTIC, 228) {
        override suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String?): Result<String> { sentBins += data; return Result.success("x") }
    }
    val mm = MediaTransferManager({ mapOf(ProtocolType.MESHTASTIC to fake) }, { 0L })
    val tid = mm.send(ProtocolType.MESHTASTIC, 0, null, MediaType.VOICE_CODEC2, ByteArray(500) { 1 })
    delay(300)
    val nChunks = sentBins.size
    mm.onPacket(MeshPacket("n", ProtocolType.MESHTASTIC, "bob", "bob", null, 0, MeshPayload.Binary(256, MediaFrame.nack(MediaType.VOICE_CODEC2, tid, nChunks, listOf(1)))))
    delay(300)
    check("Media: 500 bytes -> 4 chunks, NACK resends chunk 1", nChunks == 4 && sentBins.size == 5 && sentBins[4].contentEquals(sentBins[1]))

    coroutineContext.cancelChildren()
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
    org.junit.Assert.assertEquals(0, fails)
}
}
