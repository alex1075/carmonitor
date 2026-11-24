package uk.co.ashunt.carmonitor.core

import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS fix from the phone. */
data class Fix(
    val tMs: Long, val lat: Double, val lon: Double,
    val altM: Double? = null, val speedMs: Double? = null, val accM: Double? = null,
)

object Geo {
    private const val R_KM = 6371.0088
    fun haversineKm(a: Fix, b: Fix): Double {
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R_KM * asin(sqrt(h))
    }
}

/**
 * Distance from GPS fixes: drops imprecise fixes and jumps faster than any car, ignores jitter
 * below a few metres so a parked phone doesn't accumulate distance.
 */
class GpsOdometer(private val maxAccM: Double = 30.0, private val minStepM: Double = 5.0, private val maxKmh: Double = 250.0) {
    var km = 0.0; private set
    private var last: Fix? = null

    fun reset() { km = 0.0; last = null }

    /** Adds [f]; returns true if it counted (good accuracy, plausible). */
    fun add(f: Fix): Boolean {
        if ((f.accM ?: 0.0) > maxAccM) return false
        val prev = last ?: run { last = f; return true }
        val d = Geo.haversineKm(prev, f)
        val hours = (f.tMs - prev.tMs) / 3_600_000.0
        if (hours <= 0 || d / hours > maxKmh) return false
        if (d * 1000 < minStepM) return true              // jitter: keep the anchor
        km += d; last = f
        return true
    }
}

/** Minimal GPX 1.1 track writer (one track, one segment). */
class GpxWriter(private val w: Writer, name: String) {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    init {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"CarMonitor\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n" +
            "<trk><name>${esc(name)}</name><trkseg>\n")
    }
    fun point(f: Fix) {
        w.write(String.format(Locale.ROOT, "<trkpt lat=\"%.7f\" lon=\"%.7f\">", f.lat, f.lon))
        f.altM?.let { w.write(String.format(Locale.ROOT, "<ele>%.1f</ele>", it)) }
        w.write("<time>${iso.format(Date(f.tMs))}</time></trkpt>\n")
    }
    fun close() { w.write("</trkseg></trk>\n</gpx>\n"); w.flush() }
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Home Assistant side of GPS: a device tracker for the car plus a GPS-measured trip distance. */
object GpsHa {
    fun locationTopic(t: TopicScheme) = "${t.base}/gps/location"
    fun tripKmTopic(t: TopicScheme) = "${t.base}/gps/trip_distance_km"

    fun entities(t: TopicScheme): List<Pair<String, String>> {
        val dev = linkedMapOf<String, Any?>("identifiers" to listOf(t.deviceId), "name" to t.deviceName)
        val tracker = linkedMapOf<String, Any?>(
            "name" to "Location", "unique_id" to "${t.deviceId}_location", "object_id" to "${t.deviceId}_location",
            "json_attributes_topic" to locationTopic(t), "source_type" to "gps", "icon" to "mdi:car", "device" to dev,
        )
        val trip = linkedMapOf<String, Any?>(
            "name" to "GPS trip distance", "unique_id" to "${t.deviceId}_gps_trip_distance",
            "object_id" to "${t.deviceId}_gps_trip_distance", "state_topic" to tripKmTopic(t),
            "unit_of_measurement" to "km", "device_class" to "distance", "state_class" to "measurement",
            "icon" to "mdi:map-marker-distance", "device" to dev,
        )
        return listOf(
            "homeassistant/device_tracker/${t.deviceId}_location/config" to MiniJson.write(tracker),
            "homeassistant/sensor/${t.deviceId}_gps_trip_distance/config" to MiniJson.write(trip),
        )
    }

    /** Retained, so HA keeps showing where the car was parked. */
    fun locationPayload(f: Fix): String = MiniJson.write(linkedMapOf(
        "latitude" to f.lat, "longitude" to f.lon, "gps_accuracy" to (f.accM ?: 0.0),
        "altitude" to f.altM, "speed_kmh" to f.speedMs?.let { it * 3.6 },
    ).filterValues { it != null })
}
