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
    var publishAll: Boolean get() = sp.getBoolean("all", false); set(v) = sp.edit().putBoolean("all", v).apply()
    var publishCandidates: Boolean get() = sp.getBoolean("cand", true); set(v) = sp.edit().putBoolean("cand", v).apply()
    var autoStart: Boolean get() = sp.getBoolean("boot", true); set(v) = sp.edit().putBoolean("boot", v).apply()
    var sweep: String get() = str("sweep", "7E0:0300-04FF, 7E0:0500-05FF"); set(v) = put("sweep", v)

    // --- vehicle (OBDb repo) and HA naming -------------------------------------------
    /** OBDb repo, e.g. "Mazda-CX-5"; "" = generic SAE J1979 only. */
    var vehicleRepo: String get() = str("repo", "Mazda-CX-5"); set(v) = put("repo", v)
    var signalsetFile: String get() = str("ssfile", "default.json"); set(v) = put("ssfile", v)
    var topicBase: String get() = str("tbase", TopicScheme.MAZDA_CX5_LEGACY.base); set(v) = put("tbase", v)
    var haDeviceId: String get() = str("devid", TopicScheme.MAZDA_CX5_LEGACY.deviceId); set(v) = put("devid", v)
    var haDeviceName: String get() = str("devname", TopicScheme.MAZDA_CX5_LEGACY.deviceName); set(v) = put("devname", v)
    var legacyTopics: Boolean get() = sp.getBoolean("legacy", true); set(v) = sp.edit().putBoolean("legacy", v).apply()

    fun vehicle(): VehicleRef? = vehicleRepo.takeIf { it.isNotBlank() }?.let { ObdbCatalog.parseRepoName(it) }

    fun scheme() = TopicScheme(topicBase.trim('/'), haDeviceId, haDeviceName, vehicle()?.model, legacyTopics)

    /** Switch vehicle: reset HA naming to that car's defaults (CX-5 keeps the Pi-logger preset). */
    fun selectVehicle(v: VehicleRef?, file: String) {
        vehicleRepo = v?.repo ?: ""
        signalsetFile = file
        val s = if (v?.repo == "Mazda-CX-5") TopicScheme.MAZDA_CX5_LEGACY else TopicScheme.forVehicle(v)
        topicBase = s.base; haDeviceId = s.deviceId; haDeviceName = s.deviceName; legacyTopics = s.legacy
        profileFile.delete()          // a profile belongs to one car's signalset
        sp.edit().remove("fuelstate").remove("tank").remove("diesel").apply()
    }

    // --- fuel ------------------------------------------------------------------------
    /** Tank size in litres; 0 = unknown (then only cluster-reported litres give "remaining"). */
    var tankLitres: Double
        get() = sp.getFloat("tank", if (vehicleRepo == "Mazda-CX-5") 56f else 0f).toDouble()
        set(v) = sp.edit().putFloat("tank", v.toFloat()).apply()
    var diesel: Boolean
        get() = sp.getBoolean("diesel", vehicleRepo == "Mazda-CX-5")
        set(v) = sp.edit().putBoolean("diesel", v).apply()
    /** FuelTracker state (long-run average, fill-to-fill baseline) carried between drives. */
    var fuelState: Map<String, Any?>
        get() = try { uk.co.ashunt.carmonitor.core.MiniJson.obj(str("fuelstate", "{}")) } catch (_: Exception) { emptyMap() }
        set(v) = put("fuelstate", uk.co.ashunt.carmonitor.core.MiniJson.write(v))

    fun loggerConfig() = LoggerConfig(
        publishAll = publishAll, publishCandidates = publishCandidates,
        fuelType = if (diesel) uk.co.ashunt.carmonitor.core.FuelType.DIESEL else uk.co.ashunt.carmonitor.core.FuelType.PETROL,
        tankLitres = tankLitres.takeIf { it > 0 },
        fuelState = fuelState,
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
        val layers = mutableListOf(SignalSet.parse("SAEJ1979", asset("obdb/SAEJ1979.json")!!))
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
