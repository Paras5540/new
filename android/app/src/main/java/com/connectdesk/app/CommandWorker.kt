package com.connectdesk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Executes dashboard-originated commands on the device:
 *  - send_sms: sends via the telephony stack with SEND_SMS permission;
 *    the message lands in the normal Sent inbox (fully visible to the user).
 *  - fetch_media: reads a photo/video/music file from MediaStore and uploads
 *    it to the dashboard in ~350KB base64 chunks (media capability).
 *  - start_screen: raises Android's MediaProjection consent dialog on the
 *    device; capture only begins after the user taps "Allow" (screen_share
 *    capability + an active dashboard session are verified server-side too).
 *
 * Every command is consent-gated twice: the dashboard owner enabled the
 * capability AND the server verified it again when delivering the command.
 */
object CommandWorker {

    private const val CHUNK_SIZE = 350_000 // raw bytes per uploaded chunk

    fun runPending(context: Context, token: String) {
        val commands = ApiClient.popCommands(token)
        for (cmd in commands) {
            val outcome: Pair<Boolean, String> = when (cmd.type) {
                "send_sms" -> sendSms(context, cmd.payload)
                "fetch_media" -> fetchMedia(context, token, cmd.payload)
                "start_screen" -> startScreen(context, token, cmd.id, cmd.payload)
                else -> Pair(false, "Unknown command type: ${cmd.type}")
            }
            // start_screen reports asynchronously (after the user answers the
            // system dialog); everything else reports right here.
            if (cmd.type != "start_screen") {
                ApiClient.completeCommand(token, cmd.id, outcome.first, outcome.second)
            }
        }
    }

    /** Sends the SMS; requires SEND_SMS granted. Returns (ok, detail). */
    @SuppressLint("MissingPermission")
    private fun sendSms(context: Context, payload: JSONObject): Pair<Boolean, String> {
        val to = payload.optString("to")
        val body = payload.optString("body")
        if (to.isEmpty() || body.isEmpty()) return Pair(false, "Missing recipient or body")
        if (!hasPermission(context, Manifest.permission.SEND_SMS)) {
            return Pair(false, "SEND_SMS permission not granted on device")
        }
        return try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
            // Long messages: multipart send (auto-segmented, normal inbox entry).
            val parts = sm.divideMessage(body)
            if (parts.size == 1) {
                sm.sendTextMessage(to, null, body, null, null)
            } else {
                sm.sendMultipartTextMessage(to, null, parts, null, null)
            }
            Pair(true, "SMS sent to $to")
        } catch (e: Exception) {
            Pair(false, "SMS send failed: ${e.message}")
        }
    }

    /** Reads the media file and uploads it in chunks. Returns (ok, detail). */
    private fun fetchMedia(
        context: Context,
        token: String,
        payload: JSONObject,
    ): Pair<Boolean, String> {
        val mediaId = payload.optString("mediaId")
        val name = payload.optString("name")
        if (mediaId.isEmpty() || name.isEmpty()) return Pair(false, "Missing media id/name")
        if (!hasMediaReadPermission(context)) {
            return Pair(false, "Media read permission not granted on device")
        }
        return try {
            val uri = resolveMediaUri(context, name)
                ?: return Pair(false, "File not found on device")
            val bytes = readAll(context, uri)
                ?: return Pair(false, "Could not read file")
            val total = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
            for (i in 0 until total) {
                val from = i * CHUNK_SIZE
                val to = minOf(from + CHUNK_SIZE, bytes.size)
                val part = bytes.copyOfRange(from, to)
                val b64 = android.util.Base64.encodeToString(part, android.util.Base64.NO_WRAP)
                val ok = ApiClient.uploadMediaChunk(token, mediaId, i, total, b64)
                if (!ok) return Pair(false, "Upload failed at chunk ${i + 1}/$total")
            }
            Pair(true, "Uploaded $total chunk(s), ${bytes.size} bytes")
        } catch (e: Exception) {
            Pair(false, "Fetch failed: ${e.message}")
        }
    }

    /**
     * Screen share request: launches the consent activity, which shows the
     * Android MediaProjection dialog. The user MUST tap Allow — capture never
     * starts silently. Result is reported back asynchronously.
     */
    private fun startScreen(
        context: Context,
        @Suppress("UNUSED_PARAMETER") token: String,
        commandId: String,
        payload: JSONObject,
    ): Pair<Boolean, String> {
        if (ScreenCaptureService.isSharing) {
            return Pair(true, "Screen sharing already active")
        }
        if (Build.VERSION.SDK_INT < 29) {
            return Pair(false, "Screen share needs Android 10+")
        }
        val width = payload.optInt("width", 720).coerceIn(360, 1080)
        val intervalMs = payload.optInt("intervalMs", 1000).coerceIn(400, 5000)
        val intent = Intent(context, ScreenConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ScreenConsentActivity.EXTRA_COMMAND_ID, commandId)
            .putExtra(ScreenCaptureService.EXTRA_WIDTH, width)
            .putExtra(ScreenCaptureService.EXTRA_INTERVAL_MS, intervalMs)
        return try {
            context.startActivity(intent)
            Pair(true, "Consent dialog raised on device")
        } catch (e: Exception) {
            Pair(false, "Could not raise consent dialog: ${e.message}")
        }
    }

    /** SDK-aware media read permission check. */
    private fun hasMediaReadPermission(context: Context): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.any { hasPermission(context, it) }
    }

    /** Finds the MediaStore item by display name across photo/video/music. */
    private fun resolveMediaUri(context: Context, name: String): Uri? {
        val collections = listOf(
            Triple(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media._ID,
            ),
            Triple(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                android.provider.MediaStore.Video.Media._ID,
            ),
            Triple(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                android.provider.MediaStore.Audio.Media._ID,
            ),
        )
        for ((contentUri, nameCol, idCol) in collections) {
            context.contentResolver.query(
                contentUri,
                arrayOf(idCol),
                "$nameCol = ?",
                arrayOf(name),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    return ContentUris.withAppendedId(contentUri, c.getLong(0))
                }
            }
        }
        return null
    }

    private fun readAll(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) out.write(buf, 0, n)
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    private fun hasPermission(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}
