package uk.co.ashunt.carmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
        if (i?.action == ACTION_START_FROM_NOTIFICATION) { ObdService.start(this, ObdService.ACTION_LOG); toast("Logger started") }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        tick()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    /** Live bits, every second. */
    private fun tick() {
        model.state = Status.state; model.detail = Status.detail; model.mqtt = Status.mqtt
        model.dash = Status.dash; model.log = Status.logText()
        ui.postDelayed({ tick() }, 1000)
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
        model.dongle = prefs.deviceAddress?.let { a -> "${names[a] ?: "?"}  ($a)" } ?: "No dongle selected"
        val cars = prefs.carDevices.map { names[it] ?: it }
        val dongleName = prefs.deviceAddress?.let { names[it] ?: it } ?: "the dongle"
        model.carTrigger = if (cars.isEmpty()) "Trigger: the dongle only" else "When ${cars.joinToString(" or ")} connects → dial $dongleName"
        model.linked = isLinked()
        model.btAutostart = prefs.btAutostart
        model.connection = prefs.connectionSummary()
        model.topicBase = prefs.topicBase; model.deviceName = prefs.haDeviceName
        model.layout = prefs.layout; model.theme = prefs.theme; model.units = prefs.units()
        model.startOnBoot = prefs.autoStart; model.candidates = prefs.publishCandidates; model.logSweep = prefs.logSweepHits
        model.modelYear = prefs.modelYear.toString(); model.sweepRanges = prefs.sweep
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
                prefs.deviceAddress = list[i].second; refresh()
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
    override fun exportDrive() {
        val f = prefs.logDir.listFiles()?.maxByOrNull { it.lastModified() }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        if (requestCode == 2 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) scanAndPair()
    }

    override fun onDestroy() {
        stopScan()
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
                            prefs.deviceAddress = d.address
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

    @SuppressLint("MissingPermission")
    private fun showFound() {
        if (found.isEmpty()) {
            toast("No unpaired classic Bluetooth devices found. Is the ignition on? BLE-only dongles aren't supported yet.")
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
        val group = RadioGroup(this).apply { addView(modeMqtt); addView(modeHa) }
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

        box.addView(mqttBox); box.addView(haBox)
        fun apply(mode: String) {
            mqttBox.visibility = if (mode == "mqtt") View.VISIBLE else View.GONE
            haBox.visibility = if (mode == "ha") View.VISIBLE else View.GONE
        }
        group.check(if (prefs.connMode == "ha") modeHa.id else modeMqtt.id)
        apply(prefs.connMode)
        group.setOnCheckedChangeListener { _, id -> apply(if (id == modeHa.id) "ha" else "mqtt") }

        fun store() {
            prefs.connMode = if (group.checkedRadioButtonId == modeHa.id) "ha" else "mqtt"
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

    /** Check the configured uplink and publish a 'Connection test' sensor HA can show. */
    override fun testConnection() {
        val t = prefs.scheme()
        val (cfgTopic, cfg) = uk.co.ashunt.carmonitor.core.HaDiscovery.connectionTest(t)
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.UK).format(java.util.Date())
        val ok = "In HA: device '${t.deviceName}' → 'Connection test' = OK $now"
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
