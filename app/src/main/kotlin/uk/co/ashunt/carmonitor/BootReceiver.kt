package uk.co.ashunt.carmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** BOOT_COMPLETED is an allowed trigger for a connectedDevice foreground service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = Prefs(ctx)
        if (p.autoStart && p.deviceAddress != null) ObdService.start(ctx, ObdService.ACTION_LOG)
    }
}
