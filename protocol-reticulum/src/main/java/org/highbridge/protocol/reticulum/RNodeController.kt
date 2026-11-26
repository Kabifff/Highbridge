package org.highbridge.protocol.reticulum

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.highbridge.core.protocol.link.StreamLink
import kotlin.math.pow

/** LoRa settings for an RNode. Frequency and bandwidth in Hz. */
data class RNodeParams(val frequencyHz: Long, val bandwidthHz: Long, val txPowerDbm: Int, val spreadingFactor: Int, val codingRate: Int) {
    init {
        require(frequencyHz in 137_000_000L..3_000_000_000L) { "Frequency out of range" }
        require(spreadingFactor in 5..12 && codingRate in 5..8) { "SF 5-12 and CR 5-8" }
    }
    /** Bits per second, same estimate RNS uses. */
    val bitrate: Int get() = (spreadingFactor * ((4.0 / codingRate) / (2.0.pow(spreadingFactor) / (bandwidthHz / 1000.0))) * 1000).toInt()
}

/** Talks KISS to RNode firmware over USB or Bluetooth: detects it, sets the radio up, and carries Reticulum packets. */
class RNodeController(private val link: StreamLink) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var detect = CompletableDeferred<Boolean>()
    private val _stats = MutableStateFlow(RNodeStats())
    val stats: StateFlow<RNodeStats> = _stats
    var onPacket: (ByteArray) -> Unit = {}
    var onLog: (String) -> Unit = {}
    private val decoder = Kiss.Decoder { cmd, data -> handle(cmd, data) }

    data class RNodeStats(val firmware: String? = null, val rssi: Int? = null, val snr: Float? = null, val batteryPct: Int? = null, val radioOn: Boolean = false)

    private fun handle(cmd: Int, d: ByteArray) {
        when (cmd) {
            Kiss.CMD_DATA -> onPacket(d)
            Kiss.CMD_DETECT -> if (d.firstOrNull()?.toInt() == Kiss.DETECT_RESP) detect.complete(true)
            Kiss.CMD_FW_VERSION -> if (d.size >= 2) _stats.value = _stats.value.copy(firmware = "${d[0]}.${"%02d".format(d[1])}")
            Kiss.CMD_STAT_RSSI -> d.firstOrNull()?.let { _stats.value = _stats.value.copy(rssi = (it.toInt() and 0xFF) - 157) }
            Kiss.CMD_STAT_SNR -> d.firstOrNull()?.let { _stats.value = _stats.value.copy(snr = it / 4f) }
            Kiss.CMD_STAT_BAT -> if (d.size >= 2) _stats.value = _stats.value.copy(batteryPct = d[1].toInt() and 0xFF)
            Kiss.CMD_RADIO_STATE -> _stats.value = _stats.value.copy(radioOn = d.firstOrNull()?.toInt() == Kiss.RADIO_STATE_ON)
            Kiss.CMD_ERROR -> onLog("RNode error 0x%02x".format(d.firstOrNull() ?: 0))
        }
    }

    suspend fun open(): Boolean {
        if (!link.isOpen.value) link.open().getOrThrow()
        scope.launch { link.incoming.collect { c -> c.forEach { decoder.feed(it) } } }
        return isRNode()
    }

    suspend fun isRNode(): Boolean {
        detect = CompletableDeferred()
        link.write(Kiss.frame(Kiss.CMD_DETECT, byteArrayOf(Kiss.DETECT_REQ.toByte())) + Kiss.frame(Kiss.CMD_FW_VERSION, byteArrayOf(0)))
        return withTimeoutOrNull(2_500) { detect.await() } ?: false
    }

    suspend fun configure(p: RNodeParams) {
        link.write(Kiss.frame(Kiss.CMD_FREQUENCY, Kiss.be32(p.frequencyHz))); delay(60)
        link.write(Kiss.frame(Kiss.CMD_BANDWIDTH, Kiss.be32(p.bandwidthHz))); delay(60)
        link.write(Kiss.frame(Kiss.CMD_TXPOWER, byteArrayOf(p.txPowerDbm.toByte()))); delay(60)
        link.write(Kiss.frame(Kiss.CMD_SF, byteArrayOf(p.spreadingFactor.toByte()))); delay(60)
        link.write(Kiss.frame(Kiss.CMD_CR, byteArrayOf(p.codingRate.toByte()))); delay(60)
        link.write(Kiss.frame(Kiss.CMD_RADIO_STATE, byteArrayOf(Kiss.RADIO_STATE_ON.toByte())))
    }

    suspend fun send(packet: ByteArray) { link.write(Kiss.frame(Kiss.CMD_DATA, packet)) }

    fun close() {
        scope.launch { runCatching { link.write(Kiss.frame(Kiss.CMD_LEAVE, byteArrayOf(0xFF.toByte()))) } }
        scope.cancel(); link.close()
    }
}
