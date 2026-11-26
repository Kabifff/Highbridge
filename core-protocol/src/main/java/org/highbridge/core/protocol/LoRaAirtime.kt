// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.core.protocol

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/** Semtech time-on-air formula, used for the airtime (duty-cycle) monitor. */
object LoRaAirtime {
    data class Params(val sf: Int, val bandwidthHz: Double, val codingRate: Int /* 5..8 = 4/5..4/8 */, val preambleSymbols: Int = 16, val explicitHeader: Boolean = true, val crc: Boolean = true)

    fun forPreset(p: MeshtasticModemPreset): Params = when (p) {
        MeshtasticModemPreset.LONG_FAST -> Params(11, 250_000.0, 5)
        MeshtasticModemPreset.LONG_SLOW -> Params(12, 125_000.0, 8)
        MeshtasticModemPreset.VERY_LONG_SLOW -> Params(12, 62_500.0, 8)
        MeshtasticModemPreset.MEDIUM_SLOW -> Params(10, 250_000.0, 5)
        MeshtasticModemPreset.MEDIUM_FAST -> Params(9, 250_000.0, 5)
        MeshtasticModemPreset.SHORT_SLOW -> Params(8, 250_000.0, 5)
        MeshtasticModemPreset.SHORT_FAST -> Params(7, 250_000.0, 5)
        MeshtasticModemPreset.LONG_MODERATE -> Params(11, 125_000.0, 8)
        MeshtasticModemPreset.SHORT_TURBO -> Params(7, 500_000.0, 5)
        else -> Params(11, 500_000.0, 8)
    }

    fun forMeshCore(p: MeshCoreRadioPreset) = Params(p.spreadingFactor, p.bandwidthKhz * 1000.0, p.codingRate, preambleSymbols = 8)

    /** Milliseconds on air for a payload of [payloadBytes]. */
    fun timeOnAirMs(payloadBytes: Int, p: Params): Double {
        val tSym = 2.0.pow(p.sf) / p.bandwidthHz * 1000.0
        val de = if (tSym > 16.0) 1 else 0   // low data-rate optimisation
        val ih = if (p.explicitHeader) 0 else 1
        val crc = if (p.crc) 1 else 0
        val num = 8.0 * payloadBytes - 4.0 * p.sf + 28 + 16 * crc - 20 * ih
        val payloadSymbols = 8 + max(ceil(num / (4.0 * (p.sf - 2 * de))) * p.codingRate, 0.0)
        return (p.preambleSymbols + 4.25) * tSym + payloadSymbols * tSym
    }
}
