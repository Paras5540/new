package com.connectdesk.app

import android.content.Context
import java.security.MessageDigest

/**
 * Optional PIN lock for the app's own console screen.
 *
 * WHAT THIS IS, AND IS NOT
 * ------------------------
 * It is access control for ConnectDesk's own UI: whoever picks up the phone
 * cannot flip setup toggles, disconnect the service, or read the status
 * readout without the PIN. The owner knows the PIN.
 *
 * It is NOT a hiding mechanism. The launcher icon, the Settings entry and the
 * persistent service notification all stay exactly where they are — this app
 * never hides (see the manifest and SECURITY_PRIVACY.md), it only locks its
 * own front door.
 *
 * The PIN is never stored: only a random 16-hex-digit salt plus the SHA-256
 * of `salt + pin`. Verification compares message digests with
 * [MessageDigest.isEqual], which is constant-time. The background sync service
 * is untouched by any of this — the lock guards the console, not the
 * connection, exactly as the owner asked.
 */
object UiLock {

    private const val PREFS = "connectdesk_prefs"
    private const val KEY_SALT = "uiLockSalt"
    private const val KEY_HASH = "uiLockHash"

    /** True when the owner has configured a PIN. */
    fun isSet(context: Context): Boolean =
        prefs(context).getString(KEY_HASH, null) != null

    /** Stores a new PIN (replaces any old one). */
    fun set(context: Context, pin: String) {
        val salt = (0 until 16).map { (0..15).random().toString(16) }.joinToString("")
        prefs(context).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, sha256(salt + pin))
            .apply()
    }

    /** Removes the lock entirely. */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_SALT).remove(KEY_HASH).apply()
    }

    /** True when `pin` matches the stored salt+hash. */
    fun verify(context: Context, pin: String): Boolean {
        val salt = prefs(context).getString(KEY_SALT, null) ?: return false
        val hash = prefs(context).getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(
            sha256(salt + pin).toByteArray(),
            hash.toByteArray(),
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
