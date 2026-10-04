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

    override fun onCreate() {
        super.onCreate()
        // Anything still queued from a previous run goes back on the wire.
        restorePending()
        restoreChats()
        ApiClient.loadToken(this)?.let {
            drainPending(it)
            drainPendingChats(it)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handlePosted(sbn)
    }

    /**
     * Everything already sitting in the shade when the listener connects.
     *
     * Android hands the whole current shade to us here and never replays it
     * afterwards, so without this sweep every notification that arrived while
     * the listener was disconnected -- a reboot, the app being force-stopped,
     * the phone restarting after a crash -- simply never reached the dashboard.
     * That is why the feed could look empty on a phone that plainly had
     * unread messages waiting. The 24h retention sweep then deletes whatever is
     * older than the window, so a backfill cannot resurrect stale history.
     */
    override fun onListenerConnected() {
        stateCache = null // force a fresh capability check
        val token = ApiClient.loadToken(this) ?: return
        thread(name = "connectdesk-backfill") {
            try {
                val active = activeNotifications ?: return@thread
                for (sbn in active) handlePosted(sbn)
                drainPending(token)
                drainPendingChats(token)
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
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
            val state = state(token)
            // Unknown state no longer means "drop the message". A chat message
            // that arrived in a lift used to vanish here for good.
            if (state != null && state.chats != true) return // explicitly OFF

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
                // Chats went through a bare thread + catch with no retry and no
                // queue, while notifications got one. A single failed POST --
                // lift, timeout, no signal -- silently lost that message for
                // good, and the comment claiming "the next message retries" was
                // never true. That is why the Chats page stayed empty on a
                // phone that plainly had messages arriving.
                // Queue first, then drain. The old code sent straight from a
                // bare `thread { }` with its return value thrown away, so a
                // message that failed to leave the phone was simply gone --
                // the retry claim in the comment was never implemented.
                enqueueChat(
                    PendingChat(
                        chatApp,
                        appLabel,
                        parsed.conversation,
                        parsed.body,
                        parsed.isGroup,
                        parsed.direction,
                        sbn.postTime,
                    ),
                )
                drainPendingChats(token)
            }
            // Chat previews go to the Chats page, not the generic feed, so the
            // same message never shows up twice.
            return
        }

        // ---- 1. Ordinary notification ------------------------------------
        if (!Prefs.notifSyncEnabled(this)) return

        val (t, b) = mask(title, body) ?: return // dropped = sensitive

        // deliver() queues when the server is unreachable instead of dropping
        // the notification, and drains the queue once the state comes back.
        thread {
            deliver(token, appLabel, t, b, sbn.postTime)
        }
        drainPending(token)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Nothing to do: messages are captured on post, never on dismissal,
        // so dismissing a notification on the phone cannot erase the record.
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

    // ---- Pending queue: a notification is never thrown away ----------------
    //
    // `state()` performs a NETWORK call, and it returns null whenever that call
    // fails -- a lift, a timeout, mobile data with no signal, a paused
    // deployment. Both sync paths used to `return` on that null, so the
    // notification was dropped on the floor and never seen by anyone again.
    // The comment claimed "next notification retries", but nothing retried:
    // that one notification was simply gone.
    //
    // On a phone sitting in a lift, EVERY notification for the next 60 s (the
    // cache window) was lost the same way. That is what made the dashboard's
    // Notifications page look empty on a device that plainly had notifications
    // to show.
    //
    // So when the capability state is unknown we QUEUE instead of dropping, and
    // drain the queue as soon as the state can be read again. The consent gate
    // is unchanged -- a queued item is still discarded if the dashboard turns
    // the capability off, it just is not lost to a network blip.
    private data class Pending(
        val appLabel: String,
        val title: String,
        val body: String,
        val postedAt: Long,
    )

    private val pending = ArrayDeque<Pending>()

    // `const` is only legal at top level, in a named object or in a companion
    // object -- a plain class body is none of those, so `private const val`
    // here does not compile ("Const 'val' are only allowed on top level, in
    // named objects, or in companion objects"). These are instance-level queue
    // caps, so an ordinary `private val` is the correct declaration.
    private val PENDING_MAX = 100

    // ---- Chat queue: same durability as notifications ---------------------
    // A chat message is more sensitive and more wanted than a generic
    // notification, so losing one to a network blip is worse, not better.
    private data class PendingChat(
        val app: String,
        val appName: String,
        val conversation: String,
        val body: String,
        val isGroup: Boolean,
        val direction: String,
        val postedAt: Long,
    )

    private val pendingChats = ArrayDeque<PendingChat>()
    private val PENDING_CHATS_MAX = 200

    private fun enqueueChat(c: PendingChat) {
        synchronized(pendingChats) {
            if (pendingChats.size >= PENDING_CHATS_MAX) pendingChats.removeFirst()
            pendingChats.addLast(c)
            persistChats()
        }
    }

    private fun persistChats() {
        val arr = org.json.JSONArray()
        synchronized(pendingChats) {
            for (c in pendingChats) {
                arr.put(
                    org.json.JSONObject()
                        .put("ap", c.app)
                        .put("an", c.appName)
                        .put("c", c.conversation)
                        .put("b", c.body)
                        .put("g", c.isGroup)
                        .put("d", c.direction)
                        .put("p", c.postedAt),
                )
            }
        }
        try {
            getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
                .edit().putString(QUEUE_CHATS_KEY, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreChats() {
        try {
            val raw = getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
                .getString(QUEUE_CHATS_KEY, null) ?: return
            val arr = org.json.JSONArray(raw)
            synchronized(pendingChats) {
                pendingChats.clear()
                for (i in 0 until minOf(arr.length(), PENDING_CHATS_MAX)) {
                    val o = arr.getJSONObject(i)
                    pendingChats.addLast(
                        PendingChat(
                            o.optString("ap"),
                            o.optString("an"),
                            o.optString("c"),
                            o.optString("b"),
                            o.optBoolean("g", false),
                            o.optString("d", "incoming"),
                            o.optLong("p", 0L),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Sends one chat message; false means "could not get it out". */
    private fun sendChat(token: String, c: PendingChat): Boolean = try {
        ApiClient.pushChatMessage(
            token = token,
            app = c.app,
            appName = c.appName,
            conversation = c.conversation,
            body = c.body,
            isGroup = c.isGroup,
            direction = c.direction,
            postedAt = c.postedAt,
        ) != null
    } catch (_: Exception) {
        false
    }

    private fun drainPendingChats(token: String) {
        val batch = synchronized(pendingChats) {
            if (pendingChats.isEmpty()) return
            val copy = pendingChats.toList()
            pendingChats.clear()
            persistChats()
            copy
        }
        thread(name = "connectdesk-chat-drain") {
            for (c in batch) {
                val st = state(token)
                // An explicitly switched-off capability is a real decision, so
                // anything still queued for it is discarded rather than resent.
                if (st != null && st.chats != true) return@thread
                if (!sendChat(token, c)) {
                    if (ApiClient.lastFailureIsRetryable()) {
                        // Offline / server down: keep it, it is not lost.
                        enqueueChat(c)
                        return@thread
                    }
                    // The server refused it (bad token, chats disabled on this
                    // device). Retrying forever would only block the queue.
                    return@thread
                }
            }
        }
    }

    /**
     * Queues a notification and mirrors the queue to disk.
     *
     * It used to live only in a field, so an app kill, a low-memory kill or a
     * reboot threw away everything that had not gone out yet — which is
     * exactly the window where notifications matter most (signal drops in a
     * lift). The queue is small, so it is persisted as one JSON blob and
     * reloaded on service create.
     */
    private fun enqueue(p: Pending) {
        synchronized(pending) {
            if (pending.size >= PENDING_MAX) pending.removeFirst() // drop OLDEST
            pending.addLast(p)
            persistPending()
        }
    }

    private fun persistPending() {
        val json = org.json.JSONArray()
        synchronized(pending) {
            for (q in pending) {
                json.put(
                    org.json.JSONObject()
                        .put("a", q.appLabel)
                        .put("t", q.title)
                        .put("b", q.body)
                        .put("p", q.postedAt),
                )
            }
        }
        try {
            getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
                .edit().putString(QUEUE_KEY, json.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun restorePending() {
        try {
            val raw = getSharedPreferences(QUEUE_PREFS, MODE_PRIVATE)
                .getString(QUEUE_KEY, null) ?: return
            val arr = org.json.JSONArray(raw)
            synchronized(pending) {
                pending.clear()
                for (i in 0 until minOf(arr.length(), PENDING_MAX)) {
                    val o = arr.getJSONObject(i)
                    pending.addLast(
                        Pending(
                            o.optString("a"),
                            o.optString("t"),
                            o.optString("b"),
                            o.optLong("p", 0L),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
    }


    /** Sends everything that was waiting for the capability state to come back. */
    private fun drainPending(token: String) {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            persistPending()
            copy
        }
        thread(name = "connectdesk-notif-drain") {
            for (p in batch) {
                val ok = try {
                    ApiClient.pushNotification(token, p.appLabel, p.title, p.body)
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    if (ApiClient.lastFailureIsRetryable()) {
                        // Still no network. Put it back so it is not lost.
                        enqueue(p)
                        return@thread
                    }
                    return@thread // refused, not unreachable
                }
            }
        }
    }

    /**
     * Pushes one notification, queueing it if the server cannot be reached.
     *
     * Returns true when it went out (or was queued), false only when the
     * dashboard has the capability OFF -- which is a real decision and must
     * not be second-guessed.
     */
    private fun deliver(token: String, appLabel: String, t: String, b: String, postedAt: Long): Boolean {
        val st = state(token)
        if (st == null) {
            // Unknown, not "off". Hold it rather than lose it.
            enqueue(Pending(appLabel, t, b, postedAt))
            return true
        }
        if (st.notifications != true) return false
        return try {
            val sent = ApiClient.pushNotification(token, appLabel, t, b)
            if (!sent && !ApiClient.lastFailureIsRetryable()) {
                // Refused, not unreachable: retrying would block the queue.
                false
            } else {
                if (!sent) enqueue(Pending(appLabel, t, b, postedAt))
                true
            }
        } catch (_: Exception) {
            enqueue(Pending(appLabel, t, b, postedAt))
            true
        }
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
        /** On-disk mirror of the pending queue, so an app kill cannot lose it. */
        const val QUEUE_PREFS = "connectdesk_notif_queue"
        const val QUEUE_KEY = "pending"
        const val QUEUE_CHATS_KEY = "pending_chats"

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
            // Defaults to ON for the same reason as chats: the dashboard's
            // per-device `notifications` capability is the real consent gate,
            // and this default only decides what a phone sends BEFORE anyone
            // has touched the toggle. Was `false`, which meant the
            // Notifications page stayed empty on a freshly paired device.
            .getBoolean(KEY_NOTIF, true)

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
            // Defaults to ON so a paired device starts delivering chats as
            // soon as the dashboard enables the `chats` capability. It was
            // `false`, which meant a freshly paired phone silently synced
            // nothing and the Chats page looked permanently empty. The
            // dashboard's per-device `chats` capability still has to be on,
            // and notification access is still required, so this is not a
            // blanket opt-out of consent.
            .getBoolean(KEY_CHATS, true)

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