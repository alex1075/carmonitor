package uk.co.ashunt.carmonitor.core

enum class FuelType(val stoichAfr: Double, val densityGPerL: Double) {
    DIESEL(14.5, 832.0),
    PETROL(14.7, 745.0),
}

/** How fuel use is being measured (published so HA can show how far to trust it). */
enum class FuelSource { MEASURED, INJECTION_QUANTITY, ESTIMATED_MAF_LAMBDA, ESTIMATED_MAF_O2, FUEL_LEVEL, NONE }

/**
 * Fuel rate, economy, remaining fuel and range from whatever the car offers, best first:
 *  1. measured fuel rate: J1979 015E (L/h), 019D (g/s), Mazda 22F45E; or injection quantity
 *     (mm³/stroke) × cylinders × rpm/2, once a validated quantity PID exists
 *  2. estimate from mass air flow and *standard* J1979 wide-band lambda / O2 % (018C):
 *       λ ≈ 20.9 / (20.9 − O2%),  fuel = MAF / (AFR_stoich × λ). Roughly ±10 % on a diesel.
 *     Values outside physical limits (O2 > 20.95 %, λ outside 0.6–20) are rejected, so a mis-scaled
 *     PID can't produce nonsense.
 *  3. fuel level: per-trip litres from the smoothed level, plus a fill-to-fill long-run average
 *     that resets on refuelling. No instant economy in this mode.
 * Remaining = cluster litres if reported, else smoothed level % × tank size.
 */
class FuelTracker(
    private val fuel: FuelType,
    private val tankLitres: Double?,
    /** Long-run average consumption carried between drives (L/100 km), or null if unknown yet. */
    var longRunLPer100: Double?,
    private val cylinders: Int = 4,
) {
    var source = FuelSource.NONE; private set
    var rateLh: Double? = null; private set
    /** Instantaneous consumption while moving (> 5 km/h); null when stationary or level-based. */
    var instantLPer100: Double? = null; private set
    private var rateLitres = 0.0          // litres integrated from a measured/estimated rate this trip
    private var tripKm = 0.0
    private var lastTs: Long? = null

    // --- fuel-level tracking -------------------------------------------------------
    private val levelWin = ArrayDeque<Double>()
    private var tripStartL: Double? = null
    /** Fill-to-fill state, persisted between drives via [state]/[restore]. */
    var baselineL: Double? = null; private set
    var kmSinceBaseline = 0.0; private set
    private var lastSmoothedL: Double? = null

    /** Smoothed litres in the tank (median of recent samples; fuel sloshes). */
    var smoothedLitres: Double? = null; private set

    private val levelMode get() = source == FuelSource.FUEL_LEVEL || source == FuelSource.NONE

    val tripLitres: Double get() =
        if (levelMode) ((tripStartL ?: 0.0) - (smoothedLitres ?: tripStartL ?: 0.0)).coerceAtLeast(0.0)
        else rateLitres

    val tripLPer100: Double? get() {
        val minKm = if (levelMode) 15.0 else 0.5   // level deltas are coarse
        return if (tripKm >= minKm && tripLitres > 0) tripLitres / tripKm * 100 else null
    }

    /** Fill-to-fill average once there's enough distance and fuel used to mean something. */
    val fillToFillLPer100: Double? get() {
        val b = baselineL ?: return null
        val now = smoothedLitres ?: return null
        val used = b - now
        return if (kmSinceBaseline >= 30 && used >= 2) used / kmSinceBaseline * 100 else null
    }

    fun startTrip() { rateLitres = 0.0; tripKm = 0.0; lastTs = null; tripStartL = smoothedLitres }

    /** Fold the finished trip into the long-run average. */
    fun endTrip() {
        fillToFillLPer100?.let { if (it in 1.0..40.0) { longRunLPer100 = it; return } }
        if (levelMode) return                       // single-trip level deltas are too coarse
        val t = tripLPer100 ?: return
        if (tripKm < 2.0 || t !in 1.0..40.0) return
        val prev = longRunLPer100
        longRunLPer100 = if (prev == null) t else {
            val w = (tripKm / 1000.0).coerceIn(0.01, 0.5)
            prev * (1 - w) + t * w
        }
    }

    /** Feed the latest fuel level (any time, trip or not). */
    fun observeLevel(v: (String) -> Double?) {
        val l = rawLitres(v) ?: return
        levelWin.addLast(l)
        while (levelWin.size > 60) levelWin.removeFirst()
        if (levelWin.size < 5) return
        val sm = levelWin.sorted()[levelWin.size / 2]
        smoothedLitres = sm
        if (tripStartL == null && levelWin.size >= 10) tripStartL = sm
        val prev = lastSmoothedL
        // Refuel (also between drives, via the persisted last level): smoothed level up by > 4 L / 8 % of tank
        val jump = maxOf(4.0, (tankLitres ?: 50.0) * 0.08)
        if (baselineL == null || (prev != null && sm - prev > jump)) {
            baselineL = sm; kmSinceBaseline = 0.0
            if (prev != null) tripStartL = sm
        }
        if (levelWin.size >= 10) lastSmoothedL = sm
    }

    /** Call once per logger step while a trip is active. */
    fun update(t: Long, v: (String) -> Double?, speedKmh: Double?) {
        rateLh = computeRate(v)
        if (rateLh == null && smoothedLitres != null) source = FuelSource.FUEL_LEVEL
        val dt = lastTs?.let { (t - it) / 3_600_000.0 }   // hours
        lastTs = t
        if (dt != null && dt > 0 && dt < 0.01) {          // ignore gaps > 36 s (link drops)
            rateLh?.let { rateLitres += it * dt }
            speedKmh?.let { val km = it * dt; tripKm += km; kmSinceBaseline += km }
        }
        instantLPer100 = if (speedKmh != null && speedKmh > 5 && rateLh != null) rateLh!! / speedKmh * 100 else null
    }

    private fun computeRate(v: (String) -> Double?): Double? {
        v("FUEL_RATE")?.takeIf { it in 0.0..200.0 }?.let { source = FuelSource.MEASURED; return it }   // L/h
        (v("FUEL_RATE_ALT") ?: v("VFUEL_RATE"))?.takeIf { it in 0.0..50.0 }
            ?.let { source = FuelSource.MEASURED; return it * 3600 / fuel.densityGPerL }
        v("CX5_ENG_FUEL_RATE")?.takeIf { it in 0.0..200.0 }?.let { source = FuelSource.MEASURED; return it }
        // Injection quantity (mm³ per stroke, per cylinder) × cylinders × injections/min (4-stroke: rpm/2)
        val q = (v("MZD_INJ_QTY") ?: v("INJ_QTY"))?.takeIf { it in 0.0..150.0 }
        val rpm = v("RPM")
        if (q != null && rpm != null) {
            source = FuelSource.INJECTION_QUANTITY
            return q * cylinders * rpm / 2 * 60 / 1e6
        }
        // Prefer MAF-A (0166) when present: on some diesels 0110 looks mis-scaled (seen: 1.4 g/s at idle)
        val maf = (v("MAFA") ?: v("MAF"))?.takeIf { it in 0.0..700.0 }
        if (maf != null) {
            v("LAMBDA11")?.takeIf { it in 0.6..20.0 }?.let { source = FuelSource.ESTIMATED_MAF_LAMBDA; return gps(maf, it) }
            v("O2S11_PCT")?.takeIf { it in 0.0..20.95 }?.let { o2 ->
                source = FuelSource.ESTIMATED_MAF_O2
                return if (o2 > 20.3) 0.0 else gps(maf, (20.9 / (20.9 - o2)).coerceAtMost(20.0))   // overrun ≈ fuel cut
            }
        }
        source = FuelSource.NONE
        return null
    }

    private fun gps(mafGs: Double, lambda: Double) = mafGs / (fuel.stoichAfr * lambda) * 3600 / fuel.densityGPerL

    private fun rawLitres(v: (String) -> Double?): Double? =
        v("CX5_FLV")?.takeIf { it in 0.0..200.0 }
            ?: (v("FLI") ?: v("CX5_FLI"))?.takeIf { it in 0.0..100.0 }?.let { pct -> tankLitres?.let { it * pct / 100 } }

    /** Litres in the tank (smoothed once there are enough samples, else the latest reading). */
    fun remainingLitres(v: (String) -> Double?): Double? = smoothedLitres ?: rawLitres(v)

    fun rangeKm(v: (String) -> Double?): Double? {
        val rem = remainingLitres(v) ?: return null
        val avg = fillToFillLPer100 ?: longRunLPer100 ?: tripLPer100 ?: return null
        return if (avg > 0) rem / avg * 100 else null
    }

    /** Persist between drives. */
    fun state(): Map<String, Any?> = linkedMapOf(
        "longRunLPer100" to longRunLPer100, "baselineL" to baselineL,
        "kmSinceBaseline" to kmSinceBaseline, "lastL" to lastSmoothedL,
    )

    fun restore(m: Map<String, Any?>) {
        (m["longRunLPer100"] as? Number)?.toDouble()?.let { longRunLPer100 = it }
        (m["baselineL"] as? Number)?.toDouble()?.let { baselineL = it }
        (m["kmSinceBaseline"] as? Number)?.toDouble()?.let { kmSinceBaseline = it }
        (m["lastL"] as? Number)?.toDouble()?.let { lastSmoothedL = it }
    }

    companion object {
        const val L_PER_UK_GAL = 4.54609
        /** UK mpg from L/100 km (282.48 / L/100km). */
        fun mpgUk(lPer100: Double) = 100 / lPer100 * L_PER_UK_GAL / 1.609344
    }
}
