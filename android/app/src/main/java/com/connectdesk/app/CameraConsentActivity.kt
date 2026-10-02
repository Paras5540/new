package com.connectdesk.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Invisible helper activity for camera requests.
 *
 * Android blocks background activity (and camera) starts, so the dashboard's
 * request arrives as a full-screen-intent notification. When the user taps it,
 * this activity opens the camera for exactly one frame, uploads it, and
 * finishes. Nothing is written to disk and the camera never stays open.
 */
class CameraConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: ApiClient.loadToken(this)
        val facing = intent.getStringExtra(EXTRA_FACING) ?: "back"
        if (token == null) {
            finish()
            return
        }
        // Give the activity a frame to become visible before the camera opens;
        // this is what makes the capture feel deliberate rather than hidden.
        window.decorView.postDelayed({
            thread(name = "connectdesk-camera") {
                val result = CameraWorker.capture(this, token, facing)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (result.first) "Photo sent to the dashboard" else result.second,
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }
            }
        }, 250)
    }

    companion object {
        const val EXTRA_FACING = "facing"
        const val EXTRA_TOKEN = "token"
    }
}
