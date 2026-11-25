package uk.co.ashunt.carmonitor.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uplink to the CarMonitor Home Assistant integration's webhook. The drive CSVs are the queue: every
 * [flushMs] it sends the rows of each drive file past what that file already had accepted, so a dropout
 * only delays rows (they keep their own timestamps) and past drives can be uploaded the same way. How far
 * each file got is kept in `.sent` next to the drives. Names, units and expiry come from the discovery
 * configs LoggerEngine already publishes; derived values (trip, fuel, DTCs) from its other topics.
 */
class WebhookUplink(
    private val url: String,
    private val dir: File,
    private val base: String,
    private val uploadPast: Boolean,
    private val flushMs: Long = 3000,
    private val log: (String) -> Unit = {},
    private val post: (url: String, body: String) -> Int = ::httpPost,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** POST returning the reply body on 2xx, else null: asks what the integration already has. */
    private val ask: (url: String, body: String) -> String? = ::httpAsk,
    worker: Boolean = true,
) : Uplink {
    @Volatile override var connected = false; private set
    @Volatile override var lastError: String? = null; private set
    private val lock = Object()
    private val defs = LinkedHashMap<String, Map<String, Any?>>()   // not yet accepted
    private val extra = LinkedHashMap<String, Pair<Long, String>>() // derived values, latest per id
    private var status: String? = null
    private val sentFile = File(dir, ".sent")
    private val baselineFile = File(dir, ".sent-since")
    @Volatile private var running = true
    private var lastFail = ""
    @Volatile private var lastOk = 0L

    private val thread = if (!worker) null else Thread({
        while (running) {
            try { Thread.sleep(flushMs) } catch (_: InterruptedException) {}
            try { flush() } catch (e: Exception) { lastError = e.message }
        }
    }, "webhook").apply { isDaemon = true; start() }

    override fun publish(topic: String, payload: String, retain: Boolean) {
        synchronized(lock) {
            when {
                topic.startsWith("homeassistant/") && topic.endsWith("/config") -> {
                    val cfg = try { MiniJson.obj(payload) } catch (_: Exception) { return }
                    val st = cfg["state_topic"] as? String ?: return
                    val id = idFor(st) ?: return
                    defs[id] = linkedMapOf("name" to cfg["name"], "unit" to cfg["unit_of_measurement"],
                        "expire" to cfg["expire_after"].asInt()?.takeIf { it in 10..86400 }) +
                        (if (cfg["unit_of_measurement"] == null && cfg["state_class"] == null) mapOf("text" to true) else emptyMap())
                }
                topic == "$base/logger/status" -> status = payload
                topic.startsWith("$base/sig/") -> {}   // every reading is in the drive CSV already
                // JSON payloads (the MQTT device tracker) have no meaning here: position comes from the CSV's GPS rows.
                else -> idFor(topic)?.let { if (payload.length <= 255 && !payload.startsWith("{")) extra[it] = now() to payload }
            }
        }
    }

    /** Signal id for a topic under [base]: sig/RPM -> RPM, trip/distance_km -> trip_distance_km. */
    internal fun idFor(topic: String): String? {
        if (!topic.startsWith("$base/")) return null
        val rest = topic.removePrefix("$base/").removePrefix("sig/")
        val id = rest.replace(Regex("[^A-Za-z0-9_]"), "_").take(64)
        return id.ifEmpty { null }
    }

    private fun offsets(): MutableMap<String, Long> =
        if (!sentFile.exists()) HashMap() else sentFile.readLines().mapNotNull { l ->
            val p = l.split('\t'); if (p.size == 2) p[1].toLongOrNull()?.let { p[0] to it } else null
        }.toMap(HashMap())

    private fun saveOffsets(o: Map<String, Long>) {
        val tmp = File(dir, ".sent.tmp")
        tmp.writeText(o.entries.joinToString("") { it.key + "\t" + it.value + "\n" })
        tmp.renameTo(sentFile)
    }

    /** Drive files with rows not yet accepted, oldest first. Files from before this mode was first used count only with [uploadPast]. */
    fun pending(): List<File> {
        if (!baselineFile.exists()) baselineFile.writeText(now().toString())
        val since = baselineFile.readText().trim().toLongOrNull() ?: 0L
        val o = offsets()
        return (dir.listFiles { f -> f.name.startsWith("drive-") && f.name.endsWith(".csv") } ?: emptyArray())
            .filter { (uploadPast || it.lastModified() >= since) && it.length() > (o[it.name] ?: 0L) }
            .sortedBy { it.name }
    }

    /** Send what is pending. Returns the number of rows accepted. */
    @Synchronized
    fun flush(): Int {
        var accepted = 0
        val files = pending()
        val latest = (dir.listFiles { f -> f.name.startsWith("drive-") && f.name.endsWith(".csv") } ?: emptyArray()).maxByOrNull { it.name }
        val o = offsets()
        var rounds = 0
        for (f in files) {
            // A drive never sent from this phone may be in HA already (reinstall, another phone, a lost .sent):
            // drives are keyed by their date-time, so ask before sending it again.
            if (o[f.name] == null && alreadyThere(f)) { o[f.name] = f.length(); saveOffsets(o); continue }
            while (rounds++ < 40) {
                val off = o[f.name] ?: 0L
                val (rows, used) = readRows(f, off, MAX_ROWS)
                if (used == 0L) break
                val isLatest = f == latest
                if (!send(driveId(f), rows, isLatest)) return accepted
                accepted += rows.size
                o[f.name] = off + used; saveOffsets(o)
            }
        }
        // Nothing left in the files: still deliver status, names and derived values.
        val idle = synchronized(lock) { status != null || defs.isNotEmpty() || extra.isNotEmpty() }
        if (idle) send(latest?.let { driveId(it) } ?: "live", emptyList(), true)
        return accepted
    }

    private fun alreadyThere(f: File): Boolean {
        val last = lastTs(f) ?: return false
        val reply = try { ask(url, MiniJson.write(mapOf("drive" to driveId(f), "rows" to emptyList<Any>()))) } catch (_: IOException) { null } ?: return false
        val end = try { MiniJson.obj(reply)["have"].asMap()["end"].asDouble()?.toLong() } catch (_: Exception) { null } ?: return false
        if (end >= last) log("drive ${driveId(f)} is already in Home Assistant, not sending it again")
        return end >= last
    }

    private fun send(drive: String, rows: List<List<Any>>, withState: Boolean): Boolean {
        val (st, d, x) = synchronized(lock) {
            if (withState) Triple(status, LinkedHashMap(defs), LinkedHashMap(extra)) else Triple(null, emptyMap<String, Map<String, Any?>>(), emptyMap<String, Pair<Long, String>>())
        }
        val all = rows + x.map { (id, v) -> listOf(v.first, id, v.second.toDoubleOrNull() ?: v.second) }
        val body = linkedMapOf<String, Any?>("drive" to drive, "rows" to all)
        if (st != null) body["status"] = st
        if (d.isNotEmpty()) body["defs"] = d
        val code = try { post(url, MiniJson.write(body)) } catch (e: IOException) { -1.also { lastError = "unreachable: ${e.message}" } }
        if (code in 200..299) {
            connected = true; lastError = null; lastFail = ""; lastOk = now()
            synchronized(lock) {
                if (st != null && status == st) status = null
                for (k in d.keys) if (defs[k] == d[k]) defs.remove(k)
                for ((k, v) in x) if (extra[k] == v) extra.remove(k)
            }
            return true
        }
        connected = false
        if (code >= 0) lastError = when (code) { 404 -> "webhook not found (404): check the URL"; 400 -> "integration rejected the data (400)"; else -> "HTTP $code" }
        if (lastError != lastFail) { lastFail = lastError ?: ""; log("integration: $lastError") }
        return false
    }

    /** An empty post (no rows, no status) when nothing went out for a minute: the car stays offline in HA. */
    override fun probe() {
        if (now() - lastOk < 60_000) return
        synchronized(this) { send("live", emptyList(), false) }
    }

    override fun close() {
        running = false
        thread?.interrupt()
        try { flush() } catch (_: Exception) {}
    }

    companion object {
        const val MAX_ROWS = 5000
        private val SIG = Regex("^[A-Za-z0-9_]{1,64}$")

        fun driveId(f: File) = f.name.removePrefix("drive-").removeSuffix(".csv").replace(Regex("[^A-Za-z0-9_.-]"), "_").take(40)

        /** Rows from byte [off], whole lines only: ([ts, signal, value] rows, bytes consumed). */
        fun readRows(f: File, off: Long, max: Int): Pair<List<List<Any>>, Long> {
            val len = f.length()
            if (len <= off) return emptyList<List<Any>>() to 0L
            val buf = ByteArray(minOf(len - off, 1L shl 20).toInt())
            RandomAccessFile(f, "r").use { it.seek(off); it.readFully(buf) }
            val rows = ArrayList<List<Any>>()
            var used = 0L; var start = 0
            for (i in buf.indices) {
                if (buf[i] != '\n'.code.toByte()) continue
                val line = String(buf, start, i - start, Charsets.UTF_8)
                start = i + 1; used = start.toLong()
                val p = line.split(',', limit = 5)
                val ts = p.getOrNull(0)?.toLongOrNull()
                if (p.size >= 4 && ts != null && p[2] != "raw" && SIG.matches(p[2]) && p[3].isNotEmpty()) {
                    rows += listOf(ts, p[2], p[3].toDoubleOrNull() ?: p[3].take(255))
                }
                if (rows.size >= max) break
            }
            return rows to used
        }

        /** The newest timestamp in the file's last few KB (GPS and OBD rows interleave slightly out of order). */
        fun lastTs(f: File): Long? {
            val len = f.length(); if (len == 0L) return null
            val n = minOf(len, 8192L).toInt()
            val buf = ByteArray(n)
            RandomAccessFile(f, "r").use { it.seek(len - n); it.readFully(buf) }
            return String(buf, Charsets.UTF_8).split('\n').dropLast(1).mapNotNull { it.substringBefore(',').toLongOrNull() }.maxOrNull()
        }

        fun httpAsk(url: String, body: String): String? {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 30_000
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (c.responseCode !in 200..299) return null
                return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally { c.disconnect() }
        }

        fun httpPost(url: String, body: String): Int {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 30_000
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                try { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } } catch (_: IOException) {}
                return code
            } finally { c.disconnect() }
        }
    }
}
