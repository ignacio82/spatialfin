package dev.jdtech.jellyfin.core.diagnostics

import android.content.Context

object PlayerLaunchBreadcrumbs {
    private const val PREFS_NAME = "player_launch_breadcrumbs"
    private const val KEY_PHASE = "phase"
    private const val KEY_TIMESTAMP_MS = "timestamp_ms"
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    fun markPending(context: Context, phase: String) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHASE, phase)
            .putLong(KEY_TIMESTAMP_MS, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun consumePending(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val phase = prefs.getString(KEY_PHASE, null)
        val timestampMs = prefs.getLong(KEY_TIMESTAMP_MS, 0L)
        clear(context)
        if (phase.isNullOrBlank() || timestampMs <= 0L) return null
        val ageMs = System.currentTimeMillis() - timestampMs
        if (ageMs !in 0..MAX_AGE_MS) return null
        return "$phase (${ageMs}ms before relaunch)"
    }
}
