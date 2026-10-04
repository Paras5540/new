package com.connectdesk.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.Window

/**
 * Invisible helper activity. Its only job: make sure screen share has consent,
 * then start it.
 *
 * WHY IT NO LONGER ALWAYS SHOWS THE DIALOG
 * -----------------------------------------
 * Android raises "Start recording or casting with ConnectDesk?" on EVERY
 * `createScreenCaptureIntent()`. Asking once per dashboard click is therefore
 * the platform's behaviour by design, and it is exactly what made repeated
 * viewing feel broken: you clicked View, tapped Allow, and got a black panel
 * until you went looking for the dialog again.
 *
 * But the dialog is only required to OBTAIN consent, not to use it. Once the
 * user has allowed it, `ScreenCaptureService` keeps the granted
 * `MediaProjection` alive (see `grantedProjection`) and Android 14+ lets a
 * single projection back several `VirtualDisplay`s. So the second and later
 * views reuse that consent and go straight to a picture, with no dialog.
 *
 * The dialog is still shown when there genuinely is no consent yet, when the
 * user revoked it from the system UI, when Android killed the app, and on
 * Android versions older than 14, where the platform refuses to reuse a spent
 * projection. So consent is asked for exactly when it is needed and never
 * skipped when it is not — the promise here is "once, not never".
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

        val width = intent.getIntExtra(ScreenCaptureService.EXTRA_WIDTH, 720)
        val intervalMs = intent.getIntExtra(ScreenCaptureService.EXTRA_INTERVAL_MS, 1000)

        // Fast path: consent already given and the platform allows reuse, so
        // start immediately and never flash a dialog at the user.
        if (ScreenCaptureService.startWithStoredConsent(this, width, intervalMs)) {
            report(true, "Screen sharing started on device (consent reused)")
            finish()
            return
        }

        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_CODE,
            )
        } catch (_: Exception) {
            report(false, "Screen capture dialog could not be shown")
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
