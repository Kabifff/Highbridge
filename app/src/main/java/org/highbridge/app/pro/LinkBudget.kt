// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kabifff and the Highbridge contributors
package org.highbridge.app.pro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Antenna coverage prediction: a LoRa link budget, the radio horizon for two antenna heights,
 * and the Okumura-Hata path-loss model (with its suburban and open-area corrections) for how far
 * that budget reaches over different ground.
 * It is a planning estimate, not a terrain model: hills, buildings and trees change real coverage a lot.
 */
object LinkBudget {
    enum class Terrain(val label: String, val marginDb: Double) {
        OPEN("Open water or flat farmland", 8.0),
        RURAL("Rural, some trees", 9.0),
        SUBURBAN("Suburban houses and trees", 10.0),
        URBAN("City buildings", 10.0),
    }

    /**
     * Okumura-Hata median path loss in dB at [dKm], for a base antenna [hbM] and a mobile antenna [hmM]
     * (small/medium city formula, then the suburban and open-area corrections).
     */
    fun hataDb(dKm: Double, freqMhz: Double, hbM: Double, hmM: Double, terrain: Terrain): Double {
        val lf = log10(freqMhz); val hb = hbM.coerceAtLeast(1.0); val hm = hmM.coerceIn(1.0, 10.0)
        val a = (1.1 * lf - 0.7) * hm - (1.56 * lf - 0.8)
        val urban = 69.55 + 26.16 * lf - 13.82 * log10(hb) - a + (44.9 - 6.55 * log10(hb)) * log10(dKm)
        val suburban = urban - 2 * log10(freqMhz / 28).pow(2) - 5.4
        val open = urban - 4.78 * lf * lf + 18.33 * lf - 40.94
        return when (terrain) { Terrain.URBAN -> urban; Terrain.SUBURBAN -> suburban; Terrain.RURAL -> (suburban + open) / 2; Terrain.OPEN -> open }
    }

    /** LoRa demodulator SNR limit by spreading factor (Semtech datasheet figures). */
    fun snrLimitDb(sf: Int): Double = when (sf) { 5 -> -2.5; 6 -> -5.0; 7 -> -7.5; 8 -> -10.0; 9 -> -12.5; 10 -> -15.0; 11 -> -17.5; else -> -20.0 }

    /** Receiver sensitivity in dBm: thermal noise over the bandwidth, plus noise figure, plus the SNR limit. */
    fun sensitivityDbm(sf: Int, bwHz: Double, noiseFigureDb: Double = 6.0): Double = -174 + 10 * log10(bwHz) + noiseFigureDb + snrLimitDb(sf)

    /** Free-space path loss in dB. */
    fun fsplDb(distanceKm: Double, freqMhz: Double): Double = 20 * log10(distanceKm) + 20 * log10(freqMhz) + 32.44

    /** Radio horizon in km between two antennas, with the usual 4/3 earth radius for refraction. */
    fun horizonKm(h1M: Double, h2M: Double): Double = 4.12 * (sqrt(h1M.coerceAtLeast(0.0)) + sqrt(h2M.coerceAtLeast(0.0)))

    data class Result(val budgetDb: Double, val sensitivityDbm: Double, val pathLossRangeKm: Double, val horizonKm: Double, val freeSpaceKm: Double) {
        /** The estimate: whichever limit is reached first. */
        val rangeKm get() = minOf(pathLossRangeKm, horizonKm)
        val limitedByHorizon get() = horizonKm < pathLossRangeKm
    }

    fun predict(txDbm: Double, txGainDbi: Double, rxGainDbi: Double, cableLossDb: Double, sf: Int, bwHz: Double, freqMhz: Double,
                h1M: Double, h2M: Double, terrain: Terrain): Result {
        val sens = sensitivityDbm(sf, bwHz)
        val budget = txDbm + txGainDbi + rxGainDbi - cableLossDb - sens
        val usable = budget - terrain.marginDb
        val hb = maxOf(h1M, h2M); val hm = minOf(h1M, h2M)
        // Hata loss rises with log(d): solve loss(d) = usable for d.
        val l1 = hataDb(1.0, freqMhz, hb, hm, terrain)
        val slope = hataDb(10.0, freqMhz, hb, hm, terrain) - l1
        val hata = 10.0.pow((usable - l1) / slope)
        val free = 10.0.pow((usable - fsplDb(1.0, freqMhz)) / 20)
        return Result(budget, sens, hata, horizonKm(h1M, h2M), free)
    }

    /** A predicted coverage circle to draw on the map: centre and radius in metres. */
    data class Circle(val lat: Double, val lon: Double, val radiusM: Double, val label: String)
    val shownOnMap = MutableStateFlow<Circle?>(null)
}
