package dev.jdtech.jellyfin.settings.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject

data class VoiceTelemetryEntry(
    val transcript: String,
    val normalizedTranscript: String = "",
    val action: String,
    val strategy: String,
    val latencyMs: Long,
    val success: Boolean,
    val retry: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val selectedSkill: String = "",
    val validatedInput: String = "",
    val resultDisposition: String = "",
    val details: String = "",
)

data class VoiceTelemetrySummary(
    val totalAttempts: Int = 0,
    val successfulAttempts: Int = 0,
    val averageLatencyMs: Long = 0,
    val lastAction: String = "None",
    val lastTranscript: String = "None",
    val recentEntries: List<VoiceTelemetryEntry> = emptyList(),
)

class VoiceTelemetryStore @Inject constructor(
    @ApplicationContext context: Context,
    private val appPreferences: AppPreferences,
) {
    private val preferences =
        context.getSharedPreferences("voice_telemetry", Context.MODE_PRIVATE)

    fun record(entry: VoiceTelemetryEntry) {
        val previousEntry = recentEntries().firstOrNull()
        val retryAdjusted =
            entry.copy(
                retry = entry.retry || isRetryAttempt(entry, previousEntry),
            )
        // Raw transcripts can carry PII (names, addresses, anything the user
        // said out loud). They're persisted to plain SharedPreferences and
        // can surface in the in-app telemetry dashboard / companion log
        // uploads, so default to redacting them down to length metadata and
        // only store the verbatim text when the user has explicitly opted
        // in via AppPreferences.voiceAssistantStoreTranscripts.
        val enrichedEntry =
            if (appPreferences.getValue(appPreferences.voiceAssistantStoreTranscripts)) {
                retryAdjusted
            } else {
                retryAdjusted.copy(
                    transcript = redactTranscript(retryAdjusted.transcript),
                    normalizedTranscript = redactTranscript(retryAdjusted.normalizedTranscript),
                    validatedInput = redactTranscript(retryAdjusted.validatedInput),
                )
            }
        val totalAttempts = preferences.getInt(KEY_TOTAL_ATTEMPTS, 0) + 1
        val successfulAttempts =
            preferences.getInt(KEY_SUCCESSFUL_ATTEMPTS, 0) + if (enrichedEntry.success) 1 else 0
        val totalLatencyMs = preferences.getLong(KEY_TOTAL_LATENCY_MS, 0L) + enrichedEntry.latencyMs
        val recentEntries = listOf(encode(enrichedEntry)) + recentEntries().take(MAX_RECENT_ENTRIES - 1)

        preferences
            .edit()
            .putInt(KEY_TOTAL_ATTEMPTS, totalAttempts)
            .putInt(KEY_SUCCESSFUL_ATTEMPTS, successfulAttempts)
            .putLong(KEY_TOTAL_LATENCY_MS, totalLatencyMs)
            .putString(KEY_LAST_ACTION, enrichedEntry.action)
            .putString(KEY_LAST_TRANSCRIPT, enrichedEntry.transcript)
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
            sanitize(entry.normalizedTranscript),
            sanitize(entry.action),
            sanitize(entry.strategy),
            entry.latencyMs.toString(),
            entry.success.toString(),
            entry.retry.toString(),
            entry.timestampMs.toString(),
            sanitize(entry.selectedSkill),
            sanitize(entry.validatedInput),
            sanitize(entry.resultDisposition),
            sanitize(entry.details),
        ).joinToString("|")
    }

    private fun decode(raw: String): VoiceTelemetryEntry? {
        val parts = raw.split("|")
        if (parts.size !in 5..12) return null
        val legacy = parts.size <= 6
        val modern = parts.size >= 12
        val retryIndex = if (legacy) null else 6
        val timestampIndex = if (legacy) null else 7
        val selectedSkillIndex = if (modern) 8 else null
        val validatedInputIndex = if (modern) 9 else null
        val resultDispositionIndex = if (modern) 10 else null
        val detailsIndex =
            when {
                legacy -> 5
                modern -> 11
                else -> 8
            }
        return VoiceTelemetryEntry(
            transcript = parts[0],
            normalizedTranscript = if (legacy) parts[0] else parts[1],
            action = parts[if (legacy) 1 else 2],
            strategy = parts[if (legacy) 2 else 3],
            latencyMs = parts[if (legacy) 3 else 4].toLongOrNull() ?: return null,
            success = parts[if (legacy) 4 else 5].toBooleanStrictOrNull() ?: return null,
            retry = retryIndex?.let(parts::getOrNull)?.toBooleanStrictOrNull() ?: false,
            timestampMs = timestampIndex?.let(parts::getOrNull)?.toLongOrNull() ?: 0L,
            selectedSkill = selectedSkillIndex?.let(parts::getOrNull).orEmpty(),
            validatedInput = validatedInputIndex?.let(parts::getOrNull).orEmpty(),
            resultDisposition = resultDispositionIndex?.let(parts::getOrNull).orEmpty(),
            details = parts.getOrElse(detailsIndex) { "" },
        )
    }

    private fun redactTranscript(raw: String): String {
        if (raw.isEmpty()) return ""
        val words = raw.trim().split(Regex("\\s+")).size
        return "[redacted; ${raw.length} chars, $words words]"
    }

    private fun isRetryAttempt(
        entry: VoiceTelemetryEntry,
        previous: VoiceTelemetryEntry?,
    ): Boolean {
        if (previous == null) return false
        val normalized = entry.normalizedTranscript.ifBlank { entry.transcript }
        val previousNormalized = previous.normalizedTranscript.ifBlank { previous.transcript }
        val closeInTime = entry.timestampMs - previous.timestampMs in 0..20_000L
        val repeatedUtterance = normalized.equals(previousNormalized, ignoreCase = true)
        return closeInTime && (repeatedUtterance || !previous.success)
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
