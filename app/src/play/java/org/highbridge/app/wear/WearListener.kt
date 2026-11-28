// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives replies, check-ins and SOS from the user's watch. Only apps signed with the same key can reach it. */
class WearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith("/highbridge/")) return
        WearLink.onRequest(event.sourceNodeId, event.path, String(event.data))
    }
}
