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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var seq = 0L
    private var width = 720
    private var intervalMs = 1000L
    private var staleStreak = 0

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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User pressed "Stop" on the system cast dialog — honor it.
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val token = ApiClient.loadToken(this) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        this.token = token

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode == Int.MIN_VALUE || data == null) {
            // No fresh projection consent — cannot (and must not) capture.
            stopSelf()
            return START_NOT_STICKY
        }

        width = intent.getIntExtra(EXTRA_WIDTH, 720).coerceIn(360, 1080)
// The dashboard asks for 1 second. The floor is now 300ms so a fast
        // connection can go quicker, and the ceiling is unchanged at 5s to
        // stop a client from asking for a frame flood.
        intervalMs = intent.getIntExtra(EXTRA_INTERVAL_MS, 1000).coerceIn(300, 5000).toLong()

        startAsForeground()

        if (running) return START_STICKY
        running = true

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection = mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection?.registerCallback(projectionCallback, mainHandler)

        captureThread = HandlerThread("connectdesk-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        setupVirtualDisplay()
        captureHandler?.post(captureLoop)

        return START_STICKY
    }

    private var token: String? = null

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
            imageReader!!.surface, null, captureHandler,
        )
    }

    /** Captures one frame per tick and uploads it; backs off on failures. */
    private val captureLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val sent = captureAndUpload()
                if (sent) {
                    staleStreak = 0
                } else {
                    staleStreak++
                }
                if (staleStreak >= MAX_CONSECUTIVE_FAILURES) {
                    // Session gone or network dead — stop capture, stay silent.
                    stopSelf()
                    return
                }
            } catch (_: Exception) {
                staleStreak++
                if (staleStreak >= MAX_CONSECUTIVE_FAILURES) {
                    stopSelf()
                    return
                }
            }
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    /** Returns true when a frame reached the server (or was accepted). */
    private fun captureAndUpload(): Boolean {
        val reader = imageReader ?: return false
        // Never start a second upload while one is in flight (see `uploading`).
        if (!uploading.compareAndSet(false, true)) return true
        try {
            val image: Image = try {
                reader.acquireLatestImage() ?: return true // nothing new; keep going
            } catch (_: Exception) {
                return false
            }
            val jpeg = try {
                imageToJpeg(image)
            } catch (_: Exception) {
                null
            } finally {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
            val bytes = jpeg ?: return false
            if (bytes.size > MAX_JPEG_BYTES) return false // skip oversized frame
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val result = ApiClient.pushScreenFrame(
                token ?: return false,
                ++seq,
                reader.width,
                reader.height,
                b64,
            )
            lastUploadAt = System.currentTimeMillis()
            // Server says the session is gone (owner stopped it / timed out) or
            // the capability was turned off: stop capturing immediately.
            return when (result) {
                "stored" -> true
                "no_session", "capability_off" -> {
                    stopSelf()
                    false
                }
                else -> false // transient error; retry next tick
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
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        imageReader = null
        projection = null
        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {
        }
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "connectdesk_screen"
        private const val NOTIF_ID = 43
        private const val JPEG_QUALITY = 55
        private const val MAX_JPEG_BYTES = 500_000 // server cap is ~525KB decoded
        private const val MAX_CONSECUTIVE_FAILURES = 6

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_INTERVAL_MS = "intervalMs"
        const val ACTION_STOP = "com.connectdesk.app.STOP_SCREEN"

        /** True while the user is actively sharing their screen. */
        @Volatile
        var isSharing: Boolean = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent, width: Int, intervalMs: Int) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
            context.startForegroundService(intent)
            isSharing = true
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
