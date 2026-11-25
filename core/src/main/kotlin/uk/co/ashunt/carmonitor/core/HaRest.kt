package uk.co.ashunt.carmonitor.core

import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A Sink with a lifecycle and a health flag: MQTT broker or HA REST. */
interface Uplink : Sink, Closeable {
    val connected: Boolean
    /** Human-readable description of the last error, or null. */
    val lastError: String?
    /** Check the link while nothing is being sent, so [connected] means something when the car is off. */
    fun probe() {}
}

/**
 * Publishes through Home Assistant's REST API with a long-lived access token, calling the
 * `mqtt.publish` service. HA forwards to its own MQTT broker, so topics, discovery and entities
 * are identical to direct-MQTT mode, but only HTTPS to HA is needed (no broker port, no VPN).
 *
 * Messages are coalesced (latest payload per topic) and flushed every [flushMs] on a background
 * thread. Retained messages that fail are kept and retried; live ones are dropped.
 */
class HaRestSink(
    baseUrl: String,
    private val token: String,
    private val flushMs: Long = 3000,
    private val log: (String) -> Unit = {},
    private val post: (url: String, token: String, body: String) -> Int = ::httpPost,
) : Uplink {
    private val base = baseUrl.trim().trimEnd('/')
    private val pending = LinkedHashMap<String, Pair<String, Boolean>>()
    private val lock = Object()
    @Volatile private var running = true
    @Volatile override var connected = false; private set
    @Volatile override var lastError: String? = null; private set

    private val worker = Thread({
        while (running) {
            try { Thread.sleep(flushMs) } catch (_: InterruptedException) {}
            flush()
        }
    }, "ha-rest").apply { isDaemon = true; start() }

    override fun publish(topic: String, payload: String, retain: Boolean) {
        synchronized(lock) {
            // keep "retained" sticky if any queued version for this topic was retained
            val keepRetain = retain || (pending[topic]?.second ?: false)
            pending.remove(topic)
            pending[topic] = payload to keepRetain
        }
    }

    /** Send everything queued now. Returns the number of messages delivered. */
    fun flush(): Int {
        val batch = synchronized(lock) { LinkedHashMap(pending).also { pending.clear() } }
        var ok = 0
        for ((topic, pr) in batch) {
            val (payload, retain) = pr
            val body = MiniJson.write(linkedMapOf("topic" to topic, "payload" to payload, "retain" to retain))
            val code = try {
                post("$base/api/services/mqtt/publish", token, body)
            } catch (e: IOException) {
                -1.also { lastError = "HA unreachable: ${e.message}" }
            }
            if (code in 200..299) {
                ok++; connected = true; lastError = null
            } else {
                connected = false
                if (code >= 0) lastError = describe(code)
                if (retain) synchronized(lock) { if (topic !in pending) pending[topic] = pr }
                if (code == 401 || code == 403 || code == -1) {
                    // don't hammer a dead/unauthorised endpoint: requeue remaining retained and stop this round
                    val rest = batch.entries.dropWhile { it.key != topic }.drop(1)
                    synchronized(lock) { for ((t, p) in rest) if (p.second && t !in pending) pending[t] = p }
                    log("HA REST: ${lastError}")
                    break
                }
            }
        }
        return ok
    }

    override fun probe() {
        if (synchronized(lock) { pending.isNotEmpty() }) return   // the next flush will tell
        val err = check(base, token)
        connected = err == null; lastError = err
    }

    override fun close() {
        running = false
        worker.interrupt()
        try { flush() } catch (_: Exception) {}
    }

    companion object {
        fun describe(code: Int): String = when (code) {
            401 -> "HA rejected the token (401): create a new long-lived access token"
            403 -> "HA refused (403): token user lacks access"
            404 -> "HA URL wrong, or the MQTT integration isn't set up (404)"
            400 -> "HA rejected the request (400): is the MQTT integration configured?"
            in 500..599 -> "HA server error ($code)"
            else -> "HA returned HTTP $code"
        }

        /** GET /api/ with the token: null if OK, otherwise a reason. */
        fun check(baseUrl: String, token: String, get: (String, String) -> Int = ::httpGet): String? = try {
            when (val c = get(baseUrl.trim().trimEnd('/') + "/api/", token)) {
                in 200..299 -> null
                else -> describe(c)
            }
        } catch (e: IOException) {
            "Can't reach ${baseUrl.trim()}: ${e.message}"
        }

        fun httpPost(url: String, token: String, body: String): Int = http(url, token, "POST", body)
        fun httpGet(url: String, token: String): Int = http(url, token, "GET", null)

        private fun http(url: String, token: String, method: String, body: String?): Int {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Content-Type", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                try { (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } } catch (_: IOException) {}
                return code
            } finally {
                c.disconnect()
            }
        }
    }
}
