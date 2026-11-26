package org.highbridge.app.pro

/**
 * Zambretti barometric forecaster (Negretti & Zambra, 1915). Works offline from a pressure
 * reading at sea level and its 3-hour trend. Uses the commonly published lookup tables.
 */
object Zambretti {
    enum class Trend { RISING, STEADY, FALLING }

    private val forecasts = listOf(
        "Settled fine", "Fine weather", "Becoming fine", "Fine, becoming less settled", "Fine, possible showers",
        "Fairly fine, improving", "Fairly fine, possible showers early", "Fairly fine, showery later", "Showery early, improving",
        "Changeable, mending", "Fairly fine, showers likely", "Rather unsettled, clearing later", "Unsettled, probably improving",
        "Showery, bright intervals", "Showery, becoming less settled", "Changeable, some rain", "Unsettled, short fine intervals",
        "Unsettled, rain later", "Unsettled, some rain", "Mostly very unsettled", "Occasional rain, worsening",
        "Rain at times, very unsettled", "Rain at frequent intervals", "Rain, very unsettled", "Stormy, may improve", "Stormy, much rain",
    )
    private val falling = intArrayOf(0, 1, 3, 6, 10, 15, 16, 19, 21, 23, 24, 25, 25)
    private val steady = intArrayOf(0, 1, 4, 9, 12, 17, 18, 20, 22, 23, 24, 25)
    private val rising = intArrayOf(0, 1, 2, 5, 7, 8, 11, 13, 14, 15, 17, 19, 21, 22, 23, 24, 25)

    /** Converts station pressure to sea-level pressure. */
    fun seaLevel(hpa: Double, altitudeM: Double, tempC: Double = 15.0): Double =
        hpa * Math.pow(1 - (0.0065 * altitudeM) / (tempC + 0.0065 * altitudeM + 273.15), -5.257)

    fun trendOf(deltaHpaPer3h: Double): Trend = when {
        deltaHpaPer3h >= 1.6 -> Trend.RISING
        deltaHpaPer3h <= -1.6 -> Trend.FALLING
        else -> Trend.STEADY
    }

    /** [summer] for the Northern Hemisphere is April–September; flip it south of the equator. */
    fun forecast(seaLevelHpa: Double, trend: Trend, summer: Boolean, windFromNorthish: Boolean? = null): String {
        val p = seaLevelHpa.coerceIn(947.0, 1050.0)
        val z = when (trend) {
            Trend.FALLING -> { var i = ((1050 - p) / 7.45).toInt(); if (!summer) i += 1; falling[i.coerceIn(0, falling.lastIndex)] }
            Trend.STEADY -> { val i = ((1050 - p) / 8.67).toInt(); steady[i.coerceIn(0, steady.lastIndex)] }
            Trend.RISING -> { var i = ((1050 - p) / 6.5).toInt(); if (summer) i -= 1; rising[i.coerceIn(0, rising.lastIndex)] }
        }
        return forecasts[z]
    }
}
