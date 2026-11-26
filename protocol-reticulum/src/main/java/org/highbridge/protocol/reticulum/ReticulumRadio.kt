package org.highbridge.protocol.reticulum

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.EncryptedChannel
import org.highbridge.core.protocol.MeshChannel
import org.highbridge.core.protocol.MeshNode
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshPayload
import org.highbridge.core.protocol.MeshRadio
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.RadioEvent

/** What the Python Reticulum stack (Chaquopy) offers. Implemented in the app module. */
interface RnsEngine {
    data class StartOptions(val configDir: String, val autoInterface: Boolean, val tcpHosts: List<String>, val useRNode: Boolean, val rnodeBitrate: Int, val displayName: String)
    fun start(o: StartOptions): String
    fun stop()
    fun announce()
    fun sendLxmf(destHex: String, text: String): String
    fun joinGroup(index: Int, name: String, key: ByteArray): String
    fun sendGroup(index: Int, sender: String, text: String): String
    fun fetchPage(nodeHex: String, path: String, timeoutSec: Int): String
    fun rnodeInbound(packet: ByteArray)
    fun status(): String
    var listener: RnsListener?
    /** Set by the radio so Python's RNode interface can transmit. */
    var rnodeWriter: ((ByteArray) -> Unit)?
}

interface RnsListener {
    fun onMessage(sourceHex: String, title: String, content: String, timeMs: Long, snr: Float?)
    fun onGroupMessage(index: Int, sender: String, text: String)
    fun onAnnounce(hashHex: String, name: String, aspect: String, hops: Int)
    fun onDelivery(idHex: String, delivered: Boolean)
    fun onLog(line: String)
}

class ReticulumRadio(
    private val engine: RnsEngine,
    private val options: RnsEngine.StartOptions,
    private val rnode: RNodeController? = null,
    private val rnodeParams: RNodeParams? = null,
) : MeshRadio, RnsListener {
    override val protocol = ProtocolType.RETICULUM
    override val maxPayloadBytes: Int = 2000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _state
    private val nodes = linkedMapOf<String, MeshNode>()
    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val discoveredNodes: StateFlow<List<MeshNode>> = _nodes
    private val _channels = MutableStateFlow<List<MeshChannel>>(emptyList())
    override val channels: StateFlow<List<MeshChannel>> = _channels
    private val _incoming = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incomingPackets: Flow<MeshPacket> = _incoming.asSharedFlow()
    private val _events = MutableSharedFlow<RadioEvent>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<RadioEvent> = _events.asSharedFlow()
    private val _myId = MutableStateFlow<String?>(null)
    override val myNodeId: StateFlow<String?> = _myId
    private val _device = MutableStateFlow<String?>(null)
    override val deviceInfo: StateFlow<String?> = _device
    private val _nomadNodes = MutableStateFlow<List<MeshNode>>(emptyList())
    /** NomadNet page servers heard announcing. */
    val nomadNodes: StateFlow<List<MeshNode>> = _nomadNodes

    override suspend fun connect(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            _state.value = ConnectionState.Connecting(if (rnode != null) "RNode" else "Reticulum")
            engine.listener = this@ReticulumRadio
            if (rnode != null && rnodeParams != null) {
                check(rnode.open()) { "No RNode answered. Is RNode firmware installed?" }
                rnode.configure(rnodeParams)
                rnode.onPacket = { engine.rnodeInbound(it) }
                engine.rnodeWriter = { p -> scope.launch { rnode.send(p) } }
            }
            val me = engine.start(options.copy(useRNode = rnode != null, rnodeBitrate = rnodeParams?.bitrate ?: 1200))
            _myId.value = me
            _device.value = "Reticulum · " + engine.status() + (rnode?.stats?.value?.firmware?.let { " · RNode $it" } ?: "")
            _state.value = ConnectionState.Connected("Reticulum", me)
        }.onFailure { _state.value = ConnectionState.Error(it.message ?: "Reticulum failed to start") }
    }

    override suspend fun disconnect() {
        runCatching { engine.stop() }
        rnode?.close()
        engine.rnodeWriter = null
        scope.cancel()
        _state.value = ConnectionState.Disconnected
    }

    fun announce() = runCatching { engine.announce() }

    override suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (toNodeId != null) engine.sendLxmf(toNodeId, text)
            else engine.sendGroup(channelIndex, options.displayName, text)
        }
    }

    override suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String?): Result<String> =
        Result.failure(UnsupportedOperationException("Send photos over Reticulum as LXMF attachments from a Reticulum app for now"))

    override suspend fun applyChannel(channel: EncryptedChannel): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(channel.protocol == ProtocolType.RETICULUM)
            engine.joinGroup(channel.slotIndex, channel.name, channel.rawKeyBytes())
            _channels.value = (_channels.value.filter { it.index != channel.slotIndex } + MeshChannel(channel.slotIndex, channel.name, protocol)).sortedBy { it.index }
        }
    }

    suspend fun fetchPage(nodeHex: String, path: String = "/page/index.mu"): Result<String> = withContext(Dispatchers.IO) {
        runCatching { engine.fetchPage(nodeHex, path, 45) }
    }

    // ---- RnsListener (called on Python threads) ----
    override fun onMessage(sourceHex: String, title: String, content: String, timeMs: Long, snr: Float?) {
        val name = nodes[sourceHex]?.shortName ?: sourceHex.take(6)
        _incoming.tryEmit(MeshPacket("lxm-$sourceHex-$timeMs", protocol, sourceHex, name, _myId.value ?: "me", 0,
            MeshPayload.Text(if (title.isNotBlank()) "$title\n$content" else content), timeMs))
    }

    override fun onGroupMessage(index: Int, sender: String, text: String) {
        _incoming.tryEmit(MeshPacket("grp-$index-${System.nanoTime()}", protocol, "name:$sender", sender, null, index, MeshPayload.Text(text)))
    }

    override fun onAnnounce(hashHex: String, name: String, aspect: String, hops: Int) {
        val n = MeshNode(hashHex, name.ifBlank { hashHex.take(6) }.take(4), name.ifBlank { hashHex.take(12) }, protocol, hopsAway = hops)
        if (aspect == "nomadnetwork.node") {
            _nomadNodes.value = (_nomadNodes.value.filter { it.id != hashHex } + n).sortedBy { it.longName }
            return
        }
        nodes[hashHex] = n
        _nodes.value = nodes.values.sortedByDescending { it.lastHeardMs }
        _events.tryEmit(RadioEvent.NodeUpdated(protocol, n))
    }

    override fun onDelivery(idHex: String, delivered: Boolean) { _events.tryEmit(RadioEvent.Ack(protocol, idHex, delivered)) }
    override fun onLog(line: String) { _events.tryEmit(RadioEvent.Log(protocol, line)) }
}
