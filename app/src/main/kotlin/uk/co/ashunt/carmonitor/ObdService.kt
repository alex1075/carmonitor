package uk.co.ashunt.carmonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import uk.co.ashunt.carmonitor.core.CsvSink
import uk.co.ashunt.carmonitor.core.Decoded
import uk.co.ashunt.carmonitor.core.DiscoverOptions
import uk.co.ashunt.carmonitor.core.Discoverer
import uk.co.ashunt.carmonitor.core.Elm327
import uk.co.ashunt.carmonitor.core.LoggerEngine
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared, UI-readable state. The activity polls this once a second. */
object Status {
    @Volatile var state = "stopped"
    @Volatile var detail = ""
    @Volatile var mqtt = false
    @Volatile var live: Map<String, String> = emptyMap()
    private val logLines = ArrayDeque<String>()
    fun log(s: String) = synchronized(logLines) {
        logLines.addLast(SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date()) + " " + s)
        while (logLines.size > 200) logLines.removeFirst()
    }
    fun logText(): String = synchronized(logLines) { logLines.reversed().joinToString("\n") }
}

/**
 * Foreground service: waits for the dongle (car on), runs discovery if asked or if there's no
 * profile yet, then logs until the link drops, and goes back to waiting. Stays up between
 * drives with a quiet notification, so it starts logging as soon as the ignition comes on.
 */
class ObdService : Service() {

    companion object {
        const val ACTION_LOG = "log"
        const val ACTION_DISCOVER = "discover"
        const val ACTION_STOP = "stop"
        const val EXTRA_SWEEP = "sweep"
        private const val CHANNEL = "obd"
        private const val NOTE_ID = 1

        fun start(ctx: Context, action: String, sweep: Boolean = false) {
            ctx.startForegroundService(Intent(ctx, ObdService::class.java).setAction(action).putExtra(EXTRA_SWEEP, sweep))
        }
    }

    @Volatile private var running = false
    @Volatile private var discoverRequested: Boolean? = null   // null = no, false = quick, true = with sweep
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            worker?.interrupt()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Status.state = "stopped"
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTE_ID, note("Waiting for the car…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (intent?.action == ACTION_DISCOVER) discoverRequested = intent.getBooleanExtra(EXTRA_SWEEP, false)
        if (worker?.isAlive != true) {
            running = true
            worker = Thread({ loop() }, "obd-worker").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        releaseWake()
        super.onDestroy()
    }

    private fun loop() {
        val prefs = Prefs(this)
        val scheme = prefs.scheme()
        val mqtt = ReconnectingMqtt(prefs.brokerHost, prefs.brokerPort, prefs.brokerUser, prefs.brokerPass, scheme.status) {
            Status.log(it)
        }
        try {
            while (running) {
                val addr = prefs.deviceAddress
                if (addr == null) { setState("no dongle selected", ""); sleep(5000); continue }
                if (!prefs.hasSignalset()) { setState("vehicle signalset not downloaded", "choose the vehicle again"); sleep(10_000); continue }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    setState("needs Bluetooth permission", ""); sleep(5000); continue
                }
                setState("waiting for the car", "dongle $addr")
                val link = try { BtSpp.open(this, addr) } catch (e: IOException) {
                    Status.detail = "dongle not reachable (${e.message})"; sleep(20_000); continue
                }
                acquireWake()
                try {
                    val elm = Elm327(link, log = { Status.log(it) })
                    elm.init()
                    Status.log("connected, ${elm.voltage()}")
                    if (elm.adapterWarnings.isNotEmpty())
                        Status.log("adapter rejected ${elm.adapterWarnings.joinToString()} - likely a poor ELM327 clone, see README")
                    val lib = prefs.library()
                    var profile = prefs.profile()
                    val want = discoverRequested
                    if (want != null || profile == null) {
                        discoverRequested = null
                        val sweep = if (want == true) prefs.sweepRanges() else emptyList()
                        setState(if (sweep.isEmpty()) "discovering" else "discovering + sweep", "")
                        profile = Discoverer(elm, lib, DiscoverOptions(yearOverride = prefs.modelYear, sweep = sweep,
                            vehicleRepo = prefs.vehicleRepo.ifBlank { null }, signalsetFile = prefs.signalsetFile),
                            progress = { d, t, l -> Status.detail = "$d / $t  $l"; if (d % 25 == 0) updateNote("Discovering $d/$t") },
                            cancelled = { !running }).run()
                        prefs.profileFile.writeText(profile.toJson())
                        val n = profile.results.values.count { it.support.name == "SUPPORTED" }
                        Status.log("discovery: $n supported, ${profile.sweepHits.size} sweep hits, VIN ${profile.vin}")
                    }
                    val csvFile = File(prefs.logDir, "drive-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date()) + ".csv")
                    csvFile.bufferedWriter().use { w ->
                        w.write("ts_ms,key,signal,value,raw\n")
                        val engine = LoggerEngine(elm, lib, profile, mqtt, scheme, prefs.loggerConfig(),
                            csv = csvRow(w), log = { Status.log(it) })
                        setState("logging", "${engine.pollCount} commands")
                        updateNote("Logging ${engine.pollCount} signals")
                        var lastUi = 0L
                        engine.run(stop = {
                            val t = System.currentTimeMillis()
                            if (t - lastUi > 1000) {
                                lastUi = t
                                Status.mqtt = mqtt.connected
                                Status.live = engine.values.entries.associate { (k, v) ->
                                    k to when (val d = v.first) { is Decoded.Num -> "%.2f".format(Locale.UK, d.v); is Decoded.Text -> d.v }
                                }
                                Status.detail = if (engine.tripActive) "trip active" + (if (engine.regenActive) " · REGEN" else "") else "idle"
                                w.flush()
                            }
                            !running || discoverRequested != null
                        })
                    }
                } catch (e: IOException) {
                    Status.log("link lost: ${e.message}")
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Status.log("error: $e")
                    sleep(5000)
                } finally {
                    try { link.close() } catch (_: Exception) {}
                    releaseWake()
                    updateNote("Waiting for the car…")
                }
            }
        } finally {
            mqtt.close()
            Status.state = "stopped"
        }
    }

    private fun csvRow(w: BufferedWriter) = CsvSink { ts, key, sig, value, raw ->
        try { w.write("$ts,$key,$sig,$value,$raw\n") } catch (_: IOException) {}
    }

    private fun setState(s: String, d: String) { Status.state = s; Status.detail = d }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false } }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carmonitor:obd").apply { acquire(4 * 60 * 60 * 1000L) }
    }
    private fun releaseWake() { try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {} }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "OBD logger", NotificationManager.IMPORTANCE_LOW))
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ObdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("CarMonitor · " + Prefs(this).scheme().deviceName)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_car), "Stop", stop).build())
            .build()
    }

    private fun updateNote(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(text))
}
