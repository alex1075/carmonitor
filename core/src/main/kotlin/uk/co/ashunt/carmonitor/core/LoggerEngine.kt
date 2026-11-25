package uk.co.ashunt.carmonitor.core

import java.io.IOException
import java.util.Locale

data class DashSnapshot(
    val tripActive: Boolean, val regenActive: Boolean, val tripKm: Double, val tripStartedAt: Long?,
    val remainingL: Double?, val rangeKm: Double?, val tripLPer100: Double?, val avgLPer100: Double?,
    val numbers: Map<String, Double>,
) {
    /** First present signal among [ids] (OBDb names differ per car: VSS vs CX5_VSS). */
    fun first(vararg ids: String): Double? = ids.firstNotNullOfOrNull { numbers[it] }
}

data class LoggerConfig(
    val publishDefaults: Boolean = true,        // DefaultSignals (core J1979 + OBDb suggestedMetric) get HA sensors
    val publishAll: Boolean = false,            // every supported signal gets a sensor
    val publishCandidates: Boolean = true,      // unvalidated overlay DIDs as raw diagnostic sensors
    val minGapMs: Long = 20,                    // pause between requests
    val freqScale: Double = 1.0,                // multiply OBDb poll intervals
    val dtcIntervalMs: Long = 60_000,
    val publishMinIntervalMs: Long = 1_000,     // per topic: HA gets at most one value a second
    val republishMs: Long = 30_000,             // keep expire_after sensors alive when unchanged
    val tripStartRpm: Double = 300.0,
    val tripStartHoldMs: Long = 5_000,
    val engineOffTimeoutMs: Long = 30_000,
    val regenEgtC: Double = 450.0,
    val regenSootDropGl: Double = 1.0,
    /** Fallback/override; the car's own fuel-type report (0151) wins unless [forceFuelType]. */
    val fuelType: FuelType = FuelType.PETROL,
    val forceFuelType: Boolean = false,
    val tankLitres: Double? = null,
    val longRunLPer100: Double? = null,
    /** FuelTracker.state() from the previous drive (fill-to-fill baseline etc.). */
    val fuelState: Map<String, Any?>? = null,
    /** Poll the discovery sweep's unidentified DIDs and log them raw (CSV + diagnostic sensors). */
    val logSweepHits: Boolean = true,
    val cylinders: Int = 4,
    /** Poll tiers: dashboard/trip/fuel signals fast, HA-visible ones medium, diagnostics slow. The fast tier
     *  stretches itself if it would take more than half the adapter's time (see LoggerEngine.fastEveryMs). */
    val fastMs: Long = 500,
    val mediumMs: Long = 15_000,
    val slowMs: Long = 60_000,
    /** User poll plan (PollPlan.tiers): command key -> tier, applied after the engine's own rules. */
    val pollPlan: Map<String, Int> = emptyMap(),
    /** Slots mode: every round of 6 requests has this many fast and medium slots, the rest slow.
     *  Replaces the due-time scheduler (intervals, fastEveryMs stretching and FAIR_EVERY). */
    val slots: Boolean = false,
    val fastSlots: Int = 3,
    val mediumSlots: Int = 2,
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
    private val polled: List<Command> = buildPolled(set)

    private fun buildPolled(set: SignalSet): List<Command> {
        val known = set.forYear(profile.modelYear, includeDebug = true).filter { profile.supported(it.key) && RequestGuard.routeAllowed(it.hdr, it.rax) }
        if (!cfg.logSweepHits) return known
        val knownKeys = known.map { it.key }.toSet()
        // Sweep hits that no signalset describes: poll as raw candidates so drives can identify them.
        val sweep = ArrayList<Command>()
        for (h in profile.sweepHits) {
            if (h.key in knownKeys) continue
            val parts = h.key.split(':')
            if (parts.size != 2) continue
            val hdr = parts[0]; val req = parts[1]
            if (!req.startsWith("22") || req.length != 6 || !RequestGuard.routeAllowed(hdr, Elm327.defaultRx(hdr))) continue
            sweep += Command(hdr, Elm327.defaultRx(hdr), "22", req.substring(2), freq = 10.0, filter = null, debug = true,
                validated = false, source = "sweep",
                signals = listOf(Signal("SWEEP_${hdr}_${req.substring(2)}", "Sweep ${h.key}", "Sweep", Fmt(len = 16))))
        }
        return known + sweep
    }
    private val nextDue = HashMap<String, Long>()
    private var picks = 0L

    /** 2 = fast, 1 = medium, 0 = slow. A slow ELM327 manages ~5 requests/s, so not everything can be fast. */
    private val tier: Map<String, Int> = polled.associate { c ->
        c.key to (cfg.pollPlan[c.key] ?: when {
            c.source == "sweep" -> 0
            c.signals.any { it.id in FAST_IDS } -> 2
            c.signals.any { it.id in FUEL_INPUTS || it.id in POST_DPF_EGT || it.id == "PF_REGEN_STAT" ||
                it.id == "MZD_SOOT_GL" || isExhaustHealth(it.id) || DefaultSignals.include(it) } -> 1
            else -> 0
        })
    }
    internal fun tierOf(key: String) = tier[key]
    /** What one request polls: one command, or several fast service 01 PIDs asked for together. */
    private inner class PollUnit(val members: List<Command>) {
        val first = members.first()
        val pids = members.map { it.pid.uppercase() }.distinct()
        val batch = pids.size > 1
        val hdr = first.hdr; val rax = first.rax
        val request = if (batch) "01" + pids.joinToString("") else first.request
        val key = if (batch) "$hdr:$request" else first.key
        val tier = members.maxOf { this@LoggerEngine.tier[it.key] ?: 0 }
        val freq = members.minOf { it.freq }
    }

    private val units: MutableList<PollUnit> = ArrayList<PollUnit>().also { out ->
        val batchable = polled.filter { (tier[it.key] ?: 0) == 2 && it.validated && MultiPid.batchable(it) }
        for (group in batchable.groupBy { it.hdr to it.rax }.values) {
            val byPid = group.groupBy { it.pid.uppercase() }.values.toList()
            for (chunk in byPid.chunked(MultiPid.MAX)) out += PollUnit(chunk.flatten())
        }
        val inBatch = batchable.toHashSet()
        for (c in polled) if (c !in inBatch) out += PollUnit(listOf(c))
    }
    private val batchMisses = HashMap<String, Int>()

    /** Fast-tier interval: the configured one, stretched so fast requests use at most half the adapter. */
    val fastEveryMs: Long get() {
        val fastUnits = units.count { it.tier == 2 }
        return maxOf(cfg.fastMs, (fastUnits * avgRequestMs * 2).toLong())
    }
    private var avgRequestMs = 0.0

    // --- slots mode -----------------------------------------------------------------------------------
    private val fastSlots = cfg.fastSlots.coerceIn(1, SLOTS - 2)
    private val mediumSlots = cfg.mediumSlots.coerceIn(1, SLOTS - 1 - fastSlots)
    /** One round, spread out: 3/2/1 gives fast, medium, slow, fast, medium, fast. */
    private val slotPattern: List<Int> = ArrayList<Int>().also { out ->
        val left = intArrayOf(SLOTS - fastSlots - mediumSlots, mediumSlots, fastSlots)
        while (out.size < SLOTS) for (k in intArrayOf(2, 1, 0)) if (left[k] > 0) { out += k; left[k]-- }
    }
    /** A tier with nothing in it lends its slot to the nearest one that has something. */
    private fun slotTier(k: Int) = listOf(k, 1, 2, 0).firstOrNull { x -> units.any { it.tier == x } } ?: k
    private var slot = 0
    private var rounds = 0L
    private val passDone = HashSet<String>()
    /** Each tier's units take turns: none repeats until all have had a slot. Within a turn, the ECU the
     *  adapter is already addressed to goes first (no ATSH/ATCRA). */
    private fun nextSlot(): PollUnit {
        val k = slotTier(slotPattern[slot])
        slot = (slot + 1) % SLOTS
        if (slot == 0) rounds++
        val list = units.filter { it.tier == k }
        var pending = list.filter { it.key !in passDone }
        if (pending.isEmpty()) { list.forEach { passDone.remove(it.key) }; pending = list }
        val u = pending.firstOrNull { it.hdr == elm.currentHeader } ?: pending.first()
        passDone += u.key
        return u
    }
    /** Last unit polled (key, tier), for tests. */
    internal var lastPick: Pair<String, Int>? = null

    /** Commands, requests and (slots mode) slots and effective interval per tier, for the log. */
    private fun tierSummary(roundMs: Double?): String = (2 downTo 0).joinToString("; ") { k ->
        val n = units.count { it.tier == k }
        val s = PollPlan.NAMES[k] + " " + tier.values.count { it == k } + " commands in " + n + " requests"
        if (!cfg.slots) return@joinToString s
        val slots = slotPattern.count { slotTier(it) == k }
        s + ", " + slots + " slots" + (if (roundMs == null || slots == 0 || n == 0) "" else
            ", every %.1f s".format(Locale.ROOT, n.toDouble() / slots * roundMs / 1000))
    }

    init {
        val unknown = cfg.pollPlan.keys.filter { it !in tier }
        if (unknown.isNotEmpty()) log("poll plan: ignoring " + unknown.size + " keys not polled this session: " + unknown.joinToString())
        val used = cfg.pollPlan.filterKeys { it in tier }
        if (used.isNotEmpty()) log("poll plan: " + used.size + " overrides: " + used.entries.joinToString { it.key + " " + PollPlan.NAMES[it.value] })
        log((if (cfg.slots) "poll mode slots $fastSlots/$mediumSlots/" + (SLOTS - fastSlots - mediumSlots) + ": " else "poll mode auto: ") +
            tierSummary(null) + (if (cfg.slots) " (intervals logged after a minute)" else ""))
    }

    private fun intervalMs(u: PollUnit): Long {
        val base = when (u.tier) { 2 -> return fastEveryMs; 1 -> maxOf(cfg.mediumMs, (u.freq * 1000).toLong()); else -> maxOf(cfg.slowMs, (u.freq * 1000).toLong()) }
        return (base * cfg.freqScale).toLong().coerceAtLeast(500)
    }
    /** Requests per poll round for the log: (units, of which multi-PID batches). */
    val unitCounts: Pair<Int, Int> get() = units.size to units.count { it.batch }
    /** HA expire_after: fast values republish at least every 30 s, so 60 s of silence means the phone is gone
     *  (token mode has no last-will). Slow diagnostics may legitimately go minutes between polls on a busy adapter. */
    internal fun expireFor(c: Command): Int = when (tier[c.key] ?: 0) { 2 -> 60; 1 -> 180; else -> 900 }
    /** Commands per tier (fast, medium, slow), for the log. */
    val tierCounts: Triple<Int, Int, Int> get() = Triple(tier.values.count { it == 2 }, tier.values.count { it == 1 }, tier.values.count { it == 0 })
    val values = LinkedHashMap<String, Pair<Decoded, Long>>()   // signal id -> value, ts
    private val lastPub = HashMap<String, Pair<String, Long>>()

    // trip / regen state
    var tripActive = false; private set
    private var rpmAboveSince: Long? = null
    private var tripKm = 0.0
    private var tripStartedAt: Long? = null

    /** What the phone's dashboard (and Android Auto) shows: metric, raw numbers; the UI converts units. */
    fun snapshot(): DashSnapshot = DashSnapshot(
        tripActive = tripActive, regenActive = regenActive, tripKm = tripKm, tripStartedAt = tripStartedAt,
        remainingL = fuel.remainingLitres(fuelVal), rangeKm = fuel.rangeKm(fuelVal),
        tripLPer100 = fuel.tripLPer100, avgLPer100 = fuel.fillToFillLPer100 ?: fuel.longRunLPer100,
        numbers = values.entries.mapNotNull { (k, v) -> (v.first as? Decoded.Num)?.let { k to it.v } }.toMap(),
    )
    private var lastSpeedTs: Long? = null
    private var tripRegen = false
    private var tripMaxSoot: Double? = null
    private var lastGoodReply = now()
    /** Time of the last positive ECU reply; the service uses it to release a dead or silent link. */
    val lastGoodReplyMs get() = lastGoodReply
    private var lastDtc = 0L
    var regenActive = false; private set
    val fuel = FuelTracker(cfg.fuelType, cfg.tankLitres, cfg.longRunLPer100, cfg.cylinders).also { f -> cfg.fuelState?.let { f.restore(it) } }
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
        pub(topics.status, "online", true, force = true)
        for ((t, p) in HaDiscovery.engineEntities(topics)) sink.publish(t, p, true)
        if (hasDpf) HaDiscovery.regenActive(topics).let { sink.publish(it.first, it.second, true) }
        if (hasFuel) for ((t, p) in HaDiscovery.fuelEntities(topics)) sink.publish(t, p, true)
        fuel.longRunLPer100?.let { pub(topics.avgEconomy, "%.1f".format(java.util.Locale.ROOT, it), true, force = true) }
        for (c in polled) {
            if (!c.validated) {
                if (cfg.publishCandidates) HaDiscovery.forRaw(topics, c.key).let { sink.publish(it.first, it.second, true) }
                continue
            }
            for (s in c.signals) if (wantsSensor(s)) HaDiscovery.forSignal(topics, s, expireFor(c)).let { sink.publish(it.first, it.second, true) }
        }
        log("polling ${polled.size} commands")
    }

    fun run(stop: () -> Boolean) {
        // A session that polls nothing can never notice the car going away: refuse to run it.
        if (polled.isEmpty()) throw IOException("no supported signals to poll")
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
        if (units.isEmpty()) { sleep(1000); return }
        maybeLogRate(t)
        pub(topics.status, "online", true)   // heartbeat every republishMs, for the HA offline blueprint
        // Among the commands that are due, the highest tier goes first, then the most overdue, so a
        // saturated adapter starves diagnostics rather than speed/RPM.
        // Every FAIR_EVERY-th pick ignores tiers and takes the most overdue command, so a saturated
        // adapter slows diagnostics down but never starves them (HA would mark them unavailable).
        // Within a tier, requests to the ECU the adapter is already addressed to go first (no ATSH/ATCRA).
        // Slots mode instead fills fixed slots per round from each tier in turn (see nextSlot).
        val c = if (cfg.slots) nextSlot() else pickDue(t) ?: return
        lastPick = c.key to c.tier

        val n0 = elm.requestNanos; val q0 = elm.requests
        val r = elm.request(c.request, c.hdr, c.rax)
        if (elm.requests > q0) {
            val ms = (elm.requestNanos - n0) / 1e6 / (elm.requests - q0)
            avgRequestMs = if (avgRequestMs == 0.0) ms else avgRequestMs * 0.9 + ms * 0.1
        }
        if (r is ObdResponse.Positive) {
            lastGoodReply = t
            if (c.batch) {
                val parts = MultiPid.split(c.pids, r.data)
                for (m in c.members) parts[m.pid.uppercase()]?.let { handle(m, it, t) }
                if (parts.size < c.pids.size) batchMiss(c, "answered ${parts.keys.joinToString(",").ifEmpty { "nothing" }}")
                else batchMisses.remove(c.key)
            } else handle(c.first, r.data, t)
        } else {
            if (c.batch && t - lastGoodReply < 5_000) batchMiss(c, r.toString())
            if (r is ObdResponse.Error && r.text.contains("UNABLE")) log("adapter: ${r.text}")
        }
        if (tripActive && t - lastGoodReply > cfg.engineOffTimeoutMs) { log("no replies, engine off"); endTrip() }
        sleep(cfg.minGapMs)
    }

    /** Auto mode: the most deserving due unit, or null after sleeping until one is due. */
    private fun pickDue(t: Long): PollUnit? {
        val fair = ++picks % FAIR_EVERY == 0L
        val cur = elm.currentHeader
        var c: PollUnit? = null
        for (x in units) {
            val d = nextDue[x.key] ?: 0L
            if (d > t) continue
            val best = c
            if (best == null) { c = x; continue }
            val db = nextDue[best.key] ?: 0L
            if (fair) { if (d < db) c = x; continue }
            val sx = x.hdr == cur; val sb = best.hdr == cur
            if (x.tier > best.tier || (x.tier == best.tier && (sx && !sb || (sx == sb && d < db)))) c = x
        }
        if (c == null) { sleep(minOf(units.minOf { nextDue[it.key] ?: 0L } - t, 200).coerceAtLeast(1)); return null }
        nextDue[c.key] = t + intervalMs(c)
        return c
    }

    /** A batch the ECU keeps answering badly is split back into single requests for this session. */
    private fun batchMiss(u: PollUnit, why: String) {
        val n = (batchMisses[u.key] ?: 0) + 1
        batchMisses[u.key] = n
        if (n < 3) return
        units.remove(u); batchMisses.remove(u.key)
        for (m in u.members) { units += PollUnit(listOf(m)); nextDue.remove(m.key) }
        log("multi-PID ${u.request} on ${u.hdr} unreliable ($why): polling its PIDs one by one")
    }

    private var rateAt = 0L; private var rateReq = 0L; private var rateRounds = 0L
    private fun maybeLogRate(t: Long) {
        if (rateAt == 0L) { rateAt = t; rateReq = elm.requests; rateRounds = rounds; return }
        if (t - rateAt < 60_000) return
        val perS = (elm.requests - rateReq) * 1000.0 / (t - rateAt)
        if (cfg.slots) {
            val roundMs = if (rounds > rateRounds) (t - rateAt).toDouble() / (rounds - rateRounds) else null
            log("poll rate %.1f req/s, %.0f ms each, round %s, response counts %s".format(java.util.Locale.ROOT,
                perS, avgRequestMs, roundMs?.let { "%.0f ms".format(Locale.ROOT, it) } ?: "not finished", if (elm.responseCounts) "on" else "off"))
            log("tiers: " + tierSummary(roundMs))
        } else {
            log("poll rate %.1f req/s, %.0f ms each, fast every %d ms, response counts %s".format(java.util.Locale.ROOT,
                perS, avgRequestMs, fastEveryMs, if (elm.responseCounts) "on" else "off"))
            log("tiers: " + tierSummary(null))
        }
        rateAt = t; rateReq = elm.requests; rateRounds = rounds
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
        if (!cfg.forceFuelType) (values["FUEL_TYP"]?.first as? Decoded.Text)?.v?.let { FuelTypes.fromJ1979(it) }?.let { fuel.fuel = it }
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
        tripStartedAt = t
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

private val FAST_IDS = setOf("RPM", "VSS", "ECT", "MAF", "MAFA", "MZD_INJ_QTY", "INJ_QTY", "FUEL_RATE", "FUEL_RATE_ALT",
    "VFUEL_RATE", "CX5_ENG_FUEL_RATE")
private val FUEL_INPUTS = setOf("MZD_INJ_QTY", "INJ_QTY", "FUEL_RATE", "FUEL_RATE_ALT", "VFUEL_RATE", "CX5_ENG_FUEL_RATE", "MAF", "MAFA", "FLI", "CX5_FLI", "CX5_FLV")

private const val FAIR_EVERY = 4
/** Requests per round in slots mode. */
private const val SLOTS = 6
/** Exhaust temperatures and particulate-filter readings: worth watching live on a diesel. */
internal fun isExhaustHealth(id: String): Boolean =
    id.startsWith("EGT") || id.startsWith("DPF") || id.contains("_EGT") || id.contains("_DPF") || id.contains("SOOT")
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
