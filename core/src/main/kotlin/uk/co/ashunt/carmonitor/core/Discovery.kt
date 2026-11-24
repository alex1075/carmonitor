package uk.co.ashunt.carmonitor.core

enum class Support { SUPPORTED, UNSUPPORTED, NO_RESPONSE, ERROR }

data class ProbeResult(val key: String, val support: Support, val rawHex: String? = null, val note: String? = null)

/** What a given car answered. Saved on the phone; the logger polls only SUPPORTED keys. */
data class VehicleProfile(
    val vin: String?,
    val modelYear: Int?,
    val adapter: String,
    val createdAtMs: Long,
    val mode01Supported: Set<String>,
    val results: Map<String, ProbeResult>,
    val sweepHits: List<ProbeResult>,
    val vehicleRepo: String? = null,
    val signalsetFile: String? = null,
    val adapterWarnings: List<String> = emptyList(),
    val format: String = CURRENT_FORMAT,
) {
    /** Made by an older version that probed fewer commands: re-discover (keeping sweep hits). */
    val isStale get() = format != CURRENT_FORMAT
    fun supported(key: String) = results[key]?.support == Support.SUPPORTED ||
        sweepHits.any { it.key == key }

    fun toJson(): String = MiniJson.write(linkedMapOf(
        "format" to format,
        "vin" to vin, "modelYear" to modelYear, "adapter" to adapter, "createdAtMs" to createdAtMs,
        "mode01Supported" to mode01Supported.sorted(),
        "results" to results.values.sortedBy { it.key }.map { r ->
            linkedMapOf("key" to r.key, "support" to r.support.name, "raw" to r.rawHex, "note" to r.note)
        },
        "sweepHits" to sweepHits.map { linkedMapOf("key" to it.key, "raw" to it.rawHex) },
    ), pretty = true)

    companion object {
        /** Bump when discovery probes a wider set of commands. 3 = includes OBDb debug commands. */
        const val CURRENT_FORMAT = "carmonitor-profile-3"
        fun fromJson(json: String): VehicleProfile {
            val m = MiniJson.obj(json)
            val results = m["results"].asList().map { it.asMap() }.associate { r ->
                val k = r["key"].toString()
                k to ProbeResult(k, Support.valueOf(r["support"].toString()), r["raw"] as? String, r["note"] as? String)
            }
            return VehicleProfile(
                vin = m["vin"] as? String, modelYear = m["modelYear"].asInt(),
                adapter = m["adapter"]?.toString() ?: "", createdAtMs = (m["createdAtMs"] as? Number)?.toLong() ?: 0L,
                mode01Supported = m["mode01Supported"].asList().map { it.toString() }.toSet(),
                results = results,
                sweepHits = m["sweepHits"].asList().map { it.asMap() }
                    .map { ProbeResult(it["key"].toString(), Support.SUPPORTED, it["raw"] as? String, "sweep") },
                vehicleRepo = m["vehicleRepo"] as? String, signalsetFile = m["signalsetFile"] as? String,
                adapterWarnings = m["adapterWarnings"].asList().map { it.toString() },
                format = m["format"]?.toString() ?: "unknown",
            )
        }

        private val YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"
        /**
         * Model year from VIN position 10. North-American rule: a digit in position 7 means the
         * 1980-2009 cycle, a letter means 2010-2039. EU/JDM VINs (like JMZ… CX-5s) often carry
         * no year code at all -> null, so the app falls back to the year set in Settings.
         */
        fun yearFromVin(vin: String?): Int? {
            if (vin == null || vin.length != 17) return null
            val i = YEAR_CODES.indexOf(vin[9])
            if (i < 0) return null
            return (if (vin[6].isDigit()) 1980 else 2010) + i
        }
    }
}

data class SweepRange(val hdr: String, val from: Int, val to: Int) {
    val size get() = to - from + 1
    override fun toString() = "%s 22%04X-%04X".format(hdr, from, to)
}

data class DiscoverOptions(
    val yearOverride: Int? = null,
    val vehicleRepo: String? = null,
    val signalsetFile: String? = null,
    val sweep: List<SweepRange> = emptyList(),
    val delayMs: Long = 40,
)

/**
 * Three-pass probe:
 *  1. Mode 01 support bitmaps + VIN (Mode 09 02)
 *  2. every command in the signalset (+ overlay) for this model year
 *  3. optional Mode 22 sweep of chosen DID ranges
 * Read-only by construction: every request goes through [Elm327.request] -> [RequestGuard].
 */
class Discoverer(
    private val elm: Elm327,
    private val set: SignalSet,
    private val opts: DiscoverOptions = DiscoverOptions(),
    private val progress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    private val cancelled: () -> Boolean = { false },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun run(): VehicleProfile {
        // Pass 1 ---------------------------------------------------------------
        val sup01 = mutableSetOf<String>()
        var base = 0x00
        while (base <= 0xE0 && !cancelled()) {
            val r = elm.request("01%02X".format(base))
            if (r !is ObdResponse.Positive || r.data.size < 4) break
            val bm = r.data.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            for (i in 0 until 32) if ((bm shr (31 - i)) and 1L == 1L) sup01 += "%02X".format(base + i + 1)
            if ("%02X".format(base + 0x20) !in sup01) break
            base += 0x20
        }
        val vin = (elm.request("0902") as? ObdResponse.Positive)?.data?.let { d ->
            val bytes = if (d.size == 18) d.drop(1) else d.toList()
            String(bytes.map { it }.toByteArray(), Charsets.US_ASCII).filter { it.isLetterOrDigit() }.takeIf { it.length == 17 }
        }
        val year = opts.yearOverride ?: VehicleProfile.yearFromVin(vin)

        // Pass 2 ---------------------------------------------------------------
        // Include OBDb "debug" commands too: probing is read-only and only answering ones get logged.
        val cmds = set.forYear(year, includeDebug = true)
        val sweepTotal = opts.sweep.sumOf { it.size }
        val total = cmds.size + sweepTotal
        val results = LinkedHashMap<String, ProbeResult>()
        var done = 0
        for (c in cmds) {
            if (cancelled()) break
            progress(done++, total, c.key)
            if (!RequestGuard.isAllowed(c.request)) {
                // e.g. OBDb debug entries using KWP service 21: never sent, by design
                results[c.key] = ProbeResult(c.key, Support.UNSUPPORTED, note = "service ${c.service} not allowed (read-only allowlist)")
                continue
            }
            if (!RequestGuard.routeAllowed(c.hdr, c.rax)) {
                // e.g. OBDb entries on 29-bit or non-CAN addresses (ATSHD016): skip, never abort discovery
                results[c.key] = ProbeResult(c.key, Support.UNSUPPORTED, note = "header ${c.hdr} not allowed (11-bit CAN only)")
                continue
            }
            if (c.service == "01" && c.hdr == "7E0" && c.pid !in sup01 && sup01.isNotEmpty()) {
                results[c.key] = ProbeResult(c.key, Support.UNSUPPORTED, note = "not in Mode 01 bitmap")
                continue
            }
            results[c.key] = probe(c.key, c.request, c.hdr, c.rax)
            sleep(opts.delayMs)
        }

        // Pass 3 ---------------------------------------------------------------
        val hits = mutableListOf<ProbeResult>()
        for (range in opts.sweep) {
            for (did in range.from..range.to) {
                if (cancelled()) break
                val req = "22%04X".format(did)
                val key = "${range.hdr}:$req"
                progress(done++, total, key)
                if (key in results) continue
                val r = probe(key, req, range.hdr, Elm327.defaultRx(range.hdr))
                if (r.support == Support.SUPPORTED) hits += r.copy(note = "sweep")
                sleep(opts.delayMs)
            }
        }
        progress(total, total, "done")
        return VehicleProfile(vin, year, elm.adapterId, now(), sup01, results, hits,
            opts.vehicleRepo, opts.signalsetFile, elm.adapterWarnings)
    }

    private fun probe(key: String, req: String, hdr: String, rax: String?): ProbeResult =
        when (val r = try { elm.request(req, hdr, rax) } catch (e: RequestGuard.Refused) {
            // Belt and braces: one refused command must never kill a 1000-probe discovery.
            return ProbeResult(key, Support.UNSUPPORTED, note = "refused: ${e.message}")
        }) {
            is ObdResponse.Positive -> ProbeResult(key, Support.SUPPORTED, r.hex())
            is ObdResponse.Negative -> ProbeResult(key, Support.UNSUPPORTED, note = r.meaning)
            ObdResponse.NoData -> ProbeResult(key, Support.NO_RESPONSE)
            is ObdResponse.Error -> ProbeResult(key, Support.ERROR, note = r.text)
        }
}
