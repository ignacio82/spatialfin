package dev.jdtech.jellyfin.player.beam.voice

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import dev.jdtech.jellyfin.models.SpatialFinItem
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.repository.JellyfinRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

data class BeamVoiceParseResult(
    val action: XrPlayerAction,
    val debugInfo: String,
)

class BeamGeminiNanoService(private val appContext: Context) {
    suspend fun generateText(prompt: String, reason: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = Generation.getClient() ?: return@withContext null
            if (client.checkStatus() != FeatureStatus.AVAILABLE) return@withContext null
            client.generateContent(prompt).candidates.firstOrNull()?.text
        }.onFailure { Timber.w(it, "GEMINI: nano %s failed", reason) }.getOrNull()
    }
}

class BeamGeminiCloudService(
    private val appContext: Context,
    private val appPreferences: AppPreferences,
    private val repository: JellyfinRepository,
) {
    private val client = OkHttpClient()

    suspend fun generateText(
        prompt: String,
        reason: String,
        temperature: Double = 0.2,
        maxOutputTokens: Int = 256,
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty().trim()
        val useProxy = apiKey.isBlank()
        val body =
            JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                    ),
                ).put(
                    "generationConfig",
                    JSONObject()
                        .put("temperature", temperature)
                        .put("maxOutputTokens", maxOutputTokens),
                )

        val request =
            if (useProxy) {
                val baseUrl = repository.getBaseUrl().trimEnd('/')
                Request.Builder()
                    .url("$baseUrl/SpatialFin/AI/Proxy")
                    .addHeader("Content-Type", JSON)
                    .post(body.toString().toRequestBody(JSON.toMediaType()))
                    .build()
            } else {
                Request.Builder()
                    .url("$BASE_URL/$MODEL:generateContent")
                    .addHeader("Content-Type", JSON)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody(JSON.toMediaType()))
                    .build()
            }

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("GEMINI: cloud %s failed http=%s", reason, response.code)
                    return@use null
                }
                extractText(response.body.string())
            }
        }.onFailure { Timber.w(it, "GEMINI: cloud %s failed", reason) }.getOrNull()
    }

    private fun extractText(responseBody: String): String? {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: continue
            val builder = StringBuilder()
            for (j in 0 until parts.length()) {
                val text = parts.optJSONObject(j)?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(text)
                }
            }
            if (builder.isNotEmpty()) return builder.toString()
        }
        return null
    }

    private companion object {
        private const val MODEL = "gemini-3.1-flash-lite-preview"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val JSON = "application/json; charset=utf-8"
    }
}

class BeamCommandCoordinator(
    private val appContext: Context,
    private val nanoService: BeamGeminiNanoService,
    private val cloudService: BeamGeminiCloudService,
) {
    suspend fun parse(transcript: String, playerState: PlayerStateSnapshot): BeamVoiceParseResult {
        val normalized =
            transcript.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        if (normalized.isBlank()) return BeamVoiceParseResult(XrPlayerAction.Unrecognized(transcript), "blank")

        keywordMatch(normalized, transcript)?.let {
            return BeamVoiceParseResult(it, "keyword")
        }

        val prompt =
            """
            Convert this media-player voice transcript into a single JSON action.
            Return only minified JSON.
            Supported actions:
            play,pause,toggle_play_pause,seek_forward,seek_backward,seek_to,skip_intro,skip_outro,next_episode,previous_episode,set_speed,set_quality,select_audio,select_subtitles,disable_subtitles,search,open_syncplay,create_syncplay,join_syncplay,leave_syncplay,refresh_syncplay,adjust_volume,go_home,close_app,go_back,show_controls,hide_controls,report_current_time,report_remaining_time,report_end_time,report_current_media,chat,unrecognized
            JSON schema:
            {"action":"play","query":"...","seconds":15,"position_seconds":120,"speed":1.25,"max_bitrate":10000000,"language":"english","index":0,"percentage":0.5,"delta":0.1,"group_name":"friends"}
            Title=${playerState.currentItemTitle}
            Series=${playerState.currentSeriesName.orEmpty()}
            Overview=${playerState.currentOverview.take(500)}
            CurrentAudio=${playerState.currentAudioTrack.orEmpty()}
            CurrentSubtitles=${playerState.currentSubtitleTrack.orEmpty()}
            Transcript=$transcript
            """.trimIndent()

        val modelText = nanoService.generateText(prompt, "voice-parse") ?: cloudService.generateText(prompt, "voice-parse")
        val json = modelText?.let(::extractJson)
        if (json != null) {
            runCatching {
                val payload = JSONObject(json)
                return BeamVoiceParseResult(mapModelAction(payload, transcript), "model")
            }.onFailure { Timber.w(it, "VOICE: failed to decode model response %s", modelText) }
        }

        return BeamVoiceParseResult(XrPlayerAction.Unrecognized(transcript), "fallback")
    }

    private fun keywordMatch(text: String, transcript: String): XrPlayerAction? {
        if (text.contains("what happened") || text.contains("who is") || text.contains("plot") || text.contains("about")) {
            return XrPlayerAction.ChatQuery(transcript)
        }
        extractPlaySearchQuery(text)?.let { return XrPlayerAction.Search(it, autoPlay = true) }
        extractSearchQuery(text)?.let { return XrPlayerAction.Search(it) }
        if (listOf("subtitle off", "subtitles off", "disable subtitles", "hide subtitles").any(text::contains)) {
            return XrPlayerAction.DisableSubtitles
        }
        extractLanguageTarget(text, "subtitles?|subs?|captions")?.let { return XrPlayerAction.SelectSubtitleTrack(language = it) }
        extractLanguageTarget(text, "audio|dub|track")?.let { return XrPlayerAction.SelectAudioTrack(language = it) }
        extractVolumeAdjustment(text)?.let { return it }
        extractQualityAdjustment(text)?.let { return it }
        extractSeekToPosition(text)?.let { return XrPlayerAction.SeekTo(it) }

        return when {
            text.matches(Regex("^(play|resume|start|unpause)$")) -> XrPlayerAction.Play
            text.matches(Regex("^(pause|stop|hold)$")) -> XrPlayerAction.Pause
            text.matches(Regex("^(toggle|play pause)$")) -> XrPlayerAction.TogglePlayPause
            text.contains("skip intro") || text.contains("skip opening") -> XrPlayerAction.SkipIntro
            text.contains("skip outro") || text.contains("skip ending") || text.contains("skip credits") -> XrPlayerAction.SkipOutro
            text.contains("next episode") || text.contains("next one") -> XrPlayerAction.NextEpisode
            text.contains("previous episode") || text.contains("last episode") -> XrPlayerAction.PreviousEpisode
            text.contains("show controls") || text.contains("open controls") -> XrPlayerAction.ShowControls
            text.contains("hide controls") || text.contains("close controls") -> XrPlayerAction.HideControls
            text.contains("syncplay") && text.contains("create") -> XrPlayerAction.CreateSyncPlayGroup
            text.contains("syncplay") && text.contains("leave") -> XrPlayerAction.LeaveSyncPlayGroup
            text.contains("syncplay") && text.contains("refresh") -> XrPlayerAction.RefreshSyncPlay
            text.contains("syncplay") -> XrPlayerAction.OpenSyncPlay
            text.contains("go home") || text.contains("home screen") -> XrPlayerAction.GoHome
            text == "back" || text == "go back" -> XrPlayerAction.GoBack
            text.contains("close app") || text.contains("exit app") || text == "quit" -> XrPlayerAction.CloseApp
            text.contains("current time") || text.contains("what time is it") -> XrPlayerAction.ReportCurrentTime
            text.contains("time left") || text.contains("remaining") -> XrPlayerAction.ReportRemainingTime
            text.contains("when does it end") || text.contains("end time") -> XrPlayerAction.ReportEndTime
            text.contains("what am i watching") || text.contains("what is this") -> XrPlayerAction.ReportCurrentMedia
            text.contains("fast forward") || text.contains("skip ahead") || text.contains("go forward") ->
                XrPlayerAction.SeekForward(extractSeconds(text) ?: 15)
            text.contains("rewind") || text.contains("skip back") || text.contains("go back") ->
                XrPlayerAction.SeekBackward(extractSeconds(text) ?: 10)
            Regex(".*speed.*(\\d+\\.?\\d*).*").matches(text) -> {
                Regex("(\\d+\\.?\\d*)").find(text)?.value?.toFloatOrNull()?.let(XrPlayerAction::SetSpeed)
            }
            else -> null
        }
    }

    private fun extractSearchQuery(text: String): String? {
        val query = text.replace(Regex("^(search for|search|find me|find|look up)\\s*"), "").trim()
        return query.takeIf { it.isNotBlank() && it != text }
    }

    private fun extractPlaySearchQuery(text: String): String? {
        val match = Regex("^(play|watch|open|start)\\s+(.+)$").matchEntire(text) ?: return null
        return match.groupValues[2].trim().takeIf { it.isNotBlank() }
    }

    private fun extractLanguageTarget(text: String, anchorPattern: String): String? {
        return Regex("(english|japanese|jpn|eng|spanish|espanol|french|german).*(?:$anchorPattern)|(?:$anchorPattern).*(english|japanese|jpn|eng|spanish|espanol|french|german)")
            .find(text)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
    }

    private fun extractVolumeAdjustment(text: String): XrPlayerAction? {
        if (!text.contains("volume")) return null
        Regex("(\\d+)\\s*(?:percent|%)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
            return XrPlayerAction.AdjustVolume(percentage = it / 100f)
        }
        return when {
            listOf("up", "increase", "louder").any(text::contains) -> XrPlayerAction.AdjustVolume(delta = 0.1f)
            listOf("down", "decrease", "quieter").any(text::contains) -> XrPlayerAction.AdjustVolume(delta = -0.1f)
            else -> null
        }
    }

    private fun extractQualityAdjustment(text: String): XrPlayerAction? {
        if (!text.contains("quality") && !text.contains("bitrate")) return null
        if (text.contains("auto")) return XrPlayerAction.SetQuality(0L)
        Regex("(\\d+)\\s*(mbps|m)?").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            return XrPlayerAction.SetQuality(it * 1_000_000L)
        }
        return when {
            text.contains("higher") || text.contains("better") -> XrPlayerAction.SetQuality(0L)
            text.contains("lower") || text.contains("worse") -> XrPlayerAction.SetQuality(10_000_000L)
            else -> null
        }
    }

    private fun extractSeekToPosition(text: String): Long? {
        val match = Regex("(\\d+):(\\d{2})").find(text) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        return minutes * 60L + seconds
    }

    private fun extractSeconds(text: String): Int? {
        return Regex("(\\d+)\\s*(seconds|second|sec|s|minutes|minute|min|m)").find(text)?.let { match ->
            val value = match.groupValues[1].toIntOrNull() ?: return null
            when (match.groupValues[2]) {
                "minutes", "minute", "min", "m" -> value * 60
                else -> value
            }
        }
    }

    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }

    private fun mapModelAction(payload: JSONObject, transcript: String): XrPlayerAction {
        return when (payload.optString("action").lowercase()) {
            "play" -> XrPlayerAction.Play
            "pause" -> XrPlayerAction.Pause
            "toggle_play_pause" -> XrPlayerAction.TogglePlayPause
            "seek_forward" -> XrPlayerAction.SeekForward(payload.optInt("seconds", 15))
            "seek_backward" -> XrPlayerAction.SeekBackward(payload.optInt("seconds", 10))
            "seek_to" -> XrPlayerAction.SeekTo(payload.optLong("position_seconds"))
            "skip_intro" -> XrPlayerAction.SkipIntro
            "skip_outro" -> XrPlayerAction.SkipOutro
            "next_episode" -> XrPlayerAction.NextEpisode
            "previous_episode" -> XrPlayerAction.PreviousEpisode
            "set_speed" -> XrPlayerAction.SetSpeed(payload.optDouble("speed", 1.0).toFloat())
            "set_quality" -> XrPlayerAction.SetQuality(payload.optLong("max_bitrate"))
            "select_audio" -> XrPlayerAction.SelectAudioTrack(payload.optString("language").ifBlank { null }, payload.optInt("index").takeIf { it >= 0 })
            "select_subtitles" -> XrPlayerAction.SelectSubtitleTrack(payload.optString("language").ifBlank { null }, payload.optInt("index").takeIf { it >= 0 })
            "disable_subtitles" -> XrPlayerAction.DisableSubtitles
            "search" -> XrPlayerAction.Search(payload.optString("query").ifBlank { transcript }, payload.optBoolean("auto_play", false))
            "open_syncplay" -> XrPlayerAction.OpenSyncPlay
            "create_syncplay" -> XrPlayerAction.CreateSyncPlayGroup
            "join_syncplay" -> XrPlayerAction.JoinSyncPlayGroup(payload.optString("group_name").ifBlank { null }, payload.optInt("index").takeIf { it >= 0 })
            "leave_syncplay" -> XrPlayerAction.LeaveSyncPlayGroup
            "refresh_syncplay" -> XrPlayerAction.RefreshSyncPlay
            "adjust_volume" -> XrPlayerAction.AdjustVolume(
                percentage = payload.optDouble("percentage", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                delta = payload.optDouble("delta", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
            )
            "go_home" -> XrPlayerAction.GoHome
            "close_app" -> XrPlayerAction.CloseApp
            "go_back" -> XrPlayerAction.GoBack
            "show_controls" -> XrPlayerAction.ShowControls
            "hide_controls" -> XrPlayerAction.HideControls
            "report_current_time" -> XrPlayerAction.ReportCurrentTime
            "report_remaining_time" -> XrPlayerAction.ReportRemainingTime
            "report_end_time" -> XrPlayerAction.ReportEndTime
            "report_current_media" -> XrPlayerAction.ReportCurrentMedia
            "chat" -> XrPlayerAction.ChatQuery(payload.optString("query").ifBlank { transcript })
            else -> XrPlayerAction.Unrecognized(transcript)
        }
    }
}

private val BEAM_RECOMMENDATION_KEYWORDS = listOf(
    "similar", "recommend", "suggestion", "what else", "what should i watch",
    "watch after", "watch next", "anything else", "like this", "other movies",
    "other shows", "what other", "something similar", "more like",
)

class BeamChatEngine(
    private val appContext: Context,
    private val nanoService: BeamGeminiNanoService,
    private val cloudService: BeamGeminiCloudService,
) {
    suspend fun query(
        question: String,
        playerState: PlayerStateSnapshot,
        recentSubtitles: String = "",
        verbosity: String = "balanced",
        spoilerPolicy: String = "cautious",
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onGetSuggestions: (suspend () -> List<SpatialFinItem>)? = null,
    ): String? {
        // Fast-path: answer unambiguous factual lookups without any model call
        confidenceGatedAnswer(question, playerState)?.let { return it }

        val isRecommendation = BEAM_RECOMMENDATION_KEYWORDS.any { question.lowercase().contains(it) }
        val relatedItemsContext: String? = if (isRecommendation && onGetSuggestions != null) {
            runCatching { onGetSuggestions() }
                .getOrNull()
                ?.take(6)
                ?.joinToString("\n") { "- ${it.name}: ${it.overview.take(120)}" }
                ?.takeIf { it.isNotBlank() }
        } else null

        val historyBlock = if (conversationHistory.isNotEmpty()) {
            "\nConversation history:\n" + conversationHistory.joinToString("\n") { (u, a) ->
                "User: $u\nAssistant: $a"
            } + "\n"
        } else ""

        val relatedBlock = if (relatedItemsContext != null) {
            "\nLibrary suggestions:\n$relatedItemsContext\n"
        } else ""

        val prompt =
            """
            You are SpatialFin on Beam Pro. Answer briefly and directly.
            Spoiler policy: $spoilerPolicy.
            Verbosity: $verbosity.
            Title=${playerState.currentItemTitle}
            Series=${playerState.currentSeriesName.orEmpty()}
            Year=${playerState.productionYear ?: ""}
            Rating=${playerState.officialRating.orEmpty()}
            Overview=${playerState.currentOverview.take(1200)}
            RecentSubtitles=${recentSubtitles.takeLast(1200)}
            Cast=${playerState.castNames.joinToString(", ")}
            Directors=${playerState.directors.joinToString(", ")}
            Writers=${playerState.writers.joinToString(", ")}
            Genres=${playerState.currentGenres.joinToString(", ")}
            CommunityRatings=${playerState.currentRatings.joinToString(", ")}
            $historyBlock$relatedBlock
            Question=$question
            """.trimIndent()
        return nanoService.generateText(prompt, "voice-chat")
            ?: cloudService.generateText(prompt, "voice-chat", temperature = 0.3, maxOutputTokens = 320)
            ?: fallback(question, playerState)
    }

    private fun confidenceGatedAnswer(question: String, playerState: PlayerStateSnapshot): String? {
        val n = question.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").trim()

        if (n.contains("who directed") || (n.contains("director") && !n.contains("style"))) {
            return if (playerState.directors.isNotEmpty()) "Directed by ${playerState.directors.joinToString(", ")}." else null
        }
        if (n.contains("who wrote") || n.contains("written by") || n.contains("writer")) {
            return if (playerState.writers.isNotEmpty()) "Written by ${playerState.writers.joinToString(", ")}." else null
        }
        if (n.contains("what year") || n.contains("year released") || (n.contains("release") && n.contains("year"))) {
            return playerState.productionYear?.let { "Released in $it." }
        }
        if ((n.contains("age rating") || n.contains("content rating") || n.contains("rated")) && !n.contains("how") && !n.contains("why")) {
            return playerState.officialRating?.let { "Rated $it." }
        }
        if (n.matches(Regex(".*\\bwhat (is|am) (i|this|it)\\b.*")) || (n.contains("title") && n.contains("what"))) {
            return playerState.currentItemTitle.takeIf { it.isNotBlank() }?.let { "You're watching $it." }
        }
        return null
    }

    private fun fallback(question: String, playerState: PlayerStateSnapshot): String {
        val normalized = question.lowercase()
        return when {
            normalized.contains("plot") || normalized.contains("about") || normalized.contains("summary") ->
                playerState.currentOverview.ifBlank { "I don't have enough metadata to answer that." }
            normalized.contains("director") && playerState.directors.isNotEmpty() ->
                "Directed by ${playerState.directors.joinToString(", ")}."
            normalized.contains("writer") && playerState.writers.isNotEmpty() ->
                "Written by ${playerState.writers.joinToString(", ")}."
            normalized.contains("who") && playerState.castNames.isNotEmpty() ->
                "Cast: ${playerState.castNames.take(5).joinToString(", ")}."
            normalized.contains("genre") && playerState.currentGenres.isNotEmpty() ->
                "Genres: ${playerState.currentGenres.take(4).joinToString(", ")}."
            normalized.contains("rating") && playerState.currentRatings.isNotEmpty() ->
                "Ratings: ${playerState.currentRatings.joinToString(", ")}."
            else -> playerState.currentOverview.ifBlank { "I don't have enough context to answer that right now." }
        }
    }
}

private fun Iterable<Any>.joinToStringOrBlank(): String = joinToString(", ")
