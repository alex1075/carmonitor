package uk.co.ashunt.carmonitor.core

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A GATT characteristic as the serial-layout picker sees it (no Android types, so it is unit-testable). */
data class BleChar(val uuid: String, val notify: Boolean, val write: Boolean, val writeNoResponse: Boolean) {
    val writable get() = write || writeNoResponse
}

data class BleService(val uuid: String, val chars: List<BleChar>)

/** The characteristic to listen on and the one to write commands to, inside one service. */
data class BleSerialPick(val service: String, val notify: BleChar, val write: BleChar)

/**
 * How cheap "OBD2 BT" ELM327 clones expose a serial port over Bluetooth LE. They are not paired in Android's
 * settings: an app scans for the adapter, opens a GATT connection, subscribes to one characteristic for replies and
 * writes commands to another. The layout is the same one the iOS app uses (ios/CarMonitor/BleLink.swift).
 */
object BleSerial {
    /** (service, notify characteristic, write characteristic), most common clone layouts first. */
    val KNOWN = listOf(
        Triple("FFF0", "FFF1", "FFF2"),   // Vgate iCar Pro BLE and many clones
        Triple("FFE0", "FFE1", "FFE1"),   // HM-10 style: one characteristic does both
        Triple("18F0", "2AF0", "2AF1"),   // some Veepeak / Viecar clones
    )

    private val BASE = Regex("^0000([0-9A-F]{4})-0000-1000-8000-00805F9B34FB$")

    /** "0000FFF0-0000-1000-8000-00805f9b34fb" -> "FFF0"; any other UUID is returned upper-cased and unchanged. */
    fun short(uuid: String): String {
        val u = uuid.trim().uppercase()
        return BASE.matchEntire(u)?.groupValues?.get(1) ?: u
    }

    fun pick(services: List<BleService>): BleSerialPick? {
        for ((svc, nid, wid) in KNOWN) {
            val s = services.firstOrNull { short(it.uuid) == svc } ?: continue
            val n = s.chars.firstOrNull { short(it.uuid) == nid && it.notify } ?: continue
            val w = s.chars.firstOrNull { short(it.uuid) == wid && it.writable } ?: continue
            return BleSerialPick(svc, n, w)
        }
        // Unknown clone: any service with a notify characteristic and a writable one.
        for (s in services) {
            val n = s.chars.firstOrNull { it.notify } ?: continue
            val w = s.chars.firstOrNull { it.writable } ?: continue
            return BleSerialPick(short(s.uuid), n, w)
        }
        return null
    }

    /** Names OBD adapters usually advertise; used only to sort a scan so the dongle is at the top of the list. */
    private val OBD_WORDS = listOf("OBD", "ELM", "VLINK", "V-LINK", "OBDLINK", "ICAR", "VGATE", "VEEPEAK", "VIECAR", "KONNWEI", "LELINK")
    fun looksLikeObd(name: String?): Boolean = name != null && OBD_WORDS.any { name.uppercase().contains(it) }

    /** Largest piece to write at once: ATT payload is MTU - 3, but never below the 20 bytes every device accepts. */
    fun chunkSize(mtu: Int): Int = maxOf(20, mtu - 3)
}

/**
 * ElmLink over a "write bytes" function and a stream of notifications, the shape of a BLE serial adapter.
 * Android's GATT callbacks call [onData] and [fail]; [chunkSize] says how big one write may be (MTU - 3).
 * Replies arrive in pieces; reads wait for the '>' prompt like [StreamLink].
 */
class NotifyLink(
    private val chunkSize: () -> Int = { 20 },
    private val write: (ByteArray) -> Unit,
) : ElmLink {
    private val q = LinkedBlockingQueue<Int>()
    @Volatile private var dead: IOException? = null

    /** A notification from the adapter. */
    fun onData(bytes: ByteArray) { for (b in bytes) q.put(b.toInt() and 0xFF) }

    /** The link dropped (disconnect, GATT error): the next send or read throws [e]. */
    fun fail(e: IOException) { if (dead == null) dead = e }

    override fun send(line: String) {
        dead?.let { throw it }
        val data = (line + "\r").toByteArray(Charsets.US_ASCII)
        val n = maxOf(1, chunkSize())
        var i = 0
        while (i < data.size) {
            write(data.copyOfRange(i, minOf(i + n, data.size)))
            i += n
        }
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

    override fun close() { fail(IOException("link closed")) }
}
