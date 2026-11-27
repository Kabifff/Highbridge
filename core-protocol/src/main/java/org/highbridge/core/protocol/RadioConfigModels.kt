// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.protocol

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

enum class MeshtasticChannelRole { PRIMARY, SECONDARY, DISABLED }

data class MeshtasticLoRaConfig(
    /** Never default to a real region: the user must confirm one (see MeshtasticRegion.suggestFor). */
    val region: MeshtasticRegion = MeshtasticRegion.UNSET,
    val preset: MeshtasticModemPreset = MeshtasticModemPreset.LONG_FAST,
    val hopLimit: Int = 3,
    /** 0 = let the firmware use the legal maximum for the region. Was 30, which is over the limit in most of the world. */
    val txPowerDbm: Int = 0,
    /** 0 = derive the slot from the primary channel name, like the official apps. */
    val frequencySlot: Int = 0,
) {
    init {
        require(hopLimit in 0..7) { "Meshtastic hop limit must be 0–7" }
        require(txPowerDbm >= 0) { "TX power can't be negative" }
    }

    /** Power actually requested from the radio, never above the region's cap. */
    val effectiveTxPowerDbm: Int get() = if (txPowerDbm == 0) 0 else minOf(txPowerDbm, region.powerLimitDbm)
}

/** One-tap Saint Paul quick-join settings (MSPMesh / West7th use Medium Fast). */
object StPaulQuickJoin {
    val meshtastic = MeshtasticLoRaConfig(region = MeshtasticRegion.US, preset = MeshtasticModemPreset.MEDIUM_FAST, hopLimit = 3)
    val meshCore: MeshCoreRadioPreset = MeshCorePresets.ALL.first { it.name == "USA / Canada" }
}

data class MeshCoreRadioConfig(
    val advertName: String = "Highbridge-MC",
    val preset: MeshCoreRadioPreset? = null,
    /** MeshCore radios top out at 22 dBm (SX1262); the regional limit may be lower. */
    val txPowerDbm: Int = 22,
) {
    init {
        require(advertName.toByteArray(StandardCharsets.UTF_8).size in 1..31) { "Name must be 1–31 bytes" }
        require(txPowerDbm in 1..22) { "MeshCore TX power must be 1–22 dBm" }
    }

    /** Power to send, capped by the band's legal limit when a preset is chosen. */
    val effectiveTxPowerDbm: Int get() = preset?.let { minOf(txPowerDbm, it.band.powerLimitDbm) } ?: txPowerDbm
}

data class MeshCoreRoomServerConfig(
    val roomName: String = "Local Room",
    val serverPubKeyHex: String = "",
    /** Keep in encrypted storage (EncryptedSharedPreferences / Keystore), never in plain Room tables. */
    val password: String = "",
    val autoSyncMinutes: Int = 5,
)

enum class LocationUpdateMode(val intervalSeconds: Int, val title: String, val subtitle: String, val isRecommended: Boolean = false) {
    FAST(35, "Fast Update", "Every 35 seconds", isRecommended = true),
    BATTERY_SAVING(300, "Battery Saving", "Every 5 minutes");
    val intervalMs: Long get() = intervalSeconds * 1000L
}

/** Values match BluetoothGatt.CONNECTION_PRIORITY_BALANCED / HIGH / LOW_POWER. */
enum class BlePriority(val gattValue: Int) { BALANCED(0), HIGH(1), LOW_POWER(2) }

enum class PowerProfile(
    val title: String, val summary: String, val locationMode: LocationUpdateMode,
    val minDistanceMeters: Float, val blePriority: BlePriority, val allowBridgeMedia: Boolean,
    val meshtasticPowerSaving: Boolean, val meshCoreTxPowerDbm: Int,
) {
    PERFORMANCE("Performance", "35s GPS, fast Bluetooth, full media bridge, max legal power", LocationUpdateMode.FAST, 0f, BlePriority.HIGH, true, false, 22),
    BALANCED("Balanced", "35s GPS (25 m filter), balanced Bluetooth, radio light-sleep", LocationUpdateMode.FAST, 25f, BlePriority.BALANCED, true, true, 20),
    ULTRA_LOW_POWER("Ultra-Low Power", "5 min GPS (50 m filter), low-power Bluetooth, text-only bridge, 17 dBm", LocationUpdateMode.BATTERY_SAVING, 50f, BlePriority.LOW_POWER, false, true, 17),
}

data class EncryptedChannel(
    val id: String = UUID.randomUUID().toString(),
    val protocol: ProtocolType,
    val slotIndex: Int,
    val name: String,
    val keyBase64Url: String,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun rawKeyBytes(): ByteArray = B64_DECODER.decode(keyBase64Url)

    /** highbridge://channel?p=..&i=..&n=..&k=..  (pure Kotlin, so it works in unit tests too). */
    fun toShareUri(): String = "highbridge://channel?p=${enc(protocol.name)}&i=$slotIndex&n=${enc(name)}&k=${enc(keyBase64Url)}"

    companion object {
        private val RNG = SecureRandom()
        private val B64_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val B64_DECODER = Base64.getUrlDecoder()

        fun keyLengthBytes(protocol: ProtocolType): Int = when (protocol) {
            ProtocolType.MESHTASTIC -> 32 // AES-256 PSK (protobuf max 32 bytes)
            ProtocolType.MESHCORE -> 16   // firmware CMD_SET_CHANNEL supports 128-bit secrets only
            ProtocolType.RETICULUM -> 64  // RNS GROUP destinations use a 64-byte (AES-256) key
        }

        /** Highest slot each protocol accepts. */
        fun maxSlot(protocol: ProtocolType): Int = when (protocol) {
            ProtocolType.MESHTASTIC -> 7   // 8 channels
            ProtocolType.MESHCORE -> 255   // channel_idx is a byte; firmware rejects unknown slots
            ProtocolType.RETICULUM -> Int.MAX_VALUE
        }

        /** Longest channel name in bytes (Meshtastic: 12-byte field incl. terminator; MeshCore: 32). */
        fun maxNameBytes(protocol: ProtocolType): Int = when (protocol) {
            ProtocolType.MESHTASTIC -> 11
            ProtocolType.MESHCORE -> 31
            ProtocolType.RETICULUM -> 64
        }

        /** Slot index meaning "the next free slot on the radio", filled in when the channel is loaded. */
        const val AUTO_SLOT = -1

        fun validate(protocol: ProtocolType, slotIndex: Int, name: String): String? = when {
            slotIndex != AUTO_SLOT && (slotIndex < 0 || slotIndex > maxSlot(protocol)) -> "Slot must be 0–${maxSlot(protocol)}"
            name.isBlank() -> "Name can't be empty"
            name.toByteArray(StandardCharsets.UTF_8).size > maxNameBytes(protocol) -> "Name is too long (max ${maxNameBytes(protocol)} bytes)"
            else -> null
        }

        fun createRandom(protocol: ProtocolType, slotIndex: Int, name: String): EncryptedChannel {
            val clean = name.trim()
            validate(protocol, slotIndex, clean)?.let { throw IllegalArgumentException(it) }
            val bytes = ByteArray(keyLengthBytes(protocol)).also { RNG.nextBytes(it) }
            return EncryptedChannel(protocol = protocol, slotIndex = slotIndex, name = clean, keyBase64Url = B64_ENCODER.encodeToString(bytes))
        }

        /**
         * MeshCore hashtag channel: anyone who knows the name can join. The key is the first 16 bytes
         * of SHA-256 of the name including "#", as the MeshCore app does. Names are kept lowercase so
         * "#StPaul" and "#stpaul" land in the same channel.
         */
        fun normalizeHashtag(name: String): String = "#" + name.trim().trimStart('#').lowercase().replace(Regex("\\s+"), "-")

        fun meshCoreHashtag(name: String, slotIndex: Int = AUTO_SLOT): EncryptedChannel {
            val n = normalizeHashtag(name)
            require(n.length > 1) { "Type a channel name" }
            validate(ProtocolType.MESHCORE, slotIndex, n)?.let { throw IllegalArgumentException(it) }
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(n.toByteArray(StandardCharsets.UTF_8)).copyOf(16)
            return EncryptedChannel(protocol = ProtocolType.MESHCORE, slotIndex = slotIndex, name = n, keyBase64Url = B64_ENCODER.encodeToString(key))
        }

        fun isHashtag(c: EncryptedChannel): Boolean = c.protocol == ProtocolType.MESHCORE && c.name.startsWith("#") &&
            runCatching { meshCoreHashtag(c.name).keyBase64Url == c.keyBase64Url }.getOrDefault(false)

        /** MeshCore app share link: meshcore://channel/add?name=<name>&secret=<32 hex>. */
        fun toMeshCoreUri(c: EncryptedChannel): String =
            "meshcore://channel/add?name=${enc(c.name).replace("+", "%20")}&secret=" + c.rawKeyBytes().joinToString("") { "%02x".format(it) }

        private fun fromMeshCoreUri(s: String): EncryptedChannel? {
            val q = s.substringAfter('?', "").split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
            val name = q["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val hex = q["secret"]?.trim()?.takeIf { it.length == 32 && it.all { ch -> ch.isDigit() || ch.lowercaseChar() in 'a'..'f' } } ?: return null
            if (validate(ProtocolType.MESHCORE, AUTO_SLOT, name) != null) return null
            val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            return EncryptedChannel(protocol = ProtocolType.MESHCORE, slotIndex = AUTO_SLOT, name = name, keyBase64Url = B64_ENCODER.encodeToString(key))
        }

        fun fromShareUri(uriString: String): EncryptedChannel? = runCatching {
            val s = uriString.trim()
            if (s.startsWith("meshcore://", ignoreCase = true)) return fromMeshCoreUri(s)
            val prefix = "highbridge://channel?"
            if (!s.startsWith(prefix, ignoreCase = true)) return null
            val q = s.substring(prefix.length).split('&').mapNotNull {
                val kv = it.split('=', limit = 2)
                if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], "UTF-8") else null
            }.toMap()
            val proto = ProtocolType.valueOf(q["p"] ?: return null)
            val idx = q["i"]?.toIntOrNull() ?: return null
            val name = q["n"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val key = q["k"]?.takeIf { it.isNotBlank() } ?: return null
            if (validate(proto, idx, name) != null) return null
            if (B64_DECODER.decode(key).size != keyLengthBytes(proto)) return null
            EncryptedChannel(protocol = proto, slotIndex = idx, name = name, keyBase64Url = key)
        }.getOrNull()

        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    }
}
