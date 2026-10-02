package com.connectdesk.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 * Single-screen client: pair via code, show connection state, grant/revoke
 * notification sync, disconnect. Everything visible; nothing hides.
 */
class MainActivity : AppCompatActivity() {
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

        etCode = findViewById(R.id.etCode)
        btnPair = findViewById(R.id.btnPair)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnNotifSettings = findViewById(R.id.btnNotifSettings)
        btnShare = findViewById(R.id.btnShare)
        switchNotif = findViewById(R.id.switchNotif)
        tvStatus = findViewById(R.id.tvStatus)
        tvConnection = findViewById(R.id.tvConnection)

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
            val intent = Intent(this, ScreenConsentActivity::class.java)
            startActivity(intent)
        }

        switchNotif.setOnCheckedChangeListener { _, checked ->
            if (checked && !hasNotificationListenerPermission()) {
                // Ask for the OS-level access first
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

    private fun pair() {
        val code = etCode.text?.toString()?.trim() ?: ""
        if (code.length < 6) {
            tvStatus.text = getString(R.string.pending_status)
            return
        }
        btnPair.isEnabled = false
        thread {
            val result = ApiClient.claim(code, android.os.Build.MODEL ?: "Android device")
            runOnUiThread {
                btnPair.isEnabled = true
                if (result == null) {
                    // Show WHY: expired/used code, wrong deployment, or no network.
                    tvStatus.text = ApiClient.lastError ?: "Pairing failed — check code and connection"
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
        refreshUi()
    }

    private fun refreshUi() {
        val token = ApiClient.loadToken(this)
        if (token == null) {
            showPairing()
            return
        }
        thread {
            val state = ApiClient.status(token)
            runOnUiThread {
                when (state?.status) {
                    "approved" -> showConnected(state.name)
                    "pending" -> showPending()
                    else -> showPairing()
                }
            }
        }
    }

    private fun showPairing() {
        connected = false
        uiHandler.removeCallbacks(syncReadout)
        etCode.visibility = View.VISIBLE
        btnPair.visibility = View.VISIBLE
        btnDisconnect.visibility = View.GONE
        btnNotifSettings.visibility = View.GONE
        btnShare.visibility = View.GONE
        switchNotif.visibility = View.GONE
        tvConnection.text = ""
    }

    private fun showPending() {
        connected = false
        uiHandler.removeCallbacks(syncReadout)
        etCode.visibility = View.GONE
        btnPair.visibility = View.GONE
        btnDisconnect.visibility = View.VISIBLE
        btnShare.visibility = View.GONE
        tvConnection.text = getString(R.string.pending_status)
    }

    private fun showConnected(deviceName: String) {
        etCode.visibility = View.GONE
        btnPair.visibility = View.GONE
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
        tvConnection.text = buildString {
            append(getString(R.string.connected_status))
            append('\n')
            append(ServiceStatus.summary())
        }
        uiHandler.removeCallbacks(syncReadout)
        uiHandler.postDelayed(syncReadout, 3_000)

        // Data-sync permissions: standard Android runtime dialog — baccha dekh
        // sakta hai kya maanga ja raha hai. Deny kare to wo sync off rahega.
        val needed = mutableListOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            needed.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            needed.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            needed.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun hasNotificationListenerPermission(): Boolean {
        val contentResolver = contentResolver
        val enabled = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners",
        ) ?: return false
        return enabled.contains(packageName)
    }
}
