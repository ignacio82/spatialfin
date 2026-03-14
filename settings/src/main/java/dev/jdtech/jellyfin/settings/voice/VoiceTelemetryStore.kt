package dev.jdtech.jellyfin.settings.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class VoiceTelemetryEntry(
    val transcript: String,
    val action: String,
    val strategy: String,
    val latencyMs: Long,
    val success: Boolean,
)

data class VoiceTelemetrySummary(
    val totalAttempts: Int = 0,
    val successfulAttempts: Int = 0,
    val averageLatencyMs: Long = 0,
    val lastAction: String = "None",
    val lastTranscript: String = "None",
    val recentEntries: List<VoiceTelemetryEntry> = emptyList(),
)

class VoiceTelemetryStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences =
        context.getSharedPreferences("voice_telemetry", Context.MODE_PRIVATE)

    fun record(entry: VoiceTelemetryEntry) {
        val totalAttempts = preferences.getInt(KEY_TOTAL_ATTEMPTS, 0) + 1
        val successfulAttempts =
            preferences.getInt(KEY_SUCCESSFUL_ATTEMPTS, 0) + if (entry.success) 1 else 0
        val totalLatencyMs = preferences.getLong(KEY_TOTAL_LATENCY_MS, 0L) + entry.latencyMs
        val recentEntries = listOf(encode(entry)) + recentEntries().take(MAX_RECENT_ENTRIES - 1)

        preferences
            .edit()
            .putInt(KEY_TOTAL_ATTEMPTS, totalAttempts)
            .putInt(KEY_SUCCESSFUL_ATTEMPTS, successfulAttempts)
            .putLong(KEY_TOTAL_LATENCY_MS, totalLatencyMs)
            .putString(KEY_LAST_ACTION, entry.action)
            .putString(KEY_LAST_TRANSCRIPT, entry.transcript)
            .putString(KEY_RECENT_ENTRIES, recentEntries.joinToString(SEPARATOR))
            .apply()
    }

    fun summary(): VoiceTelemetrySummary {
        val totalAttempts = preferences.getInt(KEY_TOTAL_ATTEMPTS, 0)
        val successfulAttempts = preferences.getInt(KEY_SUCCESSFUL_ATTEMPTS, 0)
        val totalLatencyMs = preferences.getLong(KEY_TOTAL_LATENCY_MS, 0L)
        return VoiceTelemetrySummary(
            totalAttempts = totalAttempts,
            successfulAttempts = successfulAttempts,
            averageLatencyMs = if (totalAttempts == 0) 0 else totalLatencyMs / totalAttempts,
            lastAction = preferences.getString(KEY_LAST_ACTION, "None").orEmpty(),
            lastTranscript = preferences.getString(KEY_LAST_TRANSCRIPT, "None").orEmpty(),
            recentEntries = recentEntries(),
        )
    }

    private fun recentEntries(): List<VoiceTelemetryEntry> {
        val raw = preferences.getString(KEY_RECENT_ENTRIES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR).mapNotNull(::decode)
    }

    private fun encode(entry: VoiceTelemetryEntry): String {
        fun sanitize(input: String): String = input.replace("|", "/").replace(";", ",")
        return listOf(
            sanitize(entry.transcript),
            sanitize(entry.action),
            sanitize(entry.strategy),
            entry.latencyMs.toString(),
            entry.success.toString(),
        ).joinToString("|")
    }

    private fun decode(raw: String): VoiceTelemetryEntry? {
        val parts = raw.split("|")
        if (parts.size != 5) return null
        return VoiceTelemetryEntry(
            transcript = parts[0],
            action = parts[1],
            strategy = parts[2],
            latencyMs = parts[3].toLongOrNull() ?: return null,
            success = parts[4].toBooleanStrictOrNull() ?: return null,
        )
    }

    companion object {
        private const val KEY_TOTAL_ATTEMPTS = "total_attempts"
        private const val KEY_SUCCESSFUL_ATTEMPTS = "successful_attempts"
        private const val KEY_TOTAL_LATENCY_MS = "total_latency_ms"
        private const val KEY_LAST_ACTION = "last_action"
        private const val KEY_LAST_TRANSCRIPT = "last_transcript"
        private const val KEY_RECENT_ENTRIES = "recent_entries"
        private const val MAX_RECENT_ENTRIES = 5
        private const val SEPARATOR = ";"
    }
}
