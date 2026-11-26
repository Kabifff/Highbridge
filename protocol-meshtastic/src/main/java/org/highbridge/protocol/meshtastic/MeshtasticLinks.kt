package org.highbridge.protocol.meshtastic

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.highbridge.core.protocol.link.GattClient
import org.highbridge.core.protocol.link.StreamLink
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Moves whole ToRadio / FromRadio protobufs to and from a Meshtastic radio. */
interface MeshtasticLink {
    val name: String
    val frames: SharedFlow<ByteArray>
    val logs: SharedFlow<String>
    /** False once the Bluetooth link or USB port goes away. */
    val connected: kotlinx.coroutines.flow.StateFlow<Boolean>
    suspend fun open()
    suspend fun send(toRadio: ByteArray)
    /** Called after want_config is sent; BLE needs to start draining FromRadio. */
    suspend fun afterWantConfig() {}
    fun close()
}

/** Meshtastic over Bluetooth LE: write ToRadio, read FromRadio until empty whenever FromNum notifies. */
class MeshtasticBleLink(context: Context, device: BluetoothDevice) : MeshtasticLink {
    @android.annotation.SuppressLint("MissingPermission")
    override val name: String = device.name ?: device.address
    val gatt = GattClient(context, device)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    override val frames: SharedFlow<ByteArray> = _frames
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val logs: SharedFlow<String> = _logs
    private val drainLock = Mutex()
    private var job: Job? = null
    override val connected get() = gatt.connected

    override suspend fun open() {
        gatt.ensureBonded()
        gatt.connect(512)
        check(gatt.hasService(SERVICE)) { "Not a Meshtastic radio (service missing)" }
        job = scope.launch { gatt.notifications.collect { if (it.uuid == FROM_NUM) drain() } }
        gatt.enableNotifications(SERVICE, FROM_NUM)
    }

    private suspend fun drain() = drainLock.withLock {
        while (true) {
            val bytes = runCatching { gatt.read(SERVICE, FROM_RADIO) }.getOrNull() ?: break
            if (bytes.isEmpty()) break
            _frames.emit(bytes)
        }
    }

    override suspend fun afterWantConfig() { drain() }
    override suspend fun send(toRadio: ByteArray) { gatt.write(SERVICE, TO_RADIO, toRadio); drain() }
    override fun close() { job?.cancel(); gatt.close(); scope.cancel() }

    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM: UUID = UUID.fromString("ed9da18b-a800-4f66-a670-aa7547e34453")
    }
}

/** Meshtastic over USB serial: each protobuf is framed as 0x94 0xC3 len_hi len_lo payload. Other bytes are debug log text. */
class MeshtasticSerialLink(private val stream: StreamLink) : MeshtasticLink {
    override val name: String get() = stream.name
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND)
    override val frames: SharedFlow<ByteArray> = _frames
    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val logs: SharedFlow<String> = _logs
    override val connected get() = stream.isOpen
    private val deframer = StreamDeframer({ scope.launch { _frames.emit(it) } }, { _logs.tryEmit(it) })

    override suspend fun open() {
        if (!stream.isOpen.value) stream.open().getOrThrow()
        scope.launch { stream.incoming.collect { chunk -> chunk.forEach { deframer.feed(it) } } }
        // Wake a sleeping radio and make it drop any half-received frame.
        stream.write(ByteArray(32) { START2.toByte() })
    }

    override suspend fun send(toRadio: ByteArray) { stream.write(frame(toRadio)).getOrThrow() }
    override fun close() { scope.cancel(); stream.close() }

    companion object {
        const val START1 = 0x94
        const val START2 = 0xC3
        const val MAX_LEN = 512
        fun frame(payload: ByteArray): ByteArray {
            require(payload.size <= MAX_LEN) { "Meshtastic frame too large" }
            return byteArrayOf(START1.toByte(), START2.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        }
    }
}

/** Splits a serial byte stream into Meshtastic frames, passing stray printable text through as log lines. */
class StreamDeframer(private val onFrame: (ByteArray) -> Unit, private val onLog: (String) -> Unit) {
    private var state = 0
    private var len = 0
    private val buf = ByteArrayOutputStream()
    private val line = StringBuilder()

    fun feed(b: Byte) {
        val v = b.toInt() and 0xFF
        when (state) {
            0 -> if (v == MeshtasticSerialLink.START1) state = 1 else logChar(v)
            1 -> state = if (v == MeshtasticSerialLink.START2) 2 else { logChar(MeshtasticSerialLink.START1); logChar(v); 0 }
            2 -> { len = v shl 8; state = 3 }
            3 -> {
                len = len or v
                if (len == 0 || len > MeshtasticSerialLink.MAX_LEN) state = 0 else { buf.reset(); state = 4 }
            }
            4 -> {
                buf.write(v)
                if (buf.size() == len) { onFrame(buf.toByteArray()); state = 0 }
            }
        }
    }

    private fun logChar(v: Int) {
        when {
            v == '\n'.code -> { if (line.isNotBlank()) onLog(line.toString().trimEnd()); line.setLength(0) }
            v == '\r'.code -> {}
            v in 0x20..0x7E || v == '\t'.code -> { line.append(v.toChar()); if (line.length > 500) { onLog(line.toString()); line.setLength(0) } }
        }
    }
}
