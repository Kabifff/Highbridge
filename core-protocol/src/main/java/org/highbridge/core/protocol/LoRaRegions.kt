package org.highbridge.core.protocol

/*
 * Worldwide LoRa region support.
 *
 * Meshtastic: every region the Meshtastic firmware supports, with the exact protobuf
 * RegionCode numbers (meshtastic/protobufs config.proto) and the band, duty-cycle and
 * power limits from the firmware's region table (firmware src/mesh/RadioInterface.cpp).
 * Checked November 2026.
 *
 * MeshCore: the regional presets offered by the official MeshCore app (Oct 2026).
 * MeshCore has no fixed region list in firmware; the app is the source of truth, so
 * these are editable presets, not hard limits.
 *
 * The app must never pick a region silently: suggest one from the phone's country,
 * then make the user confirm. Transmitting on the wrong band is illegal in most countries.
 */

/** One Meshtastic region. [code] is the protobuf RegionCode value sent to the radio. */
enum class MeshtasticRegion(
    val code: Int,
    val label: String,
    val startMHz: Double,
    val endMHz: Double,
    /** Max share of an hour the radio may transmit, in percent (100 = no limit). */
    val dutyCyclePercent: Int,
    /** Firmware power cap for the region, in dBm. */
    val powerLimitDbm: Int,
    /** ISO 3166-1 alpha-2 countries this region is usually used in (suggestion only). */
    val countries: Set<String> = emptySet(),
) {
    UNSET(0, "Not set", 902.0, 928.0, 100, 30),
    US(1, "United States / Canada / Mexico", 902.0, 928.0, 100, 30, setOf("US", "CA", "MX", "PR", "GU", "VI", "AS", "MP")),
    EU_433(2, "Europe 433 MHz", 433.0, 434.0, 10, 10),
    EU_868(3, "Europe / UK 868 MHz", 869.4, 869.65, 10, 27, setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT",
        "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB", "NO", "CH", "IS", "LI", "RS", "BA",
        "ME", "MK", "AL", "MD", "TR", "ZA")),
    CN(4, "China", 470.0, 510.0, 100, 19, setOf("CN")),
    JP(5, "Japan", 920.5, 923.5, 100, 13, setOf("JP")),
    ANZ(6, "Australia / New Zealand / Brazil", 915.0, 928.0, 100, 30, setOf("AU", "NZ", "CL", "AR", "CO", "PE", "UY", "EC", "CR")),
    KR(7, "South Korea", 920.0, 923.0, 100, 23, setOf("KR")),
    TW(8, "Taiwan", 920.0, 925.0, 100, 27, setOf("TW")),
    RU(9, "Russia", 868.7, 869.2, 100, 20, setOf("RU", "BY")),
    IN(10, "India", 865.0, 867.0, 100, 30, setOf("IN")),
    NZ_865(11, "New Zealand 865 MHz", 864.0, 868.0, 100, 36),
    TH(12, "Thailand", 920.0, 925.0, 10, 27, setOf("TH")),
    LORA_24(13, "2.4 GHz (worldwide)", 2400.0, 2483.5, 100, 10),
    UA_433(14, "Ukraine 433 MHz", 433.0, 434.7, 10, 10),
    UA_868(15, "Ukraine 868 MHz", 868.0, 868.6, 1, 14, setOf("UA")),
    MY_433(16, "Malaysia 433 MHz", 433.0, 435.0, 100, 20),
    MY_919(17, "Malaysia 919 MHz", 919.0, 924.0, 100, 27, setOf("MY")),
    SG_923(18, "Singapore", 917.0, 925.0, 100, 20, setOf("SG")),
    PH_433(19, "Philippines 433 MHz", 433.0, 434.7, 100, 10),
    PH_868(20, "Philippines 868 MHz", 868.0, 869.4, 100, 14),
    PH_915(21, "Philippines 915 MHz", 915.0, 918.0, 100, 24, setOf("PH")),
    ANZ_433(22, "Australia / New Zealand 433 MHz", 433.05, 434.79, 100, 14),
    KZ_433(23, "Kazakhstan 433 MHz", 433.075, 434.775, 100, 10),
    KZ_863(24, "Kazakhstan 863 MHz", 863.0, 868.0, 100, 30, setOf("KZ")),
    NP_865(25, "Nepal", 865.0, 868.0, 100, 30, setOf("NP")),
    BR_902(26, "Brazil 902 MHz", 902.0, 907.5, 100, 30, setOf("BR"));

    val bandLabel: String
        get() = if (startMHz >= 1000) "%.1f–%.1f GHz".format(startMHz / 1000, endMHz / 1000)
                else "%s–%s MHz".format(trim(startMHz), trim(endMHz))

    val hasDutyCycleLimit: Boolean get() = dutyCyclePercent < 100

    fun contains(freqMHz: Double): Boolean = freqMHz in startMHz..endMHz

    companion object {
        /**
         * Maps a code reported by the radio. Newer firmware may report region codes this app
         * does not know yet (the protobufs already define 27+); return null instead of crashing.
         */
        fun fromCode(code: Int): MeshtasticRegion? = entries.firstOrNull { it.code == code }

        /** Suggest a region for an ISO country code (from SIM, network or locale). User must confirm. */
        fun suggestFor(countryIso: String?): MeshtasticRegion? {
            val c = countryIso?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { c in it.countries }
        }

        private fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
    }
}

/** Meshtastic modem presets, with protobuf ModemPreset values. */
enum class MeshtasticModemPreset(val code: Int, val label: String) {
    LONG_FAST(0, "Long Fast"),
    LONG_SLOW(1, "Long Slow"),
    VERY_LONG_SLOW(2, "Very Long Slow"),
    MEDIUM_SLOW(3, "Medium Slow"),
    MEDIUM_FAST(4, "Medium Fast"),
    SHORT_SLOW(5, "Short Slow"),
    SHORT_FAST(6, "Short Fast"),
    LONG_MODERATE(7, "Long Moderate"),
    SHORT_TURBO(8, "Short Turbo"),
    LONG_TURBO(9, "Long Turbo");

    companion object {
        fun fromCode(code: Int): MeshtasticModemPreset? = entries.firstOrNull { it.code == code }
    }
}

/** A MeshCore radio preset: everything CMD_SET_RADIO_PARAMS needs. */
data class MeshCoreRadioPreset(
    val name: String,
    val frequencyKhz: Int,
    val bandwidthKhz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
    /** Meshtastic region whose band this frequency must sit inside (used for legality checks). */
    val band: MeshtasticRegion,
    val countries: Set<String> = emptySet(),
) {
    val frequencyMHz: Double get() = frequencyKhz / 1000.0
    val label: String get() = "%s — %.3f MHz, BW %s, SF%d, CR%d".format(
        name, frequencyMHz, if (bandwidthKhz % 1.0 == 0.0) bandwidthKhz.toInt().toString() else bandwidthKhz.toString(),
        spreadingFactor, codingRate)
}

object MeshCorePresets {
    val ALL: List<MeshCoreRadioPreset> = listOf(
        MeshCoreRadioPreset("USA / Canada", 910_525, 62.5, 7, 5, MeshtasticRegion.US, setOf("US", "CA", "MX")),
        MeshCoreRadioPreset("EU / UK (Narrow)", 869_618, 62.5, 8, 8, MeshtasticRegion.EU_868,
            MeshtasticRegion.EU_868.countries - setOf("CZ", "HU", "NL", "PT", "SK", "CH")),
        MeshCoreRadioPreset("Switzerland", 869_618, 62.5, 8, 8, MeshtasticRegion.EU_868, setOf("CH")),
        MeshCoreRadioPreset("Czech Republic (Narrow)", 869_432, 62.5, 7, 5, MeshtasticRegion.EU_868, setOf("CZ")),
        MeshCoreRadioPreset("Hungary", 869_618, 62.5, 7, 5, MeshtasticRegion.EU_868, setOf("HU")),
        MeshCoreRadioPreset("Netherlands", 869_618, 62.5, 7, 5, MeshtasticRegion.EU_868, setOf("NL")),
        MeshCoreRadioPreset("Netherlands (Limburg)", 869_618, 62.5, 8, 8, MeshtasticRegion.EU_868),
        MeshCoreRadioPreset("Portugal 868", 869_618, 62.5, 7, 6, MeshtasticRegion.EU_868, setOf("PT")),
        MeshCoreRadioPreset("Slovakia", 869_618, 62.5, 7, 5, MeshtasticRegion.EU_868, setOf("SK")),
        MeshCoreRadioPreset("Australia", 915_800, 250.0, 10, 5, MeshtasticRegion.ANZ),
        MeshCoreRadioPreset("Australia (Narrow)", 916_575, 62.5, 7, 7, MeshtasticRegion.ANZ, setOf("AU")),
        MeshCoreRadioPreset("New Zealand (Narrow)", 917_375, 62.5, 7, 5, MeshtasticRegion.ANZ, setOf("NZ")),
        MeshCoreRadioPreset("Vietnam (Narrow)", 920_250, 62.5, 8, 5, MeshtasticRegion.TW, setOf("VN")),
    )

    fun suggestFor(countryIso: String?): MeshCoreRadioPreset? {
        val c = countryIso?.trim()?.uppercase() ?: return null
        return ALL.firstOrNull { c in it.countries }
    }

    /**
     * Range checks matching the MeshCore firmware (CMD_SET_RADIO_PARAMS rejects anything outside
     * 150–2500 MHz, BW 7–500 kHz, SF 5–12, CR 5–8) plus a legality check against the chosen band.
     */
    fun validate(p: MeshCoreRadioPreset): List<String> = buildList {
        if (p.frequencyKhz !in 150_000..2_500_000) add("Frequency must be 150–2500 MHz.")
        if (p.bandwidthKhz < 7.0 || p.bandwidthKhz > 500.0) add("Bandwidth must be 7–500 kHz.")
        if (p.spreadingFactor !in 5..12) add("Spreading factor must be 5–12.")
        if (p.codingRate !in 5..8) add("Coding rate must be 5–8.")
        val halfBwMHz = p.bandwidthKhz / 2000.0
        if (p.frequencyMHz - halfBwMHz < p.band.startMHz || p.frequencyMHz + halfBwMHz > p.band.endMHz)
            add("This signal falls outside the allowed band for this region (${p.band.bandLabel}).")
    }
}
