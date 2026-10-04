package com.connectdesk.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * ONE-TIME PERMISSION SETUP. Everything ConnectDesk needs, asked for once, in
 * a single batch, the first time the app is set up.
 *
 * WHY THIS EXISTS
 * ---------------
 * Permissions used to be requested from wherever they happened to be needed:
 * flipping "live camera" asked for CAMERA, flipping "live microphone" asked for
 * RECORD_AUDIO, arming call recording asked for RECORD_AUDIO *and*
 * READ_PHONE_STATE. So the owner was interrupted mid-action, repeatedly, over
 * the life of the app, and the same permission could be asked for twice from
 * two different screens.
 *
 * That is now impossible: this is the ONLY place in the app that calls
 * `requestPermissions`, it asks for everything at once, and it runs at most
 * once per install. After that the app never prompts again — not for a photo,
 * not for live camera, not for live mic, not for screen mirroring.
 *
 * WHAT IS *NOT* HERE, AND WHY IT MATTERS
 * --------------------------------------
 * Nothing on a later screen calls `requestPermissions`. A feature whose
 * permission is somehow missing (the user revoked it in Settings, or "Don't
 * allow" was tapped) reports that it is unavailable and points at Setup. It
 * never opens a dialog. A dialog in the middle of a dashboard-driven action is
 * the exact thing this design removes: the dashboard asks, the phone shows a
 * notification saying what is happening, and that is the whole interaction.
 *
 * Three grants are Settings toggles rather than runtime dialogs and cannot be
 * bundled into the batch. They are surfaced in Setup with a button each:
 *   - notification listener access  (drives live notification + chat sync)
 *   - usage access                 (app inventory / screen time)
 *   - all files access             (file browser, on Android 11+)
 */
object PermissionSetup {

    private const val PREFS = "connectdesk_prefs"
    private const val KEY_SETUP_DONE = "permissionSetupCompleted"

    /**
     * Every runtime permission this app can make use of, for this API level.
     *
     * One flat list, requested in ONE call. Splitting it into feature groups
     * was the bug: each group then needed its own prompt, and the owner met
     * them piecemeal over weeks instead of once, up front.
     *
     * Ordering matters only for readability — Android shows the system dialog
     * itself and the user answers all of it in one pass.
     */
    fun allRuntimePermissions(): Array<String> {
        val list = mutableListOf(
            // Camera: live camera, one-shot front, one-shot back.
            Manifest.permission.CAMERA,
            // Microphone: live microphone, call recording.
            Manifest.permission.RECORD_AUDIO,
            // Telephony / call state: call recording trigger + call log.
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            // Reply-to-SMS from the dashboard.
            Manifest.permission.SEND_SMS,
            // Contacts.
            Manifest.permission.READ_CONTACTS,
            // Find Phone.
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        // Without this the app's own "a photo is being taken" notices are
        // silently dropped on Android 13+, which would defeat the point of
        // telling the owner what the dashboard is doing.
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return list.toTypedArray()
    }

    /** Runtime permissions still missing on this device. */
    fun missingRuntime(activity: Activity): List<String> =
        allRuntimePermissions().filter {
            activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasNotificationListener(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    fun hasUsageAccess(context: Context): Boolean = AppUsageWorker.hasUsageAccess(context)

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /** True only when every grant this app can ask for is in place. */
    fun isComplete(activity: Activity): Boolean =
        missingRuntime(activity).isEmpty() &&
            hasNotificationListener(activity) &&
            hasUsageAccess(activity) &&
            hasAllFilesAccess()

    /**
     * True once the one-time batch has actually been shown.
     *
     * Persisted rather than kept in a field so a process restart does not turn
     * the prompts back on, and checked *in addition to* the real grant state
     * so revoking in Settings still shows up as incomplete (without prompting).
     */
    fun hasRunOnce(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_DONE, false)

    fun markRunOnce(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    /**
     * The one and only `requestPermissions` call in the app.
     *
     * @return true when a dialog was actually shown.
     */
    fun runOnce(activity: Activity): Boolean {
        if (hasRunOnce(activity)) return false
        markRunOnce(activity)
        val missing = missingRuntime(activity)
        if (missing.isEmpty()) return false
        activity.requestPermissions(missing.toTypedArray(), REQUEST_CODE)
        return true
    }

    private const val REQUEST_CODE = 100

    // ---- Settings toggles (cannot be batched into the dialog) -------------

    fun notificationListenerIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun usageAccessIntent(): Intent = AppUsageWorker.usageAccessIntent()

    fun allFilesAccessIntent(context: Context): Intent {
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return if (direct.resolveActivity(context.packageManager) != null) {
            direct
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }

    /**
     * A short human summary of what is still missing, for the Setup screen.
     *
     * Built from the live grant state every time it is shown, so it is always
     * truthful even after the user changes something in Settings behind our
     * back.
     */
    fun summary(activity: Activity): List<String> {
        val out = mutableListOf<String>()
        if (!hasNotificationListener(activity)) {
            out.add("Notification access (live notifications + chats)")
        }
        if (!hasUsageAccess(activity)) out.add("Usage access (app list + screen time)")
        if (!hasAllFilesAccess()) out.add("All files access (file browser)")
        val runtime = missingRuntime(activity)
        if (runtime.isNotEmpty()) {
            out.add("${runtime.size} system permission(s) not granted")
        }
        return out
    }
}
