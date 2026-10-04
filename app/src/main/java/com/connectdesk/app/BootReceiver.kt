package com.connectdesk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the sync service back up automatically, without the user ever opening
 * the app after setup. Handles the three real-world cases that previously left
 * a "connected" device silent on the dashboard:
 *
 *  1. Phone rebooted          -> BOOT_COMPLETED
 *  2. App was updated/replaced -> MY_PACKAGE_REPLACED
 *  3. Data/Wi-Fi was off      -> CONNECTIVITY_CHANGE (service was killed while
 *                                offline and had nothing to retry with)
 *
 * Only restarts when a device token is already stored, so a fresh install never
 * starts anything.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        ApiClient.attach(context)
        if (ApiClient.loadToken(context) == null) return
        DeviceService.start(context)
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.net.conn.CONNECTIVITY_CHANGE",
        )
    }
}
