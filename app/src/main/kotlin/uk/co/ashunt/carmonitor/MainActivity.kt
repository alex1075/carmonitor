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
import android.content.IntentFilter
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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

    private lateinit var prefs: Prefs
    private lateinit var device: Spinner
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var year: EditText
    private lateinit var sweep: EditText
    private lateinit var all: CheckBox
    private lateinit var cand: CheckBox
    private lateinit var boot: CheckBox
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
        col.addView(row(button("Import overlay JSON") { importOverlay() }))

        col.addView(label("Vehicle (OBDb signalset)"))
        vehicleLabel = TextView(this); col.addView(vehicleLabel)
        col.addView(row(
            button("Choose vehicle…") { save(); chooseVehicle() },
            button("Generic OBD-II only") { prefs.selectVehicle(null, "default.json"); refreshVehicle(); toast("Generic J1979 PIDs only. Run Discover.") },
        ))
        col.addView(label("MQTT topic base and HA device name"))
        topicBase = edit(prefs.topicBase, "car/my_car"); col.addView(topicBase)
        devName = edit(prefs.haDeviceName, "My car"); col.addView(devName)
        legacy = CheckBox(this).apply { text = "Legacy CX-5 Pi-logger topics (mazda/cx5, miles, YAML entities)"; isChecked = prefs.legacyTopics }
        col.addView(legacy)

        col.addView(label("OBD dongle (paired devices)"))
        device = Spinner(this); col.addView(device)
        col.addView(row(button("Scan & pair…") { scanAndPair() }))
        col.addView(label("MQTT broker (HA's Mosquitto, via Tailscale/NetBird IP)"))
        host = edit(prefs.brokerHost, "100.x.y.z"); col.addView(host)
        port = edit(prefs.brokerPort.toString(), "1883", InputType.TYPE_CLASS_NUMBER); col.addView(port)
        user = edit(prefs.brokerUser, "username"); col.addView(user)
        pass = edit(prefs.brokerPass, "password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); col.addView(pass)
        col.addView(label("Model year (picks the OBDb year file; EU VINs have no year code)"))
        year = edit(prefs.modelYear.toString(), "2017", InputType.TYPE_CLASS_NUMBER); col.addView(year)
        col.addView(label("Sweep ranges (header:from-to, comma separated)"))
        sweep = edit(prefs.sweep, "7E0:0300-04FF"); col.addView(sweep)
        all = CheckBox(this).apply { text = "Publish every supported signal to HA"; isChecked = prefs.publishAll }; col.addView(all)
        cand = CheckBox(this).apply { text = "Publish unvalidated diesel candidates (raw)"; isChecked = prefs.publishCandidates }; col.addView(cand)
        boot = CheckBox(this).apply { text = "Start on boot"; isChecked = prefs.autoStart }; col.addView(boot)
        col.addView(row(button("Save settings") { save(); toast("Saved. Stop/Start to apply.") }))

        col.addView(label("Live"))
        live = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }; col.addView(live)
        col.addView(label("Log"))
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f }; col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })
        refreshVehicle()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        loadDevices()
        tick()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    private fun tick() {
        val prof = prefs.profile()
        status.text = "● ${Status.state}  ${Status.detail}\n" +
            "MQTT: ${if (Status.mqtt) "connected" else "—"}   profile: " +
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
        prefs.brokerHost = host.text.toString().trim()
        prefs.brokerPort = port.text.toString().toIntOrNull() ?: 1883
        prefs.brokerUser = user.text.toString()
        prefs.brokerPass = pass.text.toString()
        prefs.modelYear = year.text.toString().toIntOrNull() ?: 2017
        prefs.sweep = sweep.text.toString()
        prefs.publishAll = all.isChecked
        prefs.publishCandidates = cand.isChecked
        prefs.autoStart = boot.isChecked
        prefs.topicBase = topicBase.text.toString().trim().trim('/').ifBlank { prefs.topicBase }
        prefs.haDeviceName = devName.text.toString().trim().ifBlank { prefs.haDeviceName }
        prefs.legacyTopics = legacy.isChecked
    }

    private fun refreshVehicle() {
        val v = prefs.vehicle()
        vehicleLabel.text = (v?.label ?: "Generic OBD-II (SAE J1979 only)") +
            (if (v != null) "  ·  ${prefs.signalsetFile}" + (if (prefs.hasSignalset()) "" else "  (not downloaded)") else "")
        topicBase.setText(prefs.topicBase)
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
                    else AlertDialog.Builder(this).setTitle("${hits.size} matches")
                        .setItems(hits.map { it.label }.toTypedArray()) { _, i -> pick(hits[i], all) }
                        .setNegativeButton("Cancel", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("OBDb: ${e.message}") }
            }
        }.start()
    }

    private fun pick(v: VehicleRef, all: List<VehicleRef>) {
        toast("Downloading ${v.label}…")
        val year = prefs.modelYear
        Thread {
            try {
                val client = ObdbClient(this)
                val file = client.download(v.repo, year)
                ObdbCatalog.makeFallbackFor(v, all)?.let { mk -> try { client.download(mk.repo, year) } catch (_: Exception) {} }
                runOnUiThread {
                    prefs.selectVehicle(v, file)
                    refreshVehicle()
                    toast("${v.label}: $file for $year. Topics: ${prefs.topicBase}. Run Discover with the engine on.")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed: ${e.message}") }
            }
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
