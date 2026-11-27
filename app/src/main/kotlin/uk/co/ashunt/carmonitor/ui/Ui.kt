package uk.co.ashunt.carmonitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.ashunt.carmonitor.core.DashSnapshot
import uk.co.ashunt.carmonitor.core.Units

// --- state the activity refreshes every second ---------------------------------------
class UiModel {
    var state by mutableStateOf("stopped")
    var detail by mutableStateOf("")
    var mqtt by mutableStateOf(false)
    var integration by mutableStateOf(false)
    var unsent by mutableStateOf(0)
    var dash by mutableStateOf<DashSnapshot?>(null)
    var log by mutableStateOf("")
    var vehicle by mutableStateOf("Generic OBD-II")
    var vehicleSub by mutableStateOf("")
    var vin by mutableStateOf<String?>(null)
    var tank by mutableStateOf("")
    var dongle by mutableStateOf("No dongle selected")
    var carTrigger by mutableStateOf("")
    var linked by mutableStateOf(false)
    var btAutostart by mutableStateOf(true)
    var supported by mutableIntStateOf(0)
    var sweepHits by mutableIntStateOf(0)
    var profileNote by mutableStateOf("No profile yet: discovery runs on the next connect")
    var connection by mutableStateOf("")
    var topicBase by mutableStateOf("")
    var deviceName by mutableStateOf("")
    var layout by mutableStateOf("extended")
    var theme by mutableStateOf("system")
    var units by mutableStateOf(Units())
    var startOnBoot by mutableStateOf(true)
    var candidates by mutableStateOf(true)
    var logSweep by mutableStateOf(true)
    var modelYear by mutableStateOf("")
    var sweepRanges by mutableStateOf("")
    var pollMode by mutableStateOf("auto")
    var fastSlots by mutableIntStateOf(3)
    var mediumSlots by mutableIntStateOf(2)
    var planNote by mutableStateOf("")
    var gpsRecord by mutableStateOf(false)
    var gpsToHa by mutableStateOf(false)
    var gpsNote by mutableStateOf("")
    val logging get() = state.startsWith("logging") || state.startsWith("discovering")
}

/** Everything the screens can ask the activity to do. */
interface Actions {
    fun start(); fun stop()
    fun discover(sweep: Boolean)
    fun chooseVehicle(); fun genericVehicle()
    fun setTank(v: String)
    fun chooseDongle(); fun scanAndPair(); fun scanBle()
    fun setAutostart(on: Boolean); fun chooseCarDevices(); fun linkCompanion()
    fun importProfile(); fun exportProfile()
    fun editConnection(); fun testConnection(); fun uploadDrives()
    fun setTopicBase(v: String); fun setDeviceName(v: String)
    fun setLayout(v: String); fun setTheme(v: String)
    fun setUnits(u: Units)
    fun setStartOnBoot(on: Boolean); fun setCandidates(on: Boolean); fun setLogSweep(on: Boolean)
    fun setModelYear(v: String); fun setSweepRanges(v: String)
    fun setPollMode(v: String); fun setSlots(fast: Int, medium: Int); fun importPollPlan(); fun exportPollPlan()
    fun exportDrive(); fun exportLog(); fun importOverlay()
    fun setGpsRecord(on: Boolean); fun setGpsToHa(on: Boolean); fun exportTrack()
}

// --- theme: Material 3, blue seed (matches the mockup) --------------------------------
private val Dark = darkColorScheme(
    primary = Color(0xFFA8C8FF), onPrimary = Color(0xFF0B305F),
    primaryContainer = Color(0xFF254777), onPrimaryContainer = Color(0xFFD6E3FF),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF111318), surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2024), surfaceContainerHigh = Color(0xFF272A2F),
    onSurface = Color(0xFFE2E2E9), onSurfaceVariant = Color(0xFFC4C6D0), outline = Color(0xFF8E9099),
)
private val Light = lightColorScheme(
    primary = Color(0xFF415F91), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3E),
    secondaryContainer = Color(0xFFD6E3FF), onSecondaryContainer = Color(0xFF001B3E),
    background = Color(0xFFF8F9FF), surface = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFECEDF4), surfaceContainerHigh = Color(0xFFE6E8EE),
    onSurface = Color(0xFF191C20), onSurfaceVariant = Color(0xFF44474E), outline = Color(0xFF74777F),
)
private val OkContainer: Color @Composable get() = if (MaterialTheme.colorScheme.isDark()) Color(0xFF1F4D33) else Color(0xFFC8F0D4)
private val OnOkContainer: Color @Composable get() = if (MaterialTheme.colorScheme.isDark()) Color(0xFFA6F2C0) else Color(0xFF0E3B22)
private fun ColorScheme.isDark() = background.red < 0.5f

@Composable
fun CarMonitorTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

// --- icons: stroke paths, no icon library ------------------------------------------------
private fun strokeIcon(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
private val IcDrive = strokeIcon("drive", "M12 14l4-4M4 18a9 9 0 1 1 16 0")
private val IcCar = strokeIcon("car", "M5 16V11l2-5h10l2 5v5M3 16h18v3H3zM7 19v2M17 19v2")
private val IcHa = strokeIcon("ha", "M4 12h3l2-6 4 12 2-6h5")
private val IcLog = strokeIcon("log", "M4 6h16M4 12h16M4 18h10")
private val IcStop = strokeIcon("stop", "M7 7h10v10H7z")
private val IcPlay = strokeIcon("play", "M8 5l11 7-11 7z")

// --- app shell --------------------------------------------------------------------------
@Composable
fun CarMonitorApp(m: UiModel, a: Actions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(Triple("Drive", IcDrive, 0), Triple("Car", IcCar, 1), Triple("Home Assistant", IcHa, 2)).forEach { (label, icon, i) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) ExtendedFloatingActionButton(
                onClick = { if (m.logging) a.stop() else a.start() },
                icon = { Icon(if (m.logging) IcStop else IcPlay, contentDescription = null) },
                text = { Text(if (m.logging) "Stop logging" else "Start logging") },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DriveScreen(m) { showLog = true }
                1 -> CarScreen(m, a)
                else -> SettingsScreen(m, a)
            }
        }
    }
    if (showLog) AlertDialog(
        onDismissRequest = { showLog = false },
        confirmButton = { TextButton(onClick = { showLog = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { a.exportLog() }) { Text("Export") } },
        title = { Text("App log") },
        text = {
            Text(m.log.ifBlank { "Nothing logged yet" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
        },
    )
}

@Composable
private fun Page(title: String, sub: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            action?.invoke()
        }
        content()
        Spacer(Modifier.height(96.dp))   // room for the FAB
    }
}

@Composable
private fun Section(title: String? = null, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            content()
        }
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StatusChip(m: UiModel) {
    val ok = m.logging
    Surface(shape = RoundedCornerShape(8.dp), color = if (ok) OkContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).padding(0.dp)) {
                Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(4.dp),
                    color = if (ok) OnOkContainer else MaterialTheme.colorScheme.outline) {}
            }
            Spacer(Modifier.width(8.dp))
            Text(m.state.replaceFirstChar { it.uppercase() } + if (m.dash?.tripActive == true) " · trip active" else "",
                style = MaterialTheme.typography.labelLarge, color = if (ok) OnOkContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

// --- Drive ------------------------------------------------------------------------------
private class Reading(val label: String, val value: String, val unit: String)

private fun readings(d: DashSnapshot?, u: Units): List<Reading> {
    fun n(vararg ids: String) = d?.first(*ids)
    return listOf(
        Reading("Speed", Units.fmt(n("VSS", "CX5_VSS")?.let(u::speed)), u.speedUnit),
        Reading("RPM", Units.fmt(n("RPM", "CX5_RPM")), ""),
        Reading("Coolant", Units.fmt(n("ECT", "CX5_ECT")?.let(u::temp)), u.tempUnit),
        Reading("Exhaust", Units.fmt(n("CX5_EXH_GAS_T_1", "MZD_EGT_POST_DPF", "DPF1_OUTT", "EGT11")?.let(u::temp)), u.tempUnit),
    )
}

@Composable
fun DriveScreen(m: UiModel, openLog: () -> Unit) {
    val u = m.units; val d = m.dash
    Page(m.vehicle, m.vehicleSub, action = { IconButton(onClick = openLog) { Icon(IcLog, contentDescription = "App log") } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(m)
            Spacer(Modifier.weight(1f))
            Muted(if (m.mqtt) "HA connected" else "HA offline")
        }
        if (m.detail.isNotBlank() && d == null) Muted(m.detail)
        val r = readings(d, u)
        val fuelPct = d?.first("FLI", "CX5_FLI")
        val economy = (d?.tripLPer100 ?: d?.avgLPer100)?.let(u::economy)
        if (m.layout == "compact") {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(16.dp)) { r.take(2).forEach { BigValue(it, Modifier.weight(1f), 40) } }
            }
            Section {
                r.drop(2).forEach { ListRow(it.label, "${it.value} ${it.unit}".trim()) }
                ListRow("Fuel", fuelText(d, u, fuelPct))
                fuelPct?.let { LinearProgressIndicator(progress = { (it / 100).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) }
                ListRow("Range", d?.rangeKm?.let { "${Units.fmt(u.distance(it))} ${u.distanceUnit}" } ?: "learning…")
                ListRow("Economy", economy?.let { "${Units.fmt(it, 1)} ${u.economyUnit}" } ?: "learning…")
                ListRow("DPF", dpfText(d, u))
                ListRow("Trip", tripText(d, u))
            }
        } else {
            r.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { Section(null, Modifier.weight(1f)) { BigValue(it, Modifier, 36) } }
                }
            }
            Section("Fuel") {
                Text(fuelText(d, u, fuelPct), style = MaterialTheme.typography.titleMedium)
                fuelPct?.let { LinearProgressIndicator(progress = { (it / 100).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) }
                Muted("Range " + (d?.rangeKm?.let { "${Units.fmt(u.distance(it))} ${u.distanceUnit}" } ?: "—") +
                    " · economy " + (economy?.let { "${Units.fmt(it, 1)} ${u.economyUnit}" } ?: "learning"))
            }
            Section("DPF") { Text(dpfText(d, u), style = MaterialTheme.typography.titleMedium) }
            Section("This trip") { Text(tripText(d, u), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun BigValue(r: Reading, modifier: Modifier, size: Int) {
    Column(modifier) {
        Text(r.label.uppercase(), style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.value, fontSize = size.sp, fontWeight = FontWeight.Medium)
            if (r.unit.isNotBlank()) Text(" " + r.unit, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun ListRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun fuelText(d: DashSnapshot?, u: Units, pct: Double?): String {
    val rem = d?.remainingL
    return when {
        rem != null -> "${Units.fmt(u.volume(rem), 1)} ${u.volumeUnit}" + (pct?.let { " · ${Units.fmt(it)}%" } ?: "")
        pct != null -> "${Units.fmt(pct)}% (set the tank size for litres)"
        else -> "—"
    }
}
private fun dpfText(d: DashSnapshot?, u: Units): String {
    if (d == null) return "—"
    val since = d.first("MAZDA_DPF_REGEN_DIST")?.let { "${Units.fmt(u.distance(it))} ${u.distanceUnit} since regen" }
    return listOfNotNull(if (d.regenActive) "Regenerating" else "No regen", since).joinToString(" · ")
}
private fun tripText(d: DashSnapshot?, u: Units): String {
    if (d == null || !d.tripActive) return "No trip"
    val mins = d.tripStartedAt?.let { (System.currentTimeMillis() - it) / 60_000 }
    return "${Units.fmt(u.distance(d.tripKm), 1)} ${u.distanceUnit}" + (mins?.let { " · $it min" } ?: "")
}

// --- Car --------------------------------------------------------------------------------
@Composable
fun CarScreen(m: UiModel, a: Actions) {
    Page("Car") {
        Section("Vehicle") {
            Text(m.vehicle, style = MaterialTheme.typography.titleMedium)
            Muted(m.vehicleSub)
            m.vin?.let { Muted("VIN $it") }
            OutlinedTextField(m.tank, { m.tank = it; a.setTank(it) }, Modifier.fillMaxWidth(),
                label = { Text("Fuel tank (litres)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = a::chooseVehicle) { Text("Change vehicle") }
                TextButton(onClick = a::genericVehicle) { Text("Generic OBD-II") }
            }
        }
        Section("OBD dongle") {
            Text(m.dongle, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = a::chooseDongle) { Text("Choose paired") }
                OutlinedButton(onClick = a::scanAndPair) { Text("Scan & pair") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = a::scanBle) { Text("Scan BLE (no pairing)") }
            }
        }
        Section("Autostart") {
            SwitchRow("Start with the car", m.carTrigger, m.btAutostart) { m.btAutostart = it; a.setAutostart(it) }
            Muted(if (m.linked) "Background start allowed" else "Background start not linked: you'll get a tap-to-start notification")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = a::chooseCarDevices) { Text("Car Bluetooth") }
                if (!m.linked) OutlinedButton(onClick = a::linkCompanion) { Text("Allow background") }
            }
        }
        Section("Profile") {
            Row {
                Column(Modifier.weight(1f)) { Text("${m.supported}", fontSize = 24.sp); Muted("supported signals") }
                Column(Modifier.weight(1f)) { Text("${m.sweepHits}", fontSize = 24.sp); Muted("sweep hits (kept per VIN)") }
            }
            Muted(m.profileNote)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { a.discover(false) }, Modifier.weight(1f)) { Text("Discover") }
                FilledTonalButton(onClick = { a.discover(true) }, Modifier.weight(1f)) { Text("Discover + sweep") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = a::importProfile, Modifier.weight(1f)) { Text("Import profile") }
                OutlinedButton(onClick = a::exportProfile, Modifier.weight(1f)) { Text("Export profile") }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.takeIf { it.isNotBlank() }?.let { Muted(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// --- Home Assistant & settings -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, text) ->
                SegmentedButton(selected = selected == key, onClick = { onPick(key) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size)) { Text(text) }
            }
        }
    }
}

@Composable
fun SettingsScreen(m: UiModel, a: Actions) {
    Page("Home Assistant") {
        Section("Connection") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = if (m.mqtt) OkContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(if (m.mqtt) "Connected" else "Not connected", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge, color = if (m.mqtt) OnOkContainer else MaterialTheme.colorScheme.onSurface)
                }
            }
            Muted(m.connection)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = a::editConnection) { Text("Edit connection") }
                OutlinedButton(onClick = a::testConnection) { Text("Send test") }
            }
            if (m.integration) {
                OutlinedButton(onClick = a::uploadDrives) { Text(if (m.unsent > 0) "Upload drives (${m.unsent})" else "Upload drives") }
                Muted(if (m.unsent > 0) "${m.unsent} drives not fully sent yet. Uploading also runs by itself while the logger is on."
                      else "Every drive on the phone is in Home Assistant.")
            }
        }
        Section("Naming in Home Assistant") {
            OutlinedTextField(m.topicBase, { m.topicBase = it; a.setTopicBase(it) }, Modifier.fillMaxWidth(),
                label = { Text("MQTT topic base") }, singleLine = true)
            OutlinedTextField(m.deviceName, { m.deviceName = it; a.setDeviceName(it) }, Modifier.fillMaxWidth(),
                label = { Text("Device name") }, singleLine = true)
        }
        Section("Appearance") {
            Choice("Layout", listOf("extended" to "Extended", "compact" to "Compact"), m.layout) { m.layout = it; a.setLayout(it) }
            Choice("Theme", listOf("system" to "System", "light" to "Light", "dark" to "Dark"), m.theme) { m.theme = it; a.setTheme(it) }
        }
        Section("Units") {
            val u = m.units
            Choice("Speed & distance", listOf("m" to "km/h", "i" to "mph"), if (u.imperialSpeed) "i" else "m") {
                m.units = u.copy(imperialSpeed = it == "i"); a.setUnits(m.units) }
            Choice("Fuel", listOf("m" to "Litres", "i" to "Gallons"), if (u.gallons) "i" else "m") {
                m.units = u.copy(gallons = it == "i"); a.setUnits(m.units) }
            Choice("Temperature", listOf("m" to "°C", "i" to "°F"), if (u.fahrenheit) "i" else "m") {
                m.units = u.copy(fahrenheit = it == "i"); a.setUnits(m.units) }
            Muted("Display only: Home Assistant always receives metric. Gallons are UK; economy is mpg with gallons.")
        }
        Section("Recording & location") {
            Muted("Every drive is already saved on the phone as a CSV of all readings. GPS adds where you went.")
            SwitchRow("Record GPS track", "GPS in the drive CSV, plus a GPX track per drive", m.gpsRecord) { a.setGpsRecord(it) }
            HorizontalDivider()
            SwitchRow("Send location to HA", "Car location tracker + GPS trip distance", m.gpsToHa) { a.setGpsToHa(it) }
            if (m.gpsNote.isNotBlank()) Muted(m.gpsNote)
        }
        Section("Options") {
            SwitchRow("Start on boot", null, m.startOnBoot) { m.startOnBoot = it; a.setStartOnBoot(it) }
            HorizontalDivider()
            SwitchRow("Unvalidated candidates", "Raw diesel DIDs as diagnostic sensors", m.candidates) { m.candidates = it; a.setCandidates(it) }
            HorizontalDivider()
            SwitchRow("Log sweep hits", "Poll unidentified DIDs for PID hunting", m.logSweep) { m.logSweep = it; a.setLogSweep(it) }
        }
        Section("Polling") {
            Choice("Poll mode", listOf("auto" to "Auto (current behaviour)", "slots" to "Slots"), m.pollMode) { a.setPollMode(it) }
            if (m.pollMode == "slots") {
                Choice("Fast slots per round", listOf("1" to "1", "2" to "2", "3" to "3"), m.fastSlots.toString()) { a.setSlots(it.toInt(), m.mediumSlots) }
                Choice("Medium slots", listOf("2" to "2", "3" to "3"), m.mediumSlots.toString()) { a.setSlots(m.fastSlots, it.toInt()) }
                Muted("Slow slots: " + (6 - m.fastSlots - m.mediumSlots) + " of 6. A multi-PID batch counts as one slot.")
            }
            HorizontalDivider()
            Text("Poll plan", style = MaterialTheme.typography.bodyLarge)
            Muted(m.planNote)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = a::importPollPlan, Modifier.weight(1f)) { Text("Import plan") }
                OutlinedButton(onClick = a::exportPollPlan, Modifier.weight(1f)) { Text("Export plan") }
            }
        }
        Section("Advanced") {
            OutlinedTextField(m.modelYear, { m.modelYear = it; a.setModelYear(it) }, Modifier.fillMaxWidth(),
                label = { Text("Model year (EU VINs have no year code)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(m.sweepRanges, { m.sweepRanges = it; a.setSweepRanges(it) }, Modifier.fillMaxWidth(),
                label = { Text("Sweep ranges (header:from-to, …)") }, singleLine = true)
        }
        Section("Data & logs") {
            TextButton(onClick = a::exportDrive) { Text("Export last drive (CSV)") }
            TextButton(onClick = a::exportTrack) { Text("Export last GPS track (GPX)") }
            TextButton(onClick = a::exportLog) { Text("Export app log") }
            TextButton(onClick = a::importOverlay) { Text("Import overlay JSON") }
        }
    }
}

