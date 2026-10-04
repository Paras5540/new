package com.connectdesk.app

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Clipboard history, with the platform's own limits respected.
 *
 * ANDROID'S REALITY (API 29+): a backgrounded app cannot read the clipboard at
 * all. Only the focused window — or the default keyboard — may call
 * getPrimaryClip(). So this does not "hack around" it: it registers a listener
 * and reads the clipboard only while the phone is unlocked and this app is on
 * screen, which is exactly when the user is deliberately copying something.
 *
 * ON-DEVICE GUARD: anything a password manager flagged as sensitive
 * (ClipDescription.EXTRA_IS_SENSITIVE) is dropped before anything is sent, as
 * are obvious credential shapes. The server refuses those again as a
 * backstop, but the phone is the first line of defence.
 */
object ClipboardWorker {

    private val handler = Handler(Looper.getMainLooper())
    private var manager: ClipboardManager? = null
    private var appContext: Context? = null
    private var installed = false
    private var lastText: String? = null

    /** Foreground-only polling interval. Deliberately not a background loop. */
    private const val POLL_MS = 4_000L

    /** Starts watching. Safe to call repeatedly. */
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        appContext = app
        manager = cm
        installed = true
        handler.post(poll)
    }

    fun uninstall() {
        if (!installed) return
        handler.removeCallbacks(poll)
        manager = null
        appContext = null
        installed = false
        lastText = null
    }

    private val poll = object : Runnable {
        override fun run() {
            val cm = manager
            val app = appContext
            if (cm == null || app == null) return
            try {
                if (Prefs.clipboardSyncEnabled(app) && canReadNow(app)) {
                    readAndSend(app, cm)
                }
            } catch (_: Throwable) {
                // Clipboard access throws easily; never let it break the loop.
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * A locked screen means nothing may read the clipboard at all. When the
     * screen is on but another app is in front, Android itself refuses and
     * getPrimaryClip() returns null — which readAndSend treats as "nothing
     * there". We check the keyguard explicitly so the poll loop can skip the
     * work entirely instead of asking every four seconds.
     */
    private fun canReadNow(app: Context): Boolean = try {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        km?.isKeyguardLocked != true
    } catch (_: Throwable) {
        false
    }

    private fun readAndSend(context: Context, cm: ClipboardManager) {
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0) ?: return
        val text = item.coerceToText(context)?.toString()?.trim() ?: return
        if (text.isEmpty() || text.length > 4000) return
        if (text == lastText) return
        lastText = text

        val sensitive = isSensitive(clip.description, text)
        if (sensitive) {
            // Dropped here, on the phone. Nothing is sent.
            return
        }
        val token = ApiClient.loadToken(context) ?: return
        if (!Prefs.clipboardSyncEnabled(context)) return

        kotlin.concurrent.thread(name = "connectdesk-clipboard") {
            try {
                ApiClient.pushClipboard(token, text, null, false, System.currentTimeMillis())
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    /**
     * Password managers set EXTRA_IS_SENSITIVE so the OS can hide the preview;
     * we honour that flag and add a few shape checks of our own.
     */
    private fun isSensitive(description: ClipDescription?, text: String): Boolean {
        if (description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true) {
            return true
        }
        val t = text.trim().lowercase()
        if (t.length < 8) return false
        if (Regex("^(otp|one time password|verification code|pin|passcode|password)\\b").containsMatchIn(t)) {
            return true
        }
        if (Regex("\\b\\d{15,19}\\b").containsMatchIn(t)) return true
        if (Regex("[a-z0-9+/]{32,}={0,2}").containsMatchIn(t)) return true // secrets blobs
        return false
    }
}