package com.connectdesk.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
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

    /**
     * Runtime permissions and the "all files access" Settings page used to be
     * requested on EVERY resume, because they live inside showConnected().
     * Returning to the app therefore kept bouncing the user into Android
     * Settings for no reason. Asked once per process now; the switches and
     * button states still refresh on every resume.
     */
    private var permissionsRequested = false

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
            if (checked &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                switchCalls.isChecked = false
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.READ_PHONE_STATE,
                    ),
                    101,
                )
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
            if (checked &&
                checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                switchCameraLive.isChecked = false
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 102)
                return@setOnCheckedChangeListener
            }
            if (checked) {
                CameraLiveService.setArmed(this, true)
                CameraLiveService.start(this, "back")
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
            if (checked &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                switchMicLive.isChecked = false
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 103)
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
        // Self-heal: if the token is valid but the foreground service died
        // (Android killed it, or it crashed at startup), restart it.
        if (ApiClient.loadToken(this) != null && !ServiceStatus.loopRunning) {
            DeviceService.start(this)
        }
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(syncReadout)
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
        ClipboardWorker.install(this)
        if (Prefs.callRecordingArmed(this)) CallRecorderService.start(this)
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
     * Standard Android runtime dialogs. Every permission is optional: denying
     * one simply leaves that feature off, and the reason is shown in Settings.
     */
    private fun requestRuntimePermissions() {
        if (permissionsRequested) return
        permissionsRequested = true
        val needed = mutableListOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            needed.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            needed.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 26) needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
        // SEND_SMS is requested separately because it is only needed to reply.
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
        // Android 11+ needs an explicit grant before shared storage can be
        // indexed; without it the Files feature simply reports that.
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Throwable) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }
    }

    private fun hasNotificationListenerPermission(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners",
        ) ?: return false
        return enabled.contains(packageName)
    }
}
