// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.highbridge.app.data.ChannelEntity
import org.highbridge.app.data.MessageEntity
import org.highbridge.app.data.NodeEntity
import org.highbridge.app.ui.theme.StPaulMeshTheme
import org.highbridge.core.protocol.ConnectionState
import org.highbridge.core.protocol.MeshChannel
import org.highbridge.core.protocol.MeshNode
import org.highbridge.core.protocol.MeshPacket
import org.highbridge.core.protocol.MeshRadio
import org.highbridge.core.protocol.MeshtasticRegion
import org.highbridge.core.protocol.ProtocolType
import org.highbridge.core.protocol.RadioEvent
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders real app screens with sample St. Paul data for the store listings (1080 × 1920).
 * Skipped unless run with -Phb.shots; output goes to fastlane/metadata/android/en-US/images.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
class StoreScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeRadio(override val protocol: ProtocolType, device: String, me: String, chans: List<String>) : MeshRadio {
        override val maxPayloadBytes = 200
        override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected(device, me))
        override val discoveredNodes = MutableStateFlow<List<MeshNode>>(emptyList())
        override val channels = MutableStateFlow(chans.mapIndexed { i, n -> MeshChannel(i, n, protocol, isEncrypted = i > 0) })
        override val incomingPackets = MutableSharedFlow<MeshPacket>()
        override val events = MutableSharedFlow<RadioEvent>()
        override val myNodeId = MutableStateFlow<String?>(me)
        override val deviceInfo = MutableStateFlow<String?>(device)
        override suspend fun connect(address: String) = Result.success(Unit)
        override suspend fun disconnect() {}
        override suspend fun sendText(channelIndex: Int, text: String, toNodeId: String?) = Result.success("x")
        override suspend fun sendBinary(channelIndex: Int, portId: Int, data: ByteArray, toNodeId: String?) = Result.success("x")
    }

    private fun seed() = runBlocking {
        val app = HighbridgeApp.instance
        app.prefs.setOnboarded(true)
        app.prefs.setRegion(MeshtasticRegion.US)
        app.prefs.setDisplayName("Fort Road")
        val dao = app.db.dao()
        val now = System.currentTimeMillis()
        val min = 60_000L
        // Fictional nodes around West 7th and the river.
        listOf(
            NodeEntity("MESHTASTIC:!a1b2c3d4", "MESHTASTIC", "!a1b2c3d4", "IRVP", "Irvine Park", 44.9405, -93.1065, 88, 4.02f, 7.5f, -96, 1, now - 2 * min),
            NodeEntity("MESHTASTIC:!b2c3d4e5", "MESHTASTIC", "!b2c3d4e5", "HIBR", "High Bridge rooftop repeater", 44.9330, -93.1150, 100, 4.18f, 9.0f, -88, 0, now - 1 * min),
            NodeEntity("MESHTASTIC:!c3d4e5f6", "MESHTASTIC", "!c3d4e5f6", "HARI", "Harriet Island", 44.9366, -93.0960, 61, 3.81f, 2.25f, -109, 2, now - 9 * min),
            NodeEntity("MESHTASTIC:!d4e5f6a7", "MESHTASTIC", "!d4e5f6a7", "RAND", "Randolph & 7th", 44.9268, -93.1300, 47, 3.72f, -3.5f, -117, 3, now - 26 * min),
            NodeEntity("MESHCORE:a1f00d1e5eed", "MESHCORE", "a1f00d1e5eed", "Cath", "Cathedral Hill", 44.9460, -93.1080, null, 3.95f, 6.0f, null, 1, now - 4 * min),
            NodeEntity("MESHCORE:b0b5c0ffee11", "MESHCORE", "b0b5c0ffee11", "Bluf", "Bluff (repeater)", 44.9350, -93.1010, null, 4.10f, 8.25f, null, 0, now - 3 * min),
            NodeEntity("MESHCORE:c0ffee5a1a7e", "MESHCORE", "c0ffee5a1a7e", "Cand", "Candy shop corner", 44.9301, -93.1192, null, null, 1.5f, null, 2, now - 14 * min),
        ).forEach { dao.upsertNode(it) }

        fun msg(conv: String, proto: String, from: String, name: String, text: String, ago: Long, out: Boolean = false, bridged: Boolean = false, status: Int = 4) =
            MessageEntity(packetId = "s-${conv.hashCode()}-$ago", protocol = proto, conversation = conv, fromId = from, fromName = name, text = text,
                timeMs = now - ago * min, outgoing = out, status = status, viaBridge = bridged, read = true)
        val lf = "MESHTASTIC:ch:0"
        listOf(
            msg(lf, "MESHTASTIC", "!a1b2c3d4", "IRVP", "Evening! Net check at 8 tonight, all bands welcome.", 42),
            msg(lf, "MESHTASTIC", "!c3d4e5f6", "HARI", "Hearing you 2 hops out on the island. River fog is thick.", 38),
            msg(lf, "MESHTASTIC", "me", "You", "Check-in: OK on Fort Road. Bridge to MeshCore is up.", 35, out = true, status = 2),
            msg(lf, "MESHTASTIC", "!b2c3d4e5", "Cath", "[MC:Cath] Copy from Cathedral Hill, coming through the bridge.", 31, bridged = true),
            msg(lf, "MESHTASTIC", "!d4e5f6a7", "RAND", "New solar node on Randolph is up. 3.7 V and holding.", 12),
            msg(lf, "MESHTASTIC", "me", "You", "Nice. SNR from here is -3.5, try raising the antenna?", 9, out = true, status = 2),
            msg(lf, "MESHTASTIC", "!a1b2c3d4", "IRVP", "Photo of the High Bridge at sunset coming in a minute.", 2),
            msg("MESHCORE:ch:0", "MESHCORE", "name:Cath", "Cathedral Hill", "Public channel is quiet tonight. Bluff repeater looks healthy.", 20),
            msg("MESHCORE:ch:0", "MESHCORE", "me", "You", "Thanks! Bridging LongFast here from West 7th.", 18, out = true, status = 1),
            msg("MESHTASTIC:dm:!a1b2c3d4", "MESHTASTIC", "!a1b2c3d4", "IRVP", "Want to meet at the overlook Saturday to test range?", 55),
            msg("MESHTASTIC:dm:!a1b2c3d4", "MESHTASTIC", "me", "You", "Yes, 10 am. I'll bring the Heltec on a mast.", 50, out = true, status = 2),
        ).forEach { dao.insertMessage(it) }
        dao.upsertChannel(ChannelEntity("mt-river", "MESHTASTIC", 1, "RiverOps", "AQ", now))

        val radios = app.radios
        val f = radios.javaClass.getDeclaredField("_radios").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(radios) as MutableStateFlow<Map<ProtocolType, MeshRadio>>).value = mapOf(
            ProtocolType.MESHTASTIC to FakeRadio(ProtocolType.MESHTASTIC, "Meshtastic 2.7 · HELTEC_V4", "!f0e1d2c3", listOf("LongFast", "RiverOps")),
            ProtocolType.MESHCORE to FakeRadio(ProtocolType.MESHCORE, "MeshCore 1.9 · Heltec V3", "e5f0a1b2c3d4", listOf("Public")),
        )
    }

    private fun shoot(name: String, route: String?, dark: Boolean = false) {
        assumeTrue("Run with -Phb.shots", System.getProperty("hb.shots") == "on")
        seed()
        compose.setContent { StPaulMeshTheme(darkTheme = dark) { HighbridgeRoot(route) {} } }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "../fastlane/metadata/android/en-US/images/phoneScreenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun s1_chats() = shoot("1_chats", null)
    @Test fun s2_conversation() = shoot("2_conversation", "chat/" + android.net.Uri.encode("MESHTASTIC:ch:0"))
    @Test fun s3_nodes() = shoot("3_nodes", "nodes")
    @Test fun s4_radio() = shoot("4_radio", "radio")
    @Test fun s5_bridge() = shoot("5_bridge", "bridge")
    @Test fun s6_tools() = shoot("6_tools", "tools")
    @Test fun s7_night() = shoot("7_night", "chat/" + android.net.Uri.encode("MESHCORE:ch:0"), dark = true)
}

/** The 512 px store icon and the 1024 × 500 feature graphic, drawn from the app's own artwork. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1024dp-h500dp-land-mdpi")
class StoreGraphics {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File(System.getProperty("user.dir"), "../fastlane/metadata/android/en-US/images").apply { mkdirs() }

    @Test fun icon() {
        assumeTrue(System.getProperty("hb.shots") == "on")
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        // Full-bleed square: Play applies its own rounded mask. Adaptive layers are 108 dp with a 72 dp safe zone.
        listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground).forEach { id ->
            ctx.getDrawable(id)!!.apply { setBounds(-512 * 18 / 72, -512 * 18 / 72, 512 + 512 * 18 / 72, 512 + 512 * 18 / 72); draw(c) }
        }
        File(out, "icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun featureGraphic() {
        assumeTrue(System.getProperty("hb.shots") == "on")
        compose.setContent {
            StPaulMeshTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                    org.highbridge.app.ui.screens.HeroBridge(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd).width(450.dp).height(420.dp))
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(start = 56.dp)) {
                        androidx.compose.material3.Text("WEST 7TH · SAINT PAUL", color = org.highbridge.app.ui.theme.StPaulMeshPalette.SchmidtBrick,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp)
                        androidx.compose.material3.Text("Highbridge", fontSize = 76.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            fontFamily = org.highbridge.app.ui.theme.DisplayFont, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        androidx.compose.material3.Text("One bridge for every mesh.", fontSize = 30.sp, fontFamily = org.highbridge.app.ui.theme.DisplayFont,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView.findViewById<android.view.View>(android.R.id.content)
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val sized = Bitmap.createScaledBitmap(Bitmap.createBitmap(bmp, 0, 0, minOf(bmp.width, 1024), minOf(bmp.height, 500)), 1024, 500, true)
        File(out, "featureGraphic.png").outputStream().use { sized.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
