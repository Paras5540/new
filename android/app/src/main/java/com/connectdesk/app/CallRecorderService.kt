package com.connectdesk.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import java.io.File
import kotlin.concurrent.thread

/**
 * Records calls and uploads them to the dashboard.
 *
 * WHAT THIS RECORDS: the device's own microphone. That is all Android
 * permits. There is no supported way for a third-party app to capture the
 * other party's audio on a phone call — MediaProjection deliberately excludes
 * call audio, and AudioRecord cannot reach it either. So the recording is
 * one-sided, and the dashboard labels every file "device mic only" rather
 * than pretending it is a full call capture.
 *
 * WHY IT IS NOT A SURPRISE:
 *  - a persistent notification stays up for the whole recording, with a Stop
 *    action, and Android shows its own microphone-in-use indicator;
 *  - the phone owner must first flip "Record calls" inside this app;
 *  - the dashboard owner must separately hold the `media` capability;
 *  - the device ignores the dashboard's arm command unless the in-app switch
 *    is already on, so the phone owner can always veto it;
 *  - it never starts before the other side picks up, and the audio file is
 *    deleted from the phone right after upload.
 */
class CallRecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var recordingId: String? = null
    private var mediaId: String? = null
    private var startedAt = 0L
    private var uploadChunk = 256 * 1024

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ApiClient.attach(this)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_ARM -> {
                armFromDashboard(intent.getBooleanExtra(EXTRA_ARMED, false))
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    // ---- Arming (dashboard -> phone) ------------------------------------

    /**
     * The dashboard can only UN-arm, never arm. Arming has to be a choice the
     * person holding the phone makes, otherwise this would be a microphone
     * switched on remotely.
     */
    private fun armFromDashboard(armed: Boolean) {
        if (!armed) Prefs.setCallRecordingArmed(this, false)
        stopSelf()
    }

    // ---- Call detection -------------------------------------------------

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var listener: PhoneStateListener? = null

    private fun startListening() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val wasRinging = lastState == TelephonyManager.CALL_STATE_RINGING ||
                    lastState == TelephonyManager.CALL_STATE_OFFHOOK
                lastState = state
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (!wasRinging) beginRecording(phoneNumber)
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (wasRinging) endRecording()
                    }
                    else -> Unit
                }
            }
        }
        listener = l
        try {
            // PhoneState.LISTEN_CALL_STATE is the public bitmask; the older
            // PhoneStateListener.CALL_STATE_LISTENER constant was pulled from
            // the SDK and no longer resolves.
            tm.listen(l, android.telephony.PhoneState.LISTEN_CALL_STATE)
        } catch (_: Throwable) {
            listener = null
        }
    }

    /**
     * Drops our reference to the listener. There is deliberately no
     * `endCall`/`unregister` call: TelephonyManager.endCall is not in the
     * public SDK, and the listener is collected once this service is gone.
     */
    private fun stopListening() {
        listener = null
    }

    // ---- Recording ------------------------------------------------------

    private fun beginRecording(number: String?) {
        if (recorder != null) return
        if (!Prefs.callRecordingArmed(this)) return
        val token = ApiClient.loadToken(this) ?: return

        val file = File(cacheDir, "call_${System.currentTimeMillis()}.m4a")
        try {
            val rec = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outFile = file
            startedAt = System.currentTimeMillis()
        } catch (e: Throwable) {
            runCatching { recorder?.release() }
            recorder = null
            file.delete()
            ServiceStatus.markError("Recorder could not start: ${e.message}")
            return
        }

        showRecordingNotification()

        // Open a slot on the server so the audio has somewhere to land.
        thread(name = "connectdesk-call-open") {
            val resp = ApiClient.startCallRecording(token, "Call recording", number, startedAt)
            recordingId = resp?.recordingId
            mediaId = resp?.mediaId
            resp?.chunkSize?.let { uploadChunk = it }
        }
    }

    private fun endRecording() {
        val rec = recorder ?: return
        recorder = null
        val file = outFile
        outFile = null

        try {
            rec.stop()
        } catch (_: Throwable) {
            // Too short to finalise an AAC stream; drop it rather than upload
            // a corrupt file that would fail to play.
        }
        try {
            rec.release()
        } catch (_: Throwable) {
        }
        cancelNotification()
        val token = ApiClient.loadToken(this)
        if (token != null && file != null && file.exists() && file.length() > 0) {
            val id = recordingId
            val media = mediaId
            val durationSec = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            val size = file.length()
            thread(name = "connectdesk-call-upload") {
                try {
                    if (id != null && media != null) {
                        val ok = ApiClient.uploadMediaChunk(
                            token = token,
                            mediaId = media,
                            index = 0,
                            total = 1,
                            dataB64 = android.util.Base64.encodeToString(
                                file.readBytes(), android.util.Base64.NO_WRAP,
                            ),
                        )
                        if (ok) {
                            ApiClient.finishCallRecording(token, id, size, durationSec, "audio/mp4")
                        }
                    }
                } catch (_: Throwable) {
                    // best-effort
                } finally {
                    // Never leave call audio sitting in the app's cache.
                    runCatching { file.delete() }
                }
            }
        } else {
            runCatching { file?.delete() }
        }
        recordingId = null
        mediaId = null
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        startListening()
    }

    override fun onDestroy() {
        endRecording()
        stopListening()
        super.onDestroy()
    }

    // ---- Persistent, honest notification --------------------------------

    private fun showRecordingNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.call_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CallRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle(getString(R.string.call_notif_title))
            .setContentText(getString(R.string.call_notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.screen_notif_stop),
                stopIntent,
            )
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            runCatching { startForeground(NOTIF_ID, notif) }
        }
    }

    private fun cancelNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)
    }

    /** Idle-state notification so the service can legally stay alive. */
    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.call_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle(getString(R.string.call_idle_title))
            .setContentText(getString(R.string.call_idle_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {
            // Without the microphone FGS type the platform will refuse; the
            // phone-side switch in the app explains this instead of failing
            // silently.
        }
    }

    companion object {
        private const val CHANNEL = "connectdesk_calls"
        private const val NOTIF_ID = 44
        const val ACTION_STOP = "com.connectdesk.app.STOP_CALL_RECORDING"
        const val ACTION_ARM = "com.connectdesk.app.ARM_CALL_RECORDING"
        const val EXTRA_ARMED = "armed"

        /** Start listening for calls. Called once the phone-side switch is on. */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, CallRecorderService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CallRecorderService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        /** The dashboard may only ever disarm from a distance. */
        fun setArmedRemotely(context: Context, armed: Boolean) {
            runCatching {
                context.startService(
                    Intent(context, CallRecorderService::class.java)
                        .setAction(ACTION_ARM)
                        .putExtra(EXTRA_ARMED, armed),
                )
            }
        }
    }
}