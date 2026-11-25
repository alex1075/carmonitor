package uk.co.ashunt.carmonitor.core

/**
 * Tiny dependency-free JSON reader/writer. Enough for OBDb signalsets,
 * vehicle profiles and MQTT payloads. Objects -> LinkedHashMap, arrays -> List,
 * numbers -> Double (or Long when integral), plus String/Boolean/null.
 */
object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "Trailing data at ${p.i}" }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as Map<String, Any?>

    fun write(v: Any?, pretty: Boolean = false): String =
        StringBuilder().also { w(it, v, pretty, 0) }.toString()

    private fun w(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        val nl = if (pretty) "\n" + "  ".repeat(depth + 1) else ""
        val end = if (pretty) "\n" + "  ".repeat(depth) else ""
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(v)
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null")
                         else if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) sb.append(v.toLong())
                         else sb.append(v)
            is Float -> w(sb, v.toDouble(), pretty, depth)
            is Number -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(nl); str(sb, k.toString()); sb.append(if (pretty) ": " else ":")
                    w(sb, x, pretty, depth + 1)
                }
                if (!first) sb.append(end)
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(nl); w(sb, x, pretty, depth + 1)
                }
                if (!first) sb.append(end)
                sb.append(']')
            }
            is Array<*> -> w(sb, v.toList(), pretty, depth)
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        var depth = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> nested { obj() }
                '[' -> nested { arr() }
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Bad JSON char '$c' at $i")
            }
        }
        // deep [[[[... would overflow the stack, and a StackOverflowError is not caught as an Exception
        fun nested(f: () -> Any?): Any? {
            require(++depth <= 64) { "JSON nested too deep" }
            return f().also { depth-- }
        }
        fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad literal at $i" }
            i += word.length
            return v
        }
        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = string(); ws()
                require(s[i] == ':') { "Expected ':' at $i" }; i++
                m[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("Expected ',' or '}' at ${i - 1}") }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("Expected ',' or ']' at ${i - 1}") }
            }
        }
    }
}

internal fun Any?.asMap(): Map<String, Any?> =
    @Suppress("UNCHECKED_CAST") (this as? Map<String, Any?> ?: emptyMap())
internal fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()
internal fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()
internal fun Any?.asInt(): Int? = (this as? Number)?.toInt()
