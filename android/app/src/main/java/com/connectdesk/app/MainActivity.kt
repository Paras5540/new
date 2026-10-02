package com.connectdesk.app

import android.app.AppOpsManager
import android.content.Context
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
    private lateinit var switchNotif: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var tvStatus: TextView
    private lateinit var tvConnection: TextView

    /** Live sync-loop readout; proves whether heartbeats are actually flowing. */
    private val uiHandler = Handler(Looper.getMainLooper())
    private var connected = false

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

    private fun disconnect() {
        ApiClient.clearToken(this)
        DeviceService.stop(this)
        ScreenCaptureService.stop(this)
        refreshUi()
    }

    private fun refreshUi() {
        val token = ApiClient.loadToken(this)
        if (token == null) {
            showLogin()
            return
        }
        thread {
            val state = ApiClient.status(token)
            runOnUiThread {
                when (state?.status) {
                    "approved" -> showConnected(state.name)
                    "pending" -> showPending()
                    else -> showLogin()
                }
            }
        }
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
        val needed = mutableListOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.CAMERA,
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

    @Suppress("unused")
    private fun appOps(): AppOpsManager =
        getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
}
