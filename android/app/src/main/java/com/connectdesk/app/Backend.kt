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
     * device must ask that same deployment for it. Asking a different one never
     * finds the code and answers the user with a misleading "Invalid pairing
     * code" for a code that was perfectly valid.
     *
     * `notable-snail-502` is FIRST because that is what the DASHBOARD uses: it is
     * the deployment the published web build resolves to (its configured URL is
     * tried before any fallback and it answers the health probe), so that is
     * where the dashboard writes the pairing code. Listing it here is what makes
     * the phone and the dashboard agree. It was missing, and the symptom was
     * exactly `HTTP 400 Invalid pairing code` on a code the user had just
     * created on screen.
     *
     * `blessed-goat-500` follows: it is what `convex dev` pushes to, so it is
     * where the newest code lands first, and it is a live fallback if the
     * dashboard ever moves. `valuable-goldfish-43` is PAUSED and is skipped by
     * [resolve] automatically.
     */
    var candidates: List<String> = listOf(
        // ACTIVE. The whole project moved to a NEW Convex account
        // (`dev/rahula5540` / cautious-squirrel-266) because the previous team
        // hit its free-plan ceiling and Convex disabled the deployments:
        // every invoke returned
        //     HTTP 500 You have exceeded the free plan limits
        // which is what broke sign-in with the generic
        // "Connection lost while action was in flight".
        //
        // This is FIRST and not merely added to the list because pairing
        // codes are written by the dashboard onto ONE deployment, and the
        // phone has to ask that same deployment for them. The old entries are
        // kept only as harmless fallbacks; they answer `no_session`/`Unknown
        // device token` now that they hold no current code.
        //
        // `.convex.site` is the HTTP Actions URL -- the host that serves the
        // `/api/device/*` routes -- NOT the `.convex.cloud` websocket host.
        "https://cautious-squirrel-266.convex.site",
        "https://notable-snail-502.convex.site",
        "https://blessed-goat-500.convex.site",
        "https://valuable-goldfish-43.convex.site",
        "https://admired-nightingale-732.convex.site",
        // Newly added Convex account (frugal-blackbird-972). The automatic
        // config discovery (Backend.fetchConfigUrl + ApiClient.getConfig) will
        // find this when you switch project in the Convex dashboard — no
        // rebuild/reinstall needed. This entry also acts as a fallback probe.
        "https://frugal-blackbird-972.convex.site",
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
     * Fetches the current backend URL from the server's config endpoint.
     *
     * Called periodically (every ~5 min) and on background resume. If the
     * server reports a different URL than we currently use, we switch to it
     * automatically — no app restart, no reinstall needed.
     *
     * This is what makes server switches survive without rebuild: when you
     * change project/deployment in the Convex dashboard, this function reads
     * the new URL and the app follows it.
     *
     * `apiClient` must be injected by the caller; this is a pure resolve
     * helper, not the full HTTP client.
     */
    fun fetchConfigUrl(
        context: Context,
        apiClient: com.connectdesk.app.ApiClient,
    ): String? {
        return runCatching {
            // Convex query that returns the live CONVEX_URL.
            // The Android SDK resolves the actual endpoint from the stored
            // active URL, so we just call the query against whatever we
            // currently believe is active.
            val cfg = apiClient.getConfig()
            if (cfg != null && cfg.convexUrl != null && cfg.convexUrl.isNotBlank()) {
                val newUrl = cfg.convexUrl.trimEnd('/')
                if (newUrl != active) {
                    remember(context, newUrl)
                }
                newUrl
            } else {
                active
            }
        }.getOrElse { active }
    }

    /**
     * Reads the dashboard's current server URL directly from the dashboard
     * HTML page (window.__CONNECTDESK_SERVER_URL__).
     *
     * This is the PRIMARY discovery path for the mobile app: it does NOT call
     * Convex, does NOT depend on Convex being healthy, and does NOT need any
     * Convex deployment to be reachable. It just fetches the dashboard page
     * (which IS reachable because the dashboard is already serving) and reads
     * the embedded JS variable.
     *
     * This is what makes server switches survive even when the OLD Convex
     * deployment has hit its free-plan limit and returns HTTP 500 for every
     * Convex call — the dashboard page still serves, the variable is still
     * there, and the app still finds the new server.
     *
     * Returns the URL string, or null if the page could not be read.
     */
    fun discoverDashboardServerUrl(context: Context): String? {
        return runCatching {
            val dashboardUrl = DashboardUrlProvider.getDashboardUrl(context)
            if (dashboardUrl.isNullOrBlank()) return null
            val http = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(dashboardUrl)
                .header("User-Agent", "ConnectDesk/Android")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string() ?: return@runCatching null
                // Find window.__CONNECTDESK_SERVER_URL__ = "..." in the HTML/JS
                val regex = Regex("""window\.__CONNECTDESK_SERVER_URL__\s*=\s*"([^"]+)""")
                val match = regex.find(body)
                match?.groupValues?.getOrNull(1)?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrElse { null }
    }

    /**
     * Dashboard URL to probe for server discovery. Defaults to the published
     * build origin; can be overridden per-install if needed.
     */
    object DashboardUrlProvider {
        private const val PREFS = "connectdesk"
        private const val KEY_DASHBOARD = "dashboardUrl"

        fun getDashboardUrl(context: Context): String? {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DASHBOARD, null)
            return stored?.takeIf { it.isNotBlank() }
                ?: "https://connectdesk.freebuff.app/"
        }

        fun setDashboardUrl(context: Context, url: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DASHBOARD, url.trimEnd('/')).apply()
        }
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
