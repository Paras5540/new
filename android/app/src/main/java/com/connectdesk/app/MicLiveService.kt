package com.connectdesk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Live microphone streaming to the dashboard, with the phone owner in control.
 *
 * DESIGN — what this deliberately is and is not:
 *
 * This is NOT a hidden microphone. The person holding the phone has to turn
 * this on deliberately from the app, and while it runs they always see:
 *   - a permanent, non-dismissable foreground notification that says the
 *     microphone is live and offers a Stop button, and
 *   - the system's own microphone privacy indicator.
 * They can stop it at any time from the notification or by opening the app.
 * There is no way for the dashboard to switch this on: `start()` is only
 * reachable from the local UI, and the server only ever asks the device to
 * STOP (an expired/absent `mic_live` session makes the server answer
 * `no_session`, and this service shuts the microphone down at once).
 *
 * WHY A SERVICE: Android requires a foreground service with the MICROPHONE
 * type to keep recording while the app is backgrounded. The notification is not
 * decoration — it is the consent mechanism the OS is built around.
 *
 * Audio is captured in short self-contained WAV clips and streamed one at a
 * time. Nothing is written to the device's disk, and the server keeps only a
 * bounded rolling window (see `deviceApi.pushMicFrame`).
 */
class MicLiveService : Service() {

    private var running = false
    private var recorder: AudioRecord? = null
    private var failures = 0
    private var sent = 0

    /**
     * Clip counter, seeded from the wall clock so it keeps increasing across
     * service restarts. It used to start at 0 every time, so after a stop and
     * restart the server saw two different clips both numbered 1 -- the
     * dashboard orders by `seq` and evicted by arrival time, so a restarted
     * stream could replay an older clip in the middle of newer ones.
     */
    private var seq: Int = (System.currentTimeMillis() / 1000L % 1_000_000L).toInt()

    /**
     * NO BUFFER: one clip may be uploading at a time.
     *
     * The capture loop used to do the HTTPS POST INLINE, on the same thread
     * that was draining the microphone. For the whole duration of the upload
     * nothing read the AudioRecord, its buffer overran, and real audio was
     * dropped by the hardware -- which is heard as crackle and clipped words,
     * and it is the reason the live mic never sounded continuous.
     *
     * Capture and upload are now separate. The guard makes the consequence of
     * a slow link explicit: if the previous clip is still going out, this one
     * is DISCARDED rather than queued. A backlog would play old audio late and
     * drift further behind every second; dropping keeps the audio at the
     * newest point, which is what "live" means.
     */
    private val uploading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Clips discarded because an upload was still in flight. */
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
                startForegroundWithIndicator()
                if (!running) {
                    running = true
                    thread(name = "connectdesk-mic") { captureLoop() }
                }
            }
        }
        // Never auto-restart: if the user stopped sharing, we must stay off.
        return START_NOT_STICKY
    }

    /** The visible consent indicator. Non-dismissable, states it is live. */
    private fun startForegroundWithIndicator() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.mic_live_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val n: Notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.mic_live_title))
                .setContentText(getString(R.string.mic_live_body))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.mic_live_stop),
                    buildStopPendingIntent(),
                )
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Lets the user stop the microphone straight from the notification. */
    private fun buildStopPendingIntent(): android.app.PendingIntent {
        val i = Intent(this, MicLiveService::class.java).setAction(ACTION_STOP)
        val flags =
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        return android.app.PendingIntent.getService(this, 2, i, flags)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        if (!hasPermission()) return null
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (min <= 0) return null
        val bufferBytes = maxOf(min * 2, CLIP_SAMPLES * 2 * 2)
        return try {
            val r =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    bufferBytes,
                )
            if (r.state == AudioRecord.STATE_INITIALIZED) r else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun captureLoop() {
        // The microphone can be transiently unavailable -- a phone call, or
        // another app holding it. Giving up on the first failure meant the mic
        // switched itself off for something that clears in a second or two.
        var rec: AudioRecord? = null
        for (attempt in 1..5) {
            if (!running) return
            rec = openRecorder()
            if (rec != null) break
            Thread.sleep(400L * attempt)
        }
        if (rec == null) {
            stopEverything()
            return
        }
        recorder = rec
        val buffer = ShortArray(CLIP_SAMPLES)
        try {
            rec.startRecording()
        } catch (_: Throwable) {
            stopEverything()
            return
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stopEverything()
            return
        }

        while (running) {
            var read = 0
            try {
                while (read < CLIP_SAMPLES && running) {
                    // BLOCKING read: this waits for real microphone samples
                    // instead of returning immediately. A non-blocking read
                    // returns 0 while the buffer fills, which sent an empty
                    // clip every second and made the dashboard audio click and
                    // stutter. Blocking is also what keeps the capture at a
                    // steady 1-second cadence.
                    val n = rec.read(buffer, read, CLIP_SAMPLES - read)
                    if (n < 0) {
                        // Hard AudioRecord error: retrying the same call just
                        // spins. Back off briefly and try again.
                        Thread.sleep(80)
                        continue
                    }
                    read += n
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Throwable) {
                break
            }
            if (!running) break
            // A short read means the buffer underran. Never upload a partial
            // clip: a half-second of audio played back as a click.
            if (read < CLIP_SAMPLES) continue

            val wav = toWav(buffer, read)
            val token = ApiClient.loadToken(this)
            if (token == null) {
                stopEverything()
                return
            }
            // The microphone must keep draining, so the upload happens on its own
            // thread. `uploading` guarantees at most one is ever in flight.
            if (!uploading.compareAndSet(false, true)) {
                skipped++
                continue
            }
            val durationMs = read * 1000L / SAMPLE_RATE
            thread(name = "connectdesk-mic-upload") {
                try {
                    val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
                    val ok = ApiClient.postMicFrame(token, seq++, b64, durationMs)
                    if (ok) {
                        failures = 0
                        sent++
                    } else {
                        failures++
                        // Only a REFUSAL stops the microphone. A flaky
                        // connection is not a reason to end somebody's
                        // recording: the old code gave up after six, which on
                        // a weak signal meant the mic switched itself off
                        // mid-sentence. Retrying is safe because the server
                        // keeps only a short window anyway.
                        if (failures > 30 && !ApiClient.lastFailureIsRetryable()) {
                            stopEverything()
                            return@thread
                        }
                    }
                } finally {
                    uploading.set(false)
                }
            }
        }
        stopEverything()
    }

    /**
     * Wraps 16-bit mono PCM in a 44-byte WAV header so each clip is a complete
     * file the browser can play directly.
     */
    private fun toWav(samples: ShortArray, count: Int): ByteArray {
        val dataLen = count * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        val header = ByteArrayOutputStream(44)
        fun ascii(s: String) = header.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            header.write(v and 0xff)
            header.write((v shr 8) and 0xff)
            header.write((v shr 16) and 0xff)
            header.write((v shr 24) and 0xff)
        }
        fun le16(v: Int) {
            header.write(v and 0xff)
            header.write((v shr 8) and 0xff)
        }
        ascii("RIFF")
        le32(36 + dataLen)
        ascii("WAVE")
        ascii("fmt ")
        le32(16) // PCM chunk size
        le16(1) // format = PCM
        le16(CHANNEL_COUNT) // channels = mono
        le32(SAMPLE_RATE)
        le32(SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNEL_COUNT) // byte rate
        le16(BYTES_PER_SAMPLE * CHANNEL_COUNT) // block align
        le16(16) // bits per sample
        ascii("data")
        le32(dataLen)
        out.write(header.toByteArray())
        for (i in 0 until count) {
            val s = samples[i].toInt()
            out.write(s and 0xff)
            out.write((s shr 8) and 0xff)
        }
        return out.toByteArray()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        running = false
        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
        // Only disarm when the owner asked us to stop from the notification.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Clips successfully uploaded so far. Logged so a dead stream is visible. */
    fun stats(): String = "clips sent=$sent skipped=$skipped seq=$seq failures=$failures"

    companion object {
        private const val CHANNEL = "connectdesk_mic_live"
        private const val NOTIF_ID = 45
        private const val SAMPLE_RATE = 16000

        /**
         * The raw capture channel count.
         *
         * This is deliberately NOT [CHANNEL]: `CHANNEL` is the notification
         * channel *id* (a String used for the foreground-service notification),
         * while AudioRecord needs the integer `AudioFormat.CHANNEL_IN_*`.
         * Passing the id to AudioRecord does not compile.
         */
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO

        /** Channel count written into the WAV header: mono. */
        private const val CHANNEL_COUNT = 1

        /** Bytes per sample for `ENCODING_PCM_16BIT`: 2. */
        private const val BYTES_PER_SAMPLE = 2
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CLIP_SAMPLES = SAMPLE_RATE // 1 second per clip

        const val ACTION_STOP = "com.connectdesk.app.STOP_MIC_LIVE"

        /**
         * Arms live streaming. Only ever called from the local UI after the
         * user explicitly turns it on, never from a server command.
         */
        fun start(context: Context) {
            val i = Intent(context, MicLiveService::class.java).setAction("START")
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            // stopService rather than startService: this is called from the
            // dashboard's stop request and from the revoked path, both of which
            // can run while the app is backgrounded, where startService throws
            // IllegalStateException on Android 8+. stopService still triggers
            // onDestroy -> stopEverything().
            runCatching {
                context.stopService(Intent(context, MicLiveService::class.java))
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
        private const val KEY_ARMED = "micLiveArmed"
    }
}