package uk.co.ashunt.carmonitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * "The car is on" detector: fires when one of the car's Bluetooth devices (head unit / CMU, or the
 * dongle itself) connects to the phone, then starts the logger, which dials the OBD dongle at once.
 * ACL_CONNECTED is exempt from the implicit-broadcast ban, so this works from the manifest.
 * Starting a foreground service from the background additionally needs the companion-device
 * link (Settings: "Allow background start"); without it we post a tap-to-start notification.
 */
class BtTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val prefs = Prefs(ctx)
        if (!prefs.btAutostart || prefs.deviceAddress == null) return
        val dev: BluetoothDevice = deviceExtra(intent) ?: return
        if (!prefs.isTrigger(dev.address)) return
        val name = nameOf(ctx, dev)
        Status.log("car Bluetooth connected: $name, starting logger")
        try {
            ObdService.start(ctx, ObdService.ACTION_LOG)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) when not companion-linked
            Status.log("background start refused (${e.javaClass.simpleName}); posted tap-to-start notification")
            notifyTapToStart(ctx, name)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceExtra(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @SuppressLint("MissingPermission")
    private fun nameOf(ctx: Context, d: BluetoothDevice): String =
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) d.name ?: d.address else d.address

    private fun notifyTapToStart(ctx: Context, name: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("trigger", "Car detected", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 2,
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_START_FROM_NOTIFICATION),
            PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(ctx, "trigger")
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("$name connected")
            .setContentText("Tap to start logging. Enable 'Allow background start' to skip this.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
