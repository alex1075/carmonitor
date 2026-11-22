package uk.co.ashunt.carmonitor

import android.content.Context
import uk.co.ashunt.carmonitor.core.LoggerConfig
import uk.co.ashunt.carmonitor.core.SignalSet
import uk.co.ashunt.carmonitor.core.SweepRange
import uk.co.ashunt.carmonitor.core.VehicleProfile
import java.io.File

class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("carmonitor", Context.MODE_PRIVATE)

    var deviceAddress: String? get() = sp.getString("device", null); set(v) = sp.edit().putString("device", v).apply()
    var brokerHost: String get() = sp.getString("host", "") ?: ""; set(v) = sp.edit().putString("host", v).apply()
    var brokerPort: Int get() = sp.getInt("port", 1883); set(v) = sp.edit().putInt("port", v).apply()
    var brokerUser: String get() = sp.getString("user", "") ?: ""; set(v) = sp.edit().putString("user", v).apply()
    var brokerPass: String get() = sp.getString("pass", "") ?: ""; set(v) = sp.edit().putString("pass", v).apply()
    /** EU CX-5 VINs carry no model-year code, so this decides which OBDb year filters apply. */
    var modelYear: Int get() = sp.getInt("year", 2017); set(v) = sp.edit().putInt("year", v).apply()
    var publishAll: Boolean get() = sp.getBoolean("all", false); set(v) = sp.edit().putBoolean("all", v).apply()
    var publishCandidates: Boolean get() = sp.getBoolean("cand", true); set(v) = sp.edit().putBoolean("cand", v).apply()
    var autoStart: Boolean get() = sp.getBoolean("boot", true); set(v) = sp.edit().putBoolean("boot", v).apply()
    var sweep: String get() = sp.getString("sweep", "7E0:0300-04FF, 7E0:0500-05FF") ?: ""; set(v) = sp.edit().putString("sweep", v).apply()

    fun loggerConfig() = LoggerConfig(publishAll = publishAll, publishCandidates = publishCandidates)

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
    val overlayFile get() = File(ctx.filesDir, "overlay.json")      // user-edited overlay (imported)
    val logDir get() = File(ctx.filesDir, "drives").apply { mkdirs() }

    fun profile(): VehicleProfile? = profileFile.takeIf { it.exists() }?.let {
        try { VehicleProfile.fromJson(it.readText()) } catch (_: Exception) { null }
    }

    fun overlayJson(): String =
        overlayFile.takeIf { it.exists() }?.readText()
            ?: ctx.assets.open("overlay/mazda-skyactiv-d.json").bufferedReader().readText()

    fun library(): SignalSet {
        fun asset(p: String) = ctx.assets.open(p).bufferedReader().readText()
        return SignalSet.merge(
            "cx5-diesel",
            SignalSet.parse("SAEJ1979", asset("obdb/SAEJ1979.json")),
            SignalSet.parse("Mazda-CX-5", asset("obdb/Mazda-CX-5.json")),
            SignalSet.parse("overlay", overlayJson()),
        )
    }
}
