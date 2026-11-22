package uk.co.ashunt.carmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
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

        col.addView(label("OBD dongle (paired devices)"))
        device = Spinner(this); col.addView(device)
        col.addView(label("MQTT broker (HA's Mosquitto, via Tailscale/NetBird IP)"))
        host = edit(prefs.brokerHost, "100.x.y.z"); col.addView(host)
        port = edit(prefs.brokerPort.toString(), "1883", InputType.TYPE_CLASS_NUMBER); col.addView(port)
        user = edit(prefs.brokerUser, "username"); col.addView(user)
        pass = edit(prefs.brokerPass, "password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); col.addView(pass)
        col.addView(label("Model year (EU VINs have no year code)"))
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
