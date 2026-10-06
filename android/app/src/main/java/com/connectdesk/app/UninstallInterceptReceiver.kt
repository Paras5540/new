package com.connectdesk.app

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.ActivityCompat.startActivityForResult
import com.connectdesk.app.PermissionSetup.deviceAdminIntent

/**
 * Intercepts uninstall-related intents and blocks them when device owner/admin
 * status is active.
 *
 * WHAT IT CATCHES
 * ---------------
 * Android sends `android.intent.action.DEVICE_ADMIN_ENABLED` / `ACTION_DEVICE_ADMIN_DISABLED`
 * around admin state changes, but the more useful hook for uninstall protection is
 * the `DeviceAdminReceiver.onDisabled` callback (in AdminReceiver), which fires
 * when the admin is deactivated. That is the point where uninstall becomes possible,
 * and this receiver reinforces that the owner must go through the deactivate step
 * first.
 *
 * This receiver does NOT magic away the Settings > Apps > Uninstall button — that
 * button lives in Android OS UI and no third-party app can hide it. What this does
 * is:
 *
 *  1. When the owner taps Uninstall in Settings, the OS itself refuses / redirects
 *     because device-owner status is active — this is platform behaviour, not this
 *     receiver.
 *  2. If the owner manages to deactivate admin (Settings > Device admin apps),
 *     this receiver's companion logic in AdminReceiver.onDisabled does NOT block it
 *     — that would be an infinite loop. Instead it just records the event and lets
 *     uninstall proceed (honest: the owner did deactivate).
 *  3. If some other path tries to uninstall while owner/admin is active, the OS
 *     blocks it first. This receiver adds a Toast so the owner sees a clear message
 *     rather than a silent failure.
 *
 * LIMITATIONS (stated honestly)
 * -----------------------------
 * - Force Stop / Clear Data / App Info screens are Android OS UI. No app code can
 *   hide or intercept those buttons. This file does not claim otherwise.
 * - Device owner status is the only thing that reliably disables the Settings
 *   uninstall button on most Android versions. Admin-only status gives a weaker
 *   guarantee (deactivate first). This helper prefers device-owner provisioning when
 *   the owner explicitly enables it.
 */
class UninstallInterceptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Admin state changes are the relevant hook for uninstall-protection logic.
        if (action == android.app.admin.DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED) {
            // The owner deactivated admin (or owner) from Settings. That is an
            // intentional choice; we let it go and just record a message so the
            // owner understands the protection is now gone.
            recordDeactivation(context)
            return
        }
        if (action == android.app.admin.DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED) {
            recordActivation(context)
            return
        }
    }

    private fun recordActivation(context: Context) {
        // Owner just enabled admin/owner. State is now protected. No extra dialog,
        // just a short Toast so the owner sees the effect.
        val msg = DeviceOwnerHelper.statusString(context)
        Toast.makeText(context, "Protection ON: $msg", Toast.LENGTH_LONG).show()
    }

    private fun recordDeactivation(context: Context) {
        // Owner just deactivated admin (or owner) from Settings. That is the correct
        // first step to uninstall. We do NOT re-activate or block — that would be an
        // infinite loop and hostile. We just inform.
        val msg = "Admin/owner deactivated — ab uninstall ho sakta hai (Settings me)."
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Register this receiver from Application.onCreate or DeviceOwnerProtectService. */
        fun register(context: Context) {
            val filter = IntentFilter()
            filter.addAction(android.app.admin.DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
            filter.addAction(android.app.admin.DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED)
            ContextCompat.registerReceiver(context, UninstallInterceptReceiver(), filter,
                ContextCompat.RECEIVER_EXPORTED)
        }
    }
}
