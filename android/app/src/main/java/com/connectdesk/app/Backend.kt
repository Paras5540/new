package com.connectdesk.app

import android.content.Context

/**
 * Resolves which Convex deployment this device should talk to.
 *
 * Why this exists: the project has more than one Convex deployment (a dev one
 * used by `convex dev`, and the production one the deployed dashboard is built
 * against). A hard-coded URL silently breaks the moment one of them changes —
 * and the symptom is a confusing "Pairing failed" because the dashboard writes
 * the pairing code to one deployment while the app reads from another.
 *
 * Strategy:
 *  1. Use the last known-good URL if we have one.
 *  2. Otherwise probe every candidate with a cheap, side-effect-free request.
 *  3. Remember the winner, and fall back to the next candidate if it ever stops
 *     answering.
 *
 * This makes the app survive a dev -> production promotion without a rebuild.
 */
object Backend {

    private const val PREFS = "connectdesk"
    private const val KEY_URL = "backendUrl"

    /**
     * Ordered candidates. `active` wins while healthy; the others are
     * fallbacks so a deployment switch does not require a new APK.
     *
     * ORDER MATTERS, because the pairing code is created on ONE deployment: the
     * device must ask that same deployment for it. Asking a different one
     * never finds the code and answers the user with a misleading
     * "Invalid pairing code" for a code that was perfectly valid.
     *
     * `blessed-goat-500` is FIRST because that is the deployment `convex dev`
     * actually pushes to, so it is the one carrying every current function
     * (mic frames, blob streaming). `valuable-goldfish-43` is still listed, but
     * it is PAUSED, so [resolve] skips it and lands here instead — the phone
     * therefore keeps working without any APK change.
     */
    var candidates: List<String> = listOf(
        "https://blessed-goat-500.convex.site",
        "https://valuable-goldfish-43.convex.site",
        "https://admired-nightingale-732.convex.site",
    )

    @Volatile
    var active: String? = null

    fun init(context: Context) {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, null)
        if (stored != null && stored.isNotBlank()) {
            active = stored
            candidates = listOf(stored) + candidates.filter { it != stored }
        }
    }

    private fun remember(context: Context, url: String) {
        active = url
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url).apply()
    }

    /**
     * Returns the first candidate backend that answers a probe request.
     * `probe` receives a base URL and must return true if the deployment
     * responded (even with an application-level error such as
     * "Invalid pairing code" — that still proves the deployment is alive).
     */
    fun resolve(context: Context, probe: (String) -> Boolean): String? {
        val current = active
        if (current != null && probe(current)) return current
        for (candidate in candidates) {
            if (candidate == current) continue
            if (probe(candidate)) {
                remember(context, candidate)
                return candidate
            }
        }
        return current
    }

    /** Lets the app be pointed at a custom backend if one is ever needed. */
    fun setActive(context: Context, url: String) {
        remember(context, url.trimEnd('/'))
    }

    /**
     * Moves to the NEXT candidate deployment and returns true if it changed.
     *
     * Used as a recovery path: when a reachable backend answers a valid
     * request with "Invalid pairing code", the code was created on a different
     * deployment. Trying the next one is far more useful than telling the user
     * their code is wrong.
     */
    fun rotate(context: Context): Boolean {
        val list = candidates
        if (list.size < 2) return false
        val current = active
        val idx = if (current != null) list.indexOf(current) else -1
        for (step in 1..list.size) {
            val next = list[((idx + step) % list.size + list.size) % list.size]
            if (next != current) {
                remember(context, next)
                return true
            }
        }
        return false
    }
}
