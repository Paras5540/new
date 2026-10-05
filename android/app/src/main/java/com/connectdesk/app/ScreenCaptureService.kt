package com.connectdesk.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Live screen share: captures the display via MediaProjection, compresses
 * each frame to JPEG (~640px wide, ~45KB) and uploads it over HTTPS. The
 * dashboard polls the newest frame (~1 fps effective).
 *
 * CONSENT CHAIN (hard requirements, none can be skipped):
 *  1. Dashboard owner enabled the screen_share capability.
 *  2. Dashboard owner started a screen_share session (30-min hard cap).
 *  3. THE USER taps "Allow" on this device and accepts Android's
 *     MediaProjection system dialog — shown by the OS for every session.
 *  4. A persistent notification stays visible while sharing; the Stop
 *     button (notification or app) ends capture instantly.
 *
 * The server answers "no_session" the moment the session is stopped, and
 * this service shuts down on receiving it. Nothing records to disk.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    /**
     * The VirtualDisplay's OWN thread, separate from the upload loop's.
     *
     * WHY THIS HAD TO BE SPLIT
     * -------------------------
     * `createVirtualDisplay(..., callback = captureHandler)` used to hand the
     * render pipeline the very thread that then spends the whole tick doing a
     * blocking HTTPS POST. While that POST is in flight the render callbacks
     * cannot run, so the compositor has nowhere to deliver frames, and the
     * next tick's `acquireLatestImage()` can return null -- reported as "no
     * frame from the virtual display" and counted as a failure, even though
     * the mirror itself was fine. The longer the connection, the longer the
     * starvation, and on a weak link the stream could never produce a single
     * frame: `Session active — frames ka wait` with no reason on the page,
     * because from the service's point of view the reader really was empty.
     *
     * Rendering and uploading must not share a thread. The upload loop keeps
     * `captureHandler`; the VirtualDisplay gets `renderHandler`, which is idle
     * except while it is delivering buffers.
     */
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var seq = 0L
    private var width = 720
    private var intervalMs = 1000L
    private var staleStreak = 0

    /**
     * Wall-clock instant after which a share that has still never delivered a
     * frame gives up.
     *
     * WHY THIS IS A TIME AND NOT A COUNT
     * ----------------------------------
     * The rule used to be "6 consecutive failures", which at the default one
     * second cadence is six seconds. A MediaProjection's first frame routinely
     * takes longer than that on a cold start, on a slow device, or while the
     * display is waking, so a share that was about to work was torn down
     * exactly when it was warming up — and the page showed nothing. Counting
     * also scaled with the interval, so at a 5 s cadence the same rule meant
     * thirty seconds of guessing instead of six.
     *
     * [START_GRACE_MS] is the honest number: long enough for a cold start on a
     * slow phone, short enough that a genuinely broken share still ends and
     * reports itself.
     */
    @Volatile
    private var giveUpAt = 0L

    /**
     * Why the last tick produced no frame.
     *
     * Every failure path used to be swallowed by a bare `catch`, so the service
     * could die with nothing anywhere saying why and the dashboard sat on
     * "frames ka wait" forever. This carries the reason out to the heartbeat,
     * where the page can show it instead of guessing.
     */
    @Volatile
    private var lastErrorReason = ""

    /**
     * Outcome of one capture tick.
     *
     * A plain Boolean could not tell "the server accepted a frame" from "an
     * upload was already in flight, so this tick did nothing" -- and the second
     * one was being counted as the first. That reset the failure counter on a
     * stream that was not actually delivering anything.
     */
    private enum class Outcome { SENT, BUSY, FAILED }

    /**
     * Frames skipped because the last upload had not finished yet.
     *
     * A frame is captured far faster than 1/s, and each upload is a blocking
     * HTTPS POST. Uploading every captured frame therefore queued work faster
     * than the connection could drain it, so the queue grew without bound and
     * the view the user was watching fell further and further behind — the
     * classic "live view freezes" symptom. One upload at a time, and any frame
     * that arrives while one is in flight is simply skipped, keeps latency at
     * one frame instead of an ever-growing backlog.
     */
    private val uploading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Wall-clock of the last completed upload, for the fresh-frame check. */
    @Volatile
    private var lastUploadAt = 0L

    private val projectionCallback = projectionStopCallback

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // `startForegroundService()` obliges the service to call
        // `startForeground()` within 5 seconds or Android throws
        // `ForegroundServiceDidNotStartInTimeException` and kills the process.
        //
        // This call used to sit BELOW the two early `return START_NOT_STICKY`
        // paths below (no token / no projection result). On those paths the
        // service returned WITHOUT ever calling startForeground, so the app
        // was killed by the platform -- and because the companion sets
        // `isSharing = true` optimistically at start time, the dashboard kept
        // reporting "armed on phone" over a process that no longer existed.
        // That is the shape of "screen share never works, and nothing on the
        // phone says why".
        //
        // Promoting it means every exit from here is legal: either the service
        // is a foreground service, or it stopped itself.
        // Every exit from here is recorded. The failure paths used to be a bare
        // `stopSelf()`, so a share that died before its first upload left the
        // dashboard on "Session active — frames ka wait" with the badge still
        // reading "waiting for consent" and NO reason anywhere: the phone had
        // nothing to report and the page had nothing to show.
        try {
            startAsForeground()
        } catch (e: Exception) {
            return abort("Android refused the foreground service: ${e.message ?: e.javaClass.simpleName}")
        }

        val token = ApiClient.loadToken(this) ?: return abort("phone has no device token")
        this.token = token

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        // A previously granted projection is reused instead of asking again —
        // see `grantedProjection` for why this is possible and when.
        val reuse = intent?.getBooleanExtra(EXTRA_REUSE, false) == true
        val existing = grantedProjection
        if (!reuse && (resultCode == Int.MIN_VALUE || data == null)) {
            // No projection at all: cannot (and must not) capture.
            return abort("no MediaProjection consent result came back from the phone")
        }

        // `intent` is a nullable parameter of onStartCommand, so these have to
        // be safe calls. A null intent (the service being restarted) still has
        // to produce sane defaults rather than crash here.
        width = intent?.getIntExtra(EXTRA_WIDTH, 720)?.coerceIn(360, 1080) ?: 720
// The dashboard asks for 1 second. The floor is now 300ms so a fast
        // connection can go quicker, and the ceiling is unchanged at 5s to
        // stop a client from asking for a frame flood.
        intervalMs = intent?.getIntExtra(EXTRA_INTERVAL_MS, 1000)?.coerceIn(300, 5000)?.toLong() ?: 1000L

        if (running) return START_STICKY
        running = true
        // Wall-clock budget for the FIRST delivered frame, not a count of
        // tries. See `giveUpAt` in the capture loop.
        giveUpAt = System.currentTimeMillis() + START_GRACE_MS
        // A fresh attempt starts with a clean slate, so the page never shows the
        // PREVIOUS session's failure reason over a share that has just begun.
        lastError = ""
        lastErrorReason = ""
        staleStreak = 0

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (reuse && existing != null) {
            projection = existing
        } else {
            try {
                projection = mpm.getMediaProjection(resultCode, data!!)
            } catch (e: Exception) {
                // The reason this used to be invisible: it was `catch (_: Exception)`.
                // Android throws here when the consent result has already been
                // spent (a one-shot result code) or when the projection was
                // revoked system-side between the dialog and here — and the
                // service stopped with no `isSharing`, no frame and no message.
                return abort("Android refused the screen projection: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        projection?.registerCallback(projectionCallback, mainHandler)
        // Park the live projection so the NEXT session can reuse it and not
        // show the system dialog again.
        grantedProjection = projection


        captureThread = HandlerThread("connectdesk-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        renderThread = HandlerThread("connectdesk-screen-render").also { it.start() }
        renderHandler = Handler(renderThread!!.looper)
        // A throw here (an unsupported pixel format, a rejected surface size)
        // used to escape onStartCommand and take the whole PROCESS down, so
        // even the heartbeat went with it and the dashboard just saw the phone
        // go offline. It is now an ordinary, reported failure.
        try {
            setupVirtualDisplay()
        } catch (e: Exception) {
            return abort("could not open the virtual display: ${e.message ?: e.javaClass.simpleName}")
        }
        // The VirtualDisplay exists, so capture is genuinely starting. Only
        // NOW is the device honestly "sharing" -- this is the value the
        // dashboard reads on every heartbeat.
        isSharing = true
        // Capture is genuinely running, so whatever the phone was waiting on
        // the owner to do has happened. Until this line the dashboard kept
        // showing "phone par owner ko Allow dabana hai" over a share that was
        // already live.
        pendingOwnerAction = ""
        captureHandler?.post(captureLoop)

        return START_STICKY
    }

    private var token: String? = null

    /**
     * Ends this share attempt AND leaves the reason behind.
     *
     * `lastError` is reported read-only on the heartbeat, so whatever ends the
     * service has to write the reason here. A silent `stopSelf()` is the one
     * thing the dashboard cannot act on.
     */
    private fun abort(reason: String): Int {
        lastError = reason
        lastErrorReason = reason
        running = false
        isSharing = false
        stopSelf()
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        // Keep aspect; scale so the longer side <= width target.
        val scale = width.toFloat() / maxOf(screenW, screenH)
        val outW = (screenW * scale).toInt().coerceAtLeast(240)
        val outH = (screenH * scale).toInt().coerceAtLeast(240)
        imageReader = ImageReader.newInstance(outW, outH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "ConnectDeskScreen",
            outW, outH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, renderHandler,
        )
    }

    /** Captures one frame per tick and uploads it; backs off on failures. */
    private val captureLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                when (captureAndUpload()) {
                    Outcome.SENT -> {
                        staleStreak = 0
                        lastError = ""
                    }
                    // A tick skipped because the previous HTTPS POST is still in
                    // flight is neither a delivered frame nor a failure: it must
                    // not reset the counter (a wedged upload would then look
                    // healthy forever) and must not raise it either.
                    Outcome.BUSY -> Unit
                    Outcome.FAILED -> staleStreak++
                }
                if (System.currentTimeMillis() >= giveUpAt) {
                    abort("no frame delivered for ${START_GRACE_MS / 1000}s ($lastErrorReason)")
                    return
                }
            } catch (e: Exception) {
                lastErrorReason = e.message ?: e.javaClass.simpleName
                staleStreak++
                if (System.currentTimeMillis() >= giveUpAt) {
                    abort("capture crashed: $lastErrorReason")
                    return
                }
            }
            // Re-post on the CAPTURE thread. This used to be `mainHandler`, so
            // every tick after the first ran the ImageReader read, the Bitmap
            // copy, the JPEG encode AND a blocking HTTPS POST on the main looper.
            // Doing network I/O on the main looper is what killed the live
            // camera stream, and here it failed on every tick, was swallowed by
            // the catch above, pushed `staleStreak` to 6 and made the service
            // stop itself about six seconds after starting - while the page was
            // still being told "Screen sharing started on device". The whole
            // reported symptom follows from this one line.
            captureHandler?.postDelayed(this, intervalMs)
        }
    }

    /** Captures and uploads one frame; reports what actually happened. */
    private fun captureAndUpload(): Outcome {
        val reader = imageReader ?: run {
            lastErrorReason = "image reader is gone"
            return Outcome.FAILED
        }
        val deviceToken = token ?: run {
            lastErrorReason = "no device token"
            return Outcome.FAILED
        }
        // Never start a second upload while one is in flight (see `uploading`).
        if (!uploading.compareAndSet(false, true)) return Outcome.BUSY
        try {
            val image: Image = try {
                // No new image at all. The display refreshes many times per
                // second, so at a 1 s cadence a null here genuinely means the
                // mirror is not producing frames.
                reader.acquireLatestImage() ?: run {
                    lastErrorReason = "no frame from the virtual display"
                    return Outcome.FAILED
                }
            } catch (e: Exception) {
                lastErrorReason = "acquireLatestImage failed: ${e.message ?: e.javaClass.simpleName}"
                return Outcome.FAILED
            }
            val jpeg = try {
                imageToJpeg(image)
            } catch (e: Exception) {
                lastErrorReason = "jpeg encode failed: ${e.message ?: e.javaClass.simpleName}"
                null
            } finally {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
            val bytes = jpeg ?: return Outcome.FAILED
            if (bytes.size > MAX_JPEG_BYTES) {
                lastErrorReason = "frame too large (${bytes.size} bytes)"
                return Outcome.FAILED
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val result = ApiClient.pushScreenFrame(
                deviceToken,
                ++seq,
                reader.width,
                reader.height,
                b64,
            )
            lastUploadAt = System.currentTimeMillis()
            // Server says the session is gone (owner stopped it / timed out) or
            // the capability was turned off: stop capturing immediately.
            return when (result) {
                "stored" -> Outcome.SENT
                "no_session", "capability_off" -> {
                    lastErrorReason = "server ended the session ($result)"
                    stopSelf()
                    Outcome.FAILED
                }
                null -> {
                    lastErrorReason = "server did not accept the frame (network or HTTP error)"
                    Outcome.FAILED
                }
                else -> {
                    lastErrorReason = "server said: $result"
                    Outcome.FAILED
                }
            }
        } finally {
            // Released in a finally so a single failure can never wedge the
            // stream: if the flag were left set, every later frame would be
            // skipped as "upload in flight" and the view would freeze forever.
            uploading.set(false)
        }
    }

    /** RGBA Image -> ARGB Bitmap -> JPEG bytes (quality 55 keeps ~35-60KB). */
    private fun imageToJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / plane.pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        val cropped = if (rowPadding == 0) bitmap else {
            val c = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            c
        }
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        cropped.recycle()
        return out.toByteArray()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.screen_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle(getString(R.string.screen_notif_title))
            .setContentText(getString(R.string.screen_notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.screen_notif_stop),
                stopPending,
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        running = false
        // The share flag must follow the real service state, otherwise the app
        // keeps showing "Stop sharing" for a session that already ended.
        isSharing = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        // `projection.stop()` is DELIBERATELY not called when the token is
        // being kept for reuse. Stopping it invalidates the consent, which is
        // precisely why every dashboard request used to raise the system
        // dialog again. `forgetConsent()` is the only thing that drops it, and
        // it is reached from the app's own "forget / revoke" action.
        virtualDisplay = null
        imageReader = null
        projection = null
        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {
        }
        captureThread = null
        captureHandler = null
        try {
            renderThread?.quitSafely()
        } catch (_: Exception) {
        }
        renderThread = null
        renderHandler = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "connectdesk_screen"
        private const val NOTIF_ID = 43
        private const val JPEG_QUALITY = 55
        private const val MAX_JPEG_BYTES = 500_000 // server cap is ~525KB decoded
        /**
         * How long a share keeps trying before it gives up and says why.
         *
         * See [giveUpAt]: the old six-FAILURES rule killed shares that were
         * still waiting for their first frame.
         */
        private const val START_GRACE_MS = 45_000L

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_INTERVAL_MS = "intervalMs"
        const val EXTRA_REUSE = "reuseConsent"
        const val ACTION_STOP = "com.connectdesk.app.STOP_SCREEN"

        /**
         * The MediaProjection the user already consented to, kept alive
         * between sessions.
         *
         * WHY THE DIALOG DOES NOT REAPPEAR NOW
         * ------------------------------------
         * Android shows "Start recording or casting?" on every single
         * `createScreenCaptureIntent()`, so asking once per dashboard click is
         * the platform's behaviour, not a choice — and it is what the owner
         * meant by "consent har baar mat mangao".
         *
         * The way out is not to suppress the dialog (impossible, and we would
         * not want to). It is to keep the granted `MediaProjection` OBJECT and
         * hand out more `VirtualDisplay`s from it. Android 14 (API 34)
         * explicitly supports a single `MediaProjection` driving multiple
         * `createVirtualDisplay` calls, which is what lets one consent cover
         * every later session.
         *
         * Therefore `onDestroy` releases the VirtualDisplay but does NOT call
         * `projection.stop()` — stopping is what burns the consent.
         *
         * Scope, stated plainly: this lasts for the life of the PROCESS. If
         * Android kills the app the consent is gone and the next view asks
         * again, which is the correct and expected behaviour. Before Android 14
         * the platform rejects a second `createVirtualDisplay` on a used
         * projection, so `canReuseConsent` is false there and the dialog is
         * shown honestly rather than the share silently failing.
         */
        @Volatile
        private var grantedProjection: MediaProjection? = null

        /**
         * The platform callback, shared by every session.
         *
         * It lives in the companion rather than on the service instance because
         * [forgetConsent] has to unregister it, and it is called from the app
         * UI rather than from a running service.
         */
        private val projectionStopCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                // The user revoked sharing from the system UI. That is a real
                // decision and it also ends consent for good.
                forgetConsent()
            }
        }

        /** True when a still-valid projection is parked and the platform allows reuse. */
        val canReuseConsent: Boolean
            get() = grantedProjection != null && Build.VERSION.SDK_INT >= 34

        /** Drops the parked consent — next share asks the user again. */
        fun forgetConsent() {
            val p = grantedProjection
            grantedProjection = null
            try {
                p?.unregisterCallback(projectionStopCallback)
            } catch (_: Exception) {
            }
            try {
                p?.stop()
            } catch (_: Exception) {
            }
        }

        /** True while the user is actively sharing their screen. */
        @Volatile
        var isSharing: Boolean = false
            private set

        /**
         * Why the mirror is not delivering frames, in the phone's own words.
         *
         * Empty when the stream is healthy. Set on the failure path and kept
         * after `onDestroy`, so a share that died six seconds in still explains
         * itself on the dashboard instead of showing "frames ka wait" forever.
         * Reported read-only over the existing heartbeat; the dashboard can
         * display it but cannot write to it.
         */
        @Volatile
        var lastError: String = ""
            private set

        /**
         * What the phone is currently waiting on the OWNER to do, if anything.
         *
         * Empty when there is nothing to wait for. This is the missing third
         * state on the dashboard, and the whole "screen share never works"
         * dead end lived in it: the page could only see "armed" or "not armed",
         * so a share sitting in the Android consent dialog looked exactly like
         * a share that had failed, and the stale result badge from an earlier
         * consent round-trip said the opposite of what was true.
         *
         * Set by [CommandWorker] when it raises the consent prompt and cleared
         * by the service the moment capture is really running. Report-only.
         */
        @Volatile
        var pendingOwnerAction: String = ""
            private set

        /** Records what the owner still has to do, so the page can say it. */
        fun markPendingOwnerAction(action: String) {
            pendingOwnerAction = action
        }

        /** Clears the pending note when the owner finished, or gave up. */
        fun clearPendingOwnerAction() {
            pendingOwnerAction = ""
        }

        fun start(context: Context, resultCode: Int, data: Intent, width: Int, intervalMs: Int) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
            context.startForegroundService(intent)
            // See startWithStoredConsent: isSharing is set by onStartCommand
            // once capture is genuinely running, not optimistically here.
        }

        /**
         * Starts a share from consent the user already gave.
         *
         * Returns false when there is no reusable consent, in which case the
         * caller must go through [start] with a fresh dialog result — so the
         * share can never fail silently.
         */
        fun startWithStoredConsent(context: Context, width: Int, intervalMs: Int): Boolean {
            if (!canReuseConsent) return false
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_REUSE, true)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
            context.startForegroundService(intent)
            // `isSharing` is NOT set here. It used to be set optimistically at
            // start time, before the service had opened a camera, obtained a
            // projection or uploaded a single frame -- so the dashboard was
            // told "armed on phone" for a stream that never began, and kept
            // saying so through every failure. It is now set in onStartCommand
            // only once the VirtualDisplay is actually live, which makes the
            // reported state mean something.
            return true
        }

        fun stop(context: Context) {
            // stopService rather than startService: startService from the
            // background throws IllegalStateException on Android 8+, and this
            // runs from the dashboard's stop request and from the revoked path.
            runCatching {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            }
            isSharing = false
        }
    }
}
