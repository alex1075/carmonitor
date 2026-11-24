package uk.co.ashunt.carmonitor.auto

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import uk.co.ashunt.carmonitor.Prefs
import uk.co.ashunt.carmonitor.Status
import uk.co.ashunt.carmonitor.core.DashSnapshot
import uk.co.ashunt.carmonitor.core.Units

/**
 * Read-only Android Auto dashboard (templated, glanceable). It only shows what the phone's logger is
 * already reading; nothing here talks to the car.
 */
class CarDashService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = DashScreen(carContext)
    }
}

class DashScreen(ctx: CarContext) : Screen(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { invalidate(); ui.postDelayed(this, 3000) } }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { ui.post(tick) }
            override fun onStop(owner: LifecycleOwner) { ui.removeCallbacks(tick) }
        })
    }

    override fun onGetTemplate(): Template {
        val u = Prefs(carContext).units()
        val d = Status.dash
        val list = ItemList.Builder()
        // Same titles every refresh (values in the text), so the host treats updates as refreshes.
        rows(d, u).forEach { (title, text) -> list.addItem(Row.Builder().setTitle(title).addText(text).build()) }
        return ListTemplate.Builder()
            .setTitle(if (d == null) "CarMonitor · ${Status.state}" else "CarMonitor")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }

    private fun rows(d: DashSnapshot?, u: Units): List<Pair<String, String>> {
        fun n(vararg ids: String) = d?.first(*ids)
        val speed = n("VSS", "CX5_VSS")?.let { "${Units.fmt(u.speed(it))} ${u.speedUnit}" } ?: "—"
        val rpm = n("RPM", "CX5_RPM")?.let { Units.fmt(it) + " rpm" } ?: "—"
        val coolant = n("ECT", "CX5_ECT")?.let { "${Units.fmt(u.temp(it))} ${u.tempUnit}" } ?: "—"
        val egt = n("CX5_EXH_GAS_T_1", "MZD_EGT_POST_DPF", "DPF1_OUTT", "EGT11")?.let { "${Units.fmt(u.temp(it))} ${u.tempUnit}" }
        val fuel = d?.remainingL?.let { "${Units.fmt(u.volume(it), 1)} ${u.volumeUnit}" }
            ?: n("FLI", "CX5_FLI")?.let { "${Units.fmt(it)}%" } ?: "—"
        val range = d?.rangeKm?.let { "${Units.fmt(u.distance(it))} ${u.distanceUnit}" } ?: "learning"
        val econ = (d?.tripLPer100 ?: d?.avgLPer100)?.let { "${Units.fmt(u.economy(it), 1)} ${u.economyUnit}" } ?: "learning"
        val trip = if (d?.tripActive == true) "${Units.fmt(u.distance(d.tripKm), 1)} ${u.distanceUnit}" else "No trip"
        val regen = when { d == null -> "—"; d.regenActive -> "REGENERATING: keep driving"; else -> "No regen" }
        return listOf(
            "Speed · RPM" to "$speed  ·  $rpm",
            "Coolant · exhaust" to (coolant + (egt?.let { "  ·  $it" } ?: "")),
            "Fuel · range" to "$fuel  ·  $range",
            "Economy" to econ,
            "Trip" to trip,
            "DPF" to regen,
        )
    }
}
