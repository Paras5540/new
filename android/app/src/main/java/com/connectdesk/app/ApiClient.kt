package com.connectdesk.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    // Production deploy ke baad is URL ko update karna hoga.
    var BASE_URL: String = "https://valuable-goldfish-43.convex.site"

    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun post(path: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .post(body.toString().toRequestBody(json))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
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
        return if (token.isNotEmpty()) Pair(resp.optString("deviceId"), token) else null
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
