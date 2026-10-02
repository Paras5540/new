package com.connectdesk.app

/**
 * Process-wide record of the background sync loop, so the app UI can show
 * whether the foreground service is genuinely alive.
 *
 * Why this exists: the pairing screen's "Connected" label comes from a direct
 * API call and therefore does NOT prove the heartbeat loop is running. When
 * data stops appearing on the dashboard, this is the first thing to check.
 */
object ServiceStatus {
    @Volatile
    var loopRunning: Boolean = false

    @Volatile
    var tickCount: Int = 0

    /** Wall-clock of the last completed heartbeat POST. */
    @Volatile
    var lastBeatAt: Long = 0L

    /** Last heartbeat/loop error message, cleared on a successful beat. */
    @Volatile
    var lastError: String? = null

    /** Server-reported device status seen on the most recent tick. */
    @Volatile
    var serverStatus: String? = null

    /** Seconds since the last successful heartbeat, or -1 if never. */
    fun secondsSinceBeat(): Long =
        if (lastBeatAt == 0L) -1 else (System.currentTimeMillis() - lastBeatAt) / 1000

    fun markStart() {
        loopRunning = true
        tickCount = 0
        lastError = null
    }

    fun markStop() {
        loopRunning = false
    }

    fun markBeat(status: String?) {
        loopRunning = true
        tickCount++
        lastBeatAt = System.currentTimeMillis()
        serverStatus = status
        lastError = null
    }

    fun markError(message: String) {
        lastError = message
    }

    /** One-line summary for the UI. */
    fun summary(): String {
        if (!loopRunning) return "Sync service: STOPPED"
        val ago = secondsSinceBeat()
        val beat = when {
            ago < 0 -> "no heartbeat yet"
            ago < 60 -> "last beat ${ago}s ago"
            else -> "last beat ${ago / 60}m ago"
        }
        val err = lastError?.let { " · error: $it" } ?: ""
        val st = serverStatus?.let { " · $it" } ?: ""
        return "Sync service: running · $beat · ticks $tickCount$st$err"
    }
}
