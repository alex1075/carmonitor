package uk.co.ashunt.carmonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import uk.co.ashunt.carmonitor.core.BleChar
import uk.co.ashunt.carmonitor.core.BleSerial
import uk.co.ashunt.carmonitor.core.BleService
import uk.co.ashunt.carmonitor.core.ElmLink
import uk.co.ashunt.carmonitor.core.NotifyLink
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * ELM327 over Bluetooth LE (GATT), the way the cheap "OBD2 BT" clones and Car Scanner talk to them.
 * No pairing and no bond: scan for the adapter, connect, find its serial characteristics (FFF0/FFE0/18F0, else
 * any notify + write pair), subscribe, and write commands. The same layout the iOS app uses.
 *
 * Android's GATT stack is flaky on first connects (status 133 and friends), so [open] retries the whole
 * connect-and-discover sequence a few times with a fresh [BluetoothGatt] each time.
 */
object BleGatt {
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val ATTEMPTS = 4
    private const val WANT_MTU = 247

    private class Event(val kind: String, val status: Int = 0)

    @SuppressLint("MissingPermission")   // caller checks BLUETOOTH_CONNECT
    fun open(ctx: Context, address: String, log: (String) -> Unit = {}): ElmLink {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("no Bluetooth adapter")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")
        val dev = adapter.getRemoteDevice(address)
        var last: IOException? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                return connectOnce(ctx, dev, log)
            } catch (e: SecurityException) {
                throw IOException("Bluetooth permission missing: ${e.message}")
            } catch (e: IOException) {
                last = e
                log("BLE connect attempt $attempt/$ATTEMPTS failed: ${e.message}")
                if (attempt < ATTEMPTS) try { Thread.sleep(500L * attempt) } catch (_: InterruptedException) { throw IOException("interrupted") }
            }
        }
        throw last ?: IOException("BLE connect failed")
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")   // pre-33 GATT write APIs (minSdk 31)
    private fun connectOnce(ctx: Context, dev: BluetoothDevice, log: (String) -> Unit): ElmLink {
        val events = LinkedBlockingQueue<Event>()
        val mtu = java.util.concurrent.atomic.AtomicInteger(23)
        lateinit var gatt: BluetoothGatt
        var pickedNotify: BluetoothGattCharacteristic? = null
        var writeChar: BluetoothGattCharacteristic? = null
        val writeLock = Any()
        var link: NotifyLink? = null

        fun deliver(c: BluetoothGattCharacteristic, v: ByteArray?) {
            if (v != null && c.uuid == pickedNotify?.uuid) link?.onData(v)
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) events.put(Event("conn"))
                else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != 0) {
                    link?.fail(IOException("adapter disconnected (GATT status $status)"))
                    events.put(Event("disc", status))
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { if (status == 0) mtu.set(m); events.put(Event("mtu", status)) }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.put(Event("svc", status)) }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.put(Event("desc", status)) }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.put(Event("write", status)) }
            // Android 13+ delivers the value here; 12 (our minSdk) only calls the deprecated overload below.
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = deliver(c, value)
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) = deliver(c, c.value)
        }

        /** Wait for an event of [kind]; a disconnect while waiting is an error. Other events are skipped. */
        fun await(kind: String, ms: Long, mustSucceed: Boolean = true): Boolean {
            val end = System.currentTimeMillis() + ms
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) { if (mustSucceed) throw IOException("timed out waiting for $kind"); return false }
                val e = events.poll(left, TimeUnit.MILLISECONDS) ?: continue
                if (e.kind == "disc") throw IOException("adapter disconnected (GATT status ${e.status})")
                if (e.kind == kind) {
                    if (e.status != 0 && mustSucceed) throw IOException("$kind failed (GATT status ${e.status})")
                    return e.status == 0
                }
            }
        }

        fun fail(msg: String, e: Throwable? = null): Nothing {
            try { gatt.disconnect() } catch (_: Exception) {}
            try { gatt.close() } catch (_: Exception) {}
            throw if (e is IOException) e else IOException(msg)
        }

        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        try {
            await("conn", 15_000)
            // A bigger MTU means fewer writes per command; many clones ignore the request, so don't insist.
            if (gatt.requestMtu(WANT_MTU)) await("mtu", 3_000, mustSucceed = false)
            if (!gatt.discoverServices()) throw IOException("discoverServices refused")
            await("svc", 10_000)

            val services = gatt.services.map { s ->
                BleService(s.uuid.toString(), s.characteristics.map { c ->
                    val p = c.properties
                    BleChar(c.uuid.toString(),
                        notify = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                        write = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                        writeNoResponse = p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                })
            }
            val pick = BleSerial.pick(services)
                ?: throw IOException("no serial service on this device (services: " + services.joinToString { BleSerial.short(it.uuid) } + ")")
            val svc = gatt.services.first { BleSerial.short(it.uuid.toString()) == pick.service || it.uuid.toString().equals(pick.service, true) }
            val n = svc.characteristics.first { it.uuid.toString().equals(pick.notify.uuid, true) }
            val w = svc.characteristics.first { it.uuid.toString().equals(pick.write.uuid, true) }
            pickedNotify = n; writeChar = w
            log("BLE serial service ${pick.service}: notify ${BleSerial.short(n.uuid.toString())}, write ${BleSerial.short(w.uuid.toString())}, MTU ${mtu.get()}")

            link = NotifyLink({ BleSerial.chunkSize(mtu.get()) }) { chunk ->
                synchronized(writeLock) {
                    val noResp = w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                    val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    events.removeIf { it.kind == "write" }
                    val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(w, chunk, type) == BluetoothStatusCodes.SUCCESS
                    else run { w.writeType = type; w.value = chunk; gatt.writeCharacteristic(w) }
                    if (!ok) throw IOException("BLE write refused")
                    // Write-without-response may or may not call back; with-response must, or the link is stuck.
                    await("write", 2_000, mustSucceed = !noResp)
                }
            }

            // Subscribe: local flag plus the CCCD descriptor on the adapter. Without the descriptor write, no replies.
            if (!gatt.setCharacteristicNotification(n, true)) throw IOException("couldn't enable notifications")
            val cccd = n.getDescriptor(CCCD)
            if (cccd != null) {
                val value = if (n.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                else run { cccd.value = value; gatt.writeDescriptor(cccd) }
                if (!ok) throw IOException("couldn't write the notification descriptor")
                await("desc", 5_000)
            }
            // Some clones expose a second notify characteristic that must be subscribed too before they answer
            // (seen in a published recipe for dual-mode dongles). Best effort, replies still come from the one picked.
            for (extra in svc.characteristics) {
                if (extra.uuid == n.uuid || extra.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) continue
                val d = extra.getDescriptor(CCCD) ?: continue
                try {
                    gatt.setCharacteristicNotification(extra, true)
                    val ok = if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    else run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(d) }
                    if (ok) await("desc", 2_000, mustSucceed = false)
                } catch (_: Exception) {}
            }
        } catch (e: IOException) {
            fail(e.message ?: "BLE connect failed", e)
        } catch (e: RuntimeException) {
            fail("BLE connect failed: ${e.message}")
        }

        val l = link!!
        return object : ElmLink by l {
            override fun close() {
                l.close()
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
            }
        }
    }
}
