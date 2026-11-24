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
import uk.co.ashunt.carmonitor.core.SweepHits
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
    /** Persistent copy of the log (survives app restarts), set by the service/activity. */
    @Volatile var logFile: java.io.File? = null
    @Volatile var state = "stopped"
    @Volatile var detail = ""
    @Volatile var mqtt = false
    @Volatile var live: Map<String, String> = emptyMap()
    private val logLines = ArrayDeque<String>()
    fun log(s: String) = synchronized(logLines) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.UK).format(Date()) + " " + s
        logLines.addLast(line)
        while (logLines.size > 200) logLines.removeFirst()
        logFile?.let { f ->
            try {
                if (f.length() > 512_000) f.renameTo(java.io.File(f.parentFile, "app-log.1.txt"))
                f.appendText(SimpleDateFormat("yyyy-MM-dd ", Locale.UK).format(Date()) + line + "\n")
            } catch (_: Exception) {}
        }
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
        const val ACTION_CAR_OFF = "car_off"
        /** Vehicle/topics changed in the UI: end the current session and start again with the new settings. */
        const val ACTION_RELOAD = "reload"
        private const val CAR_OFF_GRACE_MS = 10_000L
        const val EXTRA_SWEEP = "sweep"
        private const val CHANNEL = "obd"
        private const val NOTE_ID = 1

        fun start(ctx: Context, action: String, sweep: Boolean = false) {
            ctx.startForegroundService(Intent(ctx, ObdService::class.java).setAction(action).putExtra(EXTRA_SWEEP, sweep))
        }
    }

    @Volatile private var running = false
    @Volatile private var reloadRequested = false
    @Volatile private var discoverRequested: Boolean? = null   // null = no, false = quick, true = with sweep
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Status.logFile = File(filesDir, "app-log.txt")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAR_OFF) {
            if (worker?.isAlive == true) carOffAt = System.currentTimeMillis()
            return START_STICKY
        }
        if (intent?.action == ACTION_RELOAD) {
            if (worker?.isAlive == true) { reloadRequested = true; poke() }
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            running = false
            poke()
            worker?.interrupt()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Status.state = "stopped"
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTE_ID, note("Waiting for the car…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (intent?.action == ACTION_DISCOVER) discoverRequested = intent.getBooleanExtra(EXTRA_SWEEP, false)
        if (intent?.action == ACTION_LOG) { carOffAt = 0L; parked = false; poke() }   // car Bluetooth connected: dial now
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

    private fun loop() = try { while (running) loopInner() } catch (e: Throwable) {
        Status.log("worker stopped: $e"); Status.state = "error: ${e.javaClass.simpleName}"
    }

    private fun loopInner() {
        val prefs = Prefs(this)
        val scheme = prefs.scheme()
        val mqtt = prefs.uplink(scheme.status) { Status.log(it) }
        try {
            while (running) {
                // Settings changed: return so loop() rebuilds prefs, topics and the MQTT link.
                if (reloadRequested) { reloadRequested = false; Status.log("vehicle/settings changed, restarting session"); return }
                val addr = prefs.deviceAddress
                if (addr == null) { setState("no dongle selected", ""); sleep(5000); continue }
                // The car's Bluetooth went away while we weren't logging (e.g. the ECU-silence watchdog had
                // already released the dongle): park instead of re-dialling a dongle that's switched off.
                val off = carOffAt
                if (!parked && off != 0L && System.currentTimeMillis() - off >= CAR_OFF_GRACE_MS &&
                    prefs.carDevices.isNotEmpty() && prefs.btAutostart) {
                    Status.log("car Bluetooth gone, parking until it reconnects"); parked = true; carOffAt = 0L; continue
                }
                if (parked && prefs.btAutostart && prefs.carDevices.isNotEmpty()) {
                    setState("parked", "waiting for the car's Bluetooth"); updateNote("Parked: waiting for the car")
                    sleep(30 * 60_000L); continue          // a car-Bluetooth connect pokes us awake
                }
                if (!prefs.hasSignalset()) { setState("vehicle signalset not downloaded", "choose the vehicle again"); sleep(10_000); continue }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    setState("needs Bluetooth permission", ""); sleep(5000); continue
                }
                setState("waiting for the car", "dongle $addr")
                val link = try { BtSpp.open(this, addr) } catch (e: Exception) {
                    // Never let a connect failure (incl. SecurityException) kill the service:
                    // START_STICKY would restart it straight into the same crash.
                    Status.detail = "dongle not reachable (${e.message})"; Status.log("connect: $e"); sleep(20_000); continue
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
                    // A profile with nothing supported (e.g. saved while the ECU was asleep) would poll
                    // nothing forever: treat it as missing and discover again.
                    if (profile != null && profile.results.values.none { it.support.name == "SUPPORTED" }) {
                        Status.log("saved profile has no supported signals, re-discovering"); profile = null
                    }
                    val oldSweep = profile?.sweepHits ?: prefs.profile()?.sweepHits ?: emptyList()
                    if (profile != null && profile.isStale) {
                        Status.log("profile from an older version, re-discovering (sweep hits kept)"); profile = null
                    }
                    if (want != null || profile == null) {
                        discoverRequested = null
                        val sweep = if (want == true) prefs.sweepRanges() else emptyList()
                        setState(if (sweep.isEmpty()) "discovering" else "discovering + sweep", "")
                        profile = Discoverer(elm, lib, DiscoverOptions(yearOverride = prefs.modelYear, sweep = sweep,
                            vehicleRepo = prefs.vehicleRepo.ifBlank { null }, signalsetFile = prefs.signalsetFile),
                            progress = { d, t, l -> Status.detail = "$d / $t  $l"; if (d % 25 == 0) updateNote("Discovering $d/$t") },
                            cancelled = { !running || reloadRequested }).run()
                        if (reloadRequested) continue        // half a discovery for the old car: don't save it
                        val n = profile.results.values.count { it.support.name == "SUPPORTED" }
                        Status.log("discovery: $n supported, ${profile.sweepHits.size} sweep hits, VIN ${profile.vin}")
                        if (n == 0) {
                            // ECU silent (ignition off / engine module asleep): don't save, wait and retry.
                            setState("car not answering", "ignition on? retrying in 30 s")
                            Status.log("no ECU replies; profile NOT saved; retrying")
                            sleep(30_000); continue
                        }
                        if (oldSweep.isNotEmpty()) profile = profile.copy(sweepHits = SweepHits.merge(oldSweep, profile.sweepHits))
                        prefs.profileFile.writeText(profile.toJson())
                    }
                    // Merge this car's permanent sweep store both ways, so sweep results survive anything.
                    val vin = profile.vin
                    if (!vin.isNullOrBlank()) {
                        val merged = prefs.addSweep(vin, profile.sweepHits)
                        if (merged.size != profile.sweepHits.size) {
                            Status.log("restored ${merged.size - profile.sweepHits.size} sweep hits for $vin (${merged.size} total)")
                            profile = profile.copy(sweepHits = merged); prefs.profileFile.writeText(profile.toJson())
                        }
                    }
                    val csvFile = File(prefs.logDir, "drive-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date()) + ".csv")
                    csvFile.bufferedWriter().use { w ->
                        w.write("ts_ms,key,signal,value,raw\n")
                        val engine = LoggerEngine(elm, lib, profile, mqtt, scheme, prefs.loggerConfig(),
                            csv = csvRow(w), log = { Status.log(it) })
                        setState("logging", "${engine.pollCount} commands")
                        engine.tierCounts.let { (f, m, s) -> Status.log("poll tiers: $f fast (3 s), $m medium (15 s), $s slow (60 s)") }
                        updateNote("Logging ${engine.pollCount} signals")
                        var lastUi = 0L
                        var lastFuelSave = System.currentTimeMillis()
                        try { engine.run(stop = {
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
                            if (t - lastFuelSave > 60_000) { lastFuelSave = t; prefs.fuelState = engine.fuel.state() }
                            // Watchdog: no ECU reply for 60 s = car off or link silently dead. Release the
                            // dongle and go back to waiting, so the next ignition/CMU trigger dials afresh.
                            val silentMs = t - engine.lastGoodReplyMs
                            if (silentMs > 60_000) Status.log("no ECU replies for ${silentMs / 1000} s, releasing the dongle")
                            // A car-Bluetooth trigger while the ECU is silent also forces a fresh connection
                            if (pokedFlag && silentMs > 5_000) Status.log("car Bluetooth connected during a silent session, reconnecting")
                            val off = carOffAt
                            val carGone = off != 0L && t - off >= CAR_OFF_GRACE_MS
                            if (carGone) { Status.log("car Bluetooth gone for ${(t - off) / 1000} s, ending session"); carOffAt = 0L }
                            val restart = silentMs > 60_000 || (pokedFlag && silentMs > 5_000) || carGone
                            // Park (stop dialling) only when the car's Bluetooth actually went away; mere ECU
                            // silence while the car is still connected just reconnects.
                            if (carGone && prefs.carDevices.isNotEmpty() && prefs.btAutostart) parked = true
                            pokedFlag = false
                            !running || discoverRequested != null || restart || reloadRequested
                        }) } finally { prefs.fuelState = engine.fuel.state() }
                    }
                } catch (e: IOException) {
                    Status.log("link lost: ${e.message}")
                    if (e.message?.startsWith("no supported signals") == true) { prefs.profileFile.delete(); sleep(5000) }
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
    private val pokeLock = Object()
    @Volatile private var poked = false
    /** Wait up to [ms], returning early if poked (car Bluetooth connected) or stopped. */
    private fun sleep(ms: Long) {
        synchronized(pokeLock) {
            if (!poked && running) try { pokeLock.wait(ms) } catch (_: InterruptedException) { running = false }
            poked = false
        }
    }
    @Volatile private var pokedFlag = false
    /** When the car's Bluetooth disconnected (0 = not off). */
    @Volatile private var carOffAt = 0L
    /** Parked: don't dial the dongle until the car's Bluetooth connects again (saves both batteries). */
    @Volatile private var parked = false
    private fun poke() = synchronized(pokeLock) { poked = true; pokedFlag = true; pokeLock.notifyAll() }

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
