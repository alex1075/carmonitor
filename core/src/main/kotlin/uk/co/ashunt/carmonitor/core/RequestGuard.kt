package uk.co.ashunt.carmonitor.core

/**
 * Hard allowlist for everything written to the ELM327. Every byte that leaves the
 * app goes through [check]; anything not explicitly read-only is refused.
 *
 * Allowed OBD/UDS services (all read-only):
 *   01 current data, 03 stored DTCs, 07 pending DTCs, 09 vehicle info,
 *   22 ReadDataByIdentifier (exactly one 2-byte DID).
 *
 * Deliberately refused, among others: 04 (clear DTCs), 10 (session control),
 * 11 (ECU reset), 14 (clear diagnostic info), 27 (security access),
 * 2E (write by identifier), 2F (IO control), 31 (routine control, e.g. forced regen),
 * 34-37 (download/upload), 3B, 3D (write memory), 85 (DTC setting control).
 */
object RequestGuard {

    class Refused(msg: String) : SecurityException(msg)

    private val SERVICE_LEN = mapOf(
        "01" to setOf(1),      // 01 PP
        "03" to setOf(0),
        "07" to setOf(0),
        "09" to setOf(1),      // 09 PP
        "22" to setOf(2),      // 22 DDDD
    )

    /** AT commands the app is allowed to send. Value = regex for the argument. */
    private val AT_ALLOWED = listOf(
        Regex("^Z$"), Regex("^WS$"), Regex("^D$"), Regex("^I$"), Regex("^RV$"), Regex("^DP$"), Regex("^DPN$"),
        Regex("^E[01]$"), Regex("^L[01]$"), Regex("^S[01]$"), Regex("^H[01]$"),
        Regex("^CAF[01]$"), Regex("^AT[012]$"), Regex("^ST[0-9A-F]{2}$"),
        Regex("^SP[0-9A-C]$"), Regex("^TP[0-9A-C]$"),
        Regex("^SH[0-9A-F]{3}$"),          // 11-bit CAN header only
        Regex("^CRA[0-9A-F]{3}$"), Regex("^CRA$"),
        Regex("^CFC[01]$"),
    )

    fun normalise(cmd: String): String = cmd.replace(" ", "").uppercase().trim()

    /** Throws [Refused] unless [raw] is an allowed AT command or read-only OBD request. */
    fun check(raw: String) {
        val c = normalise(raw)
        if (c.isEmpty()) throw Refused("empty command")
        if (c.any { it == '\r' || it == '\n' }) throw Refused("embedded newline")
        if (c.startsWith("AT")) {
            val arg = c.substring(2)
            if (AT_ALLOWED.none { it.matches(arg) }) throw Refused("AT command not allowed: $c")
            return
        }
        if (!c.all { it in "0123456789ABCDEF" } || c.length % 2 != 0)
            throw Refused("not a hex request: $c")
        val svc = c.substring(0, 2)
        val payloadBytes = (c.length - 2) / 2
        val lens = SERVICE_LEN[svc] ?: throw Refused("service $svc is not on the read-only allowlist")
        if (payloadBytes !in lens) throw Refused("bad length for service $svc: $c")
    }

    fun isAllowed(raw: String): Boolean = try { check(raw); true } catch (_: Refused) { false }
}
