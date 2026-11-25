package uk.co.ashunt.carmonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import uk.co.ashunt.carmonitor.core.ElmLink
import uk.co.ashunt.carmonitor.core.MiniMqtt
import uk.co.ashunt.carmonitor.core.HaRestSink
import uk.co.ashunt.carmonitor.core.Uplink
import uk.co.ashunt.carmonitor.core.WebhookUplink
import uk.co.ashunt.carmonitor.core.StreamLink
import java.io.IOException
import java.util.UUID

/** Classic Bluetooth SPP (RFCOMM) link to an ELM327 dongle. */
object BtSpp {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")   // caller checks BLUETOOTH_CONNECT
    fun open(ctx: Context, address: String): ElmLink {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("no Bluetooth adapter")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val dev = adapter.getRemoteDevice(address)
        // No cancelDiscovery(): we never scan, and on Android 12+ it needs BLUETOOTH_SCAN.
        var sock: BluetoothSocket? = null
        var last: IOException? = null
        for (secure in listOf(true, false)) {
            try {
                sock = if (secure) dev.createRfcommSocketToServiceRecord(SPP) else dev.createInsecureRfcommSocketToServiceRecord(SPP)
                sock.connect()
                break
            } catch (e: SecurityException) {
                try { sock?.close() } catch (_: IOException) {}
                throw IOException("Bluetooth permission missing: ${e.message}")
            } catch (e: IOException) {
                last = e
                try { sock?.close() } catch (_: IOException) {}
                sock = null
            }
        }
        val s = sock ?: throw last ?: IOException("connect failed")
        val link = StreamLink(s.inputStream, s.outputStream)
        return object : ElmLink by link {
            override fun close() { link.close(); try { s.close() } catch (_: IOException) {} }
        }
    }
}

/**
 * Sink that tolerates a flaky uplink (hotspot drops, tunnels down). Live values are dropped
 * while offline; retained ones (trip summaries, DTCs, discovery) are kept, latest per topic,
 * and flushed on reconnect.
 */
class ReconnectingMqtt(
    private val host: String, private val port: Int,
    private val user: String?, private val pass: String?,
    private val willTopic: String,
    private val log: (String) -> Unit,
) : Uplink {
    private var client: MiniMqtt? = null
    private var lastAttempt = 0L
    private val retained = LinkedHashMap<String, String>()
    override val connected get() = client?.connected == true
    @Volatile override var lastError: String? = null; private set

    @Synchronized
    override fun publish(topic: String, payload: String, retain: Boolean) {
        if (retain) retained[topic] = payload
        if (!ensure()) return
        try {
            client!!.publish(topic, payload, retain)
        } catch (e: IOException) {
            log("mqtt: ${e.message}")
        }
    }

    private fun ensure(): Boolean {
        if (client?.connected == true) return true
        if (host.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now - lastAttempt < 15_000) return false
        lastAttempt = now
        return try {
            val c = MiniMqtt(host, port, "carmonitor-" + android.os.Build.MODEL.replace(' ', '-'),
                user?.ifBlank { null }, pass?.ifBlank { null }, will = willTopic to "offline", log = log)
            c.connect()
            client = c
            for ((t, p) in retained) c.publish(t, p, true)
            true
        } catch (e: Exception) {
            lastError = e.message
            log("mqtt connect failed: ${e.message}")
            false
        }
    }

    @Synchronized
    override fun close() { client?.close(); client = null }
}

/** Build the uplink chosen in settings. */
fun Prefs.uplink(statusTopic: String, log: (String) -> Unit): Uplink = when (connMode) {
    "ha" -> HaRestSink(haUrl, haToken, log = log)
    "integration" -> WebhookUplink(webhookUrl, logDir, statusTopic.removeSuffix("/logger/status"), uploadPast, log = log)
    else -> ReconnectingMqtt(brokerHost, brokerPort, brokerUser, brokerPass, statusTopic, log)
}
