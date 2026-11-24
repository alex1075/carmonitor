package uk.co.ashunt.carmonitor

import android.content.Context
import uk.co.ashunt.carmonitor.core.LoggerConfig
import uk.co.ashunt.carmonitor.core.ObdbCatalog
import uk.co.ashunt.carmonitor.core.SignalSet
import uk.co.ashunt.carmonitor.core.SweepRange
import uk.co.ashunt.carmonitor.core.TopicScheme
import uk.co.ashunt.carmonitor.core.VehicleProfile
import uk.co.ashunt.carmonitor.core.VehicleRef
import java.io.File

class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("carmonitor", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()

    var deviceAddress: String? get() = sp.getString("device", null); set(v) = put("device", v)
    var brokerHost: String get() = str("host", ""); set(v) = put("host", v)
    var brokerPort: Int get() = sp.getInt("port", 1883); set(v) = sp.edit().putInt("port", v).apply()
    var brokerUser: String get() = str("user", ""); set(v) = put("user", v)
    /** Encrypted at rest (Android Keystore). Migrates a pre-0.3 plaintext value on first read. */
    var brokerPass: String
        get() = secret("pass_enc", legacyPlainKey = "pass")
        set(v) = put("pass_enc", SecretStore.encrypt(v))

    // --- connection mode ------------------------------------------------------------
    /** "mqtt" = direct to the broker; "ha" = Home Assistant REST API with a long-lived token. */
    var connMode: String get() = str("conn", "mqtt"); set(v) = put("conn", v)
    var haUrl: String get() = str("haurl", ""); set(v) = put("haurl", v)
    var haToken: String
        get() = secret("hatok_enc")
        set(v) = put("hatok_enc", SecretStore.encrypt(v))

    private fun secret(key: String, legacyPlainKey: String? = null): String {
        sp.getString(key, null)?.let { return SecretStore.decrypt(it) }
        val plain = legacyPlainKey?.let { sp.getString(it, null) } ?: return ""
        sp.edit().putString(key, SecretStore.encrypt(plain)).remove(legacyPlainKey).apply()
        return plain
    }

    fun connectionSummary(): String = when (connMode) {
        "ha" -> if (haUrl.isBlank()) "Home Assistant token: not set" else "Home Assistant API · $haUrl · token " + (if (haToken.isEmpty()) "missing" else "saved")
        else -> if (brokerHost.isBlank()) "MQTT broker: not set" else "MQTT · $brokerHost:$brokerPort" + (if (brokerUser.isNotBlank()) " · user $brokerUser" else "")
    }

    // --- autostart on Bluetooth connection ----------------------------------------
    var btAutostart: Boolean get() = sp.getBoolean("btauto", true); set(v) = sp.edit().putBoolean("btauto", v).apply()
    /**
     * The car's own Bluetooth devices (e.g. the Mazda CMU / head unit). When any of these connects
     * to the phone, the car is on: start the logger and dial the OBD dongle. Empty = only the dongle.
     */
    var carDevices: Set<String>
        get() = sp.getStringSet("cardevs", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("cardevs", v).apply()
    fun isTrigger(address: String) = address in carDevices || address == deviceAddress
    /** Used when the VIN carries no model-year code (most EU/JDM VINs). */
    var modelYear: Int get() = sp.getInt("year", 2017); set(v) = sp.edit().putInt("year", v).apply()
    /** Every supported signal goes to HA (the archive wants everything; no longer a setting). */
    val publishAll: Boolean get() = true
    var publishCandidates: Boolean get() = sp.getBoolean("cand", true); set(v) = sp.edit().putBoolean("cand", v).apply()
    var autoStart: Boolean get() = sp.getBoolean("boot", true); set(v) = sp.edit().putBoolean("boot", v).apply()
    var logSweepHits: Boolean get() = sp.getBoolean("logsweep", true); set(v) = sp.edit().putBoolean("logsweep", v).apply()
    var sweep: String get() = str("sweep", "7E0:0300-04FF, 7E0:0500-05FF"); set(v) = put("sweep", v)

    // --- vehicle (OBDb repo) and HA naming -------------------------------------------
    /** OBDb repo, e.g. "Mazda-CX-5"; "" = generic SAE J1979 only. */
    var vehicleRepo: String get() = str("repo", ""); set(v) = put("repo", v)
    var signalsetFile: String get() = str("ssfile", "default.json"); set(v) = put("ssfile", v)
    var topicBase: String get() = str("tbase", TopicScheme.forVehicle(null).base); set(v) = put("tbase", v)
    var haDeviceId: String get() = str("devid", TopicScheme.forVehicle(null).deviceId); set(v) = put("devid", v)
    var haDeviceName: String get() = str("devname", TopicScheme.forVehicle(null).deviceName); set(v) = put("devname", v)

    fun vehicle(): VehicleRef? = vehicleRepo.takeIf { it.isNotBlank() }?.let { ObdbCatalog.parseRepoName(it) }

    fun scheme() = TopicScheme(topicBase.trim('/'), haDeviceId, haDeviceName, vehicle()?.model, legacy = false)

    /** Switch vehicle: reset HA naming to that car's defaults (CX-5 keeps the Pi-logger preset).
     *  Each car keeps its own discovery profile and fuel settings, so switching back to a car the app
     *  already knows needs no re-discovery. */
    fun selectVehicle(v: VehicleRef?, file: String) {
        stashVehicle()
        vehicleRepo = v?.repo ?: ""
        signalsetFile = file
        val s = TopicScheme.forVehicle(v)
        topicBase = s.base; haDeviceId = s.deviceId; haDeviceName = s.deviceName
        restoreVehicle()                  // a car the app already knows gets its own topics/HA device back
    }

    private val profilesDir get() = File(ctx.filesDir, "profiles").apply { mkdirs() }
    /** One slot per vehicle repo + signalset file (a profile belongs to one signalset). */
    private fun vehicleKey() = (vehicleRepo.ifBlank { "generic" } + "_" + signalsetFile.removeSuffix(".json"))
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun stashVehicle() {
        val k = vehicleKey()
        val saved = File(profilesDir, "$k.json")
        if (profileFile.exists()) profileFile.copyTo(saved, overwrite = true)
        sp.edit().putFloat("tank@$k", sp.getFloat("tank", 0f))
            .putString("fuelstate@$k", sp.getString("fuelstate", "{}"))
            .putString("tbase@$k", topicBase).putString("devid@$k", haDeviceId).putString("devname@$k", haDeviceName).apply()
    }
    private fun restoreVehicle() {
        val k = vehicleKey()
        val saved = File(profilesDir, "$k.json")
        if (saved.exists()) saved.copyTo(profileFile, overwrite = true) else profileFile.delete()
        sp.edit().putFloat("tank", sp.getFloat("tank@$k", 0f))
            .putString("fuelstate", sp.getString("fuelstate@$k", "{}")).apply()
        sp.getString("tbase@$k", null)?.let { topicBase = it }
        sp.getString("devid@$k", null)?.let { haDeviceId = it }
        sp.getString("devname@$k", null)?.let { haDeviceName = it }
    }
    /** True if this vehicle already has a saved profile (switching to it skips discovery). */
    fun hasSavedProfile(v: VehicleRef?, file: String): Boolean {
        val k = ((v?.repo ?: "").ifBlank { "generic" } + "_" + file.removeSuffix(".json")).replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(profilesDir, "$k.json").exists() || (k == vehicleKey() && profileFile.exists())
    }

    // --- fuel ------------------------------------------------------------------------
    /** Tank size in litres; 0 = unknown (then only cluster-reported litres give "remaining"). */
    var tankLitres: Double
        get() = sp.getFloat("tank", 0f).toDouble()
        set(v) = sp.edit().putFloat("tank", v.toFloat()).apply()
    /** FuelTracker state (long-run average, fill-to-fill baseline) carried between drives. */
    var fuelState: Map<String, Any?>
        get() = try { uk.co.ashunt.carmonitor.core.MiniJson.obj(str("fuelstate", "{}")) } catch (_: Exception) { emptyMap() }
        set(v) = put("fuelstate", uk.co.ashunt.carmonitor.core.MiniJson.write(v))

    fun loggerConfig() = LoggerConfig(
        publishAll = publishAll, publishCandidates = publishCandidates,
        tankLitres = tankLitres.takeIf { it > 0 },
        fuelState = fuelState,
        logSweepHits = logSweepHits,
    )

    /** "7E0:0300-04FF, 7E0:F400-F4FF" -> ranges. Invalid entries are ignored. */
    fun sweepRanges(): List<SweepRange> = sweep.split(',', ';', '\n').mapNotNull { part ->
        val m = Regex("""^\s*([0-9A-Fa-f]{3})\s*:\s*([0-9A-Fa-f]{1,4})\s*-\s*([0-9A-Fa-f]{1,4})\s*$""").matchEntire(part)
            ?: return@mapNotNull null
        val (h, a, b) = m.destructured
        val from = a.toInt(16); val to = b.toInt(16)
        if (to < from || to - from > 0x0FFF) null else SweepRange(h.uppercase(), from, to)
    }

    // --- files ------------------------------------------------------------------
    val profileFile get() = File(ctx.filesDir, "profile.json")
    /** Permanent per-car sweep results (see SweepHits): never deleted by vehicle switches or re-discovery. */
    private fun sweepFile(vin: String) = File(File(ctx.filesDir, "sweep").apply { mkdirs() },
        vin.replace(Regex("[^A-Za-z0-9]"), "_") + ".json")
    fun storedSweep(vin: String): List<uk.co.ashunt.carmonitor.core.ProbeResult> =
        sweepFile(vin).takeIf { it.exists() }?.let { try { uk.co.ashunt.carmonitor.core.SweepHits.fromJson(it.readText()) } catch (_: Exception) { null } }
            ?: emptyList()
    /** Add [hits] to this car's stored sweep results; returns the merged set. */
    fun addSweep(vin: String, hits: List<uk.co.ashunt.carmonitor.core.ProbeResult>): List<uk.co.ashunt.carmonitor.core.ProbeResult> {
        val merged = uk.co.ashunt.carmonitor.core.SweepHits.merge(storedSweep(vin), hits)
        sweepFile(vin).writeText(uk.co.ashunt.carmonitor.core.SweepHits.toJson(vin, merged))
        return merged
    }
    val overlayFile get() = File(ctx.filesDir, "overlay.json")      // user-imported overlay
    val logDir get() = File(ctx.filesDir, "drives").apply { mkdirs() }

    fun profile(): VehicleProfile? = profileFile.takeIf { it.exists() }?.let {
        try { VehicleProfile.fromJson(it.readText()) } catch (_: Exception) { null }
    }

    private fun asset(p: String): String? = try {
        ctx.assets.open(p).bufferedReader().readText()
    } catch (_: Exception) { null }

    /**
     * SAE J1979 (bundled) + make fallback + model signalset (downloaded, or bundled for the CX-5)
     * + overlay (user-imported, else bundled overlay/<repo>.json). Later layers override earlier.
     */
    fun library(): SignalSet {
        val obdb = ObdbClient(ctx)
        val layers = mutableListOf(
            SignalSet.parse("SAEJ1979", asset("obdb/SAEJ1979.json")!!),
            SignalSet.parse("fixes", asset("overlay/_fixes.json")!!),
        )
        val v = vehicle()
        if (v != null) {
            if (!v.isMakeFallback) obdb.cachedSignalset(v.make.replace(' ', '-')).takeIf { it.exists() }
                ?.let { layers += SignalSet.parse(v.make, it.readText()) }
            val model = obdb.cachedSignalset(v.repo).takeIf { it.exists() }?.readText() ?: asset("obdb/${v.repo}.json")
            model?.let { layers += SignalSet.parse(v.repo, it) }
        }
        val overlay = overlayFile.takeIf { it.exists() }?.readText() ?: v?.let { asset("overlay/${it.repo}.json") }
        overlay?.let { layers += SignalSet.parse("overlay", it) }
        return SignalSet.merge(v?.repo ?: "generic", *layers.toTypedArray())
    }

    fun hasSignalset(): Boolean {
        val v = vehicle() ?: return true
        return ObdbClient(ctx).cachedSignalset(v.repo).exists() || asset("obdb/${v.repo}.json") != null
    }
}
