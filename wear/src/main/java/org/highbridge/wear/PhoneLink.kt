// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.wear

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

data class WMsg(val from: String, val text: String, val out: Boolean, val t: Long)
data class WConv(val id: String, val title: String, val net: String, val unread: Int, val msgs: List<WMsg>)
data class WState(val radios: List<String>, val grid: String, val convs: List<WConv>, val t: Long)

/** Talks to the Highbridge phone app over the Wearable Data Layer. */
class PhoneLink(private val ctx: Context) : DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    val state = MutableStateFlow<WState?>(null)
    /** Last answer from the phone ("Sent", "SOS sent on …"), shown briefly. */
    val toast = MutableStateFlow<String?>(null)
    val phoneFound = MutableStateFlow<Boolean?>(null)

    fun attach() {
        Wearable.getDataClient(ctx).addListener(this)
        Wearable.getMessageClient(ctx).addListener(this)
    }
    fun detach() {
        Wearable.getDataClient(ctx).removeListener(this)
        Wearable.getMessageClient(ctx).removeListener(this)
    }

    suspend fun load() {
        runCatching {
            val items = Wearable.getDataClient(ctx).dataItems.await()
            items.forEach { if (it.uri.path == PATH_STATE) parse(DataMapItem.fromDataItem(it).dataMap.getString("json")) }
            items.release()
        }
        send(PATH_REFRESH, "")
    }

    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        events.forEach { e ->
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == PATH_STATE)
                parse(DataMapItem.fromDataItem(e.dataItem).dataMap.getString("json"))
        }
    }

    override fun onMessageReceived(e: com.google.android.gms.wearable.MessageEvent) {
        if (e.path == PATH_RESULT) toast.value = String(e.data)
    }

    private fun parse(json: String?) {
        json ?: return
        state.value = runCatching { decode(json) }.getOrNull() ?: state.value
    }

    /** Sends a request to every connected phone (normally just one). Returns false if none was reachable. */
    suspend fun send(path: String, body: String): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
        phoneFound.value = nodes.isNotEmpty()
        nodes.forEach { Wearable.getMessageClient(ctx).sendMessage(it.id, path, body.toByteArray()).await() }
        nodes.isNotEmpty()
    }.getOrDefault(false)

    suspend fun reply(conv: String, text: String) =
        send(PATH_SEND, JSONObject().put("conv", conv).put("text", text).toString())

    suspend fun markRead(conv: String) = send(PATH_READ, JSONObject().put("conv", conv).toString())

    companion object {
        const val PATH_STATE = "/highbridge/state"
        const val PATH_SEND = "/highbridge/send"
        const val PATH_CHECKIN = "/highbridge/checkin"
        const val PATH_SOS = "/highbridge/sos"
        const val PATH_READ = "/highbridge/read"
        const val PATH_REFRESH = "/highbridge/refresh"
        const val PATH_RESULT = "/highbridge/result"

        fun decode(json: String): WState {
            val o = JSONObject(json)
            val r = o.optJSONArray("radios")
            val c = o.optJSONArray("convs")
            return WState(
                radios = (0 until (r?.length() ?: 0)).map { r!!.getString(it) },
                grid = o.optString("grid"),
                convs = (0 until (c?.length() ?: 0)).map { i ->
                    val x = c!!.getJSONObject(i)
                    val m = x.optJSONArray("msgs")
                    WConv(x.getString("id"), x.optString("title"), x.optString("net"), x.optInt("unread"),
                        (0 until (m?.length() ?: 0)).map { j ->
                            val y = m!!.getJSONObject(j)
                            WMsg(y.optString("from"), y.optString("text"), y.optBoolean("out"), y.optLong("t"))
                        })
                },
                t = o.optLong("t"),
            )
        }
    }
}
