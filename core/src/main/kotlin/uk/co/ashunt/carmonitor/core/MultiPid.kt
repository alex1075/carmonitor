package uk.co.ashunt.carmonitor.core

/**
 * J1979 lets one service 01 request ask for up to six PIDs ("010C0D05"). The reply repeats each PID
 * followed by its data, so splitting it needs each PID's data length. Only PIDs whose length is fixed
 * by the standard are batched; anything else is polled on its own.
 */
object MultiPid {
    const val MAX = 6

    /** Standard data lengths (bytes) of fixed-size service 01 PIDs (SAE J1979 / ISO 15031-5). */
    private val LEN: Map<Int, Int> = buildMap {
        for (p in listOf(0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0x11, 0x2C, 0x2D, 0x2E,
            0x2F, 0x30, 0x33, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x52, 0x5A, 0x5B, 0x5C, 0x61, 0x62, 0x8E)) put(p, 1)
        for (p in listOf(0x0C, 0x10, 0x1F, 0x21, 0x22, 0x23, 0x31, 0x3C, 0x3D, 0x3E, 0x3F, 0x42, 0x43, 0x44, 0x4D,
            0x4E, 0x59, 0x5D, 0x5E, 0x63)) put(p, 2)
    }

    fun batchable(c: Command): Boolean =
        c.service == "01" && c.pid.length == 2 && c.pid.toIntOrNull(16)?.let { it in LEN } == true

    /**
     * Split the payload of a positive reply to "01"+[pids] (with the first PID's echo already removed,
     * as [ObdResponse.Positive] gives it) into PID -> data. Stops at the first unknown byte, so padding
     * or a PID the ECU skipped just leaves that PID out.
     */
    fun split(pids: List<String>, data: ByteArray): Map<String, ByteArray> {
        val seq = ArrayList<Int>(data.size + 1)
        seq += pids.first().toInt(16)
        data.forEach { seq += it.toInt() and 0xFF }
        val wanted = pids.map { it.uppercase() }.toSet()
        val out = LinkedHashMap<String, ByteArray>()
        var i = 0
        while (i < seq.size) {
            val pid = seq[i]
            val n = LEN[pid] ?: break
            if (i + 1 + n > seq.size) break
            val id = "%02X".format(pid)
            if (id !in wanted || id in out) break
            out[id] = seq.subList(i + 1, i + 1 + n).map { it.toByte() }.toByteArray()
            i += 1 + n
        }
        return out
    }
}
