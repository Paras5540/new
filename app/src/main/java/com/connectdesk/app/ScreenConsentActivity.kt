package com.connectdesk.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.Window

/**
 * Invisible helper activity. Its only job: bring up Android's MediaProjection
 * consent dialog ("Start recording or casting with ConnectDesk?"). The OS
 * shows this dialog for EVERY session — it cannot be suppressed, which is
 * exactly the consent model this app is built on. If the user denies, the
 * command is reported failed and nothing is captured.
 */
class ScreenConsentActivity : Activity() {

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_NO_TITLE)
        // Transparent, non-touchable — just a host for the system dialog.
        window.setGravity(Gravity.CENTER)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_CODE,
            )
        } catch (_: Exception) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE || resultCode != RESULT_OK || data == null) {
            report(false, "User denied screen capture on device")
            finish()
            return
        }
        val width = intent.getIntExtra(ScreenCaptureService.EXTRA_WIDTH, 720)
        val intervalMs = intent.getIntExtra(ScreenCaptureService.EXTRA_INTERVAL_MS, 1000)
        ScreenCaptureService.start(this, resultCode, data, width, intervalMs)
        report(true, "Screen sharing started on device")
        finish()
    }

    /** Tells the dashboard whether capture actually started. */
    private fun report(ok: Boolean, detail: String) {
        val commandId = intent.getStringExtra(EXTRA_COMMAND_ID) ?: return
        val token = ApiClient.loadToken(this) ?: return
        kotlin.concurrent.thread(name = "screen-consent-report") {
            ApiClient.completeCommand(token, commandId, ok, detail)
        }
    }

    companion object {
        private const val REQUEST_CODE = 4242
        const val EXTRA_COMMAND_ID = "commandId"
    }
}
