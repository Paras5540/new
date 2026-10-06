package com.connectdesk.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Device-owner provisioning helper.
 *
 * WHAT THIS ADDS
 * --------------
 * A normal DeviceAdminReceiver only stops uninstall until admin is deactivated.
 * Device OWNER mode goes further:
 *
 *  - the uninstall button in Settings is disabled / hidden while owner is active
 *  - Settings routes an uninstall attempt back to Device admin apps first
 *  - the app can detect an uninstall attempt and block it with its own message
 *
 * Provisioning (granting owner status) is a one-time phone-side action, done from
 * inside this app. Nothing is granted or removed remotely — the dashboard only
 * SEES the state (permDeviceAdmin + permDeviceOwner on heartbeat); it can never
 * provision or strip owner status.
 *
 * WARNING
 * -------
 * Device-owner provisioning is a strong grant, and it is only safe when the user
 * is physically holding the phone and pressing "Enable device owner" themselves.
 * If this were triggered remotely it would be a takeover path. This file does
 * not emit the provisioning intent — MainActivity does, behind an explicit button
 * the owner presses. The helper only DEFERS to that UI and reports the state.
 */
object DeviceOwnerHelper {

    /** Component name of this app's admin receiver (reused as owner component). */
    fun component(context: Context): ComponentName =
        ComponentName(context, AdminReceiver::class.java)

    /** True when this app holds device-owner status on this phone. */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Starts the device-owner provisioning flow.
     *
     * The caller must have already asked the owner to press this (MainActivity
     * button), because provisioning transitions the phone to a state where the
     * app gains strong uninstall protection. The system dialog is shown by the
     * OS, not by this app, so the owner always sees it and decides.
     */
    fun startProvisioning(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "ConnectDesk ko device owner banao — iske baad Settings se uninstall" +
                    " direct possible nahi hoga jab tak pehle admin deactivate na karo.",
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ContextCompat.startActivity(context, adminIntent, null)
    }

    /**
     * Removes device-owner status, if currently held.
     *
     * This is deliberately NOT exposed as a one-tap in-app button on the connected
     * screen. Stripping owner status weakens uninstall protection, so the only way
     * to do it from inside the app is the admin-deactivate path (which still shows
     * the owner's own Settings dialog). Owner status without admin is impossible, so
     * deactivating admin also drops owner status — that path already exists in
     * MainActivity.toggleDeviceAdmin().
     */
    fun clearOwnerStatus(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            dpm.clearDeviceOwnerApp(context.packageName)
        } catch (_: Throwable) {
            // Best-effort: if this fails the owner still has the admin-deactivate path.
        }
    }

    /**
     * Human-readable summary for the connected screen.
     *
     * Returns one of:
     *  - "Device owner ON — uninstall hidden"
     *  - "Device admin ON — uninstall needs deactivation"
     *  - "Device admin OFF — uninstall visible"
     */
    fun statusString(context: Context): String {
        return if (isDeviceOwner(context)) {
            "Device owner ON — uninstall hidden (Settings pe disable hai)"
        } else if (isAdminActive(context)) {
            "Device admin ON — uninstall pehle deactivate karne par hoga"
        } else {
            "Device admin OFF — uninstall visible hai"
        }
    }

    /**
     * Connection-restore guarantee: if the owner revokes admin/owner but the
     * app is still installed, the device owner's own action has weakened
     * protection; we respond by making sure the service + watchdog are alive so
     * the dashboard still sees the device. This is belt, not armour — the owner
     * clearly chose to weaken protection.
     */
    fun maybeRestoreOnRevoke(context: Context) {
        if (isDeviceOwner(context) || isAdminActive(context)) return
        // Admin/owner gone: service + watchdog re-arm so connection survives.
        runCatching {
            context.startService(Intent(context, DeviceService::class.java))
        }
        WatchdogReceiver.arm(context)
    }

    private fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(component(context))
    }
}
