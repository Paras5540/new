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

    /** Probes a candidate deployment: any HTTP answer proves it is alive. */
    private fun probe(url: String): Boolean = try {
        val request = Request.Builder()
            .url("$url/api/device/commands")
            .post(JSONObject().put("deviceToken", "probe").toString().toRequestBody(json))
            .build()
        client.newCall(request).execute().use { true }
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
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody(json))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
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
            null
        }
    }

    fun claim(code: String, deviceName: String): Pair<String, String>? {
        val body = JSONObject()
            .put("code", code.trim().uppercase())
            .put("deviceName", deviceName)
            .put("osVersion", android.os.Build.VERSION.RELEASE)
            .put("appVersion", BuildConfig.VERSION_NAME)
        val resp = post("/api/device/claim", body) ?: return null
        val token = resp.optString("deviceToken")
        if (token.isEmpty()) {
            val reason = resp.optString("error").ifEmpty { resp.optString("message") }
            lastError = if (reason.isNotEmpty()) reason else "Server did not return a device token"
            return null
        }
        return Pair(resp.optString("deviceId"), token)
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
    )

    fun status(token: String): DeviceState? {
        val body = JSONObject().put("deviceToken", token)
        val resp = post("/api/device/status", body) ?: return null
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
        )
    }

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

    // ---- Command queue + media transfer (dashboard -> device) ----

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
