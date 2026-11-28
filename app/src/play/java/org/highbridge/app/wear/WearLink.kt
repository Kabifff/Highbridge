// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.wear

import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.highbridge.app.HighbridgeApp

/**
 * Google Play build: mirrors chats to a paired Wear OS watch through the Wearable Data Layer.
 * Data goes only to the user's own paired watch, over Google Play services' encrypted link.
 */
object WearLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: HighbridgeApp? = null

    @OptIn(FlowPreview::class)
    fun start(app: HighbridgeApp) {
        this.app = app
        scope.launch {
            combine(app.db.dao().conversations(), app.radios.radios) { _, _ -> Unit }
                .debounce(1500)
                .collect { push() }
        }
    }

    suspend fun push() {
        val a = app ?: return
        runCatching {
            val nodes = Wearable.getNodeClient(a).connectedNodes.await()
            if (nodes.isEmpty()) return
            val req = PutDataMapRequest.create(WatchState.PATH_STATE).apply {
                dataMap.putString("json", WatchState.build(a))
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(a).putDataItem(req).await()
        }.onFailure { Log.w("WearLink", "push failed", it) }
    }

    fun reply(nodeId: String, text: String) {
        val a = app ?: return
        if (text.isEmpty()) return
        scope.launch { runCatching { Wearable.getMessageClient(a).sendMessage(nodeId, WatchState.PATH_RESULT, text.toByteArray()).await() } }
    }

    fun onRequest(nodeId: String, path: String, body: String) {
        val a = app ?: return
        scope.launch {
            if (path == WatchState.PATH_REFRESH) { push(); return@launch }
            val result = runCatching { WatchState.handle(a, path, body) }.getOrElse { "Failed: ${it.message}" }
            reply(nodeId, result)
            push()
        }
    }
}
