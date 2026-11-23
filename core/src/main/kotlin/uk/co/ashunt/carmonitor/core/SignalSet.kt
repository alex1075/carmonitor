package uk.co.ashunt.carmonitor.core

/**
 * OBDb signalset model (https://github.com/OBDb), schema v3.
 *
 * fmt semantics: bit offset `bix` (MSB-first, from the first byte after the response echo),
 * `len` in bits, value = raw * mul / div + add, `sign` = two's complement,
 * `map` = enum lookup, `nullmin`/`nullmax` = sentinel values that mean "no reading".
 *
 * Extension fields used by the local overlay (ignored by OBDb tooling):
 *   command.validated (Boolean, default true) — false = candidate; value is published
 *   only as raw hex until confirmed against FORScan / Car Scanner.
 */
data class Fmt(
    val bix: Int = 0, val len: Int = 8,
    val mul: Double = 1.0, val div: Double = 1.0, val add: Double = 0.0,
    val sign: Boolean = false,
    val min: Double? = null, val max: Double? = null,
    val nullMin: Double? = null, val nullMax: Double? = null,
    val unit: String? = null,
    val map: Map<String, String>? = null,
)

data class Signal(val id: String, val name: String, val path: String?, val fmt: Fmt, val hidden: Boolean = false,
                  val suggestedMetric: String? = null)

data class YearFilter(val from: Int? = null, val to: Int? = null, val years: Set<Int> = emptySet()) {
    /** OBDb semantics: matches if year <= to OR year >= from OR year in years. */
    fun matches(year: Int): Boolean =
        (to != null && year <= to) || (from != null && year >= from) || year in years
}

data class Command(
    val hdr: String, val rax: String?, val service: String, val pid: String,
    val freq: Double, val filter: YearFilter?, val debug: Boolean,
    val validated: Boolean, val source: String,
    val signals: List<Signal>,
) {
    val request: String get() = service + pid
    val key: String get() = "$hdr:$request"
    fun appliesTo(year: Int?): Boolean = year == null || filter == null || filter.matches(year)
}

class SignalSet(val name: String, val commands: List<Command>) {

    fun forYear(year: Int?, includeDebug: Boolean = false) =
        commands.filter { it.appliesTo(year) && (includeDebug || !it.debug) }

    fun signal(id: String): Pair<Command, Signal>? {
        for (c in commands) for (s in c.signals) if (s.id == id) return c to s
        return null
    }

    companion object {
        fun parse(name: String, json: String): SignalSet {
            val root = MiniJson.obj(json)
            val cmds = root["commands"].asList().mapNotNull { parseCommand(it.asMap(), name) }
            return SignalSet(name, cmds)
        }

        private fun yf(m: Map<String, Any?>?): YearFilter? {
            if (m == null || m.isEmpty()) return null
            return YearFilter(m["from"].asInt(), m["to"].asInt(), m["years"].asList().mapNotNull { it.asInt() }.toSet())
        }

        private fun parseCommand(c: Map<String, Any?>, source: String): Command? {
            val cmd = c["cmd"].asMap()
            val (svc, pid) = cmd.entries.firstOrNull() ?: return null
            val pidStr = pid?.toString() ?: return null
            val hdr = c["hdr"]?.toString() ?: "7E0"
            val sigs = c["signals"].asList().map { s ->
                val sm = s.asMap()
                val f = sm["fmt"].asMap()
                Signal(
                    id = sm["id"].toString(),
                    name = sm["name"]?.toString() ?: sm["id"].toString(),
                    path = sm["path"]?.toString(),
                    hidden = sm["hidden"] == true,
                    suggestedMetric = sm["suggestedMetric"]?.toString(),
                    fmt = Fmt(
                        bix = f["bix"].asInt() ?: 0, len = f["len"].asInt() ?: 8,
                        mul = f["mul"].asDouble() ?: 1.0, div = f["div"].asDouble() ?: 1.0,
                        add = f["add"].asDouble() ?: 0.0, sign = f["sign"] == true,
                        min = f["min"].asDouble(), max = f["max"].asDouble(),
                        nullMin = f["nullmin"].asDouble(), nullMax = f["nullmax"].asDouble(),
                        unit = f["unit"]?.toString(),
                        map = f["map"]?.asMap()?.mapValues { (_, v) -> v.asMap()["value"]?.toString() ?: v.toString() },
                    ),
                )
            }
            return Command(
                hdr = hdr, rax = c["rax"]?.toString() ?: Elm327.defaultRx(hdr),
                service = svc.uppercase(), pid = pidStr.uppercase(),
                freq = c["freq"].asDouble() ?: 1.0,
                filter = yf(c["filter"]?.asMap()),
                debug = c["dbg"] == true,
                validated = c["validated"] as? Boolean ?: true,
                source = source,
                signals = sigs,
            )
        }

        fun merge(name: String, vararg sets: SignalSet): SignalSet {
            // Later sets override earlier ones for the same header+request.
            val byKey = LinkedHashMap<String, Command>()
            for (s in sets) for (c in s.commands) byKey[c.key] = c
            return SignalSet(name, byKey.values.toList())
        }
    }
}

sealed class Decoded {
    data class Num(val v: Double) : Decoded()
    data class Text(val v: String) : Decoded()
}

object Decoder {
    /** Extract [len] bits starting at MSB-first bit offset [bix]. Null if out of range. */
    fun bits(data: ByteArray, bix: Int, len: Int): Long? {
        if (len <= 0 || len > 63 || bix < 0 || bix + len > data.size * 8) return null
        var v = 0L
        for (i in 0 until len) {
            val bit = bix + i
            val b = (data[bit / 8].toInt() shr (7 - bit % 8)) and 1
            v = (v shl 1) or b.toLong()
        }
        return v
    }

    fun decode(s: Signal, data: ByteArray): Decoded? {
        val f = s.fmt
        var raw = bits(data, f.bix, f.len) ?: return null
        f.map?.let { m -> return m[raw.toString()]?.let { Decoded.Text(it) } ?: Decoded.Text(raw.toString()) }
        if (f.sign && f.len < 64 && (raw shr (f.len - 1)) and 1L == 1L) raw -= (1L shl f.len)
        val v = raw * f.mul / f.div + f.add
        // Reject values outside the signal's own declared range (catches data bugs such as an
        // OBDb entry with a wrong offset): 1 % margin for rounding.
        val span = ((f.max ?: 0.0) - (f.min ?: 0.0)).let { if (it > 0) it * 0.01 else 1e-6 }
        if (f.min != null && v < f.min - span) return null
        if (f.max != null && v > f.max + span) return null
        if (f.nullMax != null && v >= f.nullMax - 1e-9) return null
        if (f.nullMin != null && v <= f.nullMin + 1e-9) return null
        return Decoded.Num(v)
    }

    fun decodeAll(c: Command, data: ByteArray): Map<String, Decoded> {
        val out = LinkedHashMap<String, Decoded>()
        for (s in c.signals) decode(s, data)?.let { out[s.id] = it }
        return out
    }
}
