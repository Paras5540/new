package com.connectdesk.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * Installed apps + how each one is actually used.
 *
 * Honest limits, stated up front:
 *  - The package list needs no permission on Android 10 and below. From
 *    Android 11 package-visibility filtering applies, so the app declares
 *    QUERY_ALL_PACKAGES -- which Google only allows for device-management
 *    style apps, which is exactly what ConnectDesk is.
 *  - Launch counts and foreground time need "Usage access"
 *    (PACKAGE_USAGE_STATS). That is a Settings toggle, not a dialog: the
 *    user has to find it in Android Settings. Without it Android returns
 *    nothing and the dashboard says so instead of showing a fake empty list.
 *  - Other apps' private DATA (their databases, documents, caches) is never
 *    readable. We list what is installed and how long it ran, nothing more.
 *  - Numbers are bucketed PER CALENDAR DAY from raw foreground transitions,
 *    which is how Android's own Digital Wellbeing counts screen time. The
 *    dashboard therefore shows the same minutes for a given day that the
 *    phone shows. If it ever disagrees, the disagreement is in the phone's
 *    measurement, not in a number invented here.
 */
object AppUsageWorker {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * How many days of real history one sync uploads.
     *
     * Seven is the window Android itself shows by default. Each day is a
     * separate snapshot, so one sync fills a week of the dashboard's date
     * picker with measured data instead of a single cumulative total wearing
     * today's label.
     */
    private const val HISTORY_DAYS = 7
    private const val MAX_APPS = 400

    /**
     * How often a full app scan may run on its own.
     *
     * This matters because `DataSyncWorker` runs once a MINUTE. Walking seven
     * days of `queryEvents` and POSTing seven payloads on top of that would
     * burn battery and mobile data to re-upload history that cannot have
     * changed. Foreground time only moves for today (and occasionally
     * yesterday, when a transition is delivered late), so everything older is
     * immutable and is sent exactly once.
     */
    private const val SYNC_INTERVAL_MS = 30 * 60 * 1000L

    private const val PREFS = "connectdesk_appusage"
    private const val KEY_LAST_SYNC = "lastSyncAt"
    private const val KEY_DONE_DAYS = "doneDays"

    /** Days already uploaded that can no longer change. */
    private fun doneDays(context: Context): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DONE_DAYS, emptySet())!!.toMutableSet()

    private fun setDoneDays(context: Context, days: Set<String>) {
        // Keep the set bounded: 30 days matches what the server retains.
        val trimmed = days.toList().sortedDescending().take(30).toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DONE_DAYS, trimmed)
            .apply()
    }

    private fun lastSyncAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC, 0L)

    private fun setLastSyncAt(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SYNC, at).apply()
    }

    /**
     * True once the phone owner granted Usage access. Used to show an honest
     * "you still need to grant this" state instead of an empty table.
     */
    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    /** Today's date in the device's own timezone as `YYYY-MM-DD`. */
    fun todayLocal(): String = formatDay(System.currentTimeMillis())

    /**
     * Settings intent for the Usage-access toggle.
     *
     * Two things were making the user get bounced back instead of landing on
     * the Usage-access screen:
     *
     *  1. Without `data = package:<us>` the Settings app opens the generic
     *     usage-access list. On many OEM builds that list only refreshes its
     *     state when the returning app declares the package it wants, so the
     *     toggle appears to revert and the user is sent back.
     *  2. `FLAG_ACTIVITY_NEW_TASK` started Settings as a NEW task on top of
     *     ours, so pressing Back returned to wherever that task happened to
     *     start -- not to the app that asked. Launching as a child of the
     *     current task keeps the back stack correct.
     */
    fun usageAccessIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${BuildConfig.APPLICATION_ID}")
            // Explicitly NOT NEW_TASK: Settings must sit on the current task
            // so Back returns here rather than dumping the user at whatever
            // the launcher had behind us.
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /**
     * Scans and uploads app usage.
     *
     * [force] is set by the dashboard's "Rescan on device" button so the owner
     * gets an immediate refresh instead of being told to wait out the
     * background interval. Without it a periodic sync that runs once a minute
     * would do nothing but burn battery.
     */
    fun sync(context: Context, token: String, force: Boolean = false): Pair<Boolean, String> {
        if (!hasUsageAccess(context)) {
            return Pair(false, "Usage access has not been granted on the device")
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt(context) < SYNC_INTERVAL_MS) {
            return Pair(true, "App usage throttled — next scan in " +
                "${((SYNC_INTERVAL_MS - (now - lastSyncAt(context))) / 60000L) + 1} min")
        }
        return try {
            val installed = installedApps(context)
            if (installed.isEmpty()) return Pair(false, "No apps reported")
            // Resolved once, not once per app per day.
            val labels = labelsFor(context, installed)

            // Per calendar day, exactly how Digital Wellbeing counts it.
            val daily = perDayUsage(context, HISTORY_DAYS)
            if (daily.isEmpty()) return Pair(false, "No usage recorded yet")

            val done = doneDays(context)
            val today = todayLocal()
            val yesterday = formatDay(startOfDay(now) - DAY_MS)

            var sent = 0
            var lastError = ""
            // Oldest first. The server replaces each day's rows wholesale, so
            // order does not affect correctness, but it leaves the newest day --
            // the one people actually look at -- as the final write.
            for ((day, usage) in daily) {
                // A day that is neither today nor yesterday cannot gain new
                // events, so once it has been uploaded it is never re-sent.
                if (day != today && day != yesterday && done.contains(day)) continue
                val array = buildArray(installed, labels, usage)
                if (array.length() == 0) continue
                val ok = ApiClient.postSync(
                    JSONObject()
                        .put("deviceToken", token)
                        .put("type", "apps")
                        // Local calendar day, so the dashboard can file this
                        // snapshot under the day the PHONE is on and show a
                        // real history instead of only today.
                        .put("day", day)
                        .put("apps", array),
                )
                if (ok) {
                    sent++
                    if (day != today && day != yesterday) done.add(day)
                } else {
                    lastError = ApiClient.lastError ?: "App upload failed for $day"
                }
            }
            setDoneDays(context, done)
            // Only a run that actually uploaded something counts as a scan,
            // so a device with no network does not then throttle itself for
            // half an hour the moment the connection returns.
            if (sent > 0) setLastSyncAt(context, now)

            if (sent == 0 && lastError.isNotEmpty()) {
                Pair(false, lastError)
            } else {
                Pair(true, "Sent $sent day(s) of app usage")
            }
        } catch (e: Throwable) {
            Pair(false, "App scan failed: ${e.message}")
        }
    }

    /** Installed packages. Empty means nothing was readable on this phone. */
    private fun installedApps(context: Context): List<ApplicationInfo> = try {
        @Suppress("DEPRECATION")
        context.packageManager.getInstalledApplications(0)
            .filter { !it.packageName.isNullOrEmpty() }
    } catch (_: Throwable) {
        emptyList()
    }

    /** What actually happened on one calendar day, per package. */
    private class DayUsage {
        val foregroundMs = HashMap<String, Long>()
        val launches = HashMap<String, Int>()
        val lastUsedAt = HashMap<String, Long>()

        fun add(pkg: String, ms: Long) {
            if (ms <= 0) return
            foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + ms
        }

        fun launch(pkg: String, at: Long) {
            launches[pkg] = (launches[pkg] ?: 0) + 1
            val prev = lastUsedAt[pkg] ?: 0L
            if (at > prev) lastUsedAt[pkg] = at
        }
    }

    /**
     * Foreground time, launches and last-used, bucketed by CALENDAR DAY.
     *
     * WHY NOT `queryUsageStats`: its `totalTimeInForeground` is the total over
     * the whole query WINDOW, not per day. The previous code asked for seven
     * days and stored that single cumulative number under today's date, so
     * the dashboard's "today" was really "the last seven days summed". That is
     * why the numbers never matched Digital Wellbeing, and it was a real
     * measurement being reported under the wrong label -- no public API gives
     * per-day totals, so the buckets are built the way Digital Wellbeing
     * builds them: pair every foreground transition with the one that ends it
     * and add the elapsed time to the day that transition fell in.
     *
     * Intervals are clamped to their own day, so an app left open across
     * midnight contributes to both days instead of all of it landing on one.
     * An app still in the foreground at the end of a day is closed at that
     * day's end rather than silently discarded.
     */
    private fun perDayUsage(
        context: Context,
        days: Int,
    ): Map<String, DayUsage> {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val out = LinkedHashMap<String, DayUsage>()
        val now = System.currentTimeMillis()

        for (back in days - 1 downTo 0) {
            val dayStart = startOfDay(now - back * DAY_MS)
            val dayEnd = dayStart + DAY_MS
            if (dayStart >= now) break // a day that has not happened yet
            val bucket = DayUsage()

            try {
                val events = usage.queryEvents(dayStart, dayEnd)
                val event = UsageEvents.Event()
                // package -> when its current foreground interval began
                val open = HashMap<String, Long>()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            // Close any interval still open for this package
                            // first: two RESUMED in a row means the PAUSED
                            // transition was not delivered, and counting both
                            // would add the whole gap twice.
                            open.remove(pkg)?.let { start ->
                                bucket.add(pkg, clamp(event.timeStamp, start, dayEnd) - start)
                            }
                            open[pkg] = event.timeStamp
                            bucket.launch(pkg, event.timeStamp)
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED,
                        -> {
                            val start = open.remove(pkg) ?: continue
                            bucket.add(pkg, clamp(event.timeStamp, start, dayEnd) - start)
                        }
                    }
                }
                // Whatever is still open ran until the end of this day.
                for ((pkg, start) in open) {
                    bucket.add(pkg, clamp(dayEnd, start, dayEnd) - start)
                }
            } catch (_: Throwable) {
                // A day we could not read is absent rather than reported as
                // zero, so a gap never reads as "no screen time that day".
                continue
            }

            out[formatDay(dayStart)] = bucket
        }
        return out
    }

    private fun clamp(value: Long, lo: Long, hi: Long): Long =
        if (value < lo) lo else if (value > hi) hi else value

    /** Local midnight of the day containing [ts]. */
    private fun startOfDay(ts: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ts
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun formatDay(ts: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ts
        return String.format(
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * Builds one day's payload: every installed app, with the minutes and
     * launches MEASURED for that day (zero for an app that was not used).
     *
     * The installed list is sent for every day on purpose, so the dashboard's
     * app list is the real package list rather than "whatever happened to run
     * on the day the snapshot was taken".
     *
     * WHICH 400 APPS GET SENT USED TO BE ARBITRARY.
     *
     * MAX_APPS is a real ceiling -- a busy phone easily has 500-700 packages --
     * but the loop simply took the first MAX_APPS entries in
     * PackageManager order, which is roughly alphabetical by package name and
     * has nothing to do with what the phone owner uses. An app that ran for an
     * hour that day could be dropped while unused `com.android.*` packages
     * filled the cap, so its minutes were never reported at all and the
     * dashboard under-counted screen time on a phone with many apps.
     *
     * Apps that were actually used are now sent first, ordered by the time
     * they consumed, and only then does the remainder get filled with the rest
     * of the installed list so the package inventory stays complete up to the
     * cap. A package outside the cap is always one the phone did not report
     * usage for, which is the only honest thing to drop.
     */
    private fun buildArray(
        installed: List<ApplicationInfo>,
        labels: Map<String, String>,
        usage: DayUsage,
    ): JSONArray {
        // Used beats unused; within each group, the heaviest user first.
        // `launchCount` breaks ties so an app opened often but briefly is still
        // ahead of one that was never opened.
        //
        // This is a `sortedWith` comparator chain, NOT `sortedByDescending`.
        // The selector of `sortedByDescending` must return a `Comparable<R>`
        // and a `Pair` is not one, so returning `Pair(usedFlag, weight)` failed
        // to compile ("inferred type is Pair<Long, Long> but Comparable<...>
        // was expected"). `compareByDescending` states the two keys
        // explicitly and evaluates each on its own, which is also why the
        // weight can be computed lazily inside the second comparator instead
        // of being packed into a tuple.
        val ordered = installed.sortedWith(
            compareByDescending<ApplicationInfo> {
                val pkg = it.packageName ?: ""
                if ((usage.foregroundMs[pkg] ?: 0L) > 0L) 1 else 0
            }.thenByDescending {
                val pkg = it.packageName ?: ""
                val ms = usage.foregroundMs[pkg] ?: 0L
                ms * 1000L + (usage.launches[pkg] ?: 0).toLong()
            },
        )
        val out = JSONArray()
        for (info in ordered) {
            if (out.length() >= MAX_APPS) break
            val pkg = info.packageName ?: continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val ms = usage.foregroundMs[pkg] ?: 0L
            try {
                out.put(
                    JSONObject()
                        .put("packageName", pkg)
                        .put("label", labels[pkg] ?: pkg)
                        .put("isSystem", isSystem)
                        .put("lastUsedAt", usage.lastUsedAt[pkg] ?: 0L)
                        .put("launchCount", usage.launches[pkg] ?: 0)
                        .put("durationMinutes", (ms / 60000L).toInt()),
                )
            } catch (_: Throwable) {
                continue
            }
        }
        return out
    }

    /** Label lookup is done once per sync, not once per app per day. */
    private fun labelsFor(
        context: Context,
        installed: List<ApplicationInfo>,
    ): Map<String, String> {
        val pm = context.packageManager
        val out = HashMap<String, String>()
        for (info in installed) {
            val pkg = info.packageName ?: continue
            out[pkg] = try {
                pm.getApplicationLabel(info).toString()
            } catch (_: Throwable) {
                pkg
            }
        }
        return out
    }
}