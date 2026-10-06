package com.connectdesk.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device-admin component.
 *
 * WHY THIS EXISTS
 * ---------------
 * The owner runs this app on their own phone as a device manager. Device
 * admin buys exactly two things, and both are visible:
 *
 *  1. The app cannot be silently uninstalled — Android requires the admin to
 *     be deactivated first, which shows up on the phone. A lost/stolen-device
 *     manager that any user could delete in one tap protects nothing.
 *  2. `lockNow()` becomes available, which the Find Phone feature can use to
 *     lock the screen on request.
 *
 * The requested policy set is deliberately MINIMAL (see
 * `res/xml/device_admin_policies.xml`): force-lock only. No wipe-data, no
 * disable-camera (that would break the app's own camera feature), no password
 * rules. An admin grant is a strong grant; this app asks for the least it can
 * actually use.
 *
 * Activation is one-time, from the app's own Setup screen, exactly like every
 * other grant in [PermissionSetup]. Nothing is enabled remotely: the dashboard
 * can see whether admin is active (permDeviceAdmin on the heartbeat) but can
 * never activate or deactivate it.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(
            context,
            "ConnectDesk device admin ON — uninstall ab pehle deactivate karne par hoga.",
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "ConnectDesk device admin OFF — ab app normally uninstall ho sakti hai.",
            Toast.LENGTH_SHORT,
        ).show()
    }
}
