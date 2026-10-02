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
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Foreground service that keeps the device connected: sends a heartbeat every
 * ~60s and shows the persistent "ConnectDesk connected" notification. The
 * notification is a hard consent requirement — removing it stops the service.
 *
 * Loop design notes (hardened after a "connected but no data" incident):
 *  - The heartbeat POST is the FIRST thing each tick, so a slow or failing
 *    status/sync call can never starve it.
 *  - Every failure mode is caught as Throwable, and a failed tick retries after
 *    a short delay instead of sleeping the full interval.
 *  - Loop state is mirrored into ServiceStatus so the UI can prove the service
 *    is genuinely alive (the "Connected" label alone does not prove that).
 */
class DeviceService : Service() {
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ApiClient.attach(this)
        val token = ApiClient.loadToken(this) ?: run {
            ServiceStatus.markStop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Must happen before any slow work, or Android kills the service.
        startAsForeground()
        registerLocationListener()
        if (!running) {
            running = true
            ServiceStatus.markStart()
            thread(name = "connectdesk-loop") { loop(token) }
            // A second, faster poller so dashboard commands (media download,
            // SMS reply, camera shot) start almost immediately instead of
            // waiting for the next 60s heartbeat.
            thread(name = "connectdesk-commands") { fastCommandLoop(token) }
        }
        return START_STICKY
    }

    private val commandBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Polls the command queue on a short cadence. Guarded by an atomic flag so
     * it can never overlap with the main loop's command handling — the queue is
     * marked delivered server-side, but overlapping polls could execute one
     * command twice (two SMS replies, two uploads).
     */
    private fun fastCommandLoop(token: String) {
        while (running) {
            try {
                Thread.sleep(COMMAND_POLL_MS)
                // Commands only matter once the device is actually approved.
                if (commandBusy.compareAndSet(false, true)) {
                    try {
                        val probe = ApiClient.status(token)
                        if (probe?.status == "approved") {
                            CommandWorker.runPending(this@DeviceService, token)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        commandBusy.set(false)
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
            }
        }
    }

    private fun loop(token: String) {
        var failures = 0
        while (running) {
            var ok = false
            try {
                // 1. Heartbeat FIRST — never blocked by anything else.
                val battery = batteryPct()
                val storage = storageMb()
                val beat = ApiClient.heartbeat(
                    token, battery, storage?.first, storage?.second,
                )
                if (beat != null) {
                    ok = true
                    failures = 0
                } else {
                    failures++
                    ServiceStatus.markError(
                        ApiClient.lastError ?: "heartbeat rejected",
                    )
                }

                // 2. Everything else is best-effort and must never kill the loop.
                if (ok) {
                    try {
                        val state = ApiClient.status(token)
                        ServiceStatus.markBeat(state?.status ?: beat)
                        val status = state?.status
                        if (status == "revoked") {
                            ApiClient.clearToken(this)
                            running = false
                            stopSelf()
                            return
                        }
                        if (state != null && status == "approved") {
                            tick++
                            // Location: ~60s freshness from the passive cache.
                            try { maybePostLocation(token, state) } catch (_: Throwable) {}
                            // Commands (SMS reply, photo fetch, screen share).
                            try {
                                if (commandBusy.compareAndSet(false, true)) {
                                    try {
                                        CommandWorker.runPending(this@DeviceService, token)
                                    } finally {
                                        commandBusy.set(false)
                                    }
                                }
                            } catch (_: Throwable) {}
                            // Bulk sync (SMS/calls/contacts/media) every 5th tick.
                            if (tick % 5 == 1) {
                                try {
                                    DataSyncWorker.syncAll(this@DeviceService, token, state)
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (t: Throwable) {
                        ServiceStatus.markError("post-beat: ${t.message ?: t::class.simpleName}")
                    }
                }
            } catch (t: Throwable) {
                // Catch Throwable, not just Exception: an Error here (OOM, no
                // thread space) would otherwise kill the heartbeat forever.
                failures++
                ServiceStatus.markError(t.message ?: t::class.simpleName ?: "unknown error")
            }

            // Retry quickly after a failure so the device self-heals instead of
            // staying silent for a full minute.
            val wait = if (ok) TICK_MS else minOf(RETRY_MS, TICK_MS)
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return
            }
        }
        ServiceStatus.markStop()
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
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Posts a fresh location fix. Uses the cached fix when it is recent, and
     * otherwise asks the OS for one live fix (rather than waiting for the
     * passive listener, which can stay empty indefinitely if the phone has not
     * moved). Still capability + permission double-gated.
     */
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
            // Prefer network: it resolves in seconds, GPS can take 30s+.
            val provider = lm.getProviders(true).firstOrNull { it == LocationManager.NETWORK_PROVIDER }
                ?: lm.getProviders(true).firstOrNull { it == LocationManager.GPS_PROVIDER }
                ?: return

            val cached = lm.getLastKnownLocation(provider)
            val ageMs = cached?.let { System.currentTimeMillis() - it.time } ?: Long.MAX_VALUE
            if (cached != null && ageMs < CACHE_MAX_AGE_MS) {
                postLocation(token, cached, "cached")
                return
            }
            // Stale or missing: ask for one live fix. Non-blocking — the cached
            // value (if any) has already been sent above.
            requestLiveFix(token, lm, provider)
        } catch (_: Throwable) {
        }
    }

    /** Asks the OS for a single fresh fix and posts it when it arrives. */
    @SuppressLint("MissingPermission")
    private fun requestLiveFix(token: String, lm: LocationManager, provider: String) {
        if (liveFixPending) return
        liveFixPending = true
        val consumer = android.os.Consumer<android.location.Location> { loc ->
            liveFixPending = false
            if (loc != null) postLocation(token, loc, "live")
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(
                    provider,
                    null,
                    androidx.core.content.ContextCompat.getMainExecutor(this),
                    consumer,
                )
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(
                    provider,
                    object : LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            liveFixPending = false
                            postLocation(token, location, "live")
                        }

                        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {
                            liveFixPending = false
                        }

                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    },
                    Looper.getMainLooper(),
                )
            }
        } catch (_: Throwable) {
            liveFixPending = false
        }
    }

    private fun postLocation(token: String, loc: android.location.Location, source: String) {
        try {
            ApiClient.postSync(
                JSONObject()
                    .put("deviceToken", token)
                    .put("type", "location")
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("accuracyM", loc.accuracy.toDouble())
                    .put("source", source),
            )
        } catch (_: Throwable) {
        }
    }

    @Volatile
    private var liveFixPending = false

    private fun batteryPct(): Int? = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Throwable) {
        null
    }

    private fun storageMb(): Pair<Long, Long>? = try {
        val dir = android.os.Environment.getDataDirectory()
        val total = dir.totalSpace / (1024 * 1024)
        val free = dir.freeSpace / (1024 * 1024)
        Pair(total - free, total)
    } catch (_: Throwable) {
        null
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("ConnectDesk connected")
            .setContentText("Tap to manage or disconnect")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Declare the type explicitly: the 2-arg form is rejected on
                // newer Android versions when the manifest declares a type.
                startForeground(
                    NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            // Never let a notification problem stop the heartbeat loop.
            runCatching { startForeground(NOTIF_ID, notif) }
        }
    }

    override fun onDestroy() {
        running = false
        ServiceStatus.markStop()
        try {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .removeUpdates(locationListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "connectdesk_status"
        private const val NOTIF_ID = 42

        /** Normal heartbeat cadence. */
        private const val TICK_MS = 60_000L

        /** Fast retry after a failed tick, so the device self-heals. */
        private const val RETRY_MS = 15_000L

        /** A cached fix younger than this is good enough to post as-is. */
        private const val CACHE_MAX_AGE_MS = 90_000L

        /**
         * How often the device checks for new dashboard commands. Short enough
         * that a download/photo request feels immediate, long enough to be
         * battery-friendly.
         */
        private const val COMMAND_POLL_MS = 12_000L

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DeviceService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DeviceService::class.java)) }
        }
    }
}
