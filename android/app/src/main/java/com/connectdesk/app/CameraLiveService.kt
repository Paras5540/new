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
        // Stream at a modest size: the dashboard view is small and this keeps
        // each upload far below the server's frame cap.
        val w = 480
        val h = 640
        val ir = ImageReader.newInstance(w, h, android.graphics.ImageFormat.JPEG, 2)
        reader = ir
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            if (img != null) {
                upload(img)
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
                    val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STREAMING)
                    builder.addTarget(ir.surface)
                    builder.set(
                        CaptureRequest.CONTROL_MODE,
                        CaptureRequest.CONTROL_MODE_AUTO,
                    )
                    builder.set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(10, 15),
                    )
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
        val buf = image.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val token = ApiClient.loadToken(this) ?: run {
            stopEverything()
            return
        }
        val ok = ApiClient.postCameraFrame(token, b64, image.width, image.height, seq++)
        if (ok) {
            failures = 0
        } else {
            failures++
            // The dashboard may have turned it off, or the server is gone.
            if (failures > 6) stopEverything()
        }
    }

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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "connectdesk_camera_live"
        private const val NOTIF_ID = 44
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
            context.startService(
                Intent(context, CameraLiveService::class.java).setAction(ACTION_STOP),
            )
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