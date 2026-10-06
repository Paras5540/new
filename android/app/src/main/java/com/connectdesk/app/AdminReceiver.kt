package com.connectdesk.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device-admin + device-owner component.
 *
 * PROTECTION MODEL
 * ----------------
 * There are two levels, both initiated by the owner inside this app:
 *
 *  1. Device ADMIN (the base level) — Settings will not uninstall this app
 *     while admin is active; the owner must first deactivate admin, which is
 *     visible in Settings > Device admin apps. This is the normal path and
 *     works on every Android version.
 *
 *  2. Device OWNER (the stronger level) — provisioning is a one-time phone-
 *     side action, behind an explicit "Enable device owner" button in the app.
 *     When owner status is active, the Settings > Apps > Uninstall button is
 *     disabled / hidden on most Android versions, AND the owner must first go
 *     to Settings > Device admin apps and deactivate before uninstall becomes
 *     possible.
 *
 * The uninstal-button that lives in Settings > Apps is Android OS UI. No app
 * code can hide it. What THIS component does is tell the OS to refuse / redirect
 * that button while protection is active. Device-owner status is what makes the
 * OS actually disable the button on most platforms; admin-only status tells the
 * OS to require deactivation first.
 *
 * The dashboard can SEE the state (permDeviceAdmin, permDeviceOwner on heartbeat)
 * but can never provision or strip owner/admin status.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val msg = when {
            DeviceOwnerHelper.isDeviceOwner(context) ->
                "Device owner + admin ON — uninstall ab Settings se direct possible nahi hai.\n" +
                    "Uninstall ke liye pehle Settings > Device admin apps me jaake deactivate karo."
            else ->
                "Device admin ON — uninstall ab pehle Settings > Device admin apps me deactivate karne par hoga."
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        val msg = when {
            DeviceOwnerHelper.isDeviceOwner(context) ->
                "Device owner still active — uninstall abhi possible nahi.\n" +
                    "Pehle Settings > Device admin apps me jaake deactivate karo."
            else ->
                "Device admin OFF — ab app normally uninstall ho sakti hai."
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        // If admin was deactivated while owner status was ALSO active, owner status
        // must be cleared too; otherwise the OS keeps the uninstall button disabled
        // even though admin is gone, which is a confusing UX (the owner thinks they
        // did the right step but uninstall still does not work).
        if (!DeviceOwnerHelper.isDeviceOwner(context)) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.clearDeviceOwnerApp(context.packageName)
        } catch (_: Throwable) {
            // Best-effort backstop. The owner still has the manual Settings path.
        }
    }

}
