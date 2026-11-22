package uk.co.ashunt.carmonitor.core

/** Where published values go (MQTT in the app, a list in tests). */
fun interface Sink {
    fun publish(topic: String, payload: String, retain: Boolean)
}

/**
 * Topics already consumed by the YAML package from the Pi logger (mazda_ha_package.yaml).
 * Publishing here brings the existing HA entities, automations and dashboard back
 * with no HA changes. No discovery is sent for these, to avoid duplicate entities.
 *
 * [sources] are signal ids in priority order; the first *validated* one with a value wins.
 */
data class LegacyTopic(val topic: String, val sources: List<String>, val format: (Double) -> String)

object Topics {
    const val BASE = "mazda/cx5"
    const val STATUS = "$BASE/logger/status"
    const val DEVICE_ID = "mazda_cx5"        // matches the existing HA device identifier
    private const val KM_TO_MI = 0.621371

    private fun dp(n: Int): (Double) -> String = { v -> if (n == 0) Math.round(v).toString() else "%.${n}f".format(java.util.Locale.ROOT, v) }

    val LEGACY = listOf(
        LegacyTopic("$BASE/rpm", listOf("RPM"), dp(0)),
        LegacyTopic("$BASE/speed_kmh", listOf("VSS"), dp(1)),
        LegacyTopic("$BASE/fuel_pct", listOf("FLI"), dp(0)),
        LegacyTopic("$BASE/soot_gL", listOf("MZD_SOOT_GL"), dp(2)),
        LegacyTopic("$BASE/pm_acc_dsd_gL", listOf("MZD_PM_ACC_DSD_GL"), dp(2)),
        LegacyTopic("$BASE/egt_post_dpf_c", listOf("MZD_EGT_POST_DPF", "DPF1_OUTT", "CX5_EXH_GAS_T_2"), dp(0)),
        LegacyTopic("$BASE/dist_since_regen", listOf("MZD_DIST_SINCE_REGEN_KM")) { v -> dp(1)(v * KM_TO_MI) },
        LegacyTopic("$BASE/oil_dilution", listOf("MZD_OIL_DILUTION", "CX5_OIL_DIL_CNT"), dp(0)),
    )
    val LEGACY_SOURCES: Set<String> = LEGACY.flatMap { it.sources }.toSet()

    // Engine-derived topics (also legacy)
    const val TRIP_ACTIVE = "$BASE/trip_active"
    const val TRIP_DIST = "$BASE/trip_distance_mi"
    const val LAST_TRIP_DIST = "$BASE/trip/last_distance_mi"
    const val LAST_TRIP_REGEN = "$BASE/trip/last_regen"
    const val DTC_COUNT = "$BASE/dtc/count"
    const val DTC_CODES = "$BASE/dtc/codes"
    const val DTC_PENDING = "$BASE/dtc/pending"
    // New topics (sent with discovery)
    const val REGEN_ACTIVE = "$BASE/regen_active"

    /** Extra signals worth a sensor in HA by default (if the car answers them). */
    val EXTRAS = setOf(
        "ECT", "EOT", "IAT", "MAP", "AAT", "BARO", "VPWR", "LOAD_PCT",
        "EGT11", "EGT12", "DPF1_DP", "DPF1_INP", "DPF1_OUTP", "DPF1_INT", "DPF1_OUTT",
        "PF_REGEN_STAT", "PF_REGEN_PCT", "PF_REGEN_AVGD", "PF_REGEN_AVGT", "PF_REGEN_TYPE",
        "CX5_EXH_GAS_T_1", "CX5_EXH_GAS_T_2", "CX5_EXH_DIFF_P", "CX5_OIL_DIL_CNT",
        "CX5_EGR_OPEN_MM", "CX5_EGR_OPEN_PERC", "CX5_EGR_DUTY", "CX5_OIL_CHANGE_DIST",
        "CLR_DIST",
    )

    fun signalTopic(id: String) = "$BASE/sig/$id"
    fun rawTopic(key: String) = "$BASE/raw/" + key.replace(':', '_')
}

object HaDiscovery {
    private val UNIT = mapOf(
        "celsius" to "°C", "kilopascal" to "kPa", "kilometersPerHour" to "km/h", "kilometers" to "km",
        "percent" to "%", "volts" to "V", "rpm" to "rpm", "minutes" to "min", "seconds" to "s",
        "bars" to "bar", "degrees" to "°", "gramsPerLiter" to "g/L", "millimeters" to "mm", "gramsPerSecond" to "g/s",
        "litersPerHour" to "L/h", "newtonMeters" to "N·m", "hertz" to "Hz", "milliamperes" to "mA",
    )
    private val DEVICE_CLASS = mapOf(
        "celsius" to "temperature", "kilopascal" to "pressure", "bars" to "pressure",
        "volts" to "voltage", "kilometers" to "distance", "kilometersPerHour" to "speed",
        "millimeters" to "distance", "minutes" to "duration", "seconds" to "duration",
    )
    private val BINARY_UNITS = setOf("offon", "noyes", "yesno")

    private val device = linkedMapOf<String, Any?>(
        "identifiers" to listOf(Topics.DEVICE_ID), "name" to "Mazda CX-5",
        "manufacturer" to "Mazda", "model" to "CX-5 2.2 SkyActiv-D (KF)",
    )

    fun isBinary(s: Signal) = s.fmt.unit in BINARY_UNITS

    /** (config topic, payload) for one signal's sensor. */
    fun forSignal(s: Signal, expireAfterSec: Int = 180): Pair<String, String> {
        val uid = "${Topics.DEVICE_ID}_${s.id.lowercase()}"
        val binary = isBinary(s)
        val comp = if (binary) "binary_sensor" else "sensor"
        val cfg = linkedMapOf<String, Any?>(
            "name" to s.name.replaceFirstChar { it.uppercase() },
            "unique_id" to uid,
            "object_id" to uid,
            "state_topic" to Topics.signalTopic(s.id),
            "availability_topic" to Topics.STATUS,
            "expire_after" to expireAfterSec,
            "device" to device,
        )
        if (binary) {
            cfg["payload_on"] = "1"; cfg["payload_off"] = "0"
        } else if (s.fmt.map == null) {
            UNIT[s.fmt.unit]?.let { cfg["unit_of_measurement"] = it }
            DEVICE_CLASS[s.fmt.unit]?.let { cfg["device_class"] = it }
            cfg["state_class"] = "measurement"
        }
        return "homeassistant/$comp/$uid/config" to MiniJson.write(cfg)
    }

    fun forRaw(key: String): Pair<String, String> {
        val uid = "${Topics.DEVICE_ID}_raw_" + key.replace(':', '_').lowercase()
        val cfg = linkedMapOf<String, Any?>(
            "name" to "Raw ${key.replace(':', ' ')}",
            "unique_id" to uid, "object_id" to uid,
            "state_topic" to Topics.rawTopic(key),
            "value_template" to "{{ value_json.u16 }}",
            "json_attributes_topic" to Topics.rawTopic(key),
            "availability_topic" to Topics.STATUS,
            "expire_after" to 180, "state_class" to "measurement",
            "entity_category" to "diagnostic",
            "device" to device,
        )
        return "homeassistant/sensor/$uid/config" to MiniJson.write(cfg)
    }

    fun regenActive(): Pair<String, String> {
        val uid = "${Topics.DEVICE_ID}_regen_active"
        return "homeassistant/binary_sensor/$uid/config" to MiniJson.write(linkedMapOf(
            "name" to "DPF regen active", "unique_id" to uid, "object_id" to uid,
            "state_topic" to Topics.REGEN_ACTIVE, "payload_on" to "on", "payload_off" to "off",
            "availability_topic" to Topics.STATUS, "expire_after" to 180, "icon" to "mdi:fire",
            "device" to device,
        ))
    }
}
