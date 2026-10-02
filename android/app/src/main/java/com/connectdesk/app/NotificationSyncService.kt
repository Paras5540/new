package com.connectdesk.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import kotlin.concurrent.thread

/**
 * Syncs notifications to the dashboard while BOTH consents are active:
 *  1. The device user enabled "Sync notifications" in this app (local toggle)
 *  2. The dashboard owner enabled the notifications capability for this device
 *
 * Content masking happens ON-DEVICE before anything leaves the phone:
 *  - OTP patterns (4-8 digits standalone) are replaced with dots
 *  - Long digit sequences (cards/phones) are masked
 *  - Title/body containing password-like keywords are dropped entirely
 */
class NotificationSyncService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val token = ApiClient.loadToken(this) ?: return
        if (!Prefs.notifSyncEnabled(this)) return

        // Server-side capability gate, cached for 60s to stay battery-friendly
        val now = System.currentTimeMillis()
        val state = if (stateCache == null || now - cacheAt > 60_000) {
            stateCache = ApiClient.status(token)
            cacheAt = now
            stateCache
        } else {
            stateCache
        }
        if (state?.notifications != true) return

        val extras = sbn.notification.extras
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0),
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val (t, b) = mask(title, text) ?: return // dropped = sensitive

        thread {
            try {
                ApiClient.pushNotification(token, appName, t, b)
            } catch (e: Exception) {
                // best-effort; next notification retries
            }
        }
    }

    override fun onListenerConnected() {
        stateCache = null // force a fresh capability check
    }

    companion object {
        @Volatile private var stateCache: ApiClient.DeviceState? = null
        @Volatile private var cacheAt: Long = 0

        /** Returns masked pair, or null if the notification should be dropped. */
        internal fun mask(title: String, body: String): Pair<String, String>? {
            val sensitiveKeywords = listOf(
                "password", "otp", "one time", "verification code", "pin",
                "credit card", "bank", "balance", "2fa", "security code",
            )
            val combined = "$title $body".lowercase()
            if (sensitiveKeywords.any { it in combined }) return null

            fun maskDigits(s: String): String =
                s.replace(Regex("\\b\\d{4,8}\\b")) { m -> "•".repeat(m.value.length) }
                    .replace(Regex("\\b\\d{10,}\\b")) { m -> "•".repeat(m.value.length) }

            return Pair(maskDigits(title), maskDigits(body))
        }
    }
}

/** Local (device-side) consent toggle storage. */
object Prefs {
    private const val PREFS = "connectdesk_prefs"
    private const val KEY_NOTIF = "notifSync"

    fun notifSyncEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIF, false)

    fun setNotifSync(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIF, enabled).apply()
    }
}
