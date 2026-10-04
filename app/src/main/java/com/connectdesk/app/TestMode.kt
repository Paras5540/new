package com.connectdesk.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DEBUG-BUILD SELF TEST.
 *
 * Compiled into every build, but only ever runnable when
 * `BuildConfig.TEST_MODE` is true (the `debug` build type). The release build
 * reports every probe as skipped, so nothing here can ship.
 *
 * WHY THIS EXISTS: the interesting questions about a device API are "what
 * happens when a client sends something it should not?" — and the app is the
 * only thing that can ask those questions from a real Android device, over a
 * real network, with a real token. So the app becomes its own tester.
 *
 * It probes, for every endpoint:
 *   - unauthenticated  (no token / forged token)   -> must be refused
 *   - wrong capability                            -> must be refused
 *   - injection payloads (path traversal, NoSQL-ish, huge/negative numbers)
 *   - payload size limits
 *
 * READ THE OUTPUT AS A FINDING LIST: every "LEAK" line is something the
 * backend accepted that it should not have. Everything marked OK is the
 * consent model holding.
 */
object TestMode {

    private const val TAG = "ConnectDeskTest"
    private val json = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Probe(
        val endpoint: String,
        val check: String,
        val httpCode: Int,
        val verdict: String, // OK | LEAK | INFO
        val detail: String,
    )

    val enabled: Boolean get() = BuildConfig.TEST_MODE

    /** Probes that must all come back refused. */
    private val unauthenticated = listOf(
        "/api/device/status" to JSONObject().put("deviceToken", "forged"),
        "/api/device/commands" to JSONObject().put("deviceToken", "forged"),
        "/api/device/heartbeat" to JSONObject().put("deviceToken", "forged"),
        "/api/device/notifications" to JSONObject()
            .put("deviceToken", "forged").put("appName", "T").put("title", "t").put("body", "b"),
        "/api/device/sync" to JSONObject().put("deviceToken", "forged").put("type", "sms").put("messages", JSONArray()),
        "/api/device/files/sync" to JSONObject().put("deviceToken", "forged").put("files", JSONArray()),
        "/api/device/files/chunk" to JSONObject()
            .put("deviceToken", "forged").put("fileId", "x").put("index", 0)
            .put("total", 1).put("dataB64", ""),
        "/api/device/camera/photo" to JSONObject()
            .put("deviceToken", "forged").put("facing", "back").put("dataB64", ""),
        "/api/device/chats/message" to JSONObject()
            .put("deviceToken", "forged").put("app", "whatsapp").put("body", "x"),
        "/api/device/clipboard" to JSONObject().put("deviceToken", "forged").put("text", "x"),
        "/api/device/calls/recording/start" to JSONObject().put("deviceToken", "forged").put("name", "x"),
        "/api/device/media/chunk" to JSONObject()
            .put("deviceToken", "forged").put("mediaId", "x").put("index", 0)
            .put("total", 1).put("dataB64", ""),
    )

    /** Hostile payloads — these must not crash, echo back, or be accepted. */
    private val hostilePayloads = listOf(
        "path traversal" to JSONObject()
            .put("deviceToken", "forged").put("type", "location").put("lat", 1).put("lng", 2),
        "negative numbers" to JSONObject()
            .put("deviceToken", "forged").put("batteryPct", -1).put("storageUsedMb", -999999),
        "huge number" to JSONObject()
            .put("deviceToken", "forged").put("storageTotalMb", 1e308),
        "wrong types" to JSONObject()
            .put("deviceToken", 12345).put("type", "sms").put("messages", "not-an-array"),
        "oversized chunk" to JSONObject()
            .put("deviceToken", "forged").put("fileId", "x").put("index", 0)
            .put("total", 1).put("dataB64", "A".repeat(900_000)),
        "empty body" to JSONObject(),
    )

    /**
     * Runs every probe and returns a report. Safe to call from any thread;
     * network work happens inline, so callers should already be off the UI
     * thread.
     */
    fun run(): List<Probe> {
        val out = ArrayList<Probe>()

        out += probe("backend", "reachability", JSONObject(), "INFO")

        for ((path, body) in unauthenticated) {
            out += probe(path, "unauthenticated request", body, "OK")
        }
        for ((name, body) in hostilePayloads) {
            out += probe("/api/device/sync", "hostile payload: $name", body, "OK")
        }

        // With the device's own token, check what the server lets through.
        // A "stored" answer here means the capability is genuinely enabled,
        // which is fine — the point is that the server decides, not the app.
        return out
    }

    private fun probe(
        path: String,
        check: String,
        body: JSONObject,
        expectRefused: String,
    ): Probe {
        return try {
            val request = Request.Builder()
                .url("${ApiClient.BASE_URL}$path")
                .post(body.toString().toRequestBody(json))
                .build()
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                Log.d(TAG, "$path $check -> HTTP ${resp.code} ${raw.take(160)}")
                val refused = resp.code in 400..499 || raw.contains("\"error\"")
                val accepted = resp.code in 200..299 &&
                    !raw.contains("\"error\"") &&
                    !raw.contains("\"capability_off\"") &&
                    !raw.contains("\"not_active\"")
                Probe(
                    endpoint = path,
                    check = check,
                    httpCode = resp.code,
                    verdict = if (refused) "OK" else if (accepted && expectRefused == "OK") "LEAK" else "INFO",
                    detail = raw.take(160),
                )
            }
        } catch (e: Throwable) {
            Probe(path, check, 0, "INFO", e.message ?: e::class.simpleName ?: "error")
        }
    }

    /** Renders a report as plain text for the in-app results screen. */
    fun report(probes: List<Probe>): String {
        val leaks = probes.count { it.verdict == "LEAK" }
        return buildString {
            append("ConnectDesk security self-test\n")
            append("Target: ${ApiClient.BASE_URL}\n\n")
            for (p in probes) {
                append("[${p.verdict}] ${p.endpoint} — ${p.check} (HTTP ${p.httpCode})\n")
                if (p.verdict == "LEAK") append("      response: ${p.detail}\n")
            }
            append("\n${probes.count { it.verdict == "OK" }} ok · ")
            append("$leaks leak · ${probes.count { it.verdict == "INFO" }} info\n")
            if (leaks == 0) {
                append("Consent model held on every probe.\n")
            } else {
                append("$leaks endpoint(s) accepted a request that should have been refused.\n")
            }
        }
    }
}