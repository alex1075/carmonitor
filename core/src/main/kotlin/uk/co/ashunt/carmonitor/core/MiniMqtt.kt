package uk.co.ashunt.carmonitor.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Minimal MQTT 3.1.1 publisher (QoS 0, retain, last-will, keep-alive). No dependencies,
 * so it runs unchanged on the JVM test harness and on Android.
 */
class MiniMqtt(
    private val host: String,
    private val port: Int = 1883,
    private val clientId: String,
    private val username: String? = null,
    private val password: String? = null,
    private val keepAliveSec: Int = 60,
    private val will: Pair<String, String>? = null,   // topic -> payload, retained
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val log: (String) -> Unit = {},
) : Closeable {

    private var sock: Socket? = null
    private var out: OutputStream? = null
    private var pinger: Thread? = null
    private var reader: Thread? = null
    @Volatile var connected = false; private set
    private val lock = Any()

    fun connect(timeoutMs: Int = 8000) {
        close()
        val s = socketFactory.createSocket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        val o = s.getOutputStream()
        val i = s.getInputStream()

        val vh = ByteArrayOutputStream()
        vh.write(str("MQTT")); vh.write(4)
        var flags = 0x02
        if (will != null) flags = flags or 0x04 or 0x20
        if (username != null) flags = flags or 0x80
        if (password != null) flags = flags or 0x40
        vh.write(flags); vh.write(keepAliveSec shr 8); vh.write(keepAliveSec and 0xFF)
        vh.write(str(clientId))
        will?.let { vh.write(str(it.first)); vh.write(bin(it.second.toByteArray())) }
        username?.let { vh.write(str(it)) }
        password?.let { vh.write(bin(it.toByteArray())) }
        o.write(packet(0x10, vh.toByteArray())); o.flush()

        val h = i.read(); val len = readLen(i)
        val body = ByteArray(len).also { readFully(i, it) }
        if (h != 0x20 || len != 2) throw IOException("bad CONNACK")
        val rc = body[1].toInt()
        if (rc != 0) throw IOException(REFUSED[rc] ?: "broker refused connection (rc=$rc)")

        s.soTimeout = 0
        sock = s; out = o; connected = true
        reader = Thread({
            try {
                while (true) {
                    val t = i.read(); if (t < 0) break
                    val n = readLen(i); readFully(i, ByteArray(n))   // PINGRESP etc.
                }
            } catch (_: IOException) {}
            connected = false
        }, "mqtt-reader").apply { isDaemon = true; start() }
        pinger = Thread({
            try {
                while (connected) {
                    Thread.sleep(keepAliveSec * 500L)
                    synchronized(lock) { out?.write(byteArrayOf(0xC0.toByte(), 0)); out?.flush() }
                }
            } catch (_: Exception) { connected = false }
        }, "mqtt-ping").apply { isDaemon = true; start() }
        log("mqtt connected to $host:$port")
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        val o = out ?: throw IOException("not connected")
        val vh = ByteArrayOutputStream()
        vh.write(str(topic)); vh.write(payload.toByteArray(Charsets.UTF_8))
        try {
            synchronized(lock) { o.write(packet(0x30 or (if (retain) 1 else 0), vh.toByteArray())); o.flush() }
        } catch (e: IOException) {
            connected = false; throw e
        }
    }

    override fun close() {
        connected = false
        try { synchronized(lock) { out?.write(byteArrayOf(0xE0.toByte(), 0)); out?.flush() } } catch (_: Exception) {}
        try { sock?.close() } catch (_: Exception) {}
        pinger?.interrupt(); sock = null; out = null
    }

    companion object {
        /** MQTT 3.1.1 CONNACK return codes, in words a user can act on. */
        val REFUSED = mapOf(
            1 to "broker refused: unsupported MQTT protocol version",
            2 to "broker refused the client id",
            3 to "broker unavailable",
            4 to "wrong MQTT username or password",
            5 to "not authorised: check the MQTT user (in HA: a Home Assistant user, Mosquitto add-on)",
        )
    }

    private fun str(s: String) = bin(s.toByteArray(Charsets.UTF_8))
    private fun bin(b: ByteArray) = byteArrayOf((b.size shr 8).toByte(), (b.size and 0xFF).toByte()) + b
    private fun packet(type: Int, body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream(); b.write(type)
        var x = body.size
        do { var d = x % 128; x /= 128; if (x > 0) d = d or 0x80; b.write(d) } while (x > 0)
        b.write(body); return b.toByteArray()
    }
    private fun readLen(i: InputStream): Int {
        var mult = 1; var v = 0
        while (true) {
            val d = i.read(); if (d < 0) throw IOException("eof")
            v += (d and 127) * mult
            if (d and 128 == 0) return v
            mult *= 128
        }
    }
    private fun readFully(i: InputStream, b: ByteArray) {
        var o = 0
        while (o < b.size) { val n = i.read(b, o, b.size - o); if (n < 0) throw IOException("eof"); o += n }
    }
}
