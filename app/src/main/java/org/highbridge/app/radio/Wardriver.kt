// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.radio

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.highbridge.app.data.MeshDao
import org.highbridge.app.data.Prefs
import org.highbridge.app.data.WardriveSampleEntity
import org.highbridge.app.pro.Geo
import org.highbridge.core.protocol.MeshRadio
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.RadioEvent
import org.highbridge.feature.bridge.MeshBridgeService
import org.highbridge.protocol.meshcore.MeshCoreRadio
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wardriving: records where your radio hears the mesh (and, with pings on, where the mesh hears you),
 * then shares the coverage with the stpaulmesh.com map.
 *
 * Kinds of sample: 0 heard a node or repeater here (passive), 1 our ping (heard = how many picked it up),
 * 2 nothing heard for a while here (dead spot).
 */
class Wardriver(
    private val ctx: Context,
    private val dao: MeshDao,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val radio: (ProtocolType) -> MeshRadio?,
    private val airtimeOk: () -> Boolean,
) {
    data class State(
        val running: Boolean = false, val protocol: ProtocolType? = null, val session: Long = 0,
        val samples: Int = 0, val pings: Int = 0, val pingsHeard: Int = 0, val bestSnr: Float? = null,
        val fix: Pair<Double, Double>? = null, val accuracyM: Float? = null, val note: String = "",
        val uploaded: Int = 0, val uploadError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var fix: Location? = null
    private var fixAt = 0L
    private var lastPingAt = 0L
    private var lastPingPos: Location? = null
    private var lastHeardAt = 0L
    private var lastDeadPos: Location? = null
    private val recent = HashMap<String, Pair<Long, Float>>() // source -> (time, snr) for de-duplication

    /** Meshtastic: ping packet id waiting for an (implicit) ack. MeshCore: time of the last #wardriving ping. */
    @Volatile private var pendingPing: Pair<String, Long>? = null
    @Volatile private var pendingPingRow: Long = 0
    @Volatile private var pendingHeard = 0
    @Volatile private var pendingBest: Float? = null
    private val pendingRepeaters = HashSet<String>()

    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { onFix(it) }

    val coverageUrl: (ProtocolType) -> String = { "https://stpaulmesh.com/${if (it == ProtocolType.MESHCORE) "meshcore" else "meshtastic"}#wardriving" }

    @SuppressLint("MissingPermission")
    fun start(protocol: ProtocolType) {
        if (_state.value.running) stop()
        require(protocol == ProtocolType.MESHTASTIC || protocol == ProtocolType.MESHCORE) { "Wardriving works with Meshtastic and MeshCore" }
        val session = System.currentTimeMillis()
        _state.value = State(running = true, protocol = protocol, session = session, note = "Waiting for GPS…")
        recent.clear(); lastPingAt = 0; lastPingPos = null; lastHeardAt = SystemClock.elapsedRealtime(); lastDeadPos = null; fix = null
        runCatching {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 5f, listener, Looper.getMainLooper())
        }.onFailure { _state.value = _state.value.copy(note = "Location is off or not allowed: ${it.message}") }
        runCatching { lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 10f, listener, Looper.getMainLooper()) }
        MeshBridgeService.setWardriving(ctx, true)
        job = scope.launch {
            var lastUpload = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(5_000)
                tick(protocol, session)
                if (prefs.wardriveUpload.value && SystemClock.elapsedRealtime() - lastUpload > 120_000) {
                    lastUpload = SystemClock.elapsedRealtime(); upload(protocol)
                }
            }
        }
    }

    fun stop() {
        val s = _state.value
        job?.cancel(); job = null
        runCatching { lm?.removeUpdates(listener) }
        MeshBridgeService.setWardriving(ctx, false)
        _state.value = s.copy(running = false, note = "Stopped")
        if (s.protocol != null && prefs.wardriveUpload.value) scope.launch { upload(s.protocol) }
    }

    private fun onFix(l: Location) {
        if (l.hasAccuracy() && l.accuracy > 60f) return // too rough to map
        fix = l; fixAt = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(fix = l.latitude to l.longitude, accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            note = if (_state.value.note.startsWith("Waiting")) "Recording" else _state.value.note)
    }

    private fun freshFix(): Location? = fix?.takeIf { SystemClock.elapsedRealtime() - fixAt < 15_000 }

    /** True inside the "hide around home" circle, where nothing is recorded. */
    private fun hidden(lat: Double, lon: Double): Boolean {
        val home = prefs.wardriveHome.value.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        return home.size == 2 && Geo.distanceM(home[0], home[1], lat, lon) < prefs.wardriveHideM.value
    }

    private suspend fun tick(protocol: ProtocolType, session: Long) {
        val l = freshFix() ?: return
        if (hidden(l.latitude, l.longitude)) { _state.value = _state.value.copy(note = "Inside your home privacy circle: not recording"); return }
        val now = SystemClock.elapsedRealtime()
        // Close out a ping whose answers have had time to arrive.
        pendingPing?.let { (_, at) -> if (now - at > 12_000) finishPing() }
        // Dead spot: a minute of silence after moving 150 m.
        if (now - lastHeardAt > 60_000 && (lastDeadPos == null || lastDeadPos!!.distanceTo(l) > 150f)) {
            lastDeadPos = l
            record(WardriveSampleEntity(session = session, protocol = protocol.name, lat = l.latitude, lon = l.longitude,
                timeMs = System.currentTimeMillis(), kind = 2, snr = null, rssi = null, heard = 0, source = null))
        }
        // Active ping: at most every 60 s (MeshCore) or 120 s (Meshtastic), only after moving 150 m, within the airtime allowance.
        val every = if (protocol == ProtocolType.MESHCORE) 60_000L else 120_000L
        if (prefs.wardrivePing.value && pendingPing == null && now - lastPingAt > every &&
            (lastPingPos == null || lastPingPos!!.distanceTo(l) > 150f) && airtimeOk()) {
            val r = radio(protocol) ?: run { _state.value = _state.value.copy(note = "Connect your ${protocol.shortTag} radio"); return }
            r.sendWardrivePing(l.latitude, l.longitude).onSuccess { id ->
                lastPingAt = now; lastPingPos = l
                synchronized(pendingRepeaters) { pendingRepeaters.clear() }
                pendingHeard = 0; pendingBest = null
                pendingPingRow = dao.insertWardrive(WardriveSampleEntity(session = session, protocol = protocol.name, lat = l.latitude, lon = l.longitude,
                    timeMs = System.currentTimeMillis(), kind = 1, snr = null, rssi = null, heard = 0, source = null))
                pendingPing = id to now
                _state.value = _state.value.copy(pings = _state.value.pings + 1, samples = _state.value.samples + 1, note = "Ping sent")
            }.onFailure { _state.value = _state.value.copy(note = it.message ?: "Ping failed") }
        }
    }

    private suspend fun finishPing() {
        val row = pendingPingRow
        pendingPing = null
        if (row == 0L) return
        val heard = pendingHeard
        if (heard > 0) {
            dao.markPing(row, heard, pendingBest)
            _state.value = _state.value.copy(pingsHeard = _state.value.pingsHeard + 1, note = "Ping heard by $heard")
        } else _state.value = _state.value.copy(note = "Ping not heard here")
    }

    /** Called for every radio event while connected. */
    fun onEvent(e: RadioEvent) {
        val s = _state.value
        if (!s.running || e.protocol != s.protocol) return
        when (e) {
            is RadioEvent.Ack -> pendingPing?.let { (id, _) ->
                if (e.packetId == id && e.delivered) { pendingHeard = 1; scope.launch { finishPing() } }
            }
            is RadioEvent.RxLog -> {
                // A repeat of our #wardriving ping: count each repeater once.
                val p = pendingPing
                if (p != null && e.channelHash == MeshCoreRadio.wardriveChannelHash && e.pathLen > 0 && e.lastHop != null) {
                    val added = synchronized(pendingRepeaters) { pendingRepeaters.add(e.lastHop!!) }
                    if (added) { pendingHeard++; pendingBest = maxOf(pendingBest ?: e.snr, e.snr) }
                }
            }
            is RadioEvent.Heard -> {
                val snr = e.snr ?: return
                val l = freshFix() ?: return
                if (hidden(l.latitude, l.longitude)) return
                lastHeardAt = SystemClock.elapsedRealtime()
                val key = e.fromId
                val prev = recent[key]
                val now = SystemClock.elapsedRealtime()
                if (prev != null && now - prev.first < 10_000 && snr <= prev.second) return
                recent[key] = now to snr
                scope.launch {
                    record(WardriveSampleEntity(session = s.session, protocol = s.protocol!!.name, lat = l.latitude, lon = l.longitude,
                        timeMs = System.currentTimeMillis(), kind = 0, snr = snr, rssi = e.rssi, heard = 1, source = key))
                }
            }
            else -> {}
        }
    }

    private suspend fun record(sample: WardriveSampleEntity) {
        dao.insertWardrive(sample)
        val st = _state.value
        _state.value = st.copy(samples = st.samples + 1, bestSnr = sample.snr?.let { maxOf(st.bestSnr ?: it, it) } ?: st.bestSnr)
    }

    /**
     * Sends samples not yet shared to stpaulmesh.com. Only position (rounded to about 10 m), time,
     * signal and counts go out; never node names, IDs or anything about you.
     */
    suspend fun upload(protocol: ProtocolType): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var total = 0
            while (true) {
                val batch = dao.wardrivePending(protocol.name, 400)
                if (batch.isEmpty()) break
                val arr = JSONArray()
                batch.forEach { s ->
                    arr.put(JSONObject().put("t", s.timeMs).put("la", Math.round(s.lat * 1e4) / 1e4).put("lo", Math.round(s.lon * 1e4) / 1e4)
                        .put("k", s.kind).put("h", s.heard).apply { s.snr?.let { put("s", it.toDouble()) }; s.rssi?.let { put("r", it) } })
                }
                val body = JSONObject().put("v", 1).put("app", "Highbridge").put("samples", arr).toString().toByteArray()
                val c = URL("$API/${if (protocol == ProtocolType.MESHCORE) "meshcore" else "meshtastic"}").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 20_000
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "Highbridge-Android")
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val reply = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                when {
                    code in 200..299 -> {}
                    // Outside the map area or rejected as implausible: keep them local, don't retry forever.
                    code == 422 -> { dao.markUploaded(batch.map { it.rowId }); error(runCatching { JSONObject(reply).optString("error") }.getOrNull() ?: "Not accepted") }
                    else -> error("stpaulmesh.com answered HTTP $code")
                }
                dao.markUploaded(batch.map { it.rowId })
                total += batch.size
                if (batch.size < 400) break
            }
            total
        }.onSuccess { n -> _state.value = _state.value.copy(uploaded = _state.value.uploaded + n, uploadError = null) }
         .onFailure { _state.value = _state.value.copy(uploadError = it.message) }
    }

    companion object {
        const val API = "https://stpaulmesh.com/api/coverage"
    }
}
