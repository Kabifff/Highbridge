// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.wear

import org.highbridge.app.HighbridgeApp
import org.highbridge.app.pro.Geo
import org.highbridge.app.ui.screens.conversationTitle
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the watch shows, as one small JSON document: connected radios, grid square, and the latest
 * chats with their last few messages. Shared by the Play build's watch link; kept here so it is tested
 * and compiled in every build.
 */
object WatchState {
    const val PATH_STATE = "/highbridge/state"
    const val PATH_SEND = "/highbridge/send"
    const val PATH_CHECKIN = "/highbridge/checkin"
    const val PATH_SOS = "/highbridge/sos"
    const val PATH_READ = "/highbridge/read"
    const val PATH_REFRESH = "/highbridge/refresh"
    const val PATH_RESULT = "/highbridge/result"
    const val MAX_CONVS = 8
    const val MAX_MSGS = 6

    suspend fun build(app: HighbridgeApp): String {
        val dao = app.db.dao()
        val radios = app.radios.radios.value
        val nodeNames = dao.nodesNow().associate { it.key to it.longName }
        val channelNames = radios.values.flatMap { it.channels.value.toList() }
            .associate { "${it.protocol.name}:ch:${it.index}" to it.name }
        val convs = JSONArray()
        dao.recentConversations(MAX_CONVS).forEach { c ->
            val msgs = JSONArray()
            dao.lastMessages(c.conversation, MAX_MSGS).reversed().forEach { m ->
                msgs.put(JSONObject().put("from", m.fromName).put("text", m.text.take(300)).put("out", m.outgoing).put("t", m.timeMs))
            }
            convs.put(JSONObject().put("id", c.conversation).put("title", conversationTitle(c.conversation, nodeNames, channelNames))
                .put("net", c.protocol).put("unread", c.unread).put("msgs", msgs))
        }
        val loc = app.radios.myLocation.value
        return JSONObject()
            .put("radios", JSONArray(radios.keys.map { it.name }))
            .put("grid", loc?.let { Geo.maidenhead(it.first, it.second, 6) } ?: "")
            .put("convs", convs)
            .put("t", System.currentTimeMillis())
            .toString()
    }

    /** Handles one request from the watch and returns a short line to show on it. */
    suspend fun handle(app: HighbridgeApp, path: String, body: String): String = when (path) {
        PATH_SEND -> {
            val o = JSONObject(body)
            val text = o.optString("text").trim().take(200)
            if (text.isEmpty()) "Nothing to send"
            else app.radios.sendText(o.getString("conv"), text).fold({ "Sent" }, { "Not sent: ${it.message}" })
        }
        PATH_CHECKIN -> app.radios.broadcastStatus(false).let { if (it.isEmpty()) "No radio connected" else "Checked in on ${it.joinToString()}" }
        PATH_SOS -> app.radios.broadcastStatus(true).let { if (it.isEmpty()) "No radio connected" else "SOS sent on ${it.joinToString()}" }
        PATH_READ -> { app.db.dao().markRead(JSONObject(body).getString("conv")); "" }
        else -> ""
    }
}
