package uk.co.ashunt.carmonitor.core

import java.io.File

/**
 * User-chosen poll tiers by command key ("7E0:22DA9F"), one file per VIN. Kept outside the profile, so
 * discovery, re-discovery and profile import never touch it. Commands it doesn't list keep the engine's tiers.
 */
data class PollPlan(val vin: String, val tiers: Map<String, Int>) {
    fun toJson(): String = MiniJson.write(linkedMapOf("format" to FORMAT, "vin" to vin,
        "tiers" to tiers.toSortedMap().mapValues { NAMES[it.value] }), pretty = true)

    companion object {
        const val FORMAT = "carmonitor-pollplan-1"
        /** Engine tier numbers. */
        val NAMES = mapOf(2 to "fast", 1 to "medium", 0 to "slow")
        private val KEY = Regex("^[0-9A-F]{3}:[0-9A-F]{2,}$")

        /** Throws IllegalArgumentException with a message the user can act on. */
        fun fromJson(json: String): PollPlan {
            @Suppress("UNCHECKED_CAST")
            val m = MiniJson.parse(json) as? Map<String, Any?> ?: throw IllegalArgumentException("not a JSON object")
            require(m["format"] == FORMAT) { "not a poll plan (format must be $FORMAT)" }
            val vin = (m["vin"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("poll plan has no VIN")
            val raw = m["tiers"] as? Map<*, *> ?: throw IllegalArgumentException("poll plan has no \"tiers\" object")
            val tiers = LinkedHashMap<String, Int>()
            for ((k, v) in raw) {
                val key = k.toString().trim().uppercase()
                require(KEY.matches(key)) { "bad command key \"$k\" (expected header:request, e.g. 7E0:22DA9F)" }
                tiers[key] = NAMES.entries.firstOrNull { it.value == v }?.key
                    ?: throw IllegalArgumentException("bad tier \"$v\" for $key (fast, medium or slow)")
            }
            return PollPlan(vin, tiers)
        }

        fun file(dir: File, vin: String) = File(dir, vin.replace(Regex("[^A-Za-z0-9]"), "_") + ".json")
        fun load(dir: File, vin: String): PollPlan? =
            file(dir, vin).takeIf { it.exists() }?.let { try { fromJson(it.readText()) } catch (_: Exception) { null } }
        fun save(dir: File, plan: PollPlan) { dir.mkdirs(); file(dir, plan.vin).writeText(plan.toJson()) }
    }
}
