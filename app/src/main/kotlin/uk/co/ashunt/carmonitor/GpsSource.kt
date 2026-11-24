package uk.co.ashunt.carmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import uk.co.ashunt.carmonitor.core.Fix
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Phone GPS for the duration of one logging session only (framework LocationManager, no Play
 * services). Fixes queue up here and the logger thread drains them.
 */
class GpsSource(private val ctx: Context) : LocationListener {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val q = ConcurrentLinkedQueue<Fix>()
    var running = false; private set

    fun permitted() = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (!permitted()) return "location permission not granted"
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "GPS is switched off on the phone"
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this, Looper.getMainLooper())
            running = true; null
        } catch (e: SecurityException) { "location not allowed: ${e.message}" }
    }

    fun stop() {
        if (running) try { lm?.removeUpdates(this) } catch (_: Exception) {}
        running = false; q.clear()
    }

    fun drain(): List<Fix> { val out = ArrayList<Fix>(); while (true) out.add(q.poll() ?: break); return out }

    override fun onLocationChanged(l: Location) {
        q.add(Fix(l.time, l.latitude, l.longitude,
            altM = if (l.hasAltitude()) l.altitude else null,
            speedMs = if (l.hasSpeed()) l.speed.toDouble() else null,
            accM = if (l.hasAccuracy()) l.accuracy.toDouble() else null))
        while (q.size > 600) q.poll()          // never grow unbounded if the logger stalls
    }
    @Deprecated("API < 29 only") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
