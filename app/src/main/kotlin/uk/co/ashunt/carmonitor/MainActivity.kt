package uk.co.ashunt.carmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import uk.co.ashunt.carmonitor.core.BleSerial
import uk.co.ashunt.carmonitor.core.ObdbCatalog
import uk.co.ashunt.carmonitor.core.VehicleRef
import uk.co.ashunt.carmonitor.core.Wmi
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import uk.co.ashunt.carmonitor.core.Units
import uk.co.ashunt.carmonitor.ui.Actions
import uk.co.ashunt.carmonitor.ui.CarMonitorApp
import uk.co.ashunt.carmonitor.ui.CarMonitorTheme
import uk.co.ashunt.carmonitor.ui.UiModel
import java.io.File

/** Material 3 (Compose) screens; dialogs, Bluetooth, SAF and the HA test stay here. */
class MainActivity : ComponentActivity(), Actions {

    companion object {
        const val ACTION_START_FROM_NOTIFICATION = "uk.co.ashunt.carmonitor.START"
        const val EXTRA_START_KEY = "start_key"

        /** A per-install secret: only our own notification knows it, so other apps can't start the logger. */
        fun startKey(ctx: android.content.Context): String {
            val sp = ctx.getSharedPreferences("trigger", MODE_PRIVATE)
            return sp.getString("key", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("key", it).apply() }
        }
    }

    private lateinit var prefs: Prefs
    private val model = UiModel()
    private val ui = Handler(Looper.getMainLooper())
    private var pendingExport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        enableEdgeToEdge()
        refresh()
        setContent { CarMonitorTheme(model.theme) { CarMonitorApp(model, this) } }
        handleIntent(intent)
        askPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ACTION_START_FROM_NOTIFICATION && i.getStringExtra(EXTRA_START_KEY) == startKey(this)) { ObdService.start(this, ObdService.ACTION_LOG); toast("Logger started") }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        tick()
        idleProbe()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    /** Live bits, twice a second (the fast poll tier runs at 0.5 s). */
    private fun tick() {
        model.state = Status.state; model.detail = Status.detail; model.mqtt = Status.mqtt
        model.dash = Status.dash; model.log = Status.logText()
        ui.postDelayed({ tick() }, 500)
    }

    private fun idle() = Status.state == "stopped" || Status.state.startsWith("error")

    /** The service checks the uplink only while it runs; with the logger stopped, check it here for the chip. */
    private fun idleProbe() {
        if (idle()) Thread {
            val ok = try { reachable() } catch (_: Exception) { false }
            if (idle()) Status.mqtt = ok
        }.start()
        ui.postDelayed({ idleProbe() }, 30_000)
    }

    private fun reachable(): Boolean = when (prefs.connMode) {
        "integration" -> prefs.webhookUrl.startsWith("http") &&
            uk.co.ashunt.carmonitor.core.WebhookUplink.httpPost(prefs.webhookUrl, """{"drive":"test","rows":[]}""") in 200..299
        "ha" -> prefs.haUrl.isNotBlank() && prefs.haToken.isNotEmpty() &&
            uk.co.ashunt.carmonitor.core.HaRestSink.check(prefs.haUrl, prefs.haToken) == null
        else -> prefs.brokerHost.isNotBlank() && run {
            val m = uk.co.ashunt.carmonitor.core.MiniMqtt(prefs.brokerHost, prefs.brokerPort, "carmonitor-probe-" + Build.MODEL.replace(' ', '-'),
                prefs.brokerUser.ifBlank { null }, prefs.brokerPass.ifBlank { null })
            m.connect(timeoutMs = 6000); m.close(); true
        }
    }

    /** Everything that comes from prefs or the profile (on start, resume and after a change). */
    private fun refresh() {
        val v = prefs.vehicle()
        model.vehicle = v?.label ?: "Generic OBD-II"
        model.vehicleSub = listOfNotNull(prefs.modelYear.toString(),
            if (v != null) prefs.signalsetFile + (if (prefs.hasSignalset()) "" else " (not downloaded)") else "SAE J1979 only").joinToString(" · ")
        model.tank = if (prefs.tankLitres > 0) "%.0f".format(prefs.tankLitres) else ""
        val p = prefs.profile()
        model.vin = p?.vin
        model.supported = p?.results?.values?.count { it.support.name == "SUPPORTED" } ?: 0
        model.sweepHits = p?.sweepHits?.size ?: 0
        model.profileNote = p?.let {
            "Discovered " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(java.util.Date(it.createdAtMs)) +
                " · adapter " + it.adapter
        } ?: "No profile yet: discovery runs on the next connect"
        val names = bonded().associate { (n, a) -> a to n }
        model.dongle = prefs.deviceAddress?.let { a ->
            "${names[a] ?: prefs.deviceLabel ?: "?"}  ($a)" + if (Dongle.isBle(this, prefs, a)) "  · BLE" else ""
        } ?: "No dongle selected"
        val cars = prefs.carDevices.map { names[it] ?: it }
        val dongleName = prefs.deviceAddress?.let { names[it] ?: prefs.deviceLabel ?: it } ?: "the dongle"
        model.carTrigger = if (cars.isEmpty()) "Trigger: the dongle only" else "When ${cars.joinToString(" or ")} connects → dial $dongleName"
        model.linked = isLinked()
        model.btAutostart = prefs.btAutostart
        model.connection = prefs.connectionSummary()
        model.integration = prefs.connMode == "integration"
        model.unsent = if (!model.integration) 0 else try { uploader().pending().size } catch (_: Exception) { 0 }
        model.topicBase = prefs.topicBase; model.deviceName = prefs.haDeviceName
        model.layout = prefs.layout; model.theme = prefs.theme; model.units = prefs.units()
        model.startOnBoot = prefs.autoStart; model.candidates = prefs.publishCandidates; model.logSweep = prefs.logSweepHits
        model.modelYear = prefs.modelYear.toString(); model.sweepRanges = prefs.sweep
        model.pollMode = prefs.pollMode; model.fastSlots = prefs.fastSlots; model.mediumSlots = prefs.mediumSlots
        val plan = p?.vin?.let { prefs.pollPlan(it) }
        model.planNote = if (p?.vin == null) "No VIN yet: discover first" else if (plan == null) "No poll plan for " + p?.vin
            else plan.tiers.size.toString() + " overrides for " + p?.vin
        model.gpsRecord = prefs.gpsRecord; model.gpsToHa = prefs.gpsToHa
        model.gpsNote = when {
            !prefs.gpsWanted -> ""
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                "Location permission is off: allow it in Android settings for GPS to work"
            else -> "Location is read only while logging. If the logger starts while the app is closed, Android may block GPS for that drive."
        }
    }
    private fun refreshCar() = refresh()

    // --- Actions (called by the Compose screens) ------------------------------------------
    override fun start() = ObdService.start(this, ObdService.ACTION_LOG)
    override fun stop() { startService(Intent(this, ObdService::class.java).setAction(ObdService.ACTION_STOP)) }
    override fun discover(sweep: Boolean) = ObdService.start(this, ObdService.ACTION_DISCOVER, sweep = sweep)
    override fun chooseVehicle() {
        val suggested = prefs.profile()?.vin?.let { Wmi.make(it) } ?: prefs.vehicle()?.make ?: ""
        val q = EditText(this).apply { setText(suggested); hint = "e.g. CX-5, RAV4, Golf"; setSingleLine(true) }
        AlertDialog.Builder(this)
            .setTitle("Find your car in OBDb")
            .setView(q)
            .setPositiveButton("Search") { _, _ -> search(q.text.toString(), refresh = false) }
            .setNeutralButton("Refresh list") { _, _ -> search(q.text.toString(), refresh = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }
    override fun genericVehicle() {
        AlertDialog.Builder(this).setTitle("Switch to generic OBD-II?")
            .setMessage("Only standard J1979 PIDs: no ${prefs.vehicle()?.label ?: "make-specific"} signals (DPF, EGT, oil…), " +
                "and Home Assistant gets a separate \"Car\" device. Your current car's profile and fuel settings are kept " +
                "and come back when you choose it again.")
            .setPositiveButton("Switch to generic") { _, _ ->
                prefs.selectVehicle(null, "default.json"); refresh(); vehicleChanged()
            }
            .setNegativeButton("Cancel", null).show()
    }
    override fun setTank(v: String) { prefs.tankLitres = v.toDoubleOrNull() ?: 0.0 }
    @SuppressLint("MissingPermission")
    override fun chooseDongle() {
        val list = bonded()
        if (list.isEmpty()) { toast("No paired devices: use Scan & pair"); return }
        AlertDialog.Builder(this).setTitle("OBD dongle")
            .setItems(list.map { "${it.first}  (${it.second})" }.toTypedArray()) { _, i ->
                prefs.selectDongle(list[i].second, null); refresh()   // link kind decided from the device (LE-only = BLE)
            }
            .setNegativeButton("Cancel", null).show()
    }
    override fun setAutostart(on: Boolean) { prefs.btAutostart = on }
    override fun importProfile() = pickFile("*/*", 13)
    override fun exportProfile() = export(prefs.profileFile, "carmonitor-profile.json", "application/json")
    override fun editConnection() = connectionDialog()
    override fun setTopicBase(v: String) { v.trim().trim('/').takeIf { it.isNotBlank() }?.let { prefs.topicBase = it } }
    override fun setDeviceName(v: String) { v.trim().takeIf { it.isNotBlank() }?.let { prefs.haDeviceName = it } }
    override fun setLayout(v: String) { prefs.layout = v }
    override fun setTheme(v: String) { prefs.theme = v }
    override fun setUnits(u: Units) { prefs.mph = u.imperialSpeed; prefs.gallons = u.gallons; prefs.fahrenheit = u.fahrenheit }
    override fun setStartOnBoot(on: Boolean) { prefs.autoStart = on }
    override fun setCandidates(on: Boolean) { prefs.publishCandidates = on }
    override fun setLogSweep(on: Boolean) { prefs.logSweepHits = on }
    override fun setModelYear(v: String) { v.toIntOrNull()?.takeIf { it in 1990..2100 }?.let { prefs.modelYear = it } }
    override fun setSweepRanges(v: String) { prefs.sweep = v }
    override fun setPollMode(v: String) { prefs.pollMode = v; refresh() }
    override fun setSlots(fast: Int, medium: Int) {
        if (6 - fast - medium < 1) { toast("Needs at least one slow slot: $fast fast + $medium medium is too many"); refresh(); return }
        prefs.fastSlots = fast; prefs.mediumSlots = medium; refresh()
    }
    override fun importPollPlan() = pickFile("*/*", 14)
    override fun exportPollPlan() {
        val vin = prefs.profile()?.vin
        if (vin == null) toast("No VIN yet: discover first")
        else export(uk.co.ashunt.carmonitor.core.PollPlan.file(prefs.pollPlanDir, vin), "carmonitor-pollplan-$vin.json", "application/json")
    }
    // --- GPS: warning once, then while-in-use location permission ------------------------
    private var pendingGps: (() -> Unit)? = null
    override fun setGpsRecord(on: Boolean) = gpsSwitch(on) { prefs.gpsRecord = on }
    override fun setGpsToHa(on: Boolean) = gpsSwitch(on) { prefs.gpsToHa = on }
    private fun gpsSwitch(on: Boolean, apply: () -> Unit) {
        if (!on) { apply(); refresh(); vehicleChanged(); return }
        val afterWarning = {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                apply(); refresh(); vehicleChanged()
            } else {
                pendingGps = apply
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            }
        }
        if (prefs.gpsWarned) { afterWarning(); return }
        AlertDialog.Builder(this)
            .setTitle("Before you turn on GPS")
            .setMessage("Don't use or adjust this app while driving: set it up while parked, and let it run on its own.\n\n" +
                "Location is read only while the logger is running and goes only to this phone and your own Home Assistant. " +
                "You use the location features at your own discretion and responsibility.")
            .setPositiveButton("I understand") { _, _ -> prefs.gpsWarned = true; afterWarning() }
            .setNegativeButton("Cancel") { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }
    override fun exportTrack() {
        val f = prefs.logDir.listFiles { x -> x.name.endsWith(".gpx") }?.maxByOrNull { it.lastModified() }
        if (f == null) toast("No GPS tracks yet") else export(f, f.name, "application/gpx+xml")
    }

    override fun exportDrive() {
        val f = prefs.logDir.listFiles { x -> x.name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }
        if (f == null) toast("No drives yet") else export(f, f.name, "text/csv")
    }
    override fun importOverlay() = pickFile("application/json", 11)
    private fun pickFile(mime: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime), code)
    }

    private fun askPermissions() {
        val need = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (ok) { pendingGps?.invoke(); vehicleChanged() } else toast("GPS needs location permission (while using the app)")
            pendingGps = null
        }
        refresh()
        if (requestCode == 2 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scanAndPair()
        if (requestCode == 4 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scanBle()
    }

    override fun onDestroy() {
        stopScan()
        stopBleScan()
        scanReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        scanReceiver = null
        super.onDestroy()
    }

    // --- Scan & pair (classic Bluetooth discovery) ----------------------------------
    private var scanReceiver: BroadcastReceiver? = null
    private val found = LinkedHashMap<String, BluetoothDevice>()

    private fun adapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    override fun scanAndPair() {
        val need = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 2); return }
        val ad = adapter() ?: run { toast("No Bluetooth adapter"); return }
        if (!ad.isEnabled) { toast("Turn Bluetooth on first"); return }
        stopScan()
        scanReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        found.clear()
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = deviceExtra(intent) ?: return
                        // OBD dongles are classic (SPP); skip BLE-only advertisers
                        if (d.type == BluetoothDevice.DEVICE_TYPE_LE) return
                        if (d.bondState == BluetoothDevice.BOND_BONDED) return
                        found[d.address] = d
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { stopScan(); showFound() }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val d = deviceExtra(intent) ?: return
                        if (d.bondState == BluetoothDevice.BOND_BONDED) {
                            prefs.selectDongle(d.address, "classic")
                            refresh()
                            toast("Paired with ${d.name ?: d.address}")
                        }
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
        scanReceiver = r
        if (ad.isDiscovering) ad.cancelDiscovery()
        if (ad.startDiscovery()) toast("Scanning ~12 s… ignition on, dongle plugged in")
        else { stopScan(); toast("Couldn't start a Bluetooth scan") }
    }

    @Suppress("DEPRECATION")
    private fun deviceExtra(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) adapter()?.cancelDiscovery() } catch (_: Exception) {}
        // keep the receiver for bond-state events until the next scan or onDestroy
    }

    // --- Scan BLE (no pairing: the app connects to the dongle directly, like Car Scanner) ----------
    private var bleScanCb: ScanCallback? = null
    private val bleFound = LinkedHashMap<String, Pair<String, Int>>()   // address -> (name, rssi)

    @SuppressLint("MissingPermission")
    override fun scanBle() {
        val need = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 4); return }
        val ad = adapter() ?: run { toast("No Bluetooth adapter"); return }
        if (!ad.isEnabled) { toast("Turn Bluetooth on first"); return }
        val scanner = ad.bluetoothLeScanner ?: run { toast("Couldn't start a BLE scan"); return }
        stopBleScan()
        bleFound.clear()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: try { r.device.name } catch (_: SecurityException) { null }
                if (name.isNullOrBlank()) return
                bleFound[r.device.address] = name to r.rssi
            }
            override fun onScanFailed(errorCode: Int) { toast("BLE scan failed (code $errorCode)") }
        }
        bleScanCb = cb
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) { bleScanCb = null; toast("Couldn't start a BLE scan: ${e.message}"); return }
        toast("Scanning ~10 s… ignition on, dongle plugged in, other OBD apps closed")
        ui.postDelayed({ if (bleScanCb === cb) { stopBleScan(); if (!isFinishing) showBleFound() } }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        val cb = bleScanCb ?: return
        bleScanCb = null
        try { if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) adapter()?.bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
    }

    private fun showBleFound() {
        if (bleFound.isEmpty()) {
            toast("No BLE devices found. Is the ignition on? Close Car Scanner or any other app holding the dongle.")
            return
        }
        // Dongle-looking names first, then the strongest signal
        val list = bleFound.entries.sortedWith(
            compareByDescending<Map.Entry<String, Pair<String, Int>>> { BleSerial.looksLikeObd(it.value.first) }
                .thenByDescending { it.value.second })
        AlertDialog.Builder(this).setTitle("Pick your OBD dongle (BLE)")
            .setItems(list.map { "${it.value.first}  ${it.key}  ${it.value.second} dBm" }.toTypedArray()) { _, i ->
                val (addr, v) = list[i].let { it.key to it.value }
                prefs.selectDongle(addr, "ble", v.first)
                refresh()
                toast("Dongle set to ${v.first}. No pairing needed.")
            }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("MissingPermission")
    private fun showFound() {
        if (found.isEmpty()) {
            toast("No unpaired classic Bluetooth devices found. Is the ignition on? For a BLE dongle use Scan BLE.")
            return
        }
        val list = found.values.sortedByDescending { d -> d.name?.let { n -> listOf("OBD", "ELM", "VLINK", "V-LINK", "OBDLINK", "ICAR", "VGATE").any { n.uppercase().contains(it) } } ?: false }
        AlertDialog.Builder(this).setTitle("Pick your OBD dongle")
            .setItems(list.map { "${it.name ?: "(no name)"}  ${it.address}" }.toTypedArray()) { _, i ->
                val d = list[i]
                if (!d.createBond()) toast("Pairing didn't start") else toast("Pairing… enter the PIN if asked (often 1234 or 0000)")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Tell a running logger to restart with the new vehicle (no-op if it isn't running). */
    private fun vehicleChanged() {
        startService(Intent(this, ObdService::class.java).setAction(ObdService.ACTION_RELOAD))
    }

    // --- OBDb vehicle picker ------------------------------------------------------
    private fun search(query: String, refresh: Boolean) {
        toast(if (refresh) "Refreshing OBDb catalogue…" else "Searching…")
        Thread {
            try {
                val all = ObdbClient(this).catalogue(refresh)
                val hits = ObdbCatalog.search(all, query).take(200)
                runOnUiThread {
                    if (hits.isEmpty()) toast("No match for '$query'. OBDb may not have it yet - try the make only, or Generic.")
                    else {
                        var dlg: AlertDialog? = null
                        dlg = AlertDialog.Builder(this).setTitle("${hits.size} matches")
                            .setItems(hits.map { it.label }.toTypedArray()) { d, i -> d.dismiss(); dlg?.dismiss(); pick(hits[i], all) }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("OBDb: ${e.message}") }
            }
        }.start()
    }

    private fun pick(v: VehicleRef, all: List<VehicleRef>) {
        val year = prefs.modelYear
        val busy = AlertDialog.Builder(this).setTitle(v.label)
            .setMessage("Downloading the OBDb signalset for $year…").setCancelable(false).show()
        Thread {
            try {
                val client = ObdbClient(this)
                val file = client.download(v.repo, year)
                ObdbCatalog.makeFallbackFor(v, all)?.let { mk -> try { client.download(mk.repo, year) } catch (_: Exception) {} }
                runOnUiThread {
                    busy.dismiss()
                    val known = prefs.hasSavedProfile(v, file)
                    prefs.selectVehicle(v, file)
                    refresh()
                    vehicleChanged()
                    toast(if (known) "${v.label}: saved profile restored, no discovery needed. Topics: ${prefs.topicBase}"
                          else "${v.label}: $file for $year. Topics: ${prefs.topicBase}. Discovery runs on the next connect.")
                }
            } catch (e: Exception) {
                runOnUiThread { busy.dismiss(); toast("Download failed: ${e.message}") }
            }
        }.start()
    }

    /** App log + this process's logcat + last crash, as one text file. */
    override fun exportLog() {
        Thread {
            val sb = StringBuilder()
            val p = prefs.profile()
            sb.append("CarMonitor ").append(packageManager.getPackageInfo(packageName, 0).versionName)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MODEL).append('\n')
            sb.append("vehicle: ").append(prefs.vehicle()?.label ?: "generic").append(" / ").append(prefs.signalsetFile)
                .append(" · year ").append(prefs.modelYear).append(" · topics ").append(prefs.topicBase).append('\n')
            sb.append("status: ").append(Status.state).append(" ").append(Status.detail).append('\n')
            p?.let { sb.append("profile: adapter='").append(it.adapter).append("' warnings=").append(it.adapterWarnings).append('\n') }
            CrashLog.file(this).takeIf { it.exists() }?.let { sb.append("\n=== last crash ===\n").append(it.readText()) }
            sb.append("\n=== app log, this session (newest first) ===\n").append(Status.logText()).append('\n')
            for (n in listOf("app-log.1.txt", "app-log.txt")) File(filesDir, n).takeIf { it.exists() }?.let {
                sb.append("\n=== persistent log $n (oldest first, last 300 KB) ===\n").append(it.readText().takeLast(300_000))
            }
            sb.append("\n=== logcat (this app) ===\n")
            try {
                val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid", android.os.Process.myPid().toString())
                    .redirectErrorStream(true).start()
                sb.append(proc.inputStream.bufferedReader().readText().takeLast(400_000))
            } catch (e: Exception) { sb.append("(logcat unavailable: ").append(e.message).append(")") }
            val f = File(cacheDir, "carmonitor-log.txt").apply { writeText(sb.toString()) }
            runOnUiThread { export(f, "carmonitor-log-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.UK).format(java.util.Date()) + ".txt", "text/plain") }
        }.start()
    }

    // --- Storage Access Framework export / import (no FileProvider needed) ----------
    private fun export(f: File, name: String, mime: String) {
        if (!f.exists()) { toast("Nothing to export yet"); return }
        pendingExport = f
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime).putExtra(Intent.EXTRA_TITLE, name), 10)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 12) { refreshCar(); if (resultCode == RESULT_OK) toast("Linked: background start allowed"); return }
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                10 -> pendingExport?.let { f -> contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }; toast("Exported") }
                13 -> {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
                    val p = uk.co.ashunt.carmonitor.core.VehicleProfile.fromJson(text)
                    val vin = p.vin?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("profile has no VIN")
                    val merged = prefs.addSweep(vin, p.sweepHits)
                    val active = !p.isStale && p.results.values.any { it.support.name == "SUPPORTED" }
                    if (active) prefs.profileFile.writeText(p.copy(sweepHits = merged).toJson())
                    startService(Intent(this, ObdService::class.java).setAction(ObdService.ACTION_RELOAD))
                    toast((if (active) "Profile imported (no discovery needed). " else "Old-format profile: sweep hits kept. ") +
                        "$vin now has ${merged.size} sweep hits.")
                }
                14 -> {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
                    val plan = uk.co.ashunt.carmonitor.core.PollPlan.fromJson(text)   // validate before saving
                    uk.co.ashunt.carmonitor.core.PollPlan.save(prefs.pollPlanDir, plan)
                    val p = prefs.profile()
                    val known = p?.let { it.results.keys + it.sweepHits.map { h -> h.key } } ?: emptySet()
                    val unknown = if (p?.vin == plan.vin) plan.tiers.keys.count { it !in known } else 0
                    refresh()
                    startService(Intent(this, ObdService::class.java).setAction(ObdService.ACTION_RELOAD))
                    toast("Poll plan saved for " + plan.vin + ": " + plan.tiers.size + " overrides" +
                        (if (p?.vin != plan.vin) " (not the current car: used when that VIN connects)" else "") +
                        (if (unknown > 0) ", $unknown not in this car's profile (ignored)" else ""))
                }
                11 -> {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
                    uk.co.ashunt.carmonitor.core.SignalSet.parse("overlay", text)   // validate before saving
                    prefs.overlayFile.writeText(text)
                    toast("Overlay imported. Run Discover to pick up new DIDs.")
                }
            }
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    // --- Connection settings submenu -----------------------------------------------
    private fun secretField(hint: String, saved: Boolean) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD   // masked; no setSingleLine (it unmasks)
        this.hint = if (saved) "•••••••• saved (leave blank to keep)" else hint
    }

    private fun showToggle(target: EditText) = CheckBox(this).apply {
        text = "Show"
        setOnCheckedChangeListener { _, on ->
            target.inputType = InputType.TYPE_CLASS_TEXT or
                (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            target.setSelection(target.text.length)
        }
    }

    private fun connectionDialog() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun field(v: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply { setText(v); this.hint = hint; inputType = type }
        fun lbl(t: String) = TextView(this).apply { text = t; setPadding(0, pad / 2, 0, 0) }

        val modeMqtt = RadioButton(this).apply { text = "MQTT broker (username / password)"; id = View.generateViewId() }
        val modeHa = RadioButton(this).apply { text = "Home Assistant API (long-lived token)"; id = View.generateViewId() }
        val modeHook = RadioButton(this).apply { text = "CarMonitor integration (webhook)"; id = View.generateViewId() }
        val group = RadioGroup(this).apply { addView(modeMqtt); addView(modeHa); addView(modeHook) }
        box.addView(group)

        val mqttBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val host = field(prefs.brokerHost, "broker host, e.g. 100.x.y.z (Tailscale/NetBird IP)")
        val port = field(prefs.brokerPort.toString(), "1883", InputType.TYPE_CLASS_NUMBER)
        val user = field(prefs.brokerUser, "username")
        val pass = secretField("password", prefs.brokerPass.isNotEmpty())
        mqttBox.addView(lbl("Host")); mqttBox.addView(host)
        mqttBox.addView(lbl("Port")); mqttBox.addView(port)
        mqttBox.addView(lbl("Username")); mqttBox.addView(user)
        mqttBox.addView(lbl("Password")); mqttBox.addView(pass); mqttBox.addView(showToggle(pass))

        val haBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val url = field(prefs.haUrl, "https://home.example.org", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val token = secretField("long-lived access token", prefs.haToken.isNotEmpty())
        haBox.addView(lbl("Home Assistant URL")); haBox.addView(url)
        haBox.addView(lbl("Access token (HA → Profile → Security → Long-lived access tokens)")); haBox.addView(token); haBox.addView(showToggle(token))
        haBox.addView(lbl("Publishes via HA's mqtt.publish service, so the MQTT integration must be set up. Updates are batched every 3 s."))

        val hookBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hook = secretField("https://…/api/webhook/…", prefs.webhookUrl.isNotEmpty())
        val unsent = try { uk.co.ashunt.carmonitor.core.WebhookUplink(prefs.webhookUrl, prefs.logDir, "", true, worker = false).pending().size } catch (_: Exception) { 0 }
        val past = CheckBox(this).apply { text = "Upload past drives ($unsent not yet sent)"; isChecked = prefs.uploadPast }
        hookBox.addView(lbl("Webhook URL")); hookBox.addView(hook); hookBox.addView(showToggle(hook))
        hookBox.addView(lbl("Paste the URL HA showed when you added the CarMonitor integration. Keep it private: it is the only credential."))
        hookBox.addView(past)

        box.addView(mqttBox); box.addView(haBox); box.addView(hookBox)
        fun modeOf(id: Int) = when (id) { modeHa.id -> "ha"; modeHook.id -> "integration"; else -> "mqtt" }
        fun apply(mode: String) {
            mqttBox.visibility = if (mode == "mqtt") View.VISIBLE else View.GONE
            haBox.visibility = if (mode == "ha") View.VISIBLE else View.GONE
            hookBox.visibility = if (mode == "integration") View.VISIBLE else View.GONE
        }
        group.check(when (prefs.connMode) { "ha" -> modeHa.id; "integration" -> modeHook.id; else -> modeMqtt.id })
        apply(prefs.connMode)
        group.setOnCheckedChangeListener { _, id -> apply(modeOf(id)) }

        fun store() {
            prefs.connMode = modeOf(group.checkedRadioButtonId)
            if (hook.text.isNotEmpty()) prefs.webhookUrl = hook.text.toString().trim()
            prefs.uploadPast = past.isChecked
            prefs.brokerHost = host.text.toString().trim()
            prefs.brokerPort = port.text.toString().toIntOrNull() ?: 1883
            prefs.brokerUser = user.text.toString().trim()
            if (pass.text.isNotEmpty()) prefs.brokerPass = pass.text.toString()
            prefs.haUrl = url.text.toString().trim().trimEnd('/')
            if (token.text.isNotEmpty()) prefs.haToken = token.text.toString().trim()
            refresh()
        }

        val dlg = AlertDialog.Builder(this).setTitle("Connection settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> store(); toast("Saved. Stop/Start the logger to apply.") }
            .setNeutralButton("Test", null)
            .setNegativeButton("Cancel", null)
            .show()
        // Keep the dialog open while testing
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { store(); testConnection() }
    }

    private fun uploader() = uk.co.ashunt.carmonitor.core.WebhookUplink(prefs.webhookUrl, prefs.logDir, prefs.scheme().base, true, worker = false)

    /** Send every drive file the integration hasn't fully accepted, now (duplicates are ignored on the HA side). */
    override fun uploadDrives() {
        if (!prefs.webhookUrl.startsWith("http")) { toast("Set the webhook URL first (Edit connection)"); return }
        val todo = try { uploader().pending().size } catch (_: Exception) { 0 }
        if (todo == 0) { toast("Every drive is already in Home Assistant"); return }
        toast("Uploading $todo drives…")
        Thread {
            val up = uploader()
            var rows = 0
            for (i in 0 until 50) { val n = up.flush(); rows += n; if (n == 0) break }
            val left = up.pending().size
            val msg = if (left == 0) "Uploaded $todo drives ($rows readings)" else "Uploaded $rows readings; $left drives left: ${up.lastError ?: "will retry"}"
            Status.log(msg); runOnUiThread { toast(msg); refresh() }
        }.start()
    }

    /** Check the configured uplink and publish a 'Connection test' sensor HA can show. */
    override fun testConnection() {
        val t = prefs.scheme()
        val (cfgTopic, cfg) = uk.co.ashunt.carmonitor.core.HaDiscovery.connectionTest(t)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.UK).format(java.util.Date())
        val ok = "In HA: device '${t.deviceName}' → 'Connection test' = OK $now"
        if (prefs.connMode == "integration") {
            val u = prefs.webhookUrl
            if (!u.startsWith("http")) { toast("Paste the webhook URL first"); return }
            toast("Testing the integration…")
            Thread {
                val code = try { uk.co.ashunt.carmonitor.core.WebhookUplink.httpPost(u, """{"drive":"test","rows":[]}""") } catch (e: Exception) { -1 }
                val msg = when (code) {
                    in 200..299 -> "The integration answered. In HA: the car's Logger sensor turns on while you drive." +
                        (if (u.startsWith("http://") && !Regex("""^http://(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|100\.|127\.)""").containsMatchIn(u))
                            "\nWarning: plain http over the internet exposes the webhook. Use https." else "")
                    404 -> "Webhook not found (404): check the URL, and that the integration is set up"
                    -1 -> "Can't reach Home Assistant at that URL"
                    else -> "Integration test failed: HTTP $code"
                }
                if (idle()) Status.mqtt = code in 200..299
                Status.log(msg); runOnUiThread { toast(msg) }
            }.start()
            return
        }
        if (prefs.connMode == "ha") {
            val base = prefs.haUrl
            if (base.isBlank() || prefs.haToken.isEmpty()) { toast("Set the HA URL and token first"); return }
            toast("Testing $base…")
            Thread {
                val tok = prefs.haToken
                val msg = uk.co.ashunt.carmonitor.core.HaRestSink.check(base, tok)?.let { "HA API test failed: $it" } ?: run {
                    val sink = uk.co.ashunt.carmonitor.core.HaRestSink(base, tok, flushMs = 3_600_000)
                    sink.publish(cfgTopic, cfg, true); sink.flush()
                    Thread.sleep(500)
                    sink.publish("${t.base}/test", "OK $now", true)
                    val sent = sink.flush(); val err = sink.lastError; sink.close()
                    if (sent == 1) "Token accepted and published via HA. $ok" +
                        (if (base.startsWith("http://") && !Regex("""^http://(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|100\.|127\.)""").containsMatchIn(base))
                            "\nWarning: plain http over the internet exposes the token. Use https." else "")
                    else "Token accepted, but publishing failed: $err"
                }
                Status.log(msg); runOnUiThread { toast(msg) }
            }.start()
            return
        }
        val host = prefs.brokerHost
        if (host.isBlank()) { toast("Set the broker host first (Connection settings…)"); return }
        toast("Testing $host:${prefs.brokerPort}…")
        Thread {
            val msg = try {
                val m = uk.co.ashunt.carmonitor.core.MiniMqtt(host, prefs.brokerPort, "carmonitor-test-" + Build.MODEL.replace(' ', '-'),
                    prefs.brokerUser.ifBlank { null }, prefs.brokerPass.ifBlank { null })
                m.connect(timeoutMs = 6000)
                m.publish(cfgTopic, cfg, retain = true)
                Thread.sleep(300)   // let HA create the entity before the first state
                m.publish("${t.base}/test", "OK $now", retain = true)
                m.close()
                "Connected to the broker. $ok"
            } catch (e: java.net.UnknownHostException) {
                "Can't resolve '$host'. Is the VPN (Tailscale/NetBird) up?"
            } catch (e: java.net.SocketTimeoutException) {
                "Timed out reaching $host:${prefs.brokerPort}: VPN down, wrong IP, or firewall"
            } catch (e: java.net.ConnectException) {
                "Connection refused on $host:${prefs.brokerPort}: wrong port, or Mosquitto not running"
            } catch (e: Exception) {
                "MQTT test failed: ${e.message}"
            }
            Status.log(msg); runOnUiThread { toast(msg) }
        }.start()
    }

    // --- Car Bluetooth trigger + companion link ----------------------------------------
    @SuppressLint("MissingPermission")
    private fun bonded(): List<Pair<String, String>> =
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) emptyList()
        else (adapter()?.bondedDevices ?: emptySet()).map { (it.name ?: "?") to it.address }.sortedBy { it.first }

    override fun chooseCarDevices() {
        val list = bonded().filter { it.second != prefs.deviceAddress }
        if (list.isEmpty()) { toast("No other paired devices. Pair the car's Bluetooth (head unit) with this phone first."); return }
        val chosen = list.map { it.second in prefs.carDevices }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Which of these are the car?")
            .setMultiChoiceItems(list.map { "${it.first}  (${it.second})" }.toTypedArray(), chosen) { _, i, on -> chosen[i] = on }
            .setPositiveButton("Save") { _, _ ->
                prefs.carDevices = list.filterIndexed { i, _ -> chosen[i] }.map { it.second }.toSet()
                refreshCar()
                toast("When the car's Bluetooth connects, CarMonitor dials the OBD dongle.")
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Address the companion link is made with: the first car device, else the dongle. */
    private fun linkTarget(): String? = prefs.carDevices.sorted().firstOrNull() ?: prefs.deviceAddress

    private fun isLinked(): Boolean {
        val addr = linkTarget() ?: return false
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.any { it.deviceMacAddress?.toString()?.equals(addr, true) == true }
            else @Suppress("DEPRECATION") cdm.associations.any { it.equals(addr, true) }
        } catch (_: Exception) { false }
    }

    /**
     * Companion-device link: the system dialog asks once; afterwards Android lets the
     * car-Bluetooth trigger start the logger's foreground service from the background.
     */
    override fun linkCompanion() {
        val addr = linkTarget() ?: run { toast("Pick the dongle or the car's Bluetooth first"); return }
        if (isLinked()) { toast("Already linked"); return }
        val cdm = getSystemService(CompanionDeviceManager::class.java) ?: run { toast("Companion devices not supported on this phone"); return }
        val req = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(addr).build())
            .setSingleDevice(true)
            .build()
        val launch = { sender: IntentSender -> startIntentSenderForResult(sender, 12, null, 0, 0, 0) }
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(req, mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) { launch(intentSender) }
                override fun onAssociationCreated(associationInfo: AssociationInfo) { runOnUiThread { refreshCar(); toast("Linked: background start allowed") } }
                override fun onFailure(error: CharSequence?) { runOnUiThread { toast("Link failed: $error") } }
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(req, object : CompanionDeviceManager.Callback() {
                @Deprecated("API 31-32 path")
                override fun onDeviceFound(intentSender: IntentSender) { launch(intentSender) }
                override fun onFailure(error: CharSequence?) { runOnUiThread { toast("Link failed: $error") } }
            }, null)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
