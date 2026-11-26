package org.highbridge.protocol.reticulum

import java.io.ByteArrayOutputStream

/** KISS framing as used by RNode firmware (constants from RNS RNodeInterface). */
object Kiss {
    const val FEND = 0xC0; const val FESC = 0xDB; const val TFEND = 0xDC; const val TFESC = 0xDD
    const val CMD_DATA = 0x00; const val CMD_FREQUENCY = 0x01; const val CMD_BANDWIDTH = 0x02; const val CMD_TXPOWER = 0x03
    const val CMD_SF = 0x04; const val CMD_CR = 0x05; const val CMD_RADIO_STATE = 0x06; const val CMD_DETECT = 0x08
    const val CMD_LEAVE = 0x0A; const val CMD_READY = 0x0F; const val CMD_STAT_RSSI = 0x23; const val CMD_STAT_SNR = 0x24
    const val CMD_STAT_BAT = 0x27; const val CMD_FW_VERSION = 0x50; const val CMD_PLATFORM = 0x48; const val CMD_MCU = 0x49
    const val CMD_ERROR = 0x90
    const val DETECT_REQ = 0x73; const val DETECT_RESP = 0x46
    const val RADIO_STATE_OFF = 0x00; const val RADIO_STATE_ON = 0x01

    fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 8)
        for (b in data) {
            when (b.toInt() and 0xFF) {
                FEND -> { out.write(FESC); out.write(TFEND) }
                FESC -> { out.write(FESC); out.write(TFESC) }
                else -> out.write(b.toInt())
            }
        }
        return out.toByteArray()
    }

    fun frame(cmd: Int, data: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(FEND.toByte(), cmd.toByte()) + escape(data) + byteArrayOf(FEND.toByte())

    fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** Streaming KISS decoder: emits (command, payload) pairs. */
    class Decoder(private val onFrame: (Int, ByteArray) -> Unit) {
        private var inFrame = false; private var escape = false; private var cmd = -1
        private val buf = ByteArrayOutputStream()
        fun feed(b: Byte) {
            val v = b.toInt() and 0xFF
            if (v == FEND) {
                if (inFrame && cmd >= 0) onFrame(cmd, buf.toByteArray())
                inFrame = true; cmd = -1; buf.reset(); escape = false
                return
            }
            if (!inFrame) return
            if (cmd < 0) { cmd = v; return }
            when {
                v == FESC -> escape = true
                escape -> { buf.write(if (v == TFEND) FEND else if (v == TFESC) FESC else v); escape = false }
                else -> buf.write(v)
            }
            if (buf.size() > 600) { inFrame = false; buf.reset() }
        }
    }
}
