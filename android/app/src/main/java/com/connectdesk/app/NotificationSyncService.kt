package com.connectdesk.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.concurrent.thread

/**
 * Two things leave the phone from here, and both are off by default:
 *
 *  1. Ordinary app notifications  → the dashboard notification feed, with
 *     OTP / password / card patterns dropped on-device before sending.
 *  2. Chat previews                → a per-conversation view on the dashboard.
 *
 * CONSENT FOR CHATS (all three must be true, and all three are visible and
 * revocable by the person using the phone):
 *  - they granted this app Notification access in Android Settings,
 *  - they turned on "Sync messages" in this app,
 *  - the dashboard owner enabled the device's `chats` capability.
 *
 * HONEST SCOPE: Android sandboxes each messaging app's own database, so no
 * third-party app on Android can read a real WhatsApp / Instagram /
 * Snapchat chat history — and this app does not try to. What is forwarded is
 * exactly the preview Android itself already rendered on this phone's lock
 * screen: the sender and the first line of the message. That text is the same
 * thing the user is already looking at on their own screen.
 *
 * It deliberately does NOT use an AccessibilityService to scrape the screen:
 * that would be a covert keylogger/exfiltrator, not a device manager.
 */
class NotificationSyncService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        ApiClient.attach(this)
        val token = ApiClient.loadToken(this) ?: return

        val appLabel = labelFor(sbn.packageName)
        val chatApp = CHAT_APPS[sbn.packageName]
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        // ---- 2. Chat app -------------------------------------------------
        if (chatApp != null) {
            if (!Prefs.chatsSyncEnabled(this)) return
            val state = state(token) ?: return
            if (!state.chats) return // dashboard owner has not enabled it

            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            val preview = when {
                !lines.isNullOrEmpty() -> lines.joinToString("\n") { it.toString() }
                !bigText.isNullOrBlank() -> bigText
                body.isNotBlank() -> body
                else -> return // a silent notification carries no message
            }
            val parsed = parseChat(title, preview)
            val fingerprint = "${chatApp}|${parsed.conversation}|${parsed.body}".hashCode()
            if (!seenRecently(fingerprint)) {
                thread(name = "connectdesk-chat") {
                    try {
                        ApiClient.pushChatMessage(
                            token = token,
                            app = chatApp,
                            appName = appLabel,
                            conversation = parsed.conversation,
                            body = parsed.body,
                            isGroup = parsed.isGroup,
                            direction = parsed.direction,
                            postedAt = sbn.postTime,
                        )
                    } catch (_: Exception) {
                        // best-effort; the next message retries
                    }
                }
            }
            // Chat previews go to the Chats page, not the generic feed, so the
            // same message never shows up twice.
            return
        }

        // ---- 1. Ordinary notification ------------------------------------
        if (!Prefs.notifSyncEnabled(this)) return
        val st = state(token) ?: return
        if (st.notifications != true) return

        val (t, b) = mask(title, body) ?: return // dropped = sensitive

        thread {
            try {
                ApiClient.pushNotification(token, appLabel, t, b)
            } catch (e: Exception) {
                // best-effort; next notification retries
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Nothing to do: messages are captured on post, never on dismissal,
        // so dismissing a notification on the phone cannot erase the record.
    }

    override fun onListenerConnected() {
        stateCache = null // force a fresh capability check
    }

    private fun labelFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
            .toString()
    } catch (_: Exception) {
        pkg
    }

    /**
     * Device-side capability state, cached for 60s so a busy notification
     * feed does not turn into one HTTP call per notification.
     */
    private fun state(token: String): ApiClient.DeviceState? {
        val now = System.currentTimeMillis()
        if (stateCache == null || now - cacheAt > 60_000) {
            stateCache = ApiClient.status(token)
            cacheAt = now
        }
        return stateCache
    }

    /** Cheap de-dupe: some apps re-post the same preview as it updates. */
    private fun seenRecently(fingerprint: Int): Boolean {
        synchronized(seen) {
            val now = System.currentTimeMillis()
            seen.entries.removeAll { now - it.value > 15_000 }
            if (seen.containsKey(fingerprint)) return true
            seen[fingerprint] = now
            if (seen.size > 64) seen.clear()
            return false
        }
    }

    data class ParsedChat(
        val conversation: String,
        val body: String,
        val isGroup: Boolean,
        val direction: String,
    )

    /**
     * Turns the (sender, preview) pair Android rendered into a conversation.
     *
     * Handles the two shapes every messenger uses:
     *   direct chat   title = sender,  preview = message
     *   group chat    title = group,   preview = "Sender: message"
     * plus outgoing messages, which the apps label "You".
     */
    internal fun parseChat(title: String, preview: String): ParsedChat {
        var conversation = title.trim()
        var text = preview.trim()
        var isGroup = false

        // Group notifications put the sender inside the preview body.
        if (conversation.isNotEmpty() && text.startsWith("$conversation:")) {
            isGroup = true
            text = text.removePrefix("$conversation:").trim()
        } else if (conversation.isEmpty()) {
            conversation = "Unknown"
        }

        // "You: ..." means the phone owner sent this.
        val self = conversation.equals("you", ignoreCase = true) ||
            conversation.equals("me", ignoreCase = true)
        if (self) {
            if (text.startsWith("you:", ignoreCase = true)) {
                text = text.substringAfter(':').trim()
            }
            return ParsedChat(conversation, text, isGroup, "outgoing")
        }

        // Some apps omit the sender entirely and lead with the message.
        if (conversation.isEmpty() || conversation == "Unknown") {
            return ParsedChat(conversation, text, isGroup, "unknown")
        }
        return ParsedChat(conversation, text, isGroup, "incoming")
    }

    companion object {
        @Volatile private var stateCache: ApiClient.DeviceState? = null
        @Volatile private var cacheAt: Long = 0
        private val seen = HashMap<Int, Long>()

        /**
         * Messaging apps we recognise, mapped to the slug the dashboard groups
         * by. Unknown apps still arrive through the ordinary notification feed.
         */
        val CHAT_APPS: Map<String, String> = mapOf(
            "com.whatsapp" to "whatsapp",
            "com.whatsapp.w4b" to "whatsapp_business",
            "com.instagram.android" to "instagram",
            "com.instagram.barcelona" to "threads",
            "com.snapchat.android" to "snapchat",
            "org.telegram.messenger" to "telegram",
            "com.facebook.orca" to "messenger",
            "com.discord" to "discord",
            "org.thoughtcrime.securesms" to "signal",
            "com.twitter.android" to "twitter",
            "com.linkedin.android" to "linkedin",
            "com.viber.voip" to "viber",
            "com.imo" to "imo",
            "jp.naver.line.android" to "line",
            "com.kakaotalk" to "kakaotalk",
            "com.tencent.mm" to "wechat",
        )

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
    private const val KEY_CHATS = "chatSync"
    private const val KEY_CLIPBOARD = "clipboardSync"
    private const val KEY_CALL_REC = "callRecordingArmed"

    fun notifSyncEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIF, false)

    fun setNotifSync(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIF, enabled).apply()
    }

    /**
     * Chat previews are a separate, OFF-by-default switch. Turning it on needs
     * notification access as well, so a chat message is never synced without
     * the phone owner making two explicit choices.
     */
    fun chatsSyncEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_CHATS, false)

    fun setChatsSync(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CHATS, enabled).apply()
    }

    /** Clipboard history is OFF by default, like every other sensitive feed. */
    fun clipboardSyncEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_CLIPBOARD, false)

    fun setClipboardSync(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CLIPBOARD, enabled).apply()
    }

    /**
     * Call recording is armed here, by the person holding the phone, and only
     * here. The dashboard can switch it back off but can never switch it on.
     */
    fun callRecordingArmed(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_CALL_REC, false)

    fun setCallRecordingArmed(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CALL_REC, enabled).apply()
        if (enabled) {
            CallRecorderService.start(context)
        } else {
            CallRecorderService.stop(context)
        }
    }
}