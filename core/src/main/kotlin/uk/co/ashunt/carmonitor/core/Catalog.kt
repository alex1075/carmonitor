package uk.co.ashunt.carmonitor.core

/**
 * OBDb vehicle catalogue. OBDb keeps one GitHub repo per make+model ("Toyota-RAV4") plus
 * make-level fallback repos ("Toyota") and the generic "SAEJ1979" repo. Each has
 * signalsets/v3/default.json and optional year-range overrides ("2012-2020.json").
 */
data class VehicleRef(val repo: String, val make: String, val model: String) {
    val isMakeFallback get() = model.isEmpty()
    val label get() = if (model.isEmpty()) "$make (all models)" else "$make $model"
}

object ObdbCatalog {
    const val ORG = "OBDb"
    const val GENERIC = "SAEJ1979"
    private val NON_VEHICLE = setOf(GENERIC, "obdb.community", ".github", ".schemas", ".vehicle-template", ".meta")
    /** Makes whose names contain a hyphen in OBDb repo names. */
    private val MULTIWORD_MAKES = listOf("Mercedes-Benz", "Land-Rover", "Alfa-Romeo", "Aston-Martin", "Rolls-Royce", "Lynk-Co")

    fun reposPageUrl(page: Int) = "https://api.github.com/orgs/$ORG/repos?per_page=100&page=$page&sort=full_name"
    fun dirUrl(repo: String) = "https://api.github.com/repos/$ORG/$repo/contents/signalsets/v3"
    fun rawUrl(repo: String, file: String) = "https://raw.githubusercontent.com/$ORG/$repo/main/signalsets/v3/$file"

    fun parseRepoName(name: String): VehicleRef? {
        if (name in NON_VEHICLE || name.startsWith(".")) return null
        val multi = MULTIWORD_MAKES.firstOrNull { name == it || name.startsWith("$it-") }
        if (multi != null) return VehicleRef(name, multi.replace('-', ' '), name.removePrefix(multi).removePrefix("-").replace('-', ' '))
        val i = name.indexOf('-')
        return if (i < 0) VehicleRef(name, name, "")
        else VehicleRef(name, name.substring(0, i), name.substring(i + 1).replace('-', ' '))
    }

    /** Parse one page of the GitHub "list org repos" response. */
    fun parseReposPage(json: String): List<VehicleRef> =
        MiniJson.parse(json).asList().mapNotNull { r ->
            val m = r.asMap()
            if (m["archived"] == true) null else m["name"]?.toString()?.let(::parseRepoName)
        }

    /** Names of files in a GitHub contents-API directory listing. */
    fun parseDirListing(json: String): List<String> =
        MiniJson.parse(json).asList().mapNotNull { it.asMap()["name"]?.toString() }.filter { it.endsWith(".json") }

    private val RANGE = Regex("""^(\d{4})-(\d{4})\.json$""")
    private val SINGLE = Regex("""^(\d{4})\.json$""")

    /** Pick the signalset file for [year]: a matching "YYYY-YYYY.json"/"YYYY.json", else default.json. */
    fun chooseFile(files: List<String>, year: Int?): String {
        if (year != null) {
            val hits = files.mapNotNull { f ->
                RANGE.matchEntire(f)?.let { m -> val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); if (year in a..b) f to (b - a) else null }
                    ?: SINGLE.matchEntire(f)?.let { m -> if (m.groupValues[1].toInt() == year) f to 0 else null }
            }
            hits.minByOrNull { it.second }?.let { return it.first }   // narrowest range wins
        }
        return "default.json"
    }

    fun search(all: List<VehicleRef>, query: String, make: String? = null): List<VehicleRef> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return all.filter { v ->
            (make == null || v.make.equals(make, true)) &&
                words.all { w -> v.label.lowercase().contains(w) || v.repo.lowercase().contains(w) }
        }.sortedWith(compareBy({ it.make.lowercase() }, { !it.isMakeFallback }, { it.model.lowercase() }))
    }

    fun makeFallbackFor(v: VehicleRef, all: List<VehicleRef>): VehicleRef? =
        if (v.isMakeFallback) null else all.firstOrNull { it.isMakeFallback && it.make.equals(v.make, true) }
}

/** Make from a VIN's World Manufacturer Identifier (first 3, or first 2, characters). Common WMIs only. */
object Wmi {
    private val THREE = mapOf(
        "JMZ" to "Mazda", "JM1" to "Mazda", "JM3" to "Mazda", "1YV" to "Mazda", "4F2" to "Mazda",
        "JTD" to "Toyota", "JTE" to "Toyota", "JTM" to "Toyota", "JTN" to "Toyota", "SB1" to "Toyota", "NMT" to "Toyota",
        "JHM" to "Honda", "SHH" to "Honda", "SHS" to "Honda", "JN1" to "Nissan", "SJN" to "Nissan", "VSK" to "Nissan",
        "WVW" to "Volkswagen", "WVG" to "Volkswagen", "WV2" to "Volkswagen", "3VW" to "Volkswagen",
        "WAU" to "Audi", "WUA" to "Audi", "TRU" to "Audi", "WBA" to "BMW", "WBS" to "BMW", "WBY" to "BMW",
        "WMW" to "MINI", "WDB" to "Mercedes Benz", "WDD" to "Mercedes Benz", "W1K" to "Mercedes Benz", "W1N" to "Mercedes Benz",
        "WF0" to "Ford", "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford", "SFA" to "Ford",
        "VF1" to "Renault", "VF3" to "Peugeot", "VF7" to "Citroen", "ZFA" to "Fiat", "ZAR" to "Alfa Romeo",
        "TMB" to "Skoda", "VSS" to "Seat", "YV1" to "Volvo", "YV4" to "Volvo", "SAL" to "Land Rover", "SAJ" to "Jaguar",
        "KMH" to "Hyundai", "TMA" to "Hyundai", "KNA" to "Kia", "KND" to "Kia", "U5Y" to "Kia",
        "JF1" to "Subaru", "JF2" to "Subaru", "JS2" to "Suzuki", "TSM" to "Suzuki", "JMB" to "Mitsubishi", "JA3" to "Mitsubishi",
        "5YJ" to "Tesla", "7SA" to "Tesla", "LRW" to "Tesla", "XP7" to "Tesla", "W0L" to "Opel", "W0V" to "Opel",
        "1G1" to "Chevrolet", "1GC" to "Chevrolet", "2T1" to "Toyota", "4T1" to "Toyota", "5YF" to "Toyota",
        "1HG" to "Honda", "2HG" to "Honda", "1N4" to "Nissan", "WP0" to "Porsche", "WP1" to "Porsche", "SCC" to "Lotus",
        "LBV" to "BMW", "LVS" to "Ford", "SCA" to "Rolls Royce", "SCF" to "Aston Martin", "ZHW" to "Lamborghini", "ZFF" to "Ferrari",
    )

    fun make(vin: String?): String? {
        if (vin == null || vin.length < 3) return null
        return THREE[vin.substring(0, 3).uppercase()]
    }
}
