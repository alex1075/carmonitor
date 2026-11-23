package uk.co.ashunt.carmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import uk.co.ashunt.carmonitor.core.ObdbCatalog
import uk.co.ashunt.carmonitor.core.VehicleRef
import uk.co.ashunt.carmonitor.core.Wmi
import java.io.File

/** Plain framework widgets, no AndroidX: one screen for setup, control and live values. */
class MainActivity : Activity() {

    companion object {
        const val ACTION_START_FROM_NOTIFICATION = "uk.co.ashunt.carmonitor.START"
    }

    private lateinit var prefs: Prefs
    private lateinit var device: Spinner
    private lateinit var tank: EditText
    private lateinit var dieselBox: CheckBox
    private lateinit var connLabel: TextView
    private lateinit var carLabel: TextView
    private lateinit var btAuto: CheckBox
    private lateinit var year: EditText
    private lateinit var sweep: EditText
    private lateinit var all: CheckBox
    private lateinit var cand: CheckBox
    private lateinit var boot: CheckBox
    private lateinit var logSweep: CheckBox
    private lateinit var status: TextView
    private lateinit var vehicleLabel: TextView
    private lateinit var topicBase: EditText
    private lateinit var devName: EditText
    private lateinit var legacy: CheckBox
    private lateinit var live: TextView
    private lateinit var log: TextView
    private var devices: List<Pair<String, String>> = emptyList()   // name, address
    private val ui = Handler(Looper.getMainLooper())
    private var pendingExport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(t: String) = TextView(this).apply { text = t; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, 0) }
        fun edit(v: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply { setText(v); this.hint = hint; inputType = type; setSingleLine(true) }
        fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        fun row(vararg v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            v.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }

        status = TextView(this).apply { textSize = 18f }
        col.addView(status)
        col.addView(row(
            button("Start") { save(); ObdService.start(this, ObdService.ACTION_LOG) },
            button("Stop") { startService(Intent(this, ObdService::class.java).setAction(ObdService.ACTION_STOP)) },
        ))
        col.addView(row(
            button("Discover") { save(); ObdService.start(this, ObdService.ACTION_DISCOVER, sweep = false) },
            button("Discover + sweep") { save(); ObdService.start(this, ObdService.ACTION_DISCOVER, sweep = true) },
        ))
        col.addView(row(
            button("Export profile") { export(prefs.profileFile, "cx5-profile.json", "application/json") },
            button("Export last drive") {
                val f = prefs.logDir.listFiles()?.maxByOrNull { it.lastModified() }
                if (f == null) toast("No drives yet") else export(f, f.name, "text/csv")
            },
        ))
        col.addView(row(
            button("Import overlay JSON") { importOverlay() },
            button("Export app log") { exportLog() },
        ))

        col.addView(label("Vehicle (OBDb signalset)"))
        vehicleLabel = TextView(this); col.addView(vehicleLabel)
        col.addView(row(
            button("Choose vehicle…") { save(); chooseVehicle() },
            button("Generic OBD-II only") { prefs.selectVehicle(null, "default.json"); refreshVehicle(); toast("Generic J1979 PIDs only. Run Discover.") },
        ))
        col.addView(label("Fuel: tank size in litres (CX-5: 56 L 2WD, 58 L AWD)"))
        tank = edit(if (prefs.tankLitres > 0) "%.0f".format(prefs.tankLitres) else "", "e.g. 56", InputType.TYPE_CLASS_NUMBER); col.addView(tank)
        dieselBox = CheckBox(this).apply { text = "Diesel engine"; isChecked = prefs.diesel }; col.addView(dieselBox)
        col.addView(label("MQTT topic base and HA device name"))
        topicBase = edit(prefs.topicBase, "car/my_car"); col.addView(topicBase)
        devName = edit(prefs.haDeviceName, "My car"); col.addView(devName)
        legacy = CheckBox(this).apply { text = "Legacy CX-5 Pi-logger topics (mazda/cx5, miles, YAML entities)"; isChecked = prefs.legacyTopics }
        col.addView(legacy)

        col.addView(label("OBD dongle (paired devices)"))
        device = Spinner(this); col.addView(device)
        col.addView(row(button("Scan & pair…") { scanAndPair() }))

        col.addView(label("Autostart: this car's Bluetooth"))
        btAuto = CheckBox(this).apply { text = "Start logging when the car's Bluetooth connects"; isChecked = prefs.btAutostart }
        col.addView(btAuto)
        carLabel = TextView(this); col.addView(carLabel)
        col.addView(row(
            button("Choose car Bluetooth…") { save(); chooseCarDevices() },
            button("Allow background start…") { save(); linkCompanion() },
        ))
        col.addView(label("Home Assistant connection"))
        connLabel = TextView(this).apply { text = prefs.connectionSummary() }; col.addView(connLabel)
        col.addView(row(
            button("Connection settings…") { save(); connectionDialog() },
            button("Test HA connection") { save(); testConnection() },
        ))
        col.addView(label("Model year (picks the OBDb year file; EU VINs have no year code)"))
        year = edit(prefs.modelYear.toString(), "2017", InputType.TYPE_CLASS_NUMBER); col.addView(year)
        col.addView(label("Sweep ranges (header:from-to, comma separated)"))
        sweep = edit(prefs.sweep, "7E0:0300-04FF"); col.addView(sweep)
        all = CheckBox(this).apply { text = "Publish every supported signal to HA"; isChecked = prefs.publishAll }; col.addView(all)
        cand = CheckBox(this).apply { text = "Publish unvalidated diesel candidates (raw)"; isChecked = prefs.publishCandidates }; col.addView(cand)
        boot = CheckBox(this).apply { text = "Start on boot"; isChecked = prefs.autoStart }; col.addView(boot)
        logSweep = CheckBox(this).apply { text = "Log unidentified sweep hits (raw, for PID hunting)"; isChecked = prefs.logSweepHits }; col.addView(logSweep)
        col.addView(row(button("Save settings") { save(); toast("Saved. Stop/Start to apply.") }))

        col.addView(label("Live"))
        live = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }; col.addView(live)
        col.addView(label("Log"))
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }; col.addView(log)

        val root = ScrollView(this).apply { addView(col) }
        // targetSdk 35+ is edge-to-edge: pad by the status/nav bars and the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        refreshVehicle()
        refreshCar()
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
        loadDevices()
        refreshCar()
        tick()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    private fun tick() {
        val prof = prefs.profile()
        status.text = "● ${Status.state}  ${Status.detail}\n" +
            "HA link: ${if (Status.mqtt) "ok" else "—"}   profile: " +
            (prof?.let { p -> "${p.results.values.count { it.support.name == "SUPPORTED" }} signals, ${p.sweepHits.size} sweep hits" } ?: "none yet")
        live.text = Status.live.entries.joinToString("\n") { "%-26s %s".format(it.key, it.value) }
        log.text = Status.logText()
        ui.postDelayed({ tick() }, 1000)
    }

    private fun askPermissions() {
        val need = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        loadDevices()
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
    private fun scanAndPair() {
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
                            loadDevices()
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

    @SuppressLint("MissingPermission")
    private fun loadDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val bonded = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices ?: emptySet()
        devices = bonded.map { (it.name ?: "?") to it.address }.sortedBy { it.first }
        device.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            devices.map { "${it.first}  (${it.second})" })
        val i = devices.indexOfFirst { it.second == prefs.deviceAddress }
        if (i >= 0) device.setSelection(i)
        else devices.indexOfFirst { it.first.contains("OBD", true) || it.first.contains("ELM", true) || it.first.contains("V-LINK", true) }
            .takeIf { it >= 0 }?.let { device.setSelection(it) }
    }

    private fun save() {
        devices.getOrNull(device.selectedItemPosition)?.let { prefs.deviceAddress = it.second }
        prefs.modelYear = year.text.toString().toIntOrNull() ?: 2017
        prefs.sweep = sweep.text.toString()
        prefs.publishAll = all.isChecked
        prefs.publishCandidates = cand.isChecked
        prefs.autoStart = boot.isChecked
        prefs.logSweepHits = logSweep.isChecked
        prefs.btAutostart = btAuto.isChecked
        prefs.tankLitres = tank.text.toString().toDoubleOrNull() ?: 0.0
        prefs.diesel = dieselBox.isChecked
        prefs.topicBase = topicBase.text.toString().trim().trim('/').ifBlank { prefs.topicBase }
        prefs.haDeviceName = devName.text.toString().trim().ifBlank { prefs.haDeviceName }
        prefs.legacyTopics = legacy.isChecked
    }

    private fun refreshVehicle() {
        val v = prefs.vehicle()
        vehicleLabel.text = (v?.label ?: "Generic OBD-II (SAE J1979 only)") +
            (if (v != null) "  ·  ${prefs.signalsetFile}" + (if (prefs.hasSignalset()) "" else "  (not downloaded)") else "")
        topicBase.setText(prefs.topicBase)
        tank.setText(if (prefs.tankLitres > 0) "%.0f".format(prefs.tankLitres) else "")
        dieselBox.isChecked = prefs.diesel
        devName.setText(prefs.haDeviceName)
        legacy.isChecked = prefs.legacyTopics
    }

    // --- OBDb vehicle picker ------------------------------------------------------
    private fun chooseVehicle() {
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
                    prefs.selectVehicle(v, file)
                    refreshVehicle()
                    toast("${v.label}: $file for $year. Topics: ${prefs.topicBase}. Run Discover with the engine on.")
                }
            } catch (e: Exception) {
                runOnUiThread { busy.dismiss(); toast("Download failed: ${e.message}") }
            }
        }.start()
    }

    /** App log + this process's logcat + last crash, as one text file. */
    private fun exportLog() {
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

    private fun importOverlay() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json"), 11)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 12) { refreshCar(); if (resultCode == RESULT_OK) toast("Linked: background start allowed"); return }
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                10 -> pendingExport?.let { f -> contentResolver.openOutputStream(uri)?.use { it.write(f.readBytes()) }; toast("Exported") }
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
            connLabel.text = prefs.connectionSummary()
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
    private fun testConnection() {
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

    private fun refreshCar() {
        val names = bonded().associate { (n, a) -> a to n }
        val cars = prefs.carDevices.map { names[it] ?: it }
        val dongle = prefs.deviceAddress?.let { names[it] ?: it } ?: "no dongle selected"
        carLabel.text = (if (cars.isEmpty()) "Trigger: the dongle only ($dongle)" else "When ${cars.joinToString(" or ")} connects → dial $dongle") +
            "\nBackground start: " + (if (isLinked()) "allowed (linked)" else "not linked yet: you'll get a tap-to-start notification")
    }

    private fun chooseCarDevices() {
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
    private fun linkCompanion() {
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
