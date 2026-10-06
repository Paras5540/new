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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Volatile private var recorder: MediaRecorder? = null
    /** The audio source the recorder currently on the air was built on. */
    private var recorderSource: Int = UNSUPPORTED_SOURCE
    /**
     * The source that last produced an AUDIBLE recording on this device,
     * re-read from `Prefs` at the start of every call. -1 until one call has
     * proven a source -- see `endRecording`, which pins and unpins it.
     */
    private var provenSource: Int = UNSUPPORTED_SOURCE
    /** Highest amplitude seen since the call started; 0 means "heard nothing". */
    @Volatile private var peak = 0
    private var peakWatch: Thread? = null
    private var outFile: File? = null
    private var recordingId: String? = null
    private var mediaId: String? = null
    private var startedAt = 0L
    private var uploadChunk = 256 * 1024
    /**
     * Counted down once the server slot (and therefore `recordingId` /
     * `mediaId`) has arrived.
     *
     * The slot is opened on a background thread so the telephony callback is
     * never blocked on HTTP -- but that used to race the upload: hang up
     * before that thread returned and `recordingId`/`mediaId` were still null,
     * so the recording was deleted and never uploaded. Short calls were the
     * ones that always vanished. The uploader waits on this instead, and the
     * latch is what makes the two fields visible across the threads.
     */
    @Volatile private var slotReady: CountDownLatch? = null

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
            // PhoneStateListener.LISTEN_CALL_STATE is the public bitmask. The older
            // PhoneStateListener.CALL_STATE_LISTENER constant and the
            // android.telephony.PhoneState class are both gone from the SDK.
            // Being a static final int, it is inlined at compile time, so
            // there is no runtime field lookup on older API levels.
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
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

    /**
     * Audio sources to try, best first.
     *
     * The original value was `AudioSource.DEFAULT`, and that is exactly why
     * every recording came out silent: DEFAULT is the source A/V capture uses,
     * and while a call is up Android routes the microphone to the voice path,
     * so DEFAULT records the muted stream. It was never an encoder problem --
     * `MediaRecorder.MIC` is the same physical microphone, and MicLiveService
     * already records through it without trouble.
     *
     * Order, best first:
     *
     *  1. `VOICE_RECOGNITION` -- the cleanest public capture: no AGC, no echo
     *     cancellation, and it does not trigger the A/V routing that mutes
     *     the stream while a call is up. On most devices it records the phone
     *     microphone plainly.
     *  2. `VOICE_COMMUNICATION` -- the OS is already in
     *     MODE_IN_COMMUNICATION for the call, so this is the source the
     *     platform is guaranteed to keep open during a call; on devices
     *     where VOICE_RECOGNITION prepares but still yields silence, this
     *     is the source that hears. The cost is AGC/echo-cancellation
     *     colouring the audio, which beats a silent file.
     *  3. `MIC` -- last resort for devices that refuse both of the above.
     *
     * A source a device rejects fails loudly at `prepare()`/`start()` and
     * the next one is tried, so a call is dropped only when every candidate
     * fails. Silence that survives preparation is caught by the amplitude
     * watch (`heardSomething`) and reported instead of uploaded.
     *
     * Note what is still NOT here: `VOICE_CALL` / `VOICE_DOWNLINK` are hidden
     * system APIs and do not resolve on the public SDK, and MediaProjection
     * deliberately excludes call audio. So the far-end voice is not reachable
     * and this stays a one-sided device-mic recording, exactly as the class
     * documentation says.
     */
    private val audioSources = intArrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
    )

    /**
     * Builds a recorder on the first source that prepares successfully.
     *
     * Falling back matters: a source that is wrong for a device fails at
     * `prepare()`/`start()`, and without the fallback the call would be dropped
     * instead of retried on the source that works there.
     */
    private fun startRecorder(file: File): MediaRecorder? {
        var last: Throwable? = null
        // A source that has already produced an audible recording on this
        // device goes FIRST; the rest keep the documented order behind it.
        // This is what makes a device that records silence from one source
        // self-heal: call 1 probes, call 2 starts directly on what worked.
        val order = if (provenSource != UNSUPPORTED_SOURCE) {
            intArrayOf(provenSource, *audioSources.filter { it != provenSource }.toIntArray())
        } else {
            audioSources
        }
        for (source in order) {
            val rec = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            try {
                rec.setAudioSource(source)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(64_000)
                rec.setAudioSamplingRate(44_100)
                rec.setOutputFile(file.absolutePath)
                rec.prepare()
                rec.start()
                recorderSource = source
                return rec
            } catch (e: Throwable) {
                last = e
                runCatching { rec.release() }
            }
        }
        ServiceStatus.markError("Recorder could not start: ${last?.message}")
        return null
    }

    /**
     * Samples `getMaxAmplitude()` while the call is up.
     *
     * `getMaxAmplitude()` reports the peak since the previous call to it, so
     * polling it and keeping the running maximum gives a truthful "did this
     * device hear anything at all" answer. That is what turns a silent capture
     * from a mystery into a named failure -- see `endRecording`.
     */
    private fun startPeakWatch() {
        peak = 0
        val t = thread(name = "connectdesk-call-peak", isDaemon = true) {
            while (recorder != null) {
                val r = recorder ?: break
                val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                if (amp > peak) peak = amp
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        peakWatch = t
    }

    private fun beginRecording(number: String?) {
        if (recorder != null) return
        if (!Prefs.callRecordingArmed(this)) return
        val token = ApiClient.loadToken(this) ?: return

        val file = File(cacheDir, "call_${System.currentTimeMillis()}.m4a")
        recorderSource = UNSUPPORTED_SOURCE
        provenSource = Prefs.callRecorderSource(this)
        val rec = startRecorder(file)
        if (rec == null) {
            file.delete()
            return
        }
        recorder = rec
        outFile = file
        startedAt = System.currentTimeMillis()
        startPeakWatch()

        showRecordingNotification()

        // Open a slot on the server so the audio has somewhere to land.
        val latch = CountDownLatch(1)
        slotReady = latch
        thread(name = "connectdesk-call-open") {
            try {
                val resp = ApiClient.startCallRecording(token, "Call recording", number, startedAt)
                recordingId = resp?.recordingId
                mediaId = resp?.mediaId
                resp?.chunkSize?.let { uploadChunk = it }
            } finally {
                latch.countDown()
            }
        }
    }

    private fun endRecording() {
        val rec = recorder ?: return
        recorder = null
        val file = outFile
        outFile = null
        runCatching { peakWatch?.interrupt() }
        peakWatch = null
        val heardSomething = peak > 0
        peak = 0

        var finalised = true
        try {
            rec.stop()
        } catch (_: Throwable) {
            // Too short to finalise an AAC stream; drop it rather than upload
            // a corrupt file that would fail to play.
            finalised = false
        }
        try {
            rec.release()
        } catch (_: Throwable) {
        }
        cancelNotification()

        fun drop(reason: String?) {
            runCatching { file?.delete() }
            if (reason != null) ServiceStatus.markError(reason)
            recordingId = null
            mediaId = null
            stopSelf()
        }

        val token = ApiClient.loadToken(this)
        if (!finalised) {
            drop("Call recording too short to finalise (dropped)")
            return
        }
        if (token == null || file == null || !file.exists() || file.length() <= 0) {
            drop("Call recording produced no file")
            return
        }
        if (!heardSomething) {
            // The device sampled its own microphone for the whole call and the
            // peak never left zero. Uploading that would put a file on the
            // dashboard that plays for its full duration and says nothing --
            // which reads as "broken" with no way to tell why. Name it instead.
            //
            // If that silence came from the very source pinned as proven, the
            // pin is stale (the OEM's audio policy changed, a headset re-routed
            // the mic): clear it so the next call re-probes the whole chain
            // instead of repeating the same silent capture.
            if (recorderSource == Prefs.callRecorderSource(this)) {
                Prefs.setCallRecorderSource(this, UNSUPPORTED_SOURCE)
            }
            drop("Call recording captured no audio: phone mic was silent during the call")
            return
        }

        // This source just produced a finalised, non-empty file the amplitude
        // watch proved audible. Pin it: the next call starts on it directly.
        Prefs.setCallRecorderSource(this, recorderSource)

        val durationSec = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
        val size = file.length()
        thread(name = "connectdesk-call-upload") {
            try {
                // Wait for the slot BEFORE reading the ids. Hanging up on a
                // short call used to beat that POST, so `id`/`media` were
                // captured as null here and the recording was deleted
                // un-uploaded. The await also orders the two fields' writes
                // against their reads.
                val opened = runCatching {
                    slotReady?.await(20, TimeUnit.SECONDS) ?: true
                }.getOrDefault(true)
                val id = recordingId
                val media = mediaId
                if (opened && id != null && media != null) {
                    // Stream it in `uploadChunk` slices. The old version read
                    // the WHOLE file with readBytes() and posted it as one
                    // base64 blob, so a long call (64 kbit/s ~= 4.8 MB for ten
                    // minutes, ~6.4 MB once base64-encoded) either timed out or
                    // blew the request limit and the recording was lost.
                    // The same chunked shape CommandWorker already uses for
                    // media downloads keeps one slice in memory at a time.
                    val chunk = uploadChunk.coerceAtLeast(64 * 1024)
                    val total = ((size + chunk - 1) / chunk).toInt().coerceAtLeast(1)
                    var ok = true
                    file.inputStream().use { input ->
                        val buf = ByteArray(chunk)
                        var index = 0
                        while (index < total) {
                            var read = 0
                            while (read < chunk) {
                                val n = input.read(buf, read, chunk - read)
                                if (n <= 0) break
                                read += n
                            }
                            if (read <= 0) break
                            val part = if (read == chunk) buf else buf.copyOf(read)
                            val b64 = android.util.Base64.encodeToString(
                                part, android.util.Base64.NO_WRAP,
                            )
                            if (!ApiClient.uploadMediaChunk(token, media, index, total, b64)) {
                                ok = false
                                break
                            }
                            index++
                        }
                    }
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
        /** Prefs sentinel: no audio source has been proven on this device yet. */
        private const val UNSUPPORTED_SOURCE = -1
        private const val CHANNEL = "connectdesk_calls"
        // Must not collide with CameraLiveService's 44 or MicLiveService's 45:
        // notification IDs are global per app, so a shared ID let one service
        // silently cancel another's permanent consent indicator.
        private const val NOTIF_ID = 46
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
            // stopService, not startService: this can be reached from the
            // background, where startService throws on Android 8+.
            runCatching {
                context.stopService(Intent(context, CallRecorderService::class.java))
            }
        }

        /**
         * The dashboard may only ever disarm from a distance.
         *
         * The old version pushed an intent to the running service. That never
         * worked: a dashboard request arrives while the app is backgrounded,
         * and startService is refused there, so the remote disarm was silently
         * dropped. Writing the consent flag directly is both simpler and
         * actually effective — the recorder re-checks it before every call.
         */
        fun setArmedRemotely(context: Context, armed: Boolean) {
            if (armed) {
                // Arming remotely is never allowed. The phone owner has to do
                // it from the app; a remote "arm" is ignored on purpose.
                return
            }
            Prefs.setCallRecordingArmed(context, false)
            runCatching {
                context.stopService(Intent(context, CallRecorderService::class.java))
            }
        }
    }
}