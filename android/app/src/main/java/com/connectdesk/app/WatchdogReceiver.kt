package com.connectdesk.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * A slow heartbeat for the heartbeat itself.
 *
 * WHY THIS EXISTS
 * ---------------
 * `DeviceService` is a foreground service with START_STICKY, and Android
 * normally restarts it on its own. But some OEM skins (Realme/OPPO/Xiaomi are
 * the usual offenders) kill even foreground services aggressively and skip the
 * sticky restart, which is exactly the "dashboard offline for hours for no
 * reason" report. This receiver is the belt under that braces:
 *
 *  - every [INTERVAL_MS] (about 15 minutes, Doze-tolerant via
 *    `setAndAllowWhileIdle`) it re-starts the service if a device token is
 *    stored — starting an already-running service is harmless (it just
 *    delivers another onStartCommand);
 *  - it re-arms itself every time it fires, so the watch never expires;
 *  - it is armed from `DeviceService.onCreate` and from `BootReceiver`, so
 *    boot, app-update and process-revival all re-arm it.
 *
 * PERMISSION NOTES
 * ----------------
 * `setAndAllowWhileIdle` needs NO special permission and survives Doze
 * (inexact — the system may defer it, which is fine for a 15-minute watch).
 * The service start is wrapped in runCatching: Android 12+ can refuse a
 * background FGS start; when that happens the receiver just exits and tries
 * again next interval, and the battery-optimization toggle in Setup is the
 * durable fix for the OEMs that cause it.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ApiClient.attach(context)
        if (ApiClient.loadToken(context) == null) return
        runCatching { DeviceService.start(context) }
        schedule(context)
    }

    companion object {
        private const val INTERVAL_MS = 15L * 60L * 1000L
        private const val REQUEST_CODE = 8891

        /** Arms (or re-arms) the next watchdog tick. Cheap and idempotent. */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + INTERVAL_MS,
                    pi,
                )
            }
        }
    }
}
