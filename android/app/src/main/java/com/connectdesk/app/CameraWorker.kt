package com.connectdesk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot camera capture for the dashboard's "take photo" request.
 *
 * Deliberately minimal and consent-gated:
 *  - requires the dashboard's `camera` capability (the server re-checks it),
 *  - requires the Android CAMERA runtime permission,
 *  - never runs in the background: the phone shows a notification, the user
 *    taps it, the camera opens for a second, then it closes again,
 *  - uploads one JPEG and stores nothing on disk.
 *
 * Threading: this is a blocking call made from a worker thread. Every camera
 * callback just flips a latch/flag; the latches below do the waiting, which
 * keeps the threading model trivial and correct.
 */
object CameraWorker {

    /** Opens the camera, grabs one frame, closes, uploads. */
    fun capture(context: Context, token: String, facing: String): Pair<Boolean, String> {
        if (!hasPermission(context)) {
            return Pair(false, "Camera permission has not been granted on the device")
        }
        return try {
            val jpeg = grabOneFrame(context, facing)
                ?: return Pair(false, "The camera did not return a frame")
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            if (ApiClient.uploadCameraPhoto(token, facing, b64)) {
                Pair(true, "Captured with the $facing camera")
            } else {
                Pair(false, ApiClient.lastError ?: "Upload failed")
            }
        } catch (e: CameraAccessException) {
            Pair(false, "Camera unavailable: ${e.message}")
        } catch (e: Throwable) {
            Pair(false, "Capture failed: ${e.message}")
        }
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun grabOneFrame(context: Context, facing: String): ByteArray? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wantBack = facing != "front"
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val lens = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (wantBack) lens == CameraCharacteristics.LENS_FACING_BACK
            else lens == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return null

        val configMap = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        // Smallest JPEG the camera offers keeps the upload tiny and fast.
        val size = configMap.getOutputSizes(ImageFormat.JPEG)?.minByOrNull { it.width * it.height }
            ?: return null

        val main = Handler(Looper.getMainLooper())
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val jpegBytes = AtomicReference<ByteArray?>(null)
        val frameLatch = CountDownLatch(1)
        val openLatch = CountDownLatch(1)
        val sessionLatch = CountDownLatch(1)
        var session: CameraCaptureSession? = null

        // This listener is what actually delivers the frame. Without it the
        // ImageReader silently drops every image and the capture never returns.
        reader.setOnImageAvailableListener({ source ->
            try {
                val image: Image = source.acquireLatestImage()
                    ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    if (bytes.isNotEmpty()) jpegBytes.set(bytes)
                } finally {
                    image.close()
                }
            } catch (_: Throwable) {
                // A dropped frame is reported as "no frame" by the caller.
            } finally {
                frameLatch.countDown()
            }
        }, main)

        val deviceCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                openLatch.countDown()
                try {
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    builder.addTarget(reader.surface)
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    )
                    builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
                    session = camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                configured.setRepeatingRequest(builder.build(), null, main)
                                sessionLatch.countDown()
                            }

                            override fun onConfigureFailed(failed: CameraCaptureSession) {
                                try {
                                    failed.close()
                                } catch (_: Throwable) {
                                }
                                sessionLatch.countDown()
                            }
                        },
                        main,
                    )
                } catch (_: Throwable) {
                    sessionLatch.countDown()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                closeQuietly(camera)
                frameLatch.countDown()
                sessionLatch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                closeQuietly(camera)
                frameLatch.countDown()
                sessionLatch.countDown()
            }
        }

        manager.openCamera(cameraId, deviceCallback, main)

        try {
            openLatch.await(5, TimeUnit.SECONDS)
            sessionLatch.await(5, TimeUnit.SECONDS)
            frameLatch.await(8, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val jpeg = jpegBytes.get()
        // Release everything before returning so the camera LED goes out and
        // another app can open the camera immediately afterwards.
        try {
            session?.stopRepeating()
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        try {
            reader.close()
        } catch (_: Throwable) {
        }
        if (jpeg == null) return null
        return downscale(jpeg)
    }

    private fun closeQuietly(camera: CameraDevice) {
        try {
            camera.close()
        } catch (_: Throwable) {
        }
    }

    /** Keeps uploads small (the dashboard shows a thumbnail + download). */
    private fun downscale(jpeg: ByteArray): ByteArray {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return jpeg
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            val scaled = out.toByteArray()
            if (scaled.size < jpeg.size) scaled else jpeg
        } catch (_: Throwable) {
            jpeg
        }
    }
}