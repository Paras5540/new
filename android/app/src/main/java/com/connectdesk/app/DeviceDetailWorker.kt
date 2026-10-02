package com.connectdesk.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BluetoothManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/**
 * Live hardware/network detail, attached to every heartbeat so the dashboard
 * always shows current state rather than a stale snapshot.
 *
 * All of it comes from public system APIs — no extra runtime permission is
 * needed for any of it.
 */
object DeviceDetailWorker {

    data class Detail(
        val wifiSsid: String?,
        val networkType: String?,
        val bluetoothOn: Boolean,
        val airplaneMode: Boolean,
        val uptimeMs: Long,
        val memoryUsedMb: Long,
        val memoryTotalMb: Long,
    )

    fun collect(context: Context): Detail {
        val mem = memoryInfo()
        return Detail(
            wifiSsid = wifiSsid(context),
            networkType = networkType(context),
            bluetoothOn = bluetoothOn(context),
            airplaneMode = airplaneMode(context),
            uptimeMs = uptimeMs(),
            memoryUsedMb = mem?.first ?: 0L,
            memoryTotalMb = mem?.second ?: 0L,
        )
    }

    /** Serialized into the heartbeat POST. */
    fun toJson(detail: Detail): JSONObject = JSONObject()
        .put("wifiSsid", detail.wifiSsid ?: JSONObject.NULL)
        .put("networkType", detail.networkType ?: JSONObject.NULL)
        .put("bluetoothOn", detail.bluetoothOn)
        .put("airplaneMode", detail.airplaneMode)
        .put("uptimeMs", detail.uptimeMs)
        .put("memoryUsedMb", detail.memoryUsedMb)
        .put("memoryTotalMb", detail.memoryTotalMb)

    /**
     * SSID of the joined WiFi network.
     *
     * Android 8.1+ returns "<unknown ssid>" unless the device is already
     * connected AND location is enabled; we report exactly what the OS gives
     * us rather than trying to work around it.
     */
    private fun wifiSsid(context: Context): String? = try {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid?.trim('"')?.trim()
        when {
            ssid.isNullOrEmpty() || ssid == "<unknown ssid>" || ssid == "0x" -> null
            else -> ssid
        }
    } catch (_: Throwable) {
        null
    }

    /** Fine-grained transport type so the dashboard can say "5G", not "online". */
    private fun networkType(context: Context): String? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    } catch (_: Throwable) {
        null
    }

    private fun bluetoothOn(context: Context): Boolean = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bm.adapter?.isEnabled == true
    } catch (_: Throwable) {
        false
    }

    private fun airplaneMode(context: Context): Boolean = try {
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            android.provider.Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) == 1
    } catch (_: Throwable) {
        false
    }

    /** Milliseconds since the device last booted — the real "last reboot". */
    private fun uptimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        0L
    }

    /** (usedMb, totalMb) read from /proc/meminfo, or null when unreadable. */
    private fun memoryInfo(): Pair<Long, Long>? = try {
        val reader = java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream("/proc/meminfo")))
        val map = HashMap<String, Long>()
        reader.use { r ->
            var line = r.readLine()
            while (line != null) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().filter { it.isDigit() }
                    value.toLongOrNull()?.let { map[key] = it }
                }
                line = r.readLine()
            }
        }
        val totalKb = map["MemTotal"] ?: 0L
        val availableKb = map["MemAvailable"] ?: map["MemFree"] ?: 0L
        (totalKb - availableKb) / 1024 to totalKb / 1024
    } catch (_: Throwable) {
        null
    }

    /** One-line summary used in the app's service readout. */
    fun summary(detail: Detail): String = buildString {
        append(detail.networkType?.uppercase() ?: "OFFLINE")
        detail.wifiSsid?.let { append(" · ").append(it) }
        append(" · BT ").append(if (detail.bluetoothOn) "on" else "off")
        if (detail.memoryTotalMb > 0) {
            append(" · RAM ")
            append(detail.memoryUsedMb)
            append("/")
            append(detail.memoryTotalMb)
            append(" MB")
        }
    }
}