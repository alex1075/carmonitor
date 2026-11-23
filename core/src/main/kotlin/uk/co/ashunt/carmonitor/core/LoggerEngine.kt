package uk.co.ashunt.carmonitor.core

import java.io.IOException
import java.util.Locale

data class LoggerConfig(
    val publishDefaults: Boolean = true,        // DefaultSignals (core J1979 + OBDb suggestedMetric) get HA sensors
    val publishAll: Boolean = false,            // every supported signal gets a sensor
    val publishCandidates: Boolean = true,      // unvalidated overlay DIDs as raw diagnostic sensors
    val minGapMs: Long = 60,                    // pause between requests
    val freqScale: Double = 1.0,                // multiply OBDb poll intervals
    val dtcIntervalMs: Long = 60_000,
    val publishMinIntervalMs: Long = 2_000,     // per topic
    val republishMs: Long = 30_000,             // keep expire_after sensors alive when unchanged
    val tripStartRpm: Double = 300.0,
    val tripStartHoldMs: Long = 5_000,
    val engineOffTimeoutMs: Long = 30_000,
    val regenEgtC: Double = 450.0,
    val regenSootDropGl: Double = 1.0,
    val fuelType: FuelType = FuelType.DIESEL,
    val tankLitres: Double? = null,
    val longRunLPer100: Double? = null,
    /** FuelTracker.state() from the previous drive (fill-to-fill baseline etc.). */
    val fuelState: Map<String, Any?>? = null,
)

/** Rows for the per-drive CSV: ts_ms, key, signal, value, raw. */
fun interface CsvSink { fun row(tsMs: Long, key: String, signal: String, value: String, raw: String) }

/**
 * Polls the signals the car supports, keeps trip/regen/DTC state, publishes to HA.
 * One [step] = one request. [run] loops until [stop] returns true or the link drops.
 */
class LoggerEngine(
    private val elm: Elm327,
    set: SignalSet,
    private val profile: VehicleProfile,
    private val sink: Sink,
    private val topics: TopicScheme = TopicScheme.forVehicle(null),
    private val cfg: LoggerConfig = LoggerConfig(),
    private val csv: CsvSink? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    private val polled: List<Command> = set.forYear(profile.modelYear).filter { profile.supported(it.key) }
    private val nextDue = HashMap<String, Long>()
    val values = LinkedHashMap<String, Pair<Decoded, Long>>()   // signal id -> value, ts
    private val lastPub = HashMap<String, Pair<String, Long>>()

    // trip / regen state
    var tripActive = false; private set
    private var rpmAboveSince: Long? = null
    private var tripKm = 0.0
    private var lastSpeedTs: Long? = null
    private var tripRegen = false
    private var tripMaxSoot: Double? = null
    private var lastGoodReply = now()
    private var lastDtc = 0L
    var regenActive = false; private set
    val fuel = FuelTracker(cfg.fuelType, cfg.tankLitres, cfg.longRunLPer100).also { f -> cfg.fuelState?.let { f.restore(it) } }
    private val fuelVal: (String) -> Double? = { id -> if (isValidatedSignal(id)) num(id) else null }

    val pollCount get() = polled.size

    private val polledSignalIds: Set<String> = polled.flatMap { it.signals }.map { it.id }.toSet()
    /** Only diesels with something DPF-ish get regen entities. */
    private val hasDpf: Boolean = polledSignalIds.any { it.startsWith("DPF") || it.startsWith("PF_REGEN") || it.startsWith("MZD_") }
    private val hasFuel: Boolean = polledSignalIds.any { it in FUEL_INPUTS }

    private fun wantsSensor(s: Signal): Boolean {
        if (s.hidden || s.id.endsWith("_SUP") || s.id in topics.legacySources) return false
        return cfg.publishAll || (cfg.publishDefaults && DefaultSignals.include(s))
    }

    fun announce() {
        sink.publish(topics.status, "online", true)
        for ((t, p) in HaDiscovery.engineEntities(topics)) sink.publish(t, p, true)
        if (hasDpf) HaDiscovery.regenActive(topics).let { sink.publish(it.first, it.second, true) }
        if (hasFuel) for ((t, p) in HaDiscovery.fuelEntities(topics)) sink.publish(t, p, true)
        fuel.longRunLPer100?.let { pub(topics.avgEconomy, "%.1f".format(java.util.Locale.ROOT, it), true, force = true) }
        for (c in polled) {
            if (!c.validated) {
                if (cfg.publishCandidates) HaDiscovery.forRaw(topics, c.key).let { sink.publish(it.first, it.second, true) }
                continue
            }
            for (s in c.signals) if (wantsSensor(s)) HaDiscovery.forSignal(topics, s).let { sink.publish(it.first, it.second, true) }
        }
        log("polling ${polled.size} commands")
    }

    fun run(stop: () -> Boolean) {
        announce()
        try {
            while (!stop()) step()
        } finally {
            if (tripActive) endTrip()
            try { sink.publish(topics.status, "offline", true) } catch (_: Exception) {}
        }
    }

    /** One scheduling step. Throws IOException if the adapter link is lost. */
    fun step() {
        val t = now()
        if (t - lastDtc >= cfg.dtcIntervalMs && (tripActive || lastDtc == 0L)) { readDtcs(); lastDtc = t }
        val c = polled.minByOrNull { nextDue[it.key] ?: 0L }
        if (c == null) { sleep(1000); return }
        val due = nextDue[c.key] ?: 0L
        if (due > t) { sleep(minOf(due - t, 500)); return }
        nextDue[c.key] = t + (c.freq * 1000 * cfg.freqScale).toLong().coerceAtLeast(500)

        val r = elm.request(c.request, c.hdr, c.rax)
        if (r is ObdResponse.Positive) {
            lastGoodReply = t
            handle(c, r.data, t)
        } else if (r is ObdResponse.Error && r.text.contains("UNABLE")) {
            log("adapter: ${r.text}")
        }
        if (tripActive && t - lastGoodReply > cfg.engineOffTimeoutMs) { log("no replies, engine off"); endTrip() }
        sleep(cfg.minGapMs)
    }

    private fun handle(c: Command, data: ByteArray, t: Long) {
        if (!c.validated) {
            val u16 = Decoder.bits(data, 0, 16) ?: Decoder.bits(data, 0, 8)
            pub(topics.rawTopic(c.key), MiniJson.write(linkedMapOf("u16" to u16, "hex" to data.toHex())), false)
            csv?.row(t, c.key, "raw", u16?.toString() ?: "", data.toHex())
        }
        for (s in c.signals) {
            val d = Decoder.decode(s, data) ?: continue
            values[s.id] = d to t
            val txt = when (d) { is Decoded.Num -> fmtNum(d.v); is Decoded.Text -> d.v }
            csv?.row(t, c.key, s.id, txt, if (c.validated) "" else data.toHex())
            if (c.validated && wantsSensor(s)) pub(topics.signalTopic(s.id), txt, false)
        }
        if (c.validated) publishLegacy(c)
        updateTrip(t)
        if (hasFuel) publishFuel(t)
    }

    private fun num(id: String): Double? = (values[id]?.first as? Decoded.Num)?.v

    private fun validatedNum(id: String): Double? {
        val found = (values[id] ?: return null)
        return (found.first as? Decoded.Num)?.v
    }

    private fun publishLegacy(c: Command) {
        val ids = c.signals.map { it.id }.toSet()
        for (lt in topics.legacyTopics) {
            if (lt.sources.none { it in ids }) continue
            val src = lt.sources.firstOrNull { validatedNum(it) != null && isValidatedSignal(it) } ?: continue
            pub(lt.topic, lt.format(validatedNum(src)!!), false)
        }
    }

    private val validatedIds: Set<String> = polled.filter { it.validated }.flatMap { it.signals }.map { it.id }.toSet()
    private fun isValidatedSignal(id: String) = id in validatedIds

    private fun updateTrip(t: Long) {
        val rpm = num("RPM")
        if (rpm != null) {
            if (!tripActive) {
                if (rpm > cfg.tripStartRpm) {
                    if (rpmAboveSince == null) rpmAboveSince = t
                    if (t - rpmAboveSince!! >= cfg.tripStartHoldMs) startTrip(t)
                } else rpmAboveSince = null
            } else if (rpm < 50 && values["RPM"]?.second == t) {
                endTrip()
            }
        }
        if (!tripActive) return
        val vss = values["VSS"]
        if (vss != null && vss.second == t) {
            val v = (vss.first as Decoded.Num).v
            lastSpeedTs?.let { tripKm += v * (t - it) / 3_600_000.0 }
            lastSpeedTs = t
            pub(topics.tripDist, "%.1f".format(java.util.Locale.ROOT, tripKm * topics.distFactor), false)
        }
        // Regen: standard J1979 status if the ECU has it, else EGT threshold, plus soot drop.
        val stat = num("PF_REGEN_STAT")
        val egt = POST_DPF_EGT.firstNotNullOfOrNull { if (isValidatedSignal(it)) num(it) else null }
        val nowRegen = when {
            stat != null -> stat >= 1.0
            egt != null -> egt >= cfg.regenEgtC
            else -> regenActive
        }
        val soot = if (isValidatedSignal("MZD_SOOT_GL")) num("MZD_SOOT_GL") else null
        if (soot != null) {
            tripMaxSoot = maxOf(tripMaxSoot ?: soot, soot)
            if (tripMaxSoot!! - soot >= cfg.regenSootDropGl) tripRegen = true
        }
        if (nowRegen) tripRegen = true
        if (!hasDpf) return
        if (nowRegen != regenActive || lastPub[topics.regenActive] == null) {
            regenActive = nowRegen
            pub(topics.regenActive, if (nowRegen) "on" else "off", true, force = true)
        }
    }

    private fun publishFuel(t: Long) {
        val f = Locale.ROOT
        fuel.observeLevel(fuelVal)
        fuel.remainingLitres(fuelVal)?.let { pub(topics.fuelRemaining, "%.1f".format(f, it), true) }
        fuel.rangeKm(fuelVal)?.let { pub(topics.fuelRange, "%.0f".format(f, it), true) }
        if (!tripActive) return
        fuel.update(t, fuelVal, fuelVal("VSS"))
        fuel.rateLh?.let { pub(topics.fuelRate, "%.2f".format(f, it), false) }
        val inst = fuel.instantLPer100
        if (inst != null && inst < 99) {
            pub(topics.fuelEconomy, "%.1f".format(f, inst), false)
            if (inst > 0.5) pub(topics.fuelEconomyMpg, "%.1f".format(f, FuelTracker.mpgUk(inst)), false)
        }
        pub(topics.fuelSource, fuel.source.name.lowercase(), true)
        pub(topics.tripFuel, "%.2f".format(f, fuel.tripLitres), true)
        fuel.tripLPer100?.let {
            pub(topics.tripEconomy, "%.1f".format(f, it), true)
            if (it > 0.5) pub(topics.tripEconomyMpg, "%.1f".format(f, FuelTracker.mpgUk(it)), true)
        }
    }

    private fun startTrip(t: Long) {
        tripActive = true; tripKm = 0.0; lastSpeedTs = null; tripRegen = false; tripMaxSoot = null
        fuel.startTrip()
        log("trip START")
        pub(topics.tripActive, "on", true, force = true)
        readDtcs(); lastDtc = t
    }

    private fun endTrip() {
        if (!tripActive) return
        tripActive = false; rpmAboveSince = null
        log("trip END %.1f km regen=%s".format(java.util.Locale.ROOT, tripKm, tripRegen))
        pub(topics.tripActive, "off", true, force = true)
        pub(topics.lastTripDist, "%.1f".format(java.util.Locale.ROOT, tripKm * topics.distFactor), true, force = true)
        if (hasDpf) pub(topics.lastTripRegen, if (tripRegen) "yes" else "no", true, force = true)
        if (regenActive) { regenActive = false; pub(topics.regenActive, "off", true, force = true) }
        if (hasFuel) {
            fuel.tripLPer100?.let { pub(topics.tripEconomy, "%.1f".format(Locale.ROOT, it), true, force = true) }
            pub(topics.tripFuel, "%.2f".format(Locale.ROOT, fuel.tripLitres), true, force = true)
            fuel.endTrip()
            fuel.longRunLPer100?.let { pub(topics.avgEconomy, "%.1f".format(Locale.ROOT, it), true, force = true) }
        }
    }

    private fun readDtcs() {
        val stored = (elm.request("03") as? ObdResponse.Positive)?.data?.let { Dtc.parse(it) }
        val pending = (elm.request("07") as? ObdResponse.Positive)?.data?.let { Dtc.parse(it) }
        if (stored != null) {
            pub(topics.dtcCount, stored.size.toString(), true, force = true)
            pub(topics.dtcCodes, stored.joinToString(",").ifEmpty { "none" }, true, force = true)
        }
        if (pending != null) pub(topics.dtcPending, pending.joinToString(",").ifEmpty { "none" }, true, force = true)
    }

    private fun pub(topic: String, payload: String, retain: Boolean, force: Boolean = false) {
        val t = now()
        val last = lastPub[topic]
        if (!force && last != null) {
            val age = t - last.second
            if (age < cfg.publishMinIntervalMs) return
            if (last.first == payload && age < cfg.republishMs) return
        }
        lastPub[topic] = payload to t
        try { sink.publish(topic, payload, retain) } catch (e: IOException) { log("publish failed: ${e.message}") }
    }

    private fun fmtNum(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString()
        else "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
}

private val FUEL_INPUTS = setOf("FUEL_RATE", "FUEL_RATE_ALT", "VFUEL_RATE", "CX5_ENG_FUEL_RATE", "MAF", "MAFA", "FLI", "CX5_FLI", "CX5_FLV")

private val POST_DPF_EGT = listOf("MZD_EGT_POST_DPF", "DPF1_OUTT", "CX5_EXH_GAS_T_2")

object Dtc {
    private val LETTER = charArrayOf('P', 'C', 'B', 'U')

    /** Parse a Mode 03/07 payload (after the 43/47 echo). Handles CAN's leading count byte. */
    fun parse(data: ByteArray): List<String> {
        val b = data.map { it.toInt() and 0xFF }
        val pairs = if (b.isNotEmpty() && (b.size - 1) % 2 == 0 && b[0] == (b.size - 1) / 2) b.drop(1) else b
        return pairs.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }.map { (a, c) ->
            "%c%d%X%02X".format(LETTER[a shr 6], (a shr 4) and 3, a and 0xF, c)
        }.distinct()
    }
}
