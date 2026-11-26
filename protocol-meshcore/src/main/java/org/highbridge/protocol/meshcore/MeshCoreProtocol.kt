// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.protocol.meshcore

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** MeshCore companion-radio protocol (firmware MyMesh.cpp). All integers are little-endian. */
object MC {
    const val CMD_APP_START = 1; const val CMD_SEND_TXT_MSG = 2; const val CMD_SEND_CHANNEL_TXT_MSG = 3
    const val CMD_GET_CONTACTS = 4; const val CMD_SET_DEVICE_TIME = 6; const val CMD_SEND_SELF_ADVERT = 7
    const val CMD_SET_ADVERT_NAME = 8; const val CMD_ADD_UPDATE_CONTACT = 9; const val CMD_SYNC_NEXT_MESSAGE = 10
    const val CMD_SET_RADIO_PARAMS = 11; const val CMD_SET_RADIO_TX_POWER = 12; const val CMD_RESET_PATH = 13
    const val CMD_SET_ADVERT_LATLON = 14; const val CMD_REMOVE_CONTACT = 15; const val CMD_REBOOT = 19
    const val CMD_GET_BATT_AND_STORAGE = 20; const val CMD_DEVICE_QUERY = 22; const val CMD_SEND_LOGIN = 26
    const val CMD_SEND_STATUS_REQ = 27; const val CMD_GET_CHANNEL = 31; const val CMD_SET_CHANNEL = 32
    const val CMD_SEND_TRACE_PATH = 36; const val CMD_SEND_TELEMETRY_REQ = 39; const val CMD_SEND_CHANNEL_DATA = 62
    const val CMD_RUN_CLI_COMMAND = 66

    const val RESP_OK = 0; const val RESP_ERR = 1; const val RESP_CONTACTS_START = 2; const val RESP_CONTACT = 3
    const val RESP_END_OF_CONTACTS = 4; const val RESP_SELF_INFO = 5; const val RESP_SENT = 6
    const val RESP_CONTACT_MSG_RECV = 7; const val RESP_CHANNEL_MSG_RECV = 8; const val RESP_CURR_TIME = 9
    const val RESP_NO_MORE_MESSAGES = 10; const val RESP_BATT_AND_STORAGE = 12; const val RESP_DEVICE_INFO = 13
    const val RESP_CONTACT_MSG_RECV_V3 = 16; const val RESP_CHANNEL_MSG_RECV_V3 = 17; const val RESP_CHANNEL_INFO = 18
    const val RESP_CHANNEL_DATA_RECV = 27; const val RESP_CLI_REPLY = 29

    const val PUSH_ADVERT = 0x80; const val PUSH_PATH_UPDATED = 0x81; const val PUSH_SEND_CONFIRMED = 0x82
    const val PUSH_MSG_WAITING = 0x83; const val PUSH_LOGIN_SUCCESS = 0x85; const val PUSH_LOGIN_FAIL = 0x86
    const val PUSH_STATUS_RESPONSE = 0x87; const val PUSH_TRACE_DATA = 0x89; const val PUSH_NEW_ADVERT = 0x8A
    const val PUSH_TELEMETRY_RESPONSE = 0x8B

    const val TXT_PLAIN = 0; const val TXT_CLI_DATA = 1; const val TXT_SIGNED_PLAIN = 2
    const val PUB_KEY_SIZE = 32; const val MAX_PATH_SIZE = 64; const val MAX_TEXT_LEN = 160
    const val APP_PROTOCOL_VER = 3
    const val ADV_TYPE_CHAT = 1; const val ADV_TYPE_REPEATER = 2; const val ADV_TYPE_ROOM = 3; const val ADV_TYPE_SENSOR = 4

    private fun buf(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun now() = (System.currentTimeMillis() / 1000).toInt()

    fun appStart(appName: String = "Highbridge"): ByteArray =
        byteArrayOf(CMD_APP_START.toByte(), APP_PROTOCOL_VER.toByte(), 0, 0, 0, 0, 0, 0) + appName.toByteArray()
    fun deviceQuery() = byteArrayOf(CMD_DEVICE_QUERY.toByte(), APP_PROTOCOL_VER.toByte())
    fun getContacts() = byteArrayOf(CMD_GET_CONTACTS.toByte())
    fun syncNext() = byteArrayOf(CMD_SYNC_NEXT_MESSAGE.toByte())
    fun selfAdvert(flood: Boolean = true) = byteArrayOf(CMD_SEND_SELF_ADVERT.toByte(), if (flood) 1 else 0)
    fun battery() = byteArrayOf(CMD_GET_BATT_AND_STORAGE.toByte())
    fun getChannel(idx: Int) = byteArrayOf(CMD_GET_CHANNEL.toByte(), idx.toByte())
    fun setTime(epochSec: Int = now()) = buf(5).put(CMD_SET_DEVICE_TIME.toByte()).putInt(epochSec).array()
    fun setAdvertName(name: String) = byteArrayOf(CMD_SET_ADVERT_NAME.toByte()) + name.toByteArray().take(31).toByteArray()
    fun setLatLon(lat: Double, lon: Double) = buf(9).put(CMD_SET_ADVERT_LATLON.toByte()).putInt((lat * 1e6).toInt()).putInt((lon * 1e6).toInt()).array()
    fun reboot() = byteArrayOf(CMD_REBOOT.toByte()) + "reboot".toByteArray()

    fun sendText(recipientPrefix6: ByteArray, text: String, attempt: Int = 0, txtType: Int = TXT_PLAIN): ByteArray {
        require(recipientPrefix6.size >= 6)
        val t = utf8Cap(text, MAX_TEXT_LEN)
        return buf(13 + t.size).put(CMD_SEND_TXT_MSG.toByte()).put(txtType.toByte()).put(attempt.toByte()).putInt(now())
            .put(recipientPrefix6, 0, 6).put(t).array()
    }

    fun sendChannelText(channelIdx: Int, text: String): ByteArray {
        val t = utf8Cap(text, MAX_TEXT_LEN)
        return buf(7 + t.size).put(CMD_SEND_CHANNEL_TXT_MSG.toByte()).put(TXT_PLAIN.toByte()).put(channelIdx.toByte()).putInt(now()).put(t).array()
    }

    /** Binary datagram on a group channel, flooded (path 0xFF). Used for photo / voice chunks. */
    fun sendChannelData(channelIdx: Int, data: ByteArray, dataType: Int = DATA_TYPE_HIGHBRIDGE): ByteArray =
        byteArrayOf(CMD_SEND_CHANNEL_DATA.toByte(), channelIdx.toByte(), 0xFF.toByte(), dataType.toByte(), (dataType shr 8).toByte()) + data
    const val DATA_TYPE_HIGHBRIDGE = 0x4248

    data class ChannelData(val channelIdx: Int, val dataType: Int, val data: ByteArray, val snr: Float)
    fun parseChannelData(f: ByteArray): ChannelData? {
        if (f.size < 9) return null
        val len = f[8].toInt() and 0xFF
        if (f.size < 9 + len) return null
        return ChannelData(f[4].toInt() and 0xFF, (f[6].toInt() and 0xFF) or ((f[7].toInt() and 0xFF) shl 8), f.copyOfRange(9, 9 + len), f[1] / 4f)
    }

    /** 128-bit secret only; the firmware rejects 256-bit channel keys. */
    fun setChannel(idx: Int, name: String, secret16: ByteArray): ByteArray {
        require(secret16.size == 16) { "MeshCore channel secrets are 16 bytes" }
        val n = ByteArray(32); name.toByteArray().copyInto(n, 0, 0, minOf(31, name.toByteArray().size))
        return byteArrayOf(CMD_SET_CHANNEL.toByte(), idx.toByte()) + n + secret16
    }

    fun setRadio(freqKhz: Int, bwHz: Int, sf: Int, cr: Int): ByteArray {
        require(freqKhz in 150_000..2_500_000 && bwHz in 7_000..500_000 && sf in 5..12 && cr in 5..8) { "Radio settings out of range" }
        return buf(11).put(CMD_SET_RADIO_PARAMS.toByte()).putInt(freqKhz).putInt(bwHz).put(sf.toByte()).put(cr.toByte()).array()
    }

    fun setTxPower(dbm: Int) = byteArrayOf(CMD_SET_RADIO_TX_POWER.toByte(), dbm.coerceIn(-9, 30).toByte())
    fun resetPath(pubKey: ByteArray) = byteArrayOf(CMD_RESET_PATH.toByte()) + pubKey.also { require(it.size == PUB_KEY_SIZE) }
    fun removeContact(pubKey: ByteArray) = byteArrayOf(CMD_REMOVE_CONTACT.toByte()) + pubKey
    fun login(pubKey: ByteArray, password: String) = byteArrayOf(CMD_SEND_LOGIN.toByte()) + pubKey + password.toByteArray().take(15).toByteArray()
    fun statusRequest(pubKey: ByteArray) = byteArrayOf(CMD_SEND_STATUS_REQ.toByte()) + pubKey
    fun telemetryRequest(pubKey: ByteArray) = byteArrayOf(CMD_SEND_TELEMETRY_REQ.toByte(), 0, 0, 0) + pubKey
    /** Runs a command on the companion radio itself (firmware v14+). */
    fun localCli(command: String) = byteArrayOf(CMD_RUN_CLI_COMMAND.toByte()) + command.toByteArray()
    /** Remote repeater CLI: a text message of type CLI_DATA to a logged-in repeater. */
    fun remoteCli(prefix6: ByteArray, command: String) = sendText(prefix6, command, txtType = TXT_CLI_DATA)

    fun tracePath(tag: Int, path: ByteArray): ByteArray =
        buf(10 + path.size).put(CMD_SEND_TRACE_PATH.toByte()).putInt(tag).putInt(0).put(0).put(path).array()

    /** Rewrites a contact with a hand-edited path (the visual path editor). [path] holds 1-byte repeater hashes. */
    fun updateContactPath(c: Contact, path: ByteArray?): ByteArray {
        val b = buf(1 + 32 + 3 + 64 + 32 + 4 + 8)
        b.put(CMD_ADD_UPDATE_CONTACT.toByte()).put(c.publicKey).put(c.type.toByte()).put(c.flags.toByte())
        if (path == null) b.put(0xFF.toByte()) else { require(path.size <= MAX_PATH_SIZE); b.put(path.size.toByte()) }
        val p = ByteArray(MAX_PATH_SIZE); path?.copyInto(p); b.put(p)
        val n = ByteArray(32); c.name.toByteArray().let { it.copyInto(n, 0, 0, minOf(31, it.size)) }; b.put(n)
        b.putInt(c.lastAdvert).putInt((c.lat * 1e6).toInt()).putInt((c.lon * 1e6).toInt())
        return b.array()
    }

    fun utf8Cap(s: String, max: Int): ByteArray {
        var bytes = s.toByteArray()
        if (bytes.size <= max) return bytes
        var end = s.length
        while (end > 0 && s.substring(0, end).toByteArray().size > max) end--
        bytes = s.substring(0, end).toByteArray()
        return bytes
    }

    fun hex(b: ByteArray, n: Int = b.size) = b.take(n).joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---------- parsing ----------

    data class Contact(
        val publicKey: ByteArray, val type: Int, val flags: Int, val pathLen: Int, val path: ByteArray,
        val name: String, val lastAdvert: Int, val lat: Double, val lon: Double,
    ) {
        val prefixHex get() = hex(publicKey, 6)
        val isRepeater get() = type == ADV_TYPE_REPEATER
        val isRoom get() = type == ADV_TYPE_ROOM
        /** 0xFF = no known path, messages flood. */
        val directPath: ByteArray? get() = if (pathLen == 0xFF || pathLen > MAX_PATH_SIZE) null else path.copyOf(pathLen)
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        val end = (off until minOf(b.size, off + len)).firstOrNull { b[it] == 0.toByte() } ?: minOf(b.size, off + len)
        return String(b, off, end - off)
    }

    fun parseContact(f: ByteArray): Contact? {
        if (f.size < 1 + 32 + 3 + 64 + 32 + 4) return null
        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        var i = 1
        val key = f.copyOfRange(i, i + 32); i += 32
        val type = f[i++].toInt() and 0xFF; val flags = f[i++].toInt() and 0xFF; val pathLen = f[i++].toInt() and 0xFF
        val path = f.copyOfRange(i, i + 64); i += 64
        val name = cstr(f, i, 32); i += 32
        val last = bb.getInt(i); i += 4
        val lat = if (f.size >= i + 8) bb.getInt(i) / 1e6 else 0.0
        val lon = if (f.size >= i + 8) bb.getInt(i + 4) / 1e6 else 0.0
        return Contact(key, type, flags, pathLen, path, name, last, lat, lon)
    }

    data class SelfInfo(val txPower: Int, val maxTxPower: Int, val publicKey: ByteArray, val lat: Double, val lon: Double,
                        val freqKhz: Int, val bwHz: Int, val sf: Int, val cr: Int, val name: String)

    fun parseSelfInfo(f: ByteArray): SelfInfo? {
        if (f.size < 58) return null
        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        val key = f.copyOfRange(4, 36)
        val lat = bb.getInt(36) / 1e6; val lon = bb.getInt(40) / 1e6
        val freq = bb.getInt(48); val bw = bb.getInt(52)
        return SelfInfo(f[2].toInt(), f[3].toInt(), key, lat, lon, freq, bw, f[56].toInt(), f[57].toInt(), String(f, 58, f.size - 58))
    }

    data class DeviceInfo(val firmwareCode: Int, val buildDate: String, val manufacturer: String, val version: String)
    fun parseDeviceInfo(f: ByteArray): DeviceInfo? {
        if (f.size < 80) return DeviceInfo(f.getOrNull(1)?.toInt() ?: 0, "", "", "")
        return DeviceInfo(f[1].toInt(), cstr(f, 8, 12), cstr(f, 20, 40), cstr(f, 60, 20))
    }

    data class Message(val isChannel: Boolean, val channelIdx: Int, val senderPrefixHex: String?, val pathLen: Int, val txtType: Int,
                       val timestamp: Int, val text: String, val snr: Float?, val authorPrefixHex: String? = null)

    fun parseMessage(f: ByteArray): Message? {
        val code = f[0].toInt() and 0xFF
        val v3 = code == RESP_CONTACT_MSG_RECV_V3 || code == RESP_CHANNEL_MSG_RECV_V3
        var i = 1
        val snr = if (v3) { val s = f[1] / 4f; i = 4; s } else null
        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        return when (code) {
            RESP_CONTACT_MSG_RECV, RESP_CONTACT_MSG_RECV_V3 -> {
                if (f.size < i + 12) return null
                val prefix = hex(f.copyOfRange(i, i + 6)); i += 6
                val path = f[i++].toInt() and 0xFF; val type = f[i++].toInt() and 0xFF
                val ts = bb.getInt(i); i += 4
                var author: String? = null
                if (type == TXT_SIGNED_PLAIN && f.size >= i + 4) { author = hex(f.copyOfRange(i, i + 4)); i += 4 }
                Message(false, 0, prefix, path, type, ts, String(f, i, f.size - i), snr, author)
            }
            RESP_CHANNEL_MSG_RECV, RESP_CHANNEL_MSG_RECV_V3 -> {
                if (f.size < i + 7) return null
                val ch = f[i++].toInt() and 0xFF; val path = f[i++].toInt() and 0xFF; val type = f[i++].toInt() and 0xFF
                val ts = bb.getInt(i); i += 4
                Message(true, ch, null, path, type, ts, String(f, i, f.size - i), snr)
            }
            else -> null
        }
    }

    data class ChannelInfo(val idx: Int, val name: String, val secret: ByteArray)
    fun parseChannelInfo(f: ByteArray): ChannelInfo? = if (f.size < 50) null else ChannelInfo(f[1].toInt() and 0xFF, cstr(f, 2, 32), f.copyOfRange(34, 50))

    /** Repeater status reply (PUSH_STATUS_RESPONSE payload after the 8-byte header). */
    data class RepeaterStatus(val battMv: Int, val queueLen: Int, val noiseFloor: Int, val lastRssi: Int, val recv: Long, val sent: Long,
                              val airTimeSec: Long, val upTimeSec: Long, val lastSnr: Float?)
    fun parseStatus(f: ByteArray): RepeaterStatus? {
        val o = 8
        if (f.size < o + 24) return null
        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        val snr = if (f.size >= o + 44) bb.getShort(o + 42) / 4f else null
        return RepeaterStatus(bb.getShort(o).toInt() and 0xFFFF, bb.getShort(o + 2).toInt() and 0xFFFF, bb.getShort(o + 4).toInt(), bb.getShort(o + 6).toInt(),
            bb.getInt(o + 8).toLong() and 0xFFFFFFFFL, bb.getInt(o + 12).toLong() and 0xFFFFFFFFL, bb.getInt(o + 16).toLong() and 0xFFFFFFFFL,
            bb.getInt(o + 20).toLong() and 0xFFFFFFFFL, snr)
    }

    data class Trace(val tag: Int, val pathHashes: ByteArray, val snrs: List<Float>, val finalSnr: Float)
    fun parseTrace(f: ByteArray): Trace? {
        if (f.size < 12) return null
        val len = f[2].toInt() and 0xFF; val flags = f[3].toInt() and 0x03
        val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
        val hashes = f.copyOfRange(12, minOf(f.size, 12 + len))
        val nSnr = len shr flags
        val snrs = (0 until nSnr).mapNotNull { f.getOrNull(12 + len + it)?.let { s -> s / 4f } }
        return Trace(bb.getInt(4), hashes, snrs, (f.lastOrNull() ?: 0) / 4f)
    }

    // ---------- USB serial framing: app->radio '<' len16 frame, radio->app '>' len16 frame ----------
    fun serialFrame(payload: ByteArray): ByteArray = byteArrayOf('<'.code.toByte(), payload.size.toByte(), (payload.size shr 8).toByte()) + payload

    class SerialDeframer(private val onFrame: (ByteArray) -> Unit) {
        private var state = 0; private var len = 0; private var got = 0
        private var buf = ByteArray(0)
        fun feed(b: Byte) {
            val v = b.toInt() and 0xFF
            when (state) {
                0 -> if (v == '>'.code) state = 1
                1 -> { len = v; state = 2 }
                2 -> { len = len or (v shl 8); if (len == 0 || len > 300) state = 0 else { buf = ByteArray(len); got = 0; state = 3 } }
                3 -> { buf[got++] = b; if (got == len) { onFrame(buf); state = 0 } }
            }
        }
    }
}
