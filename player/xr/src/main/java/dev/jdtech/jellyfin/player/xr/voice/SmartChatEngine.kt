package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import dev.jdtech.jellyfin.models.SpatialFinEpisode
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.models.SpatialFinMovie
import dev.jdtech.jellyfin.models.SpatialFinShow
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

data class AssistantPreferences(
    val verbosity: String = "balanced",
    val spoilerPolicy: String = "cautious",
    val spokenRepliesEnabled: Boolean = true,
)

class SmartChatEngine(private val appContext: Context) {
    private var generativeModel: GenerativeModel? = null
    var isModelAvailable = false
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val generationConfig =
                generationConfig {
                    this.context = appContext.applicationContext
                    temperature = 0.4f
                    maxOutputTokens = 256
                    candidateCount = 1
                }
            val downloadConfig =
                DownloadConfig(
                    object : DownloadCallback {
                        override fun onDownloadDidNotStart(e: GenerativeAIException) {
                            Timber.w(e, "VOICE CHAT: Gemini Nano download did not start")
                        }

                        override fun onDownloadFailed(
                            failureStatus: String,
                            e: GenerativeAIException,
                        ) {
                            Timber.w(e, "VOICE CHAT: Gemini Nano download failed: %s", failureStatus)
                        }
                    }
                )
            generativeModel = GenerativeModel(generationConfig, downloadConfig)
            isModelAvailable = true
        } catch (e: Exception) {
            Timber.w(e, "VOICE CHAT: Gemini Nano unavailable")
            generativeModel = null
            isModelAvailable = false
        }
    }

    suspend fun query(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String? = null,
        recentSubtitles: String = "",
        assistantPreferences: AssistantPreferences = AssistantPreferences(),
        onSearchQuery: (suspend (String) -> List<SpatialFinItem>)? = null,
    ): String? = withContext(Dispatchers.IO) {
        val model = generativeModel ?: return@withContext null

        val retrievalContext =
            if (shouldRetrieveForLibraryDiscovery(question)) {
                buildRetrievalContext(question, playerState.currentItemTitle, onSearchQuery)
            } else {
                null
            }

        val prompt =
            buildPrompt(
                question = question,
                playerState = playerState,
                storySoFarContext = storySoFarContext,
                recentSubtitles = recentSubtitles,
                assistantPreferences = assistantPreferences,
                retrievalContext = retrievalContext,
            )
        return@withContext try {
            withTimeoutOrNull(8_000L) {
                model.generateContent(prompt).text?.trim()
            }
        } catch (e: Exception) {
            Timber.w(e, "VOICE CHAT: Nano query failed for: %s", question)
            null
        }
    }

    fun destroy() {
        generativeModel?.close()
        generativeModel = null
        isModelAvailable = false
    }

    private suspend fun buildRetrievalContext(
        question: String,
        currentItemTitle: String,
        onSearchQuery: (suspend (String) -> List<SpatialFinItem>)?,
    ): RetrievalContext? {
        if (onSearchQuery == null) return null
        val searchQuery = deriveSearchQuery(question, currentItemTitle) ?: return null
        val results =
            runCatching { onSearchQuery(searchQuery) }
                .getOrElse {
                    Timber.w(it, "VOICE CHAT: Metadata retrieval failed for query=%s", searchQuery)
                    emptyList()
                }
                .take(5)
        return RetrievalContext(searchQuery = searchQuery, results = results)
    }

    private suspend fun deriveSearchQuery(question: String, currentItemTitle: String): String? {
        val model = generativeModel
        if (model != null) {
            val queryPrompt =
                """
                Convert the user's media discovery request into a short Jellyfin library search query.
                Return ONLY the search query, maximum 6 words. If no retrieval is useful, return NONE.

                Current media title: $currentItemTitle
                User request: "$question"
                """.trimIndent()
            val modelQuery =
                runCatching {
                    withTimeoutOrNull(2_500L) {
                        model.generateContent(queryPrompt).text?.trim()
                    }
                }.getOrNull()
                    ?.takeIf { !it.isNullOrBlank() }
                    ?.replace("\"", "")
                    ?.takeUnless { it.equals("none", ignoreCase = true) }
                    ?.take(80)
            if (!modelQuery.isNullOrBlank()) {
                return modelQuery
            }
        }

        return heuristicSearchQuery(question, currentItemTitle)
    }

    private fun heuristicSearchQuery(question: String, currentItemTitle: String): String? {
        val lowered = question.lowercase()
        val extracted =
            listOf(
                Regex(".*(?:like|similar to|something like)\\s+(.+)"),
                Regex(".*(?:find|show me|recommend)\\s+(.+)"),
                Regex(".*(?:with)\\s+(.+)"),
            ).firstNotNullOfOrNull { regex ->
                regex.matchEntire(lowered)?.groupValues?.getOrNull(1)
            } ?: lowered

        val sanitized =
            extracted
                .replace(currentItemTitle.lowercase(), "")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\b(movie|movies|show|shows|series|anime|watch|me|something|please|recommend|find)\\b"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        return sanitized.takeIf { it.isNotBlank() }?.split(' ')?.take(6)?.joinToString(" ")
    }

    private fun shouldRetrieveForLibraryDiscovery(question: String): Boolean {
        val lowered = question.lowercase()
        return listOf(
            "recommend",
            "what should i watch",
            "show me",
            "find",
            "something like",
            "similar to",
            "movies like",
            "anime like",
            "watch next",
        ).any(lowered::contains)
    }

    private fun buildPrompt(
        question: String,
        playerState: PlayerStateSnapshot,
        storySoFarContext: String?,
        recentSubtitles: String,
        assistantPreferences: AssistantPreferences,
        retrievalContext: RetrievalContext?,
    ): String {
        val verbosityInstruction =
            when (assistantPreferences.verbosity) {
                "concise" -> "Keep the answer to one short sentence when possible."
                "detailed" -> "Use up to four concise sentences if needed."
                else -> "Keep the answer to one or two concise sentences."
            }
        val spoilerInstruction =
            when (assistantPreferences.spoilerPolicy) {
                "strict" -> "Avoid spoilers beyond the currently provided context unless the user explicitly asks for them."
                "open" -> "You may discuss spoilers if they are directly relevant."
                else -> "Do not reveal major spoilers unless the user explicitly requests them."
            }

        return """
            You are a grounded AI assistant inside SpatialFin, an immersive XR media player.
            $verbosityInstruction
            $spoilerInstruction
            If the answer is grounded in item metadata, say so naturally, for example "From the item overview,".
            If the answer is grounded in recent subtitles, say so naturally, for example "Based on the recent subtitles,".
            If the answer is an inference, say so naturally, for example "My best inference is".
            If you are unsure, say that clearly instead of sounding certain.
            When the user asks for recommendations or discovery help, only use the retrieved library candidates below if they are present.

            Current playback context:
            ${formatPlaybackContext(playerState)}

            ${if (storySoFarContext != null) "Story so far: $storySoFarContext" else ""}
            ${if (recentSubtitles.isNotBlank()) "Recent subtitles from the last minute: $recentSubtitles" else ""}
            ${retrievalContext?.let { formatRetrievalContext(it) } ?: ""}

            User question: "$question"
            Answer:
        """.trimIndent()
    }

    private fun formatPlaybackContext(playerState: PlayerStateSnapshot): String {
        val parts = mutableListOf<String>()
        parts += "Title: ${playerState.currentItemTitle}"
        playerState.currentSeriesName?.let { parts += "Series: $it" }
        if (playerState.currentSeasonNumber != null && playerState.currentEpisodeNumber != null) {
            parts += "Episode: Season ${playerState.currentSeasonNumber}, Episode ${playerState.currentEpisodeNumber}"
        }
        if (playerState.currentOverview.isNotBlank()) {
            parts += "Overview: ${playerState.currentOverview}"
        }
        if (playerState.currentGenres.isNotEmpty()) {
            parts += "Genres: ${playerState.currentGenres.take(5).joinToString(", ")}"
        }
        if (playerState.castNames.isNotEmpty()) {
            parts += "Cast and crew: ${playerState.castNames.take(6).joinToString(", ")}"
        }
        parts += "Playback position: ${formatTime(playerState.positionSeconds)} / ${formatTime(playerState.durationSeconds)}"
        playerState.currentChapterName?.let { parts += "Current chapter: $it" }
        playerState.currentSegmentType?.let { parts += "Current segment: $it" }
        playerState.nextEpisodeTitle?.let { parts += "Next episode: $it" }
        playerState.currentAudioTrack?.let { parts += "Current audio: $it" }
        playerState.currentSubtitleTrack?.let { parts += "Current subtitles: $it" }
        if (playerState.inVoiceSearch) {
            parts += "Voice search is open for '${playerState.voiceSearchQuery.orEmpty()}' with ${playerState.voiceSearchResultsCount} results."
        }
        if (playerState.syncPlayActive) {
            parts += "SyncPlay is active${playerState.syncPlayGroupName?.let { " in group '$it'" } ?: ""}."
            if (playerState.syncPlayParticipantNames.isNotEmpty()) {
                parts += "SyncPlay participants: ${playerState.syncPlayParticipantNames.take(5).joinToString(", ")}"
            }
        }
        return parts.joinToString("\n")
    }

    private fun formatRetrievalContext(retrievalContext: RetrievalContext): String {
        if (retrievalContext.results.isEmpty()) {
            return "Retrieved library candidates for '${retrievalContext.searchQuery}': none found."
        }
        val lines =
            retrievalContext.results.mapIndexed { index, item ->
                "${index + 1}. ${describeItem(item)}"
            }
        return "Retrieved library candidates for '${retrievalContext.searchQuery}':\n${lines.joinToString("\n")}"
    }

    private fun describeItem(item: SpatialFinItem): String {
        val type =
            when (item) {
                is SpatialFinMovie -> "Movie"
                is SpatialFinEpisode -> "Episode"
                is SpatialFinShow -> "Series"
                else -> "Item"
            }
        val overviewSnippet = item.overview.take(180)
        val extra =
            when (item) {
                is SpatialFinMovie -> listOfNotNull(item.productionYear?.toString(), item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "))
                is SpatialFinShow -> listOfNotNull(item.productionYear?.toString(), item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "))
                is SpatialFinEpisode -> listOfNotNull(item.seriesName, item.seasonName)
                else -> emptyList()
            }.take(min(2, 2)).joinToString(" | ")
        return listOfNotNull("$type: ${item.name}", extra.takeIf { it.isNotBlank() }, overviewSnippet.takeIf { it.isNotBlank() }).joinToString(" - ")
    }

    private fun formatTime(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private data class RetrievalContext(
        val searchQuery: String,
        val results: List<SpatialFinItem>,
    )
}
