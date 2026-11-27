// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.aprs

/**
 * Mesh ⇄ APRS messaging gateway, in the style of aprstastic. Licensed amateurs on Meshtastic or MeshCore
 * register their own call sign by direct-messaging this node "!register CALL-SSID". After that:
 *  - "CALL: text" (or "@CALL text") to this node goes out as an APRS message from their call sign;
 *  - APRS messages to their call sign come back to them as a direct message.
 * Nothing from unregistered users ever reaches APRS, so only licensed operators' own traffic goes on the air.
 */
class AprsGateway(
    /** Lines of "meshKey=CALL", where meshKey is "PROTOCOL:nodeId". */
    private val load: () -> String,
    private val save: (String) -> Unit,
) {
    sealed interface Action {
        data class Reply(val text: String) : Action
        data class SendAprs(val fromCall: String, val toCall: String, val text: String) : Action
        data object None : Action
    }

    fun registrations(): Map<String, String> = load().lineSequence().mapNotNull { l ->
        val i = l.indexOf('='); if (i > 0) l.substring(0, i) to l.substring(i + 1) else null
    }.toMap()

    fun callFor(meshKey: String): String? = registrations()[meshKey]
    fun meshKeyFor(call: String): String? = registrations().entries.firstOrNull { it.value.equals(call, true) }?.key
    fun calls(): Set<String> = registrations().values.toSet()

    private fun put(meshKey: String, call: String?) {
        val m = registrations().toMutableMap()
        if (call == null) m.remove(meshKey) else { m.entries.removeAll { it.value.equals(call, true) }; m[meshKey] = call }
        save(m.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private val lastSend = HashMap<String, Long>()

    /** Handles a direct message to this node. [now] is for tests. */
    fun onMeshDirect(meshKey: String, text: String, now: Long = System.currentTimeMillis()): Action {
        val t = text.trim()
        val lower = t.lowercase()
        when {
            lower.startsWith("!register") -> {
                val call = t.substringAfter(' ', "").trim().uppercase()
                if (!AprsFormat.isCallsign(call)) return Action.Reply("Usage: !register YOURCALL-SSID (licensed amateurs only, your own call sign)")
                put(meshKey, call)
                return Action.Reply("Registered $call. Send \"CALL: message\" to reach an APRS station; replies come back here. !unregister to stop.")
            }
            lower == "!unregister" -> { put(meshKey, null); return Action.Reply("Unregistered. Nothing from you goes to APRS now.") }
            lower == "!help" || lower == "!aprs" -> return Action.Reply(
                "APRS gateway: !register YOURCALL-SSID, then \"CALL: message\". " + (callFor(meshKey)?.let { "You're registered as $it." } ?: "You're not registered."))
        }
        // "CALL: text" or "@CALL text" only, so ordinary chat never gets mistaken for an APRS message.
        val m = Regex("^(?:@([A-Za-z0-9]{3,7}(?:-\\d{1,2})?)\\s+|([A-Za-z0-9]{3,7}(?:-\\d{1,2})?):\\s*)(.+)$", RegexOption.DOT_MATCHES_ALL).find(t) ?: return Action.None
        val to = (m.groupValues[1].ifEmpty { m.groupValues[2] }).uppercase()
        if (!AprsFormat.isCallsign(to)) return Action.None
        val from = callFor(meshKey) ?: return Action.Reply("Register first: !register YOURCALL-SSID (licensed amateurs only)")
        if (now - (lastSend[meshKey] ?: 0) < 20_000) return Action.Reply("Please wait a few seconds between APRS messages.")
        lastSend[meshKey] = now
        return Action.SendAprs(from, to, m.groupValues[3].trim())
    }
}
