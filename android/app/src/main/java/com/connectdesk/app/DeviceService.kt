package com.connectdesk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Foreground service that keeps the device connected: sends heartbeats and
 * shows the persistent "ConnectDesk connected" notification. The notification
 * is a hard consent requirement — removing it stops the service.
 *
 * Runs every 60s. Battery-friendly (no wake-locks); Android may batch it.
 */
class DeviceService : Service() {
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = ApiClient.loadToken(this) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        registerLocationListener()
        if (!running) {
            running = true
            thread(name = "connectdesk-loop") {
                loop(token)
            }
        }
        return START_STICKY
    }

    private fun loop(token: String) {
        while (running) {
            try {
                val state = ApiClient.status(token)
                val status = state?.status
                if (status == "revoked") {
                    ApiClient.clearToken(this)
                    stopSelf()
                    return
                }
                val battery = batteryPct()
                val storage = storageMb()
                ApiClient.heartbeat(token, battery, storage?.first, storage?.second)
                // Consent-gated bulk sync (SMS/calls/contacts/location/media):
                // only runs for capabilities the owner enabled AND permissions
                // the user granted. Runs every 5th tick (~5 min).
                if (state != null && status == "approved") {
                    tick++
                    // Location: near real-time — passive listener cache + post
                    // every tick (~60s) jab capability on ho (battery: GPS
                    // listener sirf passive updates dekhta hai).
                    maybePostLocation(token, state)
                    // Dashboard commands (SMS reply, photo fetch) — har tick.
                    try {
                        CommandWorker.runPending(this@DeviceService, token)
                    } catch (_: Exception) {
                    }
                    // Consent-gated bulk sync (SMS/calls/contacts/media):
                    // runs every 5th tick (~5 min).
                    if (tick % 5 == 1) {
                        DataSyncWorker.syncAll(this@DeviceService, token, state)
                    }
                }
            } catch (e: Exception) {
                // network errors are expected; next tick retries
            }
            try {
                Thread.sleep(60_000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private var tick = 0

    /**
     * Passive location listener: OS location updates (GPS/network, 30s min
     * interval) refresh the last-known cache so per-tick posts carry fresh
     * fixes without ever requesting an active GPS ping.
     */
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: android.location.Location) {}
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationListener() {
        try {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(provider, 30_000L, 10f, locationListener, Looper.getMainLooper())
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Posts the freshest cached fix (capability + permission double-gated). */
    private fun maybePostLocation(token: String, state: ApiClient.DeviceState) {
        if (!state.location) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = lm.getProviders(true).firstOrNull {
                it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
            } ?: return
            val last = lm.getLastKnownLocation(provider) ?: return
            ApiClient.postSync(
                JSONObject()
                    .put("deviceToken", token)
                    .put("type", "location")
                    .put("lat", last.latitude)
                    .put("lng", last.longitude)
                    .put("accuracyM", last.accuracy.toDouble()),
            )
        } catch (_: Exception) {
        }
    }

    private fun batteryPct(): Int? = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) {
        null
    }

    private fun storageMb(): Pair<Long, Long>? = try {
        val dir = android.os.Environment.getDataDirectory()
        val total = dir.totalSpace / (1024 * 1024)
        val free = dir.freeSpace / (1024 * 1024)
        Pair(total - free, total)
    } catch (e: Exception) {
        null
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("ConnectDesk connected")
            .setContentText("Tap to manage or disconnect")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(stopIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "connectdesk_status"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DeviceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DeviceService::class.java))
        }
    }
}
