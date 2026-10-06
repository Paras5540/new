package com.connectdesk.app

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Uninstall + force-stop protection, with automatic connection restore.
 *
 * WHAT THIS DOES
 * --------------
 * 1. Detects an uninstall attempt while device owner/admin is active.
 *    The OS already blocks it on most platforms. This adds a clear human
 *    message so the owner sees WHY rather than a silent failure.
 *
 * 2. Detects a force-stop attempt while device owner is active.
 *    Device owner disables the Settings Force Stop button on most Android
 *    versions. If some OEM bypass allows it, this receiver catches the
 *    PACKAGE_REMOVED / ACTION_PACKAGE_CHANGED signal and re-starts the
 *    service + watchdog immediately.
 *
 * 3. Guarantees connection restore:
 *    - BootReceiver: BOOT_COMPLETED / QUICKBOOT / MY_PACKAGE_REPLACED /
 *      CONNECTIVITY_CHANGE -> DeviceService.start()
 *    - WatchdogReceiver: every 15 min, if token stored -> DeviceService.start()
 *    - DeviceService.onTaskRemoved: START_STICKY + watchdog re-arm
 *    - THIS service: on uninstall/force-stop signal -> DeviceService.start() +
 *      WatchdogReceiver.arm()
 *
 * LIMITATIONS (honest)
 * --------------------
 * - Settings > Apps > Force Stop / Clear Data / Uninstall are Android OS UI.
 *   No app code can hide those buttons. Device-owner status is what makes the
 *   OS disable them on MOST platforms. Admin-only status is weaker (deactivate
 *   first). This file does not claim to hide them.
 * - Some OEM skins (Realme/OPPO/Xiaomi especially) have their own app-management
 *   screens that can bypass standard Android paths. This service is a belt, not
 *   armour. The durable fix for those is the battery-optimization exempt toggle
 *   in Setup.
 */
class DeviceOwnerProtectService : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pkg = intent.getPackage() ?: return
        if (pkg != context.packageName) return

        when (action) {
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REPLACED,
            -> onPackageEvent(context, action, intent)
        }
    }

    private fun onPackageEvent(context: Context, action: String, intent: Intent) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminActive = dpm.isAdminActive(DeviceOwnerHelper.component(context))
        val ownerActive = DeviceOwnerHelper.isDeviceOwner(context)

        // If neither admin nor owner is active, this app has no uninstall
        // protection; nothing to do.
        if (!adminActive && !ownerActive) return

        when (action) {
            Intent.ACTION_PACKAGE_REMOVED -> {
                // Uninstall was requested. OS has already blocked it if owner
                // is active; if somehow it got through (OEM edge case) we
                // restore: start service + watchdog + toast explaining.
                // Distinguish uninstall from app-update (replacement).
                // ACTION_PACKAGE_REPLACED fires on update/replace; anything else
                // (ACTION_PACKAGE_REMOVED without REPLACE) is an uninstall attempt.
                val isReplace = action == Intent.ACTION_PACKAGE_REPLACED
                if (isReplace) {
                    // Replace (update), not uninstall. Ignore.
                    return
                }
                // Uninstall attempt detected while protected.
                runCatching {
                    context.startService(Intent(context, DeviceService::class.java))
                }
                WatchdogReceiver.arm(context)
                Toast.makeText(
                    context,
                    R.string.uninstall_blocked_toast,
                    Toast.LENGTH_LONG,
                ).show()
            }

            Intent.ACTION_PACKAGE_CHANGED -> {
                // If the installed state changed (e.g. data cleared, or
                // something toggled package state) and we are still protected,
                // make sure the service is alive.
                if (!DeviceOwnerHelper.isDeviceOwner(context) &&
                    !dpm.isAdminActive(DeviceOwnerHelper.component(context))) {
                    return
                }
                runCatching {
                    context.startService(Intent(context, DeviceService::class.java))
                }
                WatchdogReceiver.arm(context)
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                // App was updated. BootReceiver also handles MY_PACKAGE_REPLACED,
                // but this catches the case where the update happened from the
                // Play Store or a direct APK install while the service was down.
                runCatching {
                    context.startService(Intent(context, DeviceService::class.java))
                }
                WatchdogReceiver.arm(context)
            }
        }
    }

    companion object {
        /** Register this receiver from Application.onCreate. */
        fun register(context: Context) {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            ContextCompat.registerReceiver(
                context, DeviceOwnerProtectService(), filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }
}
