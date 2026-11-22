package uk.co.ashunt.carmonitor.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Raw line link to an ELM327-compatible adapter. */
interface ElmLink : Closeable {
    fun send(line: String)
    /** Read until the '>' prompt (exclusive) or timeout. Throws IOException on link loss. */
    fun readUntilPrompt(timeoutMs: Long): String
}

/**
 * ElmLink over a pair of blocking streams (Bluetooth RFCOMM socket, TCP, USB serial…).
 * A reader thread drains the input so reads can time out even though
 * BluetoothSocket.inputStream.read() itself never does.
 */
class StreamLink(private val input: InputStream, private val output: OutputStream) : ElmLink {
    private val q = LinkedBlockingQueue<Int>()
    @Volatile private var dead: IOException? = null
    private val reader = Thread({
        try {
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("adapter closed the link")
                q.put(b)
            }
        } catch (e: IOException) {
            dead = e
        } catch (_: InterruptedException) {
        }
    }, "elm-reader").apply { isDaemon = true; start() }

    override fun send(line: String) {
        dead?.let { throw it }
        output.write((line + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return sb.toString()
            val b = q.poll(left, TimeUnit.MILLISECONDS)
            if (b == null) {
                dead?.let { throw it }
                return sb.toString()
            }
            if (b == '>'.code) return sb.toString()
            if (b != 0) sb.append(b.toChar())
        }
    }

    override fun close() {
        reader.interrupt()
        try { input.close() } catch (_: IOException) {}
        try { output.close() } catch (_: IOException) {}
    }
}

sealed class ObdResponse {
    /** [data] = payload after the positive-response echo (e.g. after "62 04 2B"). */
    data class Positive(val data: ByteArray, val from: String?) : ObdResponse() {
        override fun equals(other: Any?) = other is Positive && data.contentEquals(other.data) && from == other.from
        override fun hashCode() = data.contentHashCode()
        fun hex() = data.toHex()
    }
    data class Negative(val service: Int, val nrc: Int) : ObdResponse() {
        val meaning: String get() = NRC_NAMES[nrc] ?: "NRC 0x%02X".format(nrc)
    }
    object NoData : ObdResponse() { override fun toString() = "NoData" }
    data class Error(val text: String) : ObdResponse()

    companion object {
        val NRC_NAMES = mapOf(
            0x10 to "generalReject", 0x11 to "serviceNotSupported", 0x12 to "subFunctionNotSupported",
            0x13 to "incorrectMessageLength", 0x14 to "responseTooLong", 0x21 to "busyRepeatRequest",
            0x22 to "conditionsNotCorrect", 0x31 to "requestOutOfRange", 0x33 to "securityAccessDenied",
            0x78 to "responsePending", 0x7E to "subFunctionNotSupportedInActiveSession",
            0x7F to "serviceNotSupportedInActiveSession",
        )
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }
fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/**
 * ELM327 driver: init, header management, guarded requests, ISO-TP reassembly of
 * header-on (ATH1) CAN output.
 */
class Elm327(
    private val link: ElmLink,
    private val protocol: String = "6",          // ISO 15765-4 CAN 11-bit 500 kbit (Mazda KF)
    private val timeoutMs: Long = 2000,
    private val log: (String) -> Unit = {},
) : Closeable {

    private var header: String? = null
    private var rxFilter: String? = null
    var adapterId: String = ""; private set
    /** AT commands the adapter rejected during init (a sign of a poor clone). */
    var adapterWarnings: List<String> = emptyList(); private set

    private val ERRORS = listOf(
        "NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS INIT", "BUS BUSY", "BUS ERROR",
        "STOPPED", "BUFFER FULL", "FB ERROR", "DATA ERROR", "RX ERROR", "ERROR", "?",
    )

    private fun at(cmd: String, wait: Long = timeoutMs): String {
        val full = "AT$cmd"
        RequestGuard.check(full)
        link.send(full)
        return link.readUntilPrompt(wait).trim()
    }

    fun init() {
        // Wake/flush: a bare CR can repeat the last command, so send ATZ straight away.
        adapterId = at("Z", 4000).lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""
        log("adapter: $adapterId")
        val bad = mutableListOf<String>()
        for (c in listOf("E0", "L0", "S0", "H1", "CAF1", "AT1", "SP$protocol", "SH7E0", "CRA7E8", "CRA")) {
            val r = at(c)
            if (!r.contains("OK")) { log("AT$c -> '$r'"); bad += "AT$c" }
        }
        adapterWarnings = bad
        header = null; rxFilter = null
    }

    fun voltage(): String = at("RV")

    private fun ensureHeader(hdr: String, rax: String?) {
        if (header != hdr) {
            at("SH$hdr"); header = hdr
        }
        val want = rax
        if (rxFilter != want) {
            if (want == null) at("CRA") else at("CRA$want")
            rxFilter = want
        }
    }

    /** Send a read-only request (e.g. "010C", "22042B") to [hdr]; reply expected from [rax]. */
    fun request(hex: String, hdr: String = "7E0", rax: String? = defaultRx(hdr), retriesOnPending: Int = 2): ObdResponse {
        val req = RequestGuard.normalise(hex)
        RequestGuard.check(req)
        ensureHeader(hdr, rax)
        var attempt = 0
        while (true) {
            link.send(req)
            val raw = link.readUntilPrompt(timeoutMs)
            val r = parse(req, raw, rax)
            if (r is ObdResponse.Negative && r.nrc == 0x78 && attempt < retriesOnPending) {
                attempt++; Thread.sleep(200); continue
            }
            if (r is ObdResponse.Negative && r.nrc == 0x21 && attempt < retriesOnPending) {
                attempt++; Thread.sleep(100); continue
            }
            return r
        }
    }

    override fun close() = link.close()

    companion object {
        fun defaultRx(hdr: String): String? = hdr.toIntOrNull(16)?.let { "%03X".format(it + 8) }

        private val FRAME = Regex("^([0-9A-F]{3})([0-9A-F]{2,16})$")

        /** Parse the ELM327's text output for request [req]. Pure, so it's unit-testable. */
        fun parse(req: String, raw: String, rax: String?): ObdResponse {
            val svc = req.substring(0, 2).toInt(16)
            val lines = raw.split('\r', '\n').map { it.replace(" ", "").trim().uppercase() }
                .filter { it.isNotEmpty() && it != req && !it.startsWith("SEARCHING") }
            if (lines.isEmpty()) return ObdResponse.NoData
            // Group frames by sender, reassembling ISO-TP; a new SF/FF starts a new message.
            val msgs = mutableListOf<Pair<String, MutableList<Int>>>()
            val expectLen = HashMap<String, Int>()
            val open = HashMap<String, MutableList<Int>>()
            var errorText: String? = null
            for (l in lines) {
                val m = FRAME.matchEntire(l)
                if (m == null) {
                    if (l.contains("NODATA")) continue
                    errorText = errorText ?: l
                    continue
                }
                val id = m.groupValues[1]
                val bytes = m.groupValues[2].chunked(2).map { it.toInt(16) }
                val pci = bytes[0] shr 4
                when (pci) {
                    0 -> { val n = bytes[0] and 0xF; msgs += id to bytes.drop(1).take(n).toMutableList(); open.remove(id) }
                    1 -> {
                        val n = ((bytes[0] and 0xF) shl 8) or bytes[1]
                        val buf = bytes.drop(2).toMutableList()
                        expectLen[id] = n; open[id] = buf; msgs += id to buf
                    }
                    2 -> open[id]?.addAll(bytes.drop(1))
                    else -> {}
                }
            }
            for ((id, buf) in msgs) expectLen[id]?.let { n -> while (buf.size > n) buf.removeAt(buf.size - 1) }
            val mine = if (rax != null) msgs.filter { it.first == rax } else msgs
            if (mine.isEmpty()) {
                if (errorText != null && msgs.isEmpty()) return ObdResponse.Error(errorText)
                return ObdResponse.NoData
            }
            val pos = mine.lastOrNull { it.second.isNotEmpty() && it.second[0] == svc + 0x40 }
            if (pos != null) {
                val p = pos.second
                val echo = when (svc) { 0x22 -> 3; 0x01, 0x09 -> 2; else -> 1 }
                if (p.size < echo) return ObdResponse.Error("short reply")
                val reqBytes = req.hexToBytes().map { it.toInt() and 0xFF }
                for (k in 1 until echo) if (p[k] != reqBytes[k]) return ObdResponse.Error("echo mismatch")
                return ObdResponse.Positive(p.drop(echo).map { it.toByte() }.toByteArray(), pos.first)
            }
            val neg = mine.lastOrNull { it.second.size >= 3 && it.second[0] == 0x7F }
            if (neg != null) return ObdResponse.Negative(neg.second[1], neg.second[2])
            return ObdResponse.Error("unexpected: ${mine.last().second.joinToString(" ") { "%02X".format(it) }}")
        }
    }
}
