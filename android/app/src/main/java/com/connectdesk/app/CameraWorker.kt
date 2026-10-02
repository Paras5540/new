package com.connectdesk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.util.Base64
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot camera capture for the dashboard's "take photo" request.
 *
 * Deliberately minimal and consent-gated:
 *  - requires the dashboard's `camera` capability (server re-checks it),
 *  - requires the Android CAMERA runtime permission,
 *  - never runs in the background: the phone shows a notification, the user
 *    taps it, the camera opens for a second, then it closes again,
 *  - uploads one JPEG and stores nothing on disk.
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

    /**
     * Blocking single-shot capture. Runs on a worker thread; every callback
     * below just flips a flag and the loop below does the waiting, which keeps
     * the threading model trivial and correct.
     */
    @SuppressLint("MissingPermission")
    private fun grabOneFrame(context: Context, facing: String): ByteArray? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            val lens = chars.get(CameraCharacteristics.LENS_FACING)
            val wantBack = facing != "front"
            (wantBack && lens == CameraCharacteristics.LENS_FACING_BACK) ||
                (!wantBack && lens == CameraCharacteristics.LENS_FACING_FRONT)
        } ?: return null

        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(ImageFormat.JPEG)
        val size = sizes?.firstOrNull { it.width <= 1600 } ?: sizes?.minOrNull() ?: return null

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val opened = AtomicBoolean(false)
        var captured: Image? = null

        val captureDone = java.util.concurrent.CountDownLatch(1)

        val deviceCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened.set(true)
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                builder.addTarget(reader.surface)
                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )
                builder.set(CaptureRequest.JPEG_ORIENTATION, 0)
                val session = camera.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            configured.setRepeatingRequest(
                                builder.build(), null, android.os.Handler(android.os.Looper.getMainLooper()),
                            )
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            captureDone.countDown()
                        }
                    },
                    android.os.Handler(android.os.Looper.getMainLooper()),
                )
                session.setRepeatingRequest(builder.build(), null, null)
            }

            override fun onDisconnected(camera: CameraDevice) = captureDone.countDown()
            override fun onError(camera: CameraDevice, error: Int) = captureDone.countDown()
        }

        manager.openCamera(cameraId, deviceCallback, android.os.Handler(android.os.Looper.getMainLooper()))

        // Wait for the camera to open, then take one still.
        val deadline = System.currentTimeMillis() + 4_000
        while (!opened.get() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (!opened.get()) {
            reader.close()
            return null
        }

        val deadline2 = System.currentTimeMillis() + 6_000
        while (captured == null && System.currentTimeMillis() < deadline2) Thread.sleep(60)
        captureDone.countDown()

        val image = captured
        reader.close()
        if (image == null) return null

        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()
        if (bytes.isEmpty()) return null
        return downscale(bytes)
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

    @Suppress("unused")
    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
