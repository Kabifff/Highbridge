// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.MainActivity
import org.highbridge.app.R

/**
 * Home-screen widget: which radios are connected, unread messages, a one-tap check-in, and SOS
 * (which opens the app's check-in screen so an SOS is never sent by a stray tap).
 */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val r = runCatching { HighbridgeApp.instance.radios.radios.value.keys.map { it.shortTag } }.getOrDefault(emptyList())
        render(context, r, last.second)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_CHECKIN) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sent = HighbridgeApp.instance.radios.broadcastStatus(sos = false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (sent.isEmpty()) "No radio connected; open Highbridge to connect" else "Checked in on ${sent.joinToString()}", Toast.LENGTH_SHORT).show()
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_CHECKIN = "org.highbridge.WIDGET_CHECKIN"
        @Volatile private var last: Pair<List<String>, Int> = emptyList<String>() to 0

        fun update(ctx: Context, radios: List<String>, unread: Int) {
            if (last == (radios to unread)) return
            last = radios to unread
            render(ctx, radios, unread)
        }

        private fun render(ctx: Context, radios: List<String>, unread: Int) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_status)
            val status = (if (radios.isEmpty()) "No radio connected" else radios.joinToString(" · ") + " connected") +
                if (unread > 0) " · $unread unread" else ""
            v.setTextViewText(R.id.widget_status, status)
            fun open(route: String?, code: Int) = PendingIntent.getActivity(ctx, code, Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP; route?.let { putExtra("route", it) }
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.widget_root, open(if (unread > 0) "chats" else null, 9101))
            v.setOnClickPendingIntent(R.id.widget_sos, open("checkin", 9102))
            v.setOnClickPendingIntent(R.id.widget_checkin, PendingIntent.getBroadcast(ctx, 9103,
                Intent(ctx, StatusWidget::class.java).setAction(ACTION_CHECKIN), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            manager.updateAppWidget(ids, v)
        }
    }
}
