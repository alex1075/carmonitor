package uk.co.ashunt.carmonitor

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the last uncaught exception so "Export app log" can include it after a restart. */
object CrashLog {
    fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                file(ctx).writeText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date()) + " thread=" + t.name + "\n" + sw)
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}

class CarMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
