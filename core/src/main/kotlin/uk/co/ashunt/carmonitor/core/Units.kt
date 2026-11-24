package uk.co.ashunt.carmonitor.core

import java.util.Locale

/** Display-only unit conversion: everything is logged and sent to Home Assistant in metric. */
data class Units(val imperialSpeed: Boolean = false, val gallons: Boolean = false, val fahrenheit: Boolean = false) {
    fun speed(kmh: Double) = if (imperialSpeed) kmh * KM_TO_MI else kmh
    val speedUnit get() = if (imperialSpeed) "mph" else "km/h"
    fun distance(km: Double) = if (imperialSpeed) km * KM_TO_MI else km
    val distanceUnit get() = if (imperialSpeed) "mi" else "km"
    fun temp(c: Double) = if (fahrenheit) c * 9 / 5 + 32 else c
    val tempUnit get() = if (fahrenheit) "°F" else "°C"
    fun volume(l: Double) = if (gallons) l / L_PER_UK_GAL else l
    val volumeUnit get() = if (gallons) "gal" else "L"
    /** Economy follows the fuel unit: UK mpg with gallons, L/100 km with litres. */
    fun economy(lPer100: Double) = if (gallons) MPG_UK_FACTOR / lPer100 else lPer100
    val economyUnit get() = if (gallons) "mpg" else "L/100 km"

    companion object {
        const val KM_TO_MI = 0.621371
        const val L_PER_UK_GAL = 4.54609
        const val MPG_UK_FACTOR = 282.481
        fun fmt(v: Double?, decimals: Int = 0): String =
            if (v == null || v.isNaN()) "—" else String.format(Locale.UK, "%,.${decimals}f", v)
    }
}
