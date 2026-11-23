package uk.co.ashunt.carmonitor.core

/** Where published values go (MQTT in the app, a list in tests). */
fun interface Sink {
    fun publish(topic: String, payload: String, retain: Boolean)
}

/**
 * Publishes a decoded signal (by id) to a fixed topic, bypassing discovery. Used by the
 * legacy Mazda preset whose HA entities are defined in YAML on fixed topics.
 * [sources] are signal ids in priority order; the first validated one with a value wins.
 */
data class LegacyTopic(val topic: String, val sources: List<String>, val format: (Double) -> String)

/**
 * Per-vehicle MQTT/HA naming. Generic cars get `car/<slug>/…` topics and one HA device.
 * [legacy] = the CX-5 Pi-logger topic layout (miles, fixed names, no discovery for those).
 */
data class TopicScheme(
    val base: String,
    val deviceId: String,
    val deviceName: String,
    val model: String? = null,
    val legacy: Boolean = false,
) {
    val status get() = "$base/logger/status"
    val tripActive get() = "$base/trip_active"
    val tripDist get() = if (legacy) "$base/trip_distance_mi" else "$base/trip/distance_km"
    val lastTripDist get() = if (legacy) "$base/trip/last_distance_mi" else "$base/trip/last_distance_km"
    val lastTripRegen get() = "$base/trip/last_regen"
    val dtcCount get() = "$base/dtc/count"
    val dtcCodes get() = "$base/dtc/codes"
    val dtcPending get() = "$base/dtc/pending"
    val regenActive get() = "$base/regen_active"
    val fuelRate get() = "$base/fuel/rate_lh"
    val fuelEconomy get() = "$base/fuel/economy_l100km"
    val fuelEconomyMpg get() = "$base/fuel/economy_mpg"
    val tripFuel get() = "$base/fuel/trip_used_l"
    val tripEconomy get() = "$base/fuel/trip_l100km"
    val tripEconomyMpg get() = "$base/fuel/trip_mpg"
    val avgEconomy get() = "$base/fuel/average_l100km"
    val fuelRemaining get() = "$base/fuel/remaining_l"
    val fuelRange get() = "$base/fuel/range_km"
    val fuelSource get() = "$base/fuel/source"
    /** Distance unit factor for engine-derived trip topics (km -> topic unit). */
    val distFactor get() = if (legacy) KM_TO_MI else 1.0

    fun signalTopic(id: String) = "$base/sig/$id"
    fun rawTopic(key: String) = "$base/raw/" + key.replace(':', '_')

    val legacyTopics: List<LegacyTopic> get() = if (!legacy) emptyList() else listOf(
        LegacyTopic("$base/rpm", listOf("RPM"), dp(0)),
        LegacyTopic("$base/speed_kmh", listOf("VSS"), dp(1)),
        LegacyTopic("$base/fuel_pct", listOf("FLI"), dp(0)),
        LegacyTopic("$base/soot_gL", listOf("MZD_SOOT_GL"), dp(2)),
        LegacyTopic("$base/pm_acc_dsd_gL", listOf("MZD_PM_ACC_DSD_GL"), dp(2)),
        LegacyTopic("$base/egt_post_dpf_c", listOf("MZD_EGT_POST_DPF", "DPF1_OUTT", "CX5_EXH_GAS_T_2"), dp(0)),
        LegacyTopic("$base/dist_since_regen", listOf("MZD_DIST_SINCE_REGEN_KM")) { v -> dp(1)(v * KM_TO_MI) },
        LegacyTopic("$base/oil_dilution", listOf("MZD_OIL_DILUTION", "CX5_OIL_DIL_CNT"), dp(0)),
    )
    val legacySources: Set<String> by lazy { legacyTopics.flatMap { it.sources }.toSet() }

    companion object {
        const val KM_TO_MI = 0.621371
        private fun dp(n: Int): (Double) -> String = { v -> if (n == 0) Math.round(v).toString() else "%.${n}f".format(java.util.Locale.ROOT, v) }

        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

        /** The CX-5 preset: keeps the Pi logger's topics so the existing HA YAML package keeps working. */
        val MAZDA_CX5_LEGACY = TopicScheme("mazda/cx5", "mazda_cx5", "Mazda CX-5", "CX-5 2.2 SkyActiv-D (KF)", legacy = true)

        /** Default scheme for any OBDb vehicle repo, e.g. "Toyota-RAV4" -> car/toyota_rav4. */
        fun forVehicle(v: VehicleRef?): TopicScheme {
            if (v == null) return TopicScheme("car/generic", "carmonitor_generic", "Car")
            val s = slug(v.repo)
            return TopicScheme("car/$s", "carmonitor_$s", "${v.make} ${v.model}".trim(), v.model)
        }
    }
}

/** Which signals get an HA sensor by default (when publishAll is off). */
object DefaultSignals {
    /** Core SAE J1979 signals that are useful on any car, if it answers them. */
    val CORE = setOf(
        "RPM", "VSS", "ECT", "EOT", "IAT", "MAP", "AAT", "BARO", "VPWR", "LOAD_PCT", "FLI", "CLR_DIST", "MIL", "DTC_CNT",
        "EGT11", "EGT12", "DPF1_DP", "DPF1_INP", "DPF1_OUTP", "DPF1_INT", "DPF1_OUTT",
        "PF_REGEN_STAT", "PF_REGEN_PCT", "PF_REGEN_AVGD", "PF_REGEN_AVGT", "PF_REGEN_TYPE",
    )

    /** A signal is on by default if it's core J1979, or OBDb curated it with a suggestedMetric. */
    fun include(s: Signal): Boolean = s.id in CORE || s.suggestedMetric != null
}

object HaDiscovery {
    private val UNIT = mapOf(
        "celsius" to "°C", "kilopascal" to "kPa", "kilometersPerHour" to "km/h", "kilometers" to "km",
        "percent" to "%", "volts" to "V", "rpm" to "rpm", "minutes" to "min", "seconds" to "s",
        "bars" to "bar", "degrees" to "°", "gramsPerLiter" to "g/L",
        "millimeters" to "mm", "gramsPerSecond" to "g/s",
        "litersPerHour" to "L/h", "newtonMeters" to "N·m", "hertz" to "Hz", "milliamperes" to "mA",
        "amps" to "A", "kilowatts" to "kW", "kilowattHours" to "kWh", "liters" to "L", "psi" to "psi",
    )
    private val DEVICE_CLASS = mapOf(
        "celsius" to "temperature", "kilopascal" to "pressure", "bars" to "pressure", "psi" to "pressure",
        "volts" to "voltage", "kilometers" to "distance", "kilometersPerHour" to "speed",
        "millimeters" to "distance", "minutes" to "duration", "seconds" to "duration",
        "amps" to "current", "kilowatts" to "power", "kilowattHours" to "energy",
    )
    private val BINARY_UNITS = setOf("offon", "noyes", "yesno")

    private fun device(t: TopicScheme) = linkedMapOf<String, Any?>(
        "identifiers" to listOf(t.deviceId), "name" to t.deviceName,
    ).also { m -> t.model?.let { m["model"] = it } }

    fun isBinary(s: Signal) = s.fmt.unit in BINARY_UNITS

    private fun base(t: TopicScheme, uid: String, name: String, state: String) = linkedMapOf<String, Any?>(
        "name" to name, "unique_id" to uid, "object_id" to uid, "state_topic" to state,
        "availability_topic" to t.status, "device" to device(t),
    )

    /** (config topic, payload) for one signal's sensor. */
    fun forSignal(t: TopicScheme, s: Signal, expireAfterSec: Int = 180): Pair<String, String> {
        val uid = "${t.deviceId}_${s.id.lowercase()}"
        val binary = isBinary(s)
        val cfg = base(t, uid, s.name.replaceFirstChar { it.uppercase() }, t.signalTopic(s.id))
        cfg["expire_after"] = expireAfterSec
        if (binary) {
            cfg["payload_on"] = "1"; cfg["payload_off"] = "0"
        } else if (s.fmt.map == null) {
            UNIT[s.fmt.unit]?.let { cfg["unit_of_measurement"] = it }
            DEVICE_CLASS[s.fmt.unit]?.let { cfg["device_class"] = it }
            cfg["state_class"] = "measurement"
        }
        return "homeassistant/${if (binary) "binary_sensor" else "sensor"}/$uid/config" to MiniJson.write(cfg)
    }

    fun forRaw(t: TopicScheme, key: String): Pair<String, String> {
        val uid = "${t.deviceId}_raw_" + key.replace(':', '_').lowercase()
        val cfg = base(t, uid, "Raw ${key.replace(':', ' ')}", t.rawTopic(key))
        cfg["value_template"] = "{{ value_json.u16 }}"
        cfg["json_attributes_topic"] = t.rawTopic(key)
        cfg["expire_after"] = 180; cfg["state_class"] = "measurement"; cfg["entity_category"] = "diagnostic"
        return "homeassistant/sensor/$uid/config" to MiniJson.write(cfg)
    }

    fun regenActive(t: TopicScheme): Pair<String, String> {
        val uid = "${t.deviceId}_regen_active"
        val cfg = base(t, uid, "DPF regen active", t.regenActive)
        cfg["payload_on"] = "on"; cfg["payload_off"] = "off"; cfg["icon"] = "mdi:fire"
        return "homeassistant/binary_sensor/$uid/config" to MiniJson.write(cfg)
    }

    /** Diagnostic sensor used by the app's "Test HA connection" button. */
    fun connectionTest(t: TopicScheme): Pair<String, String> {
        val uid = "${t.deviceId}_connection_test"
        val cfg = base(t, uid, "Connection test", "${t.base}/test")
        cfg.remove("availability_topic")
        cfg["entity_category"] = "diagnostic"; cfg["icon"] = "mdi:lan-check"
        return "homeassistant/sensor/$uid/config" to MiniJson.write(cfg)
    }

    /** Fuel/economy/range entities (all schemes; these are new topics even on the CX-5 preset). */
    fun fuelEntities(t: TopicScheme): List<Pair<String, String>> {
        fun s(key: String, name: String, topic: String, extra: Map<String, Any?>, live: Boolean): Pair<String, String> {
            val uid = "${t.deviceId}_$key"
            val cfg = base(t, uid, name, topic); cfg.putAll(extra)
            if (live) cfg["expire_after"] = 180 else cfg.remove("availability_topic")
            return "homeassistant/sensor/$uid/config" to MiniJson.write(cfg)
        }
        val m = mapOf("state_class" to "measurement")
        return listOf(
            s("fuel_rate", "Fuel rate", t.fuelRate, m + mapOf("unit_of_measurement" to "L/h", "icon" to "mdi:fuel"), true),
            s("fuel_economy", "Fuel economy", t.fuelEconomy, m + mapOf("unit_of_measurement" to "L/100km", "icon" to "mdi:gauge"), true),
            s("fuel_economy_mpg", "Fuel economy (mpg)", t.fuelEconomyMpg, m + mapOf("unit_of_measurement" to "mpg", "icon" to "mdi:gauge"), true),
            s("trip_fuel", "Trip fuel used", t.tripFuel, m + mapOf("unit_of_measurement" to "L", "device_class" to "volume"), false),
            s("trip_economy", "Trip economy", t.tripEconomy, m + mapOf("unit_of_measurement" to "L/100km", "icon" to "mdi:map-marker-path"), false),
            s("trip_economy_mpg", "Trip economy (mpg)", t.tripEconomyMpg, m + mapOf("unit_of_measurement" to "mpg", "icon" to "mdi:map-marker-path"), false),
            s("average_economy", "Average economy", t.avgEconomy, m + mapOf("unit_of_measurement" to "L/100km", "icon" to "mdi:chart-line"), false),
            s("fuel_remaining", "Fuel remaining", t.fuelRemaining, m + mapOf("unit_of_measurement" to "L", "device_class" to "volume_storage"), false),
            s("fuel_range", "Range", t.fuelRange, m + mapOf("unit_of_measurement" to "km", "device_class" to "distance", "icon" to "mdi:gas-station"), false),
            s("fuel_source", "Fuel rate source", t.fuelSource, mapOf("entity_category" to "diagnostic", "icon" to "mdi:information-outline"), false),
        )
    }

    /** Engine-derived entities for non-legacy schemes (legacy ones live in the user's YAML). */
    fun engineEntities(t: TopicScheme): List<Pair<String, String>> {
        if (t.legacy) return emptyList()
        fun sensor(key: String, name: String, topic: String, extra: Map<String, Any?> = emptyMap(), comp: String = "sensor"): Pair<String, String> {
            val uid = "${t.deviceId}_$key"
            val cfg = base(t, uid, name, topic); cfg.putAll(extra)
            // Trip summaries and DTCs are retained facts: keep them visible while the car is off.
            if (key != "trip_active") cfg.remove("availability_topic")
            return "homeassistant/$comp/$uid/config" to MiniJson.write(cfg)
        }
        return listOf(
            sensor("trip_active", "Trip active", t.tripActive, mapOf("payload_on" to "on", "payload_off" to "off", "device_class" to "moving"), "binary_sensor"),
            sensor("trip_distance", "Trip distance", t.tripDist, mapOf("unit_of_measurement" to "km", "device_class" to "distance", "state_class" to "measurement")),
            sensor("last_trip_distance", "Last trip distance", t.lastTripDist, mapOf("unit_of_measurement" to "km", "device_class" to "distance")),
            sensor("last_trip_regen", "Last trip regen", t.lastTripRegen, mapOf("icon" to "mdi:fire")),
            sensor("dtc_count", "Fault count", t.dtcCount, mapOf("icon" to "mdi:alert-circle", "state_class" to "measurement")),
            sensor("dtc_codes", "Fault codes", t.dtcCodes, mapOf("icon" to "mdi:alert")),
            sensor("dtc_pending", "Pending faults", t.dtcPending, mapOf("icon" to "mdi:alert-outline")),
        )
    }
}
