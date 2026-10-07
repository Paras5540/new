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
        val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "ConnectDesk ko DEVICE ADMIN banao. Ye sirf admin grant karta hai — " +
                    "Android koi installed app ko andar se device OWNER nahi bana " +
                    "sakta, aur uninstall button sirf owner par disable hota hai. " +
                    "Owner banane ka command 'Device owner' button ke dialog me " +
                    "milta hai.",
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ContextCompat.startActivity(context, adminIntent, null)
    }

    /**
     * The exact ADB command that grants DEVICE-OWNER status.
     *
     * WHY THIS CANNOT BE DONE FROM INSIDE THE APP
     * --------------------------------------------
     * [startProvisioning] fires `ACTION_ADD_DEVICE_ADMIN`, and that is the only
     * grant Android will hand to an app it is running. Admin alone is what made
     * Settings redirect the Uninstall tap to "deactivate the device admin
     * first" — it stops nothing and hides nothing.
     *
     * The Uninstall button in Settings > Apps is OS UI: no app can hide it, and
     * the only thing that makes the OS disable it is DEVICE-OWNER status. Owner
     * status is granted from outside the package — ADB, or QR / zero-touch
     * provisioning during device setup — which is why the app hands the user
     * the command instead of pretending to grant it.
     */
    fun deviceOwnerAdbCommand(context: Context): String =
        "adb shell dpm set-device-owner " +
            "${context.packageName}/${AdminReceiver::class.java.name}"

    /**
     * Blocks uninstall of this package at the package-manager level, so the
     * Settings > Apps Uninstall button has nothing left to do when tapped.
     *
     * `setUninstallBlocked` is callable only by a device or profile owner, so
     * this is a guarded no-op for admin-only installs — the state the phone is
     * in until [deviceOwnerAdbCommand] has actually been run. It is idempotent,
     * so callers can invoke it on every refresh.
     */
    fun blockUninstallIfOwner(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        return runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setUninstallBlocked(component(context), context.packageName, true)
            true
        }.getOrDefault(false)
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
     *  - "Device owner ON — Uninstall button disabled"
     *  - "Device admin ON — uninstall pehle deactivate karne par"
     *  - "Device admin OFF — uninstall visible hai"
     *
     * Deliberately does NOT claim that admin hides the button: admin-only is
     * what produced the "deactivate the device admin first" redirect, and
     * promising otherwise is what made it look like a bug.
     */
    fun statusString(context: Context): String {
        return if (isDeviceOwner(context)) {
            "Device owner ON — Settings ka Uninstall button disabled hai"
        } else if (isAdminActive(context)) {
            "Device admin ON — uninstall pehle deactivate karna padega " +
                "(button hide karne ke liye Device owner command chahiye)"
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
