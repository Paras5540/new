package com.connectdesk.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * Live camera streaming to the dashboard, with the phone owner in control.
 *
 * DESIGN — what this deliberately is and is not:
 *
 * This is NOT a silent camera. The person holding the phone has to turn this
 * on deliberately from the app, and while it runs they always see:
 *   - a permanent, non-dismissable foreground notification, and
 *   - a recording indicator in that notification stating the camera is live.
 * They can stop it at any time from the notification or by opening the app.
 * The camera light is on because the hardware demands it. There is no way for
 * the dashboard to switch this on by itself: `arm()` is only reachable from
 * the local UI, and the server can only ask the device to STOP.
 *
 * WHY A SERVICE AT ALL: Android requires a foreground service with the
 * CAMERA type to keep the camera open when the app is not in the foreground.
 * A background capture that shows no notification is exactly the behaviour we
 * refuse to build, so the notification is not decoration here — it is the
 * consent mechanism the OS is built around.
 *
 * Frames are downscaled and JPEG-compressed before upload. Only the newest
 * frame is kept server-side, so this stays near-realtime rather than filling
 * storage.
 */
class CameraLiveService : Service() {

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var seq = 0
    private var facing = "back"
    private var failures = 0
    private var uploaded = 0
    private var dropped = 0
    private var streamWidth = 0
    private var streamHeight = 0

    /**
     * Single background thread for frame encoding + upload. One thread, so
     * frames can never overlap or arrive out of order.
     */
    private val uploadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "connectdesk-camera-upload")
    }

    /**
     * Minimum gap between two camera frames.
     *
     * The camera produces frames continuously (typically 30/s), and each one is
     * a blocking HTTPS POST of a JPEG. Uploading all of them queues work far
     * faster than the radio can drain it, so the backlog grows without bound
     * and the dashboard keeps rendering a frame that is several seconds old —
     * the "live camera is stuck / frozen" symptom. The server also keeps only
     * the newest frame, so anything older than the current interval is thrown
     * away anyway. One frame a second is therefore both the fastest useful rate
     * and the only one that cannot fall behind.
     */
    private val MIN_FRAME_GAP_MS = 1_000L

    /** Wall-clock of the last upload, used to enforce [MIN_FRAME_GAP_MS]. */
    @Volatile
    private var lastUploadAt = 0L

    /** Frames skipped because one was still in flight. */
    @Volatile
    private var skipped = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> {
                facing = intent?.getStringExtra(EXTRA_FACING) ?: "back"
                startForegroundWithIndicator()
                openCamera()
            }
        }
        // Do not auto-restart: if the user stopped sharing we must stay off.
        return START_NOT_STICKY
    }

    /**
     * The visible consent indicator. Non-dismissable, and explicitly says the
     * camera is live so nobody is ever surprised by a running camera.
     */
    private fun startForegroundWithIndicator() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.camera_live_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val n: Notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.camera_live_title))
                .setContentText(getString(R.string.camera_live_body))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.camera_live_stop),
                    buildStopPendingIntent(),
                )
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Lets the user stop the camera straight from the notification. */
    private fun buildStopPendingIntent(): android.app.PendingIntent {
        val i = Intent(this, CameraLiveService::class.java).setAction(ACTION_STOP)
        val flags =
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        return android.app.PendingIntent.getService(this, 1, i, flags)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!hasPermission()) {
            stopEverything()
            return
        }
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = pickCamera(mgr) ?: run {
            stopEverything()
            return
        }

        // Size and format are chosen FROM THE CAMERA, never hard-coded.
        //
        // The previous version asked for a 480x640 JPEG ImageReader. That is
        // wrong twice over and is why the live view was black:
        //   1. 480x640 is usually not in the camera's supported JPEG output list
        //      at all, so the session configures but no image ever arrives;
        //   2. TEMPLATE_PREVIEW does not drive the JPEG still-capture pipeline.
   // TEMPLATE_PREVIEW must be paired with YUV_420_888, and the YUV frame
   // is converted to ARGB and compressed to JPEG here with Bitmap.
        val configMap = try {
            mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        } catch (_: Throwable) {
            null
        }
        val yuvSizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)
        val size = pickStreamSize(yuvSizes)
        if (size == null) {
            stopEverything()
            return
        }
        streamWidth = size.width
        streamHeight = size.height

        val ir = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            2,
        )
        reader = ir
        // Frames arrive on the main looper, but the upload is HTTPS: doing it
        // inline threw NetworkOnMainThreadException and killed the whole
        // stream after the first frame. The image is handed to a single
        // worker thread instead, which also keeps frames strictly ordered.
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                uploadExecutor.execute { upload(img) }
            } catch (_: Throwable) {
                img.close()
            }
        }, mainHandler)

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    camera = dev
                    createSession(dev, ir)
                }

                override fun onDisconnected(dev: CameraDevice) {
                    dev.close()
                    camera = null
                    stopEverything()
                }

                override fun onError(dev: CameraDevice, error: Int) {
                    dev.close()
                    camera = null
                    stopEverything()
                }
            }, mainHandler)
        } catch (e: SecurityException) {
            stopEverything()
        } catch (e: Throwable) {
            stopEverything()
        }
    }

    /**
     * Picks a YUV streaming size: the smallest one that is at least 480px on
     * its short side (so the dashboard view is legible) and not larger than
     * 1280px, which keeps every JPEG well under the server's frame cap.
     */
    private fun pickStreamSize(sizes: Array<android.util.Size>?): android.util.Size? {
        if (sizes == null || sizes.isEmpty()) return null
        val usable = sizes.filter {
            val shortSide = minOf(it.width, it.height)
            shortSide >= 480 && maxOf(it.width, it.height) <= 1280
        }
        val pool = if (usable.isEmpty()) sizes.toList() else usable
        return pool.minByOrNull { it.width * it.height }
    }

    /**
     * Converts a YUV_420_888 frame into ARGB pixels.
     *
     * WHY NOT `YuvImage`: the previous version packed the frame into NV21 and
     * handed it to `android.media.YuvImage`. That class does not resolve on this
     * project's Android classpath at all -- even `import android.media.YuvImage`
     * failed to compile -- so the whole live-camera path could not build. This
     * does the YUV->RGB conversion directly and hands the pixels to `Bitmap`,
     * which is already used (and already compiles) elsewhere in this app.
     *
     * Row and pixel strides are honoured: they are not 1 on real hardware, and
     * ignoring them is what produces skewed or black output. Chroma is
     * subsampled 2x2, so the U/V cursor only advances on even columns.
     */
    private fun yuvToArgb(image: Image): IntArray? {
        val crop = image.cropRect
        val w = crop.width()
        val h = crop.height()
        if (w <= 0 || h <= 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val out = IntArray(w * h)

        var yPos = crop.top * yPlane.rowStride + crop.left * yPlane.pixelStride
        var uPos = (crop.top / 2) * uPlane.rowStride + (crop.left / 2) * uPlane.pixelStride
        var vPos = (crop.top / 2) * vPlane.rowStride + (crop.left / 2) * vPlane.pixelStride

        for (row in 0 until h) {
            var yc = yPos
            var uc = uPos
            var vc = vPos
            for (x in 0 until w) {
                // An empty plane buffer must not throw; a black frame beats a crash.
                if (yc >= 0 && yc < yBuf.limit()) {
                    // ByteBuffer.get() returns a Byte. Kotlin has `Byte.and(Byte)` and
                    // `Int.and(Int)` but no `Byte.and(Int)`, so the byte MUST be
                    // widened to Int first -- `and 0xFF` on a Byte does not
                    // compile ("receiver type mismatch").
                    val yv = (yBuf.get(yc).toInt() and 0xFF) - 16
                    val u = if (uc in 0 until uBuf.limit()) (uBuf.get(uc).toInt() and 0xFF) - 128 else 0
                    val v = if (vc in 0 until vBuf.limit()) (vBuf.get(vc).toInt() and 0xFF) - 128 else 0
                    val yy = 298 * yv
                    var r = (yy + 409 * v + 128) shr 8
                    var g = (yy - 100 * u - 208 * v + 128) shr 8
                    var b = (yy + 516 * u + 128) shr 8
                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255
                    out[row * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    out[row * w + x] = 0xFF shl 24
                }
                yc += yPlane.pixelStride
                if (x % 2 == 1) {
                    uc += uPlane.pixelStride
                    vc += vPlane.pixelStride
                }
            }
            yPos += yPlane.rowStride
            uPos += uPlane.rowStride
            vPos += vPlane.rowStride
        }
        return out
    }

    /** YUV frame -> JPEG bytes. Returns null when compression is not possible. */
    private fun toJpeg(image: Image): ByteArray? {
        // The pixel buffer is produced from cropRect, so the Bitmap MUST be the
        // same size. Using image.width/height while cropRect is smaller made the
        // buffer and the bitmap disagree, which surfaced as a permanently black
        // live view.
        val w = image.cropRect.width()
        val h = image.cropRect.height()
        if (w <= 0 || h <= 0) return null
        val pixels = yuvToArgb(image) ?: return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            val out = ByteArrayOutputStream()
            val ok = bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bmp.recycle()
            if (!ok) null else out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun pickCamera(mgr: CameraManager): String? {
        return try {
            val wantBack = facing == "back"
            var fallback: String? = null
            for (id in mgr.cameraIdList) {
                val chars = mgr.getCameraCharacteristics(id)
                val lens = chars.get(CameraCharacteristics.LENS_FACING)
                val isBack = lens == CameraCharacteristics.LENS_FACING_BACK
                if (wantBack && isBack) return id
                if (!isBack) fallback = id
            }
            fallback ?: mgr.cameraIdList.firstOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    private fun createSession(dev: CameraDevice, ir: ImageReader) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                try {
                    // The Camera2 API has no "streaming" template. The valid ones are
                    // TEMPLATE_PREVIEW / TEMPLATE_STILL_CAPTURE /
                    // TEMPLATE_VIDEO_RECORD, and TEMPLATE_PREVIEW is the one
                    // intended for a continuous repeating-capture stream.
                    val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    builder.addTarget(ir.surface)
                    builder.set(
                        CaptureRequest.CONTROL_MODE,
                        CaptureRequest.CONTROL_MODE_AUTO,
                    )
                    // FPS range is deliberately NOT pinned: the key is typed
                    // Range<Integer> and a Kotlin `Range(10, 15)` infers
                    // Range<Int>, which fails to type-check. Leaving it at the
                    // camera's default still streams continuously.
                    s.setRepeatingRequest(builder.build(), null, mainHandler)
                } catch (e: Throwable) {
                    stopEverything()
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                stopEverything()
            }
        }
        // Bare call: the result is delivered to `callback`, and assigning it
        // would be a type error because createCaptureSession returns Unit here.
        dev.createCaptureSession(listOf(ir.surface), callback, mainHandler)
    }

    private fun upload(image: Image) {
        // Rate limit + in-flight guard. Both live on the worker thread, so
        // they cannot race each other; anything arriving inside the gap is
        // dropped rather than queued.
        val now = System.currentTimeMillis()
        if (now - lastUploadAt < MIN_FRAME_GAP_MS) {
            skipped++
            return
        }
        val jpeg = toJpeg(image)
        if (jpeg == null || jpeg.isEmpty()) {
            // A frame we cannot encode is not a frame — skip it rather than
            // sending something the dashboard would render as a black square.
            dropped++
            return
        }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val token = ApiClient.loadToken(this) ?: run {
            stopEverything()
            return
        }
        val ok = ApiClient.postCameraFrame(
            token,
            b64,
            image.cropRect.width(),
            image.cropRect.height(),
            seq++,
        )
        lastUploadAt = System.currentTimeMillis()
        if (ok) {
            failures = 0
            uploaded++
        } else {
            failures++
            // The dashboard may have turned it off, or the server is gone.
            if (failures > 6) stopEverything()
        }
    }

    /** How many frames went out / were skipped. Logged so a dead stream is visible. */
    fun stats(): String =
        "frames uploaded=$uploaded dropped=$dropped skipped=$skipped sent=$seq"

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        try {
            camera?.close()
        } catch (_: Throwable) {
        }
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        session = null
        camera = null
        reader = null
        // Release the worker before anything else, otherwise a queued frame
        // could try to touch a closed reader.
        runCatching { uploadExecutor.shutdownNow() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "connectdesk_camera_live"
        private const val NOTIF_ID = 44
        private const val JPEG_QUALITY = 70
        const val ACTION_STOP = "com.connectdesk.app.STOP_CAMERA_LIVE"
        const val EXTRA_FACING = "facing"

        /**
         * Arms live streaming. Only ever called from the local UI after the
         * user explicitly turns it on, never from a server command.
         */
        fun start(context: Context, facing: String = "back") {
            val i = Intent(context, CameraLiveService::class.java)
                .setAction("START")
                .putExtra(EXTRA_FACING, facing)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            // stopService, not startService: starting a service from the
            // background throws IllegalStateException on Android 8+, and this is
            // called from the dashboard's stop request while the app is
            // backgrounded. stopService always triggers onDestroy, which runs
            // the same teardown.
            runCatching {
                context.stopService(Intent(context, CameraLiveService::class.java))
            }
        }

        /** True when live streaming was armed by the user on this device. */
        fun isArmed(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ARMED, false)

        fun setArmed(context: Context, armed: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ARMED, armed).apply()
        }

        private const val PREFS = "connectdesk_prefs"
        private const val KEY_ARMED = "cameraLiveArmed"
    }
}