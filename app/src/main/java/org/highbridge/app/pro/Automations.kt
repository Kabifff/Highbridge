// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime

/**
 * Automations: "when this happens, do that". Rules are stored as JSON in settings.
 * Triggers: a daily time, every N minutes, a keyword in a message, a node heard again after a quiet spell,
 * phone battery low, a geofence alert. Actions: send a message, send my position, check in, call a web address
 * (webhook, including IFTTT), or show a notification.
 * Each rule runs at most once a minute, and never on its own messages, so two automations can't ping-pong.
 */
object Automations {
    enum class Trigger(val label: String) {
        DAILY("Every day at a time"), EVERY("Every few minutes"), KEYWORD("A message contains a word"),
        NODE_BACK("A node is heard again after a quiet spell"), BATTERY("Phone battery gets low"), GEOFENCE("A geofence alert"),
    }
    enum class Action(val label: String) {
        SEND("Send a message"), POSITION("Send my position"), CHECKIN("Check in on every network"),
        WEBHOOK("Call a web address (webhook)"), IFTTT("Trigger IFTTT"), NOTIFY("Show a notification on this phone"),
    }

    data class Rule(
        val id: Long = System.currentTimeMillis(),
        val name: String,
        val enabled: Boolean = true,
        val trigger: Trigger,
        /** DAILY: "HH:mm"; EVERY: minutes; KEYWORD: the word; NODE_BACK: minutes away; BATTERY: percent; GEOFENCE: unused. */
        val triggerValue: String = "",
        /** Days for DAILY as a 7-character mask Monday..Sunday ("1111100" = weekdays); KEYWORD/NODE_BACK: conversation or node key, blank = any. */
        val triggerScope: String = "",
        val action: Action,
        /** SEND: conversation key; WEBHOOK: URL; IFTTT: "key|event". */
        val target: String = "",
        /** Message or notification text, with {from} {text} {network} {time} {battery} {grid} filled in. */
        val text: String = "",
        val lastRunMs: Long = 0,
    ) {
        fun toJson() = JSONObject().put("id", id).put("name", name).put("enabled", enabled).put("trigger", trigger.name)
            .put("triggerValue", triggerValue).put("triggerScope", triggerScope).put("action", action.name)
            .put("target", target).put("text", text).put("lastRunMs", lastRunMs)
        companion object {
            fun fromJson(o: JSONObject) = Rule(o.optLong("id"), o.optString("name"), o.optBoolean("enabled", true),
                Trigger.valueOf(o.getString("trigger")), o.optString("triggerValue"), o.optString("triggerScope"),
                Action.valueOf(o.getString("action")), o.optString("target"), o.optString("text"), o.optLong("lastRunMs"))
        }
    }

    fun load(json: String): List<Rule> = runCatching {
        val a = JSONArray(json); (0 until a.length()).mapNotNull { runCatching { Rule.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())
    fun save(rules: List<Rule>): String = JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()

    /** What happened, for filling in placeholders and webhooks. */
    data class Event(val from: String = "", val text: String = "", val network: String = "", val conversation: String = "", val nodeKey: String = "")

    fun fill(template: String, e: Event, battery: Int?, grid: String?, now: LocalDateTime = LocalDateTime.now()): String = template
        .replace("{from}", e.from).replace("{text}", e.text).replace("{network}", e.network)
        .replace("{time}", "%02d:%02d".format(now.hour, now.minute)).replace("{battery}", battery?.let { "$it%" } ?: "?")
        .replace("{grid}", grid ?: "?")

    /** Rules a message fires: KEYWORD rules whose word appears (whole word, any case) in a conversation they cover. */
    fun matchMessage(rules: List<Rule>, conversation: String, text: String, now: Long): List<Rule> = rules.filter { r ->
        r.enabled && r.trigger == Trigger.KEYWORD && r.triggerValue.isNotBlank() && now - r.lastRunMs >= 60_000 &&
            (r.triggerScope.isBlank() || r.triggerScope == conversation) &&
            Regex("(?i)(^|\\W)" + Regex.escape(r.triggerValue.trim()) + "($|\\W)").containsMatchIn(text)
    }

    /** Rules due at [now] for DAILY (within the minute, on a chosen day) and EVERY (interval passed). */
    fun dueOnSchedule(rules: List<Rule>, now: LocalDateTime, nowMs: Long): List<Rule> = rules.filter { r ->
        if (!r.enabled || nowMs - r.lastRunMs < 60_000) return@filter false
        when (r.trigger) {
            Trigger.DAILY -> {
                val hm = r.triggerValue.split(':'); val h = hm.getOrNull(0)?.toIntOrNull(); val m = hm.getOrNull(1)?.toIntOrNull()
                val day = now.dayOfWeek.value - 1   // Monday = 0
                h == now.hour && m == now.minute && (r.triggerScope.length != 7 || r.triggerScope[day] == '1')
            }
            Trigger.EVERY -> {
                val mins = (r.triggerValue.toIntOrNull() ?: 0).coerceAtLeast(10)   // no more often than every 10 minutes
                nowMs - r.lastRunMs >= mins * 60_000L
            }
            else -> false
        }
    }

    /** IFTTT's Webhooks service URL for a key and event name. */
    fun iftttUrl(keyAndEvent: String): String {
        val key = keyAndEvent.substringBefore('|').trim(); val event = keyAndEvent.substringAfter('|', "").trim()
        require(key.isNotEmpty() && event.isNotEmpty()) { "Enter your IFTTT Webhooks key and an event name" }
        return "https://maker.ifttt.com/trigger/${URLEncoder.encode(event, "UTF-8")}/with/key/${URLEncoder.encode(key, "UTF-8")}"
    }

    /** POSTs JSON to [url]. For IFTTT the body is value1..3 (from, text, network). */
    fun postWebhook(url: String, rule: Rule, e: Event, ifttt: Boolean): Result<Int> = runCatching {
        require(url.startsWith("https://") || url.startsWith("http://")) { "The address must start with https://" }
        val body = if (ifttt) JSONObject().put("value1", e.from).put("value2", e.text).put("value3", e.network)
            else JSONObject().put("app", "Highbridge").put("rule", rule.name).put("trigger", rule.trigger.name.lowercase())
                .put("from", e.from).put("text", e.text).put("network", e.network).put("conversation", e.conversation).put("time", System.currentTimeMillis())
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("User-Agent", "Highbridge (Android mesh radio app)")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        check(code in 200..299) { "The server answered $code" }
        code
    }
}
