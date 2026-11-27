// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.feature.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.highbridge.core.protocol.MeshTransport
import org.highbridge.core.protocol.ProtocolType

/**
 * The app registers how to (re)connect its radios here, so the service can rebuild the bridge
 * by itself after a reboot or after Android kills and restarts it. Before, the service only
 * got radios from the screen that bound to it, so after a restart it ran with nothing connected.
 */
object TransportRegistry {
    @Volatile var provider: (suspend () -> Map<ProtocolType, MeshTransport>)? = null
    /** True while any radio is connected; the service then stays in the foreground to keep the links alive. */
    @Volatile var hasConnectedRadios: () -> Boolean = { false }
    /** Lets the service ask the app to reconnect remembered radios after a reboot. */
    @Volatile var reconnect: (suspend () -> Unit)? = null
}

/** Remembers the chosen bridge mode between restarts. */
object BridgeModeStore {
    @Volatile var mode: BridgeMode = BridgeMode.MESHTASTIC_MESHCORE
}

object BridgePrefs {
    private const val FILE = "bridge"
    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
}

class MeshBridgeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var engine: MeshBridgeEngine? = null
        private set

    inner class LocalBinder : Binder() { fun getService() = this@MeshBridgeService }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Mesh bridge", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Must call startForeground() right away on every start. The old code only did it inside
     * startBridge(), so a start from boot or a sticky restart crashed the app
     * (ForegroundServiceDidNotStartInTimeException) after a few seconds.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(if (wardriving) "Recording coverage" else if (BridgePrefs.isEnabled(this)) "Starting radio bridge…" else "Keeping your radios connected")
        val bridgeOn = BridgePrefs.isEnabled(this)
        if (!bridgeOn && !TransportRegistry.hasConnectedRadios()) {
            engine?.stop(); engine = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        serviceScope.launchCatching {
            if (!TransportRegistry.hasConnectedRadios()) TransportRegistry.reconnect?.invoke()
            val provider = TransportRegistry.provider
            if (bridgeOn && provider != null) startBridge(provider()) else {
                engine?.stop(); engine = null
                goForeground(if (wardriving) "Recording coverage" else if (provider == null) "Open the app to reconnect your radios" else "Connected to your radios")
            }
        }
        return START_STICKY
    }

    /** (Re)builds the bridge with the radios connected right now, keeping the chosen mode and routes. */
    @Synchronized
    fun startBridge(transports: Map<ProtocolType, MeshTransport>) {
        val old = engine
        val mode = old?.mode?.value ?: BridgeModeStore.mode
        val rules = old?.rules?.value
        old?.stop()
        engine = MeshBridgeEngine(transports, serviceScope).also { e ->
            e.setBridgeMode(mode)
            rules?.let { e.updateRules(it) }
            e.start()
        }
        current = engine
        goForeground("Bridging ${transports.keys.joinToString(" ⇄ ") { it.shortTag }}")
        BridgePrefs.setEnabled(this, true)
    }

    fun stopBridge() {
        engine?.stop(); engine = null; current = null
        BridgePrefs.setEnabled(this, false)
        if (!TransportRegistry.hasConnectedRadios()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        else goForeground("Connected to your radios")
    }

    private fun goForeground(text: String) {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (wardriving) "Highbridge: wardriving" else if (BridgePrefs.isEnabled(this)) "Highbridge: bridge on" else "Highbridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // replace with the app's own icon
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Wardriving adds the location type so GPS keeps working with the screen off.
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (wardriving) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            runCatching { startForeground(NOTIF_ID, notif, type) }
                .onFailure { startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        engine?.stop(); current = null; serviceScope.cancel(); super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "highbridge_bridge"
        /** The running bridge, for the Bridge screen to show stats and change mode. */
        @Volatile var current: MeshBridgeEngine? = null
            private set
        fun disable(ctx: Context) { BridgePrefs.setEnabled(ctx, false); current?.stop(); current = null; start(ctx) }
        const val NOTIF_ID = 4201
        /** Called from the app when the user turns the bridge on. */
        fun enableAndStart(ctx: Context) { BridgePrefs.setEnabled(ctx, true); start(ctx) }
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, MeshBridgeService::class.java))
        /** Set by the wardriver; the service re-posts its notification with location access. */
        @Volatile var wardriving = false
        fun setWardriving(ctx: Context, on: Boolean) { wardriving = on; start(ctx) }
    }
}

private fun CoroutineScope.launchCatching(block: suspend () -> Unit) = launch { runCatching { block() } }

/** Restarts the bridge after a reboot or app update, but only if the user had it turned on. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (BridgePrefs.isEnabled(context)) MeshBridgeService.start(context)
        }
    }
}

