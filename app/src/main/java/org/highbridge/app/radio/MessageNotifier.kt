// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.radio

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.highbridge.app.HighbridgeApp
import org.highbridge.app.MainActivity
import org.highbridge.app.R

/**
 * Message notifications in Android's messaging style, with Reply and Mark as read actions.
 * The same style is what Android Auto reads aloud and lets you answer by voice.
 */
object MessageNotifier {
    const val KEY_REPLY = "reply_text"
    const val ACTION_REPLY = "org.highbridge.REPLY"
    const val ACTION_READ = "org.highbridge.MARK_READ"

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, conv: String, lines: List<Pair<String, String>>) {
        val id = conv.hashCode()
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(if (conv.contains(":ch:")) titleOf(conv) else null)
            .setGroupConversation(conv.contains(":ch:"))
        val now = System.currentTimeMillis()
        lines.forEachIndexed { i, (from, text) -> style.addMessage(text, now - (lines.size - i) * 1000L, Person.Builder().setName(from).build()) }
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP; putExtra("conversation", conv)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val replyPi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_REPLY).putExtra("conversation", conv),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val readPi = PendingIntent.getBroadcast(ctx, id + 1, Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_READ).putExtra("conversation", conv),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_stat_highbridge, "Reply", replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).setAllowGeneratedReplies(true).build()
        val read = NotificationCompat.Action.Builder(R.drawable.ic_stat_highbridge, "Mark as read", readPi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).setShowsUserInterface(false).build()
        val n = NotificationCompat.Builder(ctx, RadioManager.CH_MESSAGES).setSmallIcon(R.drawable.ic_stat_highbridge)
            .setStyle(style).setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(reply).addInvisibleAction(read).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    private fun titleOf(conv: String) = when {
        conv.startsWith("MESHCORE") -> "MeshCore channel ${conv.substringAfterLast(':')}"
        conv.startsWith("RETICULUM") -> "Reticulum group ${conv.substringAfterLast(':')}"
        else -> "Meshtastic channel ${conv.substringAfterLast(':')}"
    }
}

/** Handles Reply (typed, voice, Android Auto or watch) and Mark as read from a message notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conv = intent.getStringExtra("conversation") ?: return
        val app = HighbridgeApp.instance
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    MessageNotifier.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(MessageNotifier.KEY_REPLY)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) app.radios.sendText(conv, text)
                        app.db.dao().markRead(conv)
                    }
                    MessageNotifier.ACTION_READ -> app.db.dao().markRead(conv)
                }
                app.radios.clearNotification(conv)
            } finally { pending.finish() }
        }
    }
}
