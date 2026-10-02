package com.connectdesk.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
 *    QUERY_ALL_PACKAGES — which Google only allows for device-management
 *    style apps, which is exactly what ConnectDesk is.
 *  - Launch counts and foreground time need "Usage access"
 *    (PACKAGE_USAGE_STATS). That is a Settings toggle, not a dialog: the
 *    user has to find it in Android Settings. Without it Android returns
 *    nothing and the dashboard says so instead of showing a fake empty list.
 *  - Other apps' private DATA (their databases, documents, caches) is never
 *    readable. We list what is installed and how long it ran, nothing more.
 */
object AppUsageWorker {

    /** Usage stats are queried over this window. */
    private const val WINDOW_DAYS = 7
    private const val MAX_APPS = 400

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

    /** Settings intent for the Usage-access toggle. */
    fun usageAccessIntent(): android.content.Intent =
        android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    fun sync(context: Context, token: String): Pair<Boolean, String> {
        if (!hasUsageAccess(context)) {
            return Pair(false, "Usage access has not been granted on the device")
        }
        return try {
            val array = buildArray(context)
            if (array.length() == 0) return Pair(false, "No apps reported")
            if (ApiClient.postSync(
                    JSONObject()
                        .put("deviceToken", token)
                        .put("type", "apps")
                        .put("apps", array),
                )
            ) {
                Pair(true, "Sent ${array.length()} apps")
            } else {
                Pair(false, ApiClient.lastError ?: "App index upload failed")
            }
        } catch (e: Throwable) {
            Pair(false, "App scan failed: ${e.message}")
        }
    }

    private fun buildArray(context: Context): JSONArray {
        val out = JSONArray()
        val pm = context.packageManager
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val since = System.currentTimeMillis() - WINDOW_DAYS * 24L * 60 * 60 * 1000

        // queryUsageStats returns a plain List<UsageStats>, not a cursor.
        // The result is bound to an explicitly typed local so the call
        // resolves against exactly one overload.
        val stats = HashMap<String, UsageStats>()
        if (usage != null) {
            try {
                val interval: Int = UsageStatsManager.INTERVAL_BEST
                val beginTime: Long = since
                val rows: List<UsageStats> = usage.queryUsageStats(interval, beginTime)
                for (us in rows) {
                    val pkg = us.packageName
                    if (pkg.isNullOrEmpty()) continue
                    val prev = stats[pkg]
                    // Keep the most informative record we saw for this package.
                    stats[pkg] =
                        if (prev == null || us.totalTimeInForeground > prev.totalTimeInForeground) {
                            us
                        } else {
                            prev
                        }
                }
            } catch (_: Throwable) {
                // Fall through: we still push the package list.
            }
        }

        val launches = countLaunches(usage, since)

        val installed: List<ApplicationInfo> = try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        } catch (_: Throwable) {
            emptyList()
        }

        for (info in installed) {
            if (out.length() >= MAX_APPS) break
            val pkg = info.packageName ?: continue
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val st = stats[pkg]
            try {
                out.put(
                    JSONObject()
                        .put("packageName", pkg)
                        .put("label", safeLabel(pm, info))
                        .put("isSystem", isSystem)
                        .put("lastUsedAt", st?.lastTimeUsed ?: 0L)
                        .put("launchCount", launches[pkg] ?: 0)
                        .put(
                            "durationMinutes",
                            ((st?.totalTimeInForeground ?: 0L) / 60000L).toInt(),
                        ),
                )
            } catch (_: Throwable) {
                continue
            }
        }
        return out
    }

    private fun safeLabel(pm: PackageManager, info: ApplicationInfo): String = try {
        pm.getApplicationLabel(info).toString()
    } catch (_: Throwable) {
        info.packageName ?: ""
    }

    /**
     * Foreground launches in the window. ACTIVITY_RESUMED transitions are the
     * closest public signal to "the user opened the app".
     */
    private fun countLaunches(usage: UsageStatsManager?, since: Long): Map<String, Int> {
        val result = HashMap<String, Int>()
        if (usage == null) return result
        try {
            val events = usage.queryEvents(since, System.currentTimeMillis())
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = event.packageName ?: continue
                result[pkg] = (result[pkg] ?: 0) + 1
            }
        } catch (_: Throwable) {
            // Partial data is still useful.
        }
        return result
    }
}