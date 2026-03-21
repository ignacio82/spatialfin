package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

data class AssistantPreferences(
    val verbosity: String = "balanced",
    val spoilerPolicy: String = "cautious",
    val spokenRepliesEnabled: Boolean = true,
)

data class AssistantReply(
    val text: String?,
    val strategy: String,
    val debugInfo: String,
)

class SmartChatEngine(
    @Suppress("UNUSED_PARAMETER") private val appContext: Context,
    private val geminiNanoService: GeminiNanoService,
    private val geminiCloudService: GeminiCloudService,
) {
    suspend fun initialize() = withContext(Dispatchers.IO) {
        geminiNanoService.initialize()
    }

    suspend fun query(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String? = null,
        recentSubtitles: String = "",
        assistantPreferences: AssistantPreferences = AssistantPreferences(),
        onSearchQuery: (suspend (String) -> List<SpatialFinItem>)? = null,
    ): AssistantReply = withContext(Dispatchers.IO) {
        val prompt =
            buildPrompt(
                question = question,
                playerState = playerState,
                storySoFarContext = storySoFarContext,
                recentSubtitles = recentSubtitles,
                assistantPreferences = assistantPreferences,
            )
        val result = geminiNanoService.generateText(prompt = prompt, reason = "chat")
        if (result.usedModel && !result.text.isNullOrBlank()) {
            return@withContext AssistantReply(
                text = result.text.trim(),
                strategy = "MODEL",
                debugInfo = result.status.details,
            )
        }

        val cloudResult =
            geminiCloudService.generateText(
                prompt = prompt,
                reason = "chat",
                temperature = 0.3,
                maxOutputTokens = 320,
            )
        if (cloudResult.status.usedModel && !cloudResult.text.isNullOrBlank()) {
            return@withContext AssistantReply(
                text = cloudResult.text.trim(),
                strategy = "CLOUD",
                debugInfo = cloudResult.status.details,
            )
        }

        Timber.d("GEMINI: chat fallback to heuristic answer details=%s", result.status.details)
        AssistantReply(
            text = heuristicAnswer(question, playerState, storySoFarContext, recentSubtitles),
            strategy = "HEURISTIC",
            debugInfo = "${result.status.details}; ${cloudResult.status.details}",
        )
    }

    fun destroy() {
        geminiNanoService.destroy()
    }

    private fun buildPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        recentSubtitles: String,
        assistantPreferences: AssistantPreferences,
    ): String {
        return """
            You are SpatialFin, an on-device XR media assistant.
            Answer briefly and directly.
            Respect the spoiler policy: ${assistantPreferences.spoilerPolicy}.
            Verbosity: ${assistantPreferences.verbosity}.
            If the answer is uncertain, say so plainly.
            Do not invent details not present in the supplied context.

            Current title: ${playerState.currentItemTitle}
            Series: ${playerState.currentSeriesName ?: ""}
            Season: ${playerState.currentSeasonNumber ?: ""}
            Episode: ${playerState.currentEpisodeNumber ?: ""}
            Overview: ${playerState.currentOverview.take(1000)}
            Genres: ${playerState.currentGenres.joinToString(", ")}
            Ratings: ${playerState.currentRatings.joinToString(", ")}
            Cast: ${playerState.castNames.take(12).joinToString(", ")}
            Current chapter: ${playerState.currentChapterName ?: ""}
            Story so far: ${(storySoFarContext ?: "").take(1200)}
            Recent subtitles: ${recentSubtitles.takeLast(1200)}
            Audio track: ${playerState.currentAudioTrack ?: ""}
            Subtitle track: ${playerState.currentSubtitleTrack ?: ""}

            User question:
            $question
        """.trimIndent()
    }

    private fun heuristicAnswer(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        recentSubtitles: String,
    ): String? {
        val normalized =
            question.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        if (normalized.isBlank()) return null

        if (
            normalized.contains("plot") ||
                normalized.contains("movie about") ||
                normalized.contains("show about") ||
                normalized.contains("summary") ||
                normalized.contains("summarize")
        ) {
            return playerState.currentOverview
                .takeIf { it.isNotBlank() }
                ?: storySoFarContext?.takeIf { it.isNotBlank() }
                ?: "I don't have a plot summary for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("story so far") || normalized.contains("recap") || (normalized.contains("previous episode") && (normalized.contains("about") || normalized.contains("happened")))) {
            return storySoFarContext?.takeIf { it.isNotBlank() }
                ?: playerState.currentOverview.takeIf { it.isNotBlank() }
                ?: "I don't have enough context yet to recap what happened in the previous episode."
        }

        if (normalized.contains("director") || normalized.contains("who directed")) {
            val directors = peopleByRole(playerState.castNames, "director")
            return when {
                directors.isNotEmpty() -> "Director: ${directors.joinToString(", ")}."
                else -> "I couldn't find director metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."
            }
        }

        if (
            normalized.contains("cast") ||
                normalized.contains("who stars") ||
                normalized.contains("who is in this") ||
                normalized.contains("actors")
        ) {
            val actors = peopleExcludingRole(playerState.castNames, setOf("director", "writer", "producer"))
            return when {
                actors.isNotEmpty() -> "Cast: ${actors.take(5).joinToString(", ")}."
                else -> "I couldn't find cast metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."
            }
        }

        if (normalized.contains("genre") || normalized.contains("what kind of")) {
            return playerState.currentGenres
                .takeIf { it.isNotEmpty() }
                ?.let { "Genres: ${it.take(4).joinToString(", ")}." }
                ?: "I couldn't find genre metadata for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("rating") || normalized.contains("score") || normalized.contains("is it good") || normalized.contains("reviews")) {
            return playerState.currentRatings
                .takeIf { it.isNotEmpty() }
                ?.let { "Ratings for ${playerState.currentItemTitle}: ${it.joinToString(", ")}." }
                ?: "I don't have rating information for ${playerState.currentItemTitle.ifBlank { "this title" }}."
        }

        if (normalized.contains("title") || normalized.contains("what am i watching")) {
            return playerState.currentItemTitle.takeIf { it.isNotBlank() } ?: "I don't know the current title."
        }

        if (normalized.contains("what happened") || normalized.contains("what did they say")) {
            return recentSubtitles.takeIf { it.isNotBlank() }
                ?.let { "Based on the recent subtitles, $it" }
                ?: storySoFarContext?.takeIf { it.isNotBlank() }
                ?: "I don't have enough recent dialogue context to answer that."
        }

        return if (playerState.currentOverview.isNotBlank()) {
            playerState.currentOverview
        } else {
            "I don't have enough metadata to answer that right now."
        }
    }

    private fun peopleByRole(people: List<String>, role: String): List<String> {
        return people.mapNotNull { person ->
            val trimmed = person.trim()
            val name = trimmed.substringBeforeLast(" (", trimmed)
            val metadata = trimmed.substringAfterLast(" (", "").removeSuffix(")")
            if (metadata.equals(role, ignoreCase = true)) name else null
        }
    }

    private fun peopleExcludingRole(people: List<String>, excludedRoles: Set<String>): List<String> {
        return people.mapNotNull { person ->
            val trimmed = person.trim()
            val name = trimmed.substringBeforeLast(" (", trimmed)
            val metadata = trimmed.substringAfterLast(" (", "").removeSuffix(")")
            if (name.isBlank() || excludedRoles.any { metadata.equals(it, ignoreCase = true) }) {
                null
            } else {
                name
            }
        }
    }
}
