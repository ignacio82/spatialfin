package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.core.llm.LlmModelManager
import org.json.JSONObject
import timber.log.Timber

enum class VoiceParseStrategy {
    KEYWORD,
    MODEL,
    CLOUD,
    GEMMA,
    FALLBACK,
}

data class VoiceParseResult(
    val action: XrPlayerAction,
    val strategy: VoiceParseStrategy,
    val normalizedTranscript: String,
    val debugInfo: String = "",
)

internal object VoiceReplayCommandLibrary {
    fun match(
        transcript: String,
        normalized: String,
        playerState: PlayerStateSnapshot,
    ): XrPlayerAction? {
        val recommendationFollowUp =
            playerState.lastRecommendationCount > 0 &&
                (
                    normalized in setOf(
                        "shorter",
                        "movie only",
                        "show only",
                        "funny",
                        "not anime",
                        "something new",
                        "with english audio",
                    ) ||
                        normalized.contains("more like the ") ||
                        normalized.contains("more like option ") ||
                        normalized.contains("similar to the ")
                )
        if (recommendationFollowUp) {
            return XrPlayerAction.ChatQuery(transcript)
        }

        return when {
            normalized == "recommend something for me to watch" -> XrPlayerAction.ChatQuery(transcript)
            normalized == "what can i watch next" -> XrPlayerAction.ChatQuery(transcript)
            normalized == "what should i watch next" -> XrPlayerAction.ChatQuery(transcript)
            normalized.startsWith("play the first") || normalized.startsWith("play first") ->
                XrPlayerAction.SelectOption(0)
            normalized.startsWith("play the second") || normalized.startsWith("play second") ->
                XrPlayerAction.SelectOption(1)
            playerState.screenContext == VoiceScreenContext.HOME &&
                normalized in setOf("library", "home library", "go to the library") ->
                XrPlayerAction.GoHome
            else -> null
        }
    }
}

class SpatialCommandCoordinator(
    @Suppress("UNUSED_PARAMETER") private val appContext: Context,
    private val geminiNanoService: GeminiNanoService,
    private val geminiCloudService: GeminiCloudService,
    private val appPreferences: AppPreferences,
    private val modelManager: LlmModelManager,
) {
    private val chatQuestionPhrases = listOf(
        "what happened",
        "what s happened",
        "what just happened",
        "what did they say",
        "what are they saying",
        "what did i miss",
        "what s going on",
        "what is going on",
        "what s happening",
        "what is happening",
        "what is this about",
        "what s this about",
        "tell me about",
        "explain this",
        "can you explain",
        "plot",
        "summarize",
        "summary",
        "story so far",
        "recap",
        "who stars",
        "who are the actors",
        "who directed",
        "who wrote",
        "director",
        "genre",
        "what kind of",
        "what year",
        "when did this come out",
        "when was this made",
        "recommend",
        "suggest",
        "what should i watch",
        "what else should i watch",
        "something similar",
        "more like this",
    )
    private val introSkipPhrases = listOf(
        "skip intro",
        "skip the intro",
        "skip opening",
        "skip the opening",
        "skip recap",
        "skip the recap",
        "skip previously on",
        "skip preview",
        "skip the preview",
    )
    private val outroSkipPhrases = listOf(
        "skip outro",
        "skip the outro",
        "skip ending",
        "skip the ending",
        "skip credits",
        "skip the credits",
    )
    private val volumeUpPhrases = listOf(
        "volume up",
        "turn it up",
        "turn the volume up",
        "increase volume",
        "increase the volume",
        "raise volume",
        "raise the volume",
        "make it louder",
        "get louder",
        "sound up",
    )
    private val volumeDownPhrases = listOf(
        "volume down",
        "turn it down",
        "turn the volume down",
        "decrease volume",
        "decrease the volume",
        "lower volume",
        "lower the volume",
        "make it quieter",
        "make it softer",
        "sound down",
    )

    suspend fun initialize() {
        if (shouldAttemptGemini()) {
            geminiNanoService.initialize()
        }
    }

    suspend fun parse(
        transcript: String,
        playerState: PlayerStateSnapshot = PlayerStateSnapshot(),
    ): VoiceParseResult {
        val normalized =
            transcript.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        if (normalized.isBlank()) {
            return VoiceParseResult(
                action = XrPlayerAction.Unrecognized(transcript),
                strategy = VoiceParseStrategy.FALLBACK,
                normalizedTranscript = normalized,
                debugInfo = "blank transcript",
            )
        }

        exactKeywordMatch(normalized, playerState)?.let {
            return VoiceParseResult(
                action = it,
                strategy = VoiceParseStrategy.KEYWORD,
                normalizedTranscript = normalized,
                debugInfo = "exact keyword matched before model",
            )
        }

        VoiceReplayCommandLibrary.match(transcript, normalized, playerState)?.let {
            return VoiceParseResult(
                action = it,
                strategy = VoiceParseStrategy.KEYWORD,
                normalizedTranscript = normalized,
                debugInfo = "replay library matched",
            )
        }

        // Fast path: if the transcript is clearly a chat/question, skip Gemma command
        // parsing entirely. This saves one full LLM inference (~2–5 s) for the most
        // common voice interactions.
        if (shouldUseGemma() && isLikelyChatQuery(normalized)) {
            return VoiceParseResult(
                action = XrPlayerAction.ChatQuery(transcript),
                strategy = VoiceParseStrategy.KEYWORD,
                normalizedTranscript = normalized,
                debugInfo = "pre-Gemma chat classification",
            )
        }

        if (shouldUseGemma()) {
            modelManager.ensureInitialized()
            val instance = modelManager.instance
            if (instance != null) {
                // Create a fresh parser each time so it always references the live engine
                // instance — a cached parser would hold a stale reference after engine re-init.
                val gemmaAction = GemmaCommandParser(instance).parse(transcript, playerState)
                if (gemmaAction !is XrPlayerAction.Unrecognized) {
                    return VoiceParseResult(
                        action = gemmaAction,
                        strategy = VoiceParseStrategy.GEMMA,
                        normalizedTranscript = normalized,
                        debugInfo = "Gemma command parser matched (${instance.backendName})",
                    )
                }
            }
        }

        keywordMatch(normalized, playerState, transcript)?.let {
            return VoiceParseResult(
                action = it,
                strategy = VoiceParseStrategy.KEYWORD,
                normalizedTranscript = normalized,
                debugInfo = "keyword parser matched",
            )
        }

        val aiAttempt = parseWithModel(transcript, normalized, playerState)
        aiAttempt.result?.let { return it }

        return VoiceParseResult(
            action = XrPlayerAction.Unrecognized(transcript),
            strategy = VoiceParseStrategy.FALLBACK,
            normalizedTranscript = normalized,
            debugInfo = aiAttempt.debugInfo.ifBlank { "model parse unavailable; fallback unrecognized" },
        )
    }

    fun destroy() {
        geminiNanoService.destroy()
    }

    private data class ParseAttemptOutcome(
        val result: VoiceParseResult? = null,
        val debugInfo: String = "",
    )

    private suspend fun parseWithModel(
        transcript: String,
        normalized: String,
        playerState: PlayerStateSnapshot,
    ): ParseAttemptOutcome {
        if (!shouldAttemptGemini()) {
            return ParseAttemptOutcome(
                debugInfo =
                    if (shouldUseGemma()) {
                        "Gemma enabled; Gemini command parsing disabled"
                    } else {
                        "Gemini disabled: cloud API key missing"
                    },
            )
        }

        val prompts =
            listOf(
                buildModelPrompt(transcript, playerState, strictRetry = false),
                buildModelPrompt(transcript, playerState, strictRetry = true),
            )

        var onDeviceDebugInfo = ""
        prompts.forEachIndexed { attemptIndex, prompt ->
            val onDeviceResult =
                geminiNanoService.generateText(
                    prompt = prompt,
                    reason = if (attemptIndex == 0) "voice-parse" else "voice-parse-retry",
                )
            onDeviceDebugInfo = onDeviceResult.status.details
            if (onDeviceResult.usedModel && !onDeviceResult.text.isNullOrBlank()) {
                val json = extractJson(onDeviceResult.text)
                if (json != null) {
                    return runCatching {
                        val payload = JSONObject(json)
                        val action = mapModelAction(payload, transcript)
                        ParseAttemptOutcome(
                            result =
                                VoiceParseResult(
                                    action = action,
                                    strategy = VoiceParseStrategy.MODEL,
                                    normalizedTranscript = normalized,
                                    debugInfo = onDeviceResult.status.details,
                                ),
                            debugInfo = onDeviceResult.status.details,
                        )
                    }.onFailure {
                        Timber.w(
                            it,
                            "GEMINI: failed to parse on-device model response as JSON retry=%s: %s",
                            attemptIndex > 0,
                            onDeviceResult.text,
                        )
                    }.getOrElse { ParseAttemptOutcome(debugInfo = onDeviceResult.status.details) }
                }
            }
        }

        var cloudDebugInfo = ""
        prompts.forEachIndexed { attemptIndex, prompt ->
            val cloudResult =
                geminiCloudService.generateText(
                    prompt = prompt,
                    reason = if (attemptIndex == 0) "voice-parse" else "voice-parse-retry",
                    temperature = 0.0,
                    maxOutputTokens = 220,
                )
            cloudDebugInfo = cloudResult.status.details
            if (cloudResult.status.usedModel && !cloudResult.text.isNullOrBlank()) {
                val cloudJson = extractJson(cloudResult.text)
                if (cloudJson != null) {
                    return runCatching {
                        val payload = JSONObject(cloudJson)
                        val action = mapModelAction(payload, transcript)
                        ParseAttemptOutcome(
                            result =
                                VoiceParseResult(
                                    action = action,
                                    strategy = VoiceParseStrategy.CLOUD,
                                    normalizedTranscript = normalized,
                                    debugInfo = cloudResult.status.details,
                                ),
                            debugInfo = cloudResult.status.details,
                        )
                    }.onFailure {
                        Timber.w(
                            it,
                            "GEMINI: failed to parse cloud model response as JSON retry=%s: %s",
                            attemptIndex > 0,
                            cloudResult.text,
                        )
                    }.getOrElse {
                        ParseAttemptOutcome(
                            debugInfo = "${onDeviceDebugInfo}; ${cloudResult.status.details}",
                        )
                    }
                }
            }
        }

        return ParseAttemptOutcome(debugInfo = "${onDeviceDebugInfo}; ${cloudDebugInfo}")
    }

    private fun exactKeywordMatch(
        text: String,
        playerState: PlayerStateSnapshot,
    ): XrPlayerAction? {
        val playbackActionsAllowed = playerState.screenContext == VoiceScreenContext.PLAYER
        extractVolumeAdjustment(text)?.let { return it }
        extractPassthroughAction(text)?.let { return it }
        extractSizeAdjustment(text)?.let { return it }
        extractStatusAction(text)?.let { return it }

        if (isSubtitleEnableCommand(text)) return XrPlayerAction.SelectSubtitleTrack()
        if (isSubtitleDisableCommand(text)) return XrPlayerAction.DisableSubtitles
        if (isGenericAudioSwitchCommand(text)) return XrPlayerAction.SelectAudioTrack()

        val subtitleLanguage = extractLanguageTarget(text, "subtitles?|subs?")
        if (subtitleLanguage != null) return XrPlayerAction.SelectSubtitleTrack(language = subtitleLanguage)

        val audioLanguage = extractLanguageTarget(text, "audio|dub|track")
        if (audioLanguage != null) return XrPlayerAction.SelectAudioTrack(language = audioLanguage)

        return when {
            playbackActionsAllowed && text.matches(Regex("^(play|resume|start|unpause)$")) -> XrPlayerAction.Play
            playbackActionsAllowed && text.matches(Regex("^(pause|stop|hold)$")) -> XrPlayerAction.Pause
            playbackActionsAllowed && text.matches(Regex("^(toggle|play pause)$")) -> XrPlayerAction.TogglePlayPause
            playbackActionsAllowed && text.matches(Regex("^(skip intro|skip the intro|skip opening|skip the opening|skip recap|skip the recap|skip previously on|skip preview|skip the preview)$")) -> XrPlayerAction.SkipIntro
            playbackActionsAllowed && text.matches(Regex("^(skip outro|skip the outro|skip ending|skip the ending|skip credits|skip the credits)$")) -> XrPlayerAction.SkipOutro
            playbackActionsAllowed && text.matches(Regex("^(play |go to |start )?(the )?(next episode|next one)$")) -> XrPlayerAction.NextEpisode
            playbackActionsAllowed && text.matches(Regex("^(play |go to |start )?(the )?(previous episode|last episode|go back one)$")) -> XrPlayerAction.PreviousEpisode
            playbackActionsAllowed && text.matches(Regex("^(show controls|open controls)$")) -> XrPlayerAction.ShowControls
            playbackActionsAllowed && text.matches(Regex("^(hide controls|close controls)$")) -> XrPlayerAction.HideControls
            text.matches(Regex("^(go home|home screen|library|back to home)$")) -> XrPlayerAction.GoHome
            text.matches(Regex("^(close (the )?app|exit (the )?app|quit (the )?app|shut down|close spatial ?fin|exit spatial ?fin|close (the )?application|exit (the )?application)$")) -> XrPlayerAction.CloseApp
            text.matches(Regex("^(exit|quit|back|go back)$")) -> if (text.matches(Regex("^(back|go back)$"))) XrPlayerAction.GoBack else XrPlayerAction.CloseApp
            else -> null
        }
    }

    private fun keywordMatch(
        text: String,
        playerState: PlayerStateSnapshot,
        transcript: String,
    ): XrPlayerAction? {
        val playbackActionsAllowed = playerState.screenContext == VoiceScreenContext.PLAYER
        extractSyncPlayAction(text)?.let { return it }
        extractSelectionAction(text)?.let { return it }

        extractPassthroughAction(text)?.let { return it }
        extractStatusAction(text)?.let { return it }

        if (text.matches(Regex(".*(skip (the )?(recap|previously on|previously)).*"))) {
            return XrPlayerAction.SkipIntro
        }

        if (playerState.lastRecommendationCount > 0 && isRecommendationFollowUp(text)) {
            return XrPlayerAction.ChatQuery(transcript)
        }

        // Prioritize Chat Queries to avoid greedy navigation matches (e.g. "previous episode about")
        if (isLikelyChatQuery(text)) {
            return XrPlayerAction.ChatQuery(transcript)
        }

        val playSearchQuery = extractPlaySearchQuery(text)
        if (playSearchQuery != null) return XrPlayerAction.Search(playSearchQuery, autoPlay = true)

        val searchQuery = extractSearchQuery(text)
        if (searchQuery != null) return XrPlayerAction.Search(searchQuery)

        if (isSubtitleEnableCommand(text)) return XrPlayerAction.SelectSubtitleTrack()

        val subtitleLanguage = extractLanguageTarget(text, "subtitles?|subs?")
        if (subtitleLanguage != null) return XrPlayerAction.SelectSubtitleTrack(language = subtitleLanguage)

        if (isGenericAudioSwitchCommand(text)) return XrPlayerAction.SelectAudioTrack()

        val audioLanguage = extractLanguageTarget(text, "audio|dub|track")
        if (audioLanguage != null) return XrPlayerAction.SelectAudioTrack(language = audioLanguage)

        if (isSubtitleDisableCommand(text)) return XrPlayerAction.DisableSubtitles

        return when {
            playbackActionsAllowed && text.matches(Regex("^(play|resume|start|unpause)$")) -> XrPlayerAction.Play
            playbackActionsAllowed && text.matches(Regex("^(pause|stop|hold)$")) -> XrPlayerAction.Pause
            playbackActionsAllowed && text.matches(Regex("^(toggle|play pause)$")) -> XrPlayerAction.TogglePlayPause
            playbackActionsAllowed && text.matches(Regex(".*(next chapter|skip chapter).*")) -> {
                playerState.chapterNames
                    .takeIf { it.isNotEmpty() }
                    ?.let { XrPlayerAction.SeekForward(60) }
            }
            playbackActionsAllowed && introSkipPhrases.any(text::contains) -> XrPlayerAction.SkipIntro
            playbackActionsAllowed && outroSkipPhrases.any(text::contains) -> XrPlayerAction.SkipOutro
            playbackActionsAllowed && text.matches(Regex("^(play |go to |start )?(the )?(next episode|next one)$")) -> XrPlayerAction.NextEpisode
            playbackActionsAllowed && text.matches(Regex("^(play |go to |start )?(the )?(previous episode|last episode|go back one)$")) -> {
                XrPlayerAction.PreviousEpisode
            }
            playbackActionsAllowed && text.matches(Regex(".*(show controls|open controls).*")) -> XrPlayerAction.ShowControls
            playbackActionsAllowed && text.matches(Regex(".*(hide controls|close controls).*")) -> XrPlayerAction.HideControls
            text.matches(Regex(".*(go home|home screen|library|back to home).*")) -> XrPlayerAction.GoHome
            text.matches(
                Regex(
                    ".*(close (the )?app|exit (the )?app|quit (the )?app|shut down|close spatial ?fin|exit spatial ?fin|close (the )?application|exit (the )?application).*"
                )
            ) -> XrPlayerAction.CloseApp
            text.matches(Regex("^(exit|quit)$")) -> XrPlayerAction.CloseApp
            text.matches(Regex("^(back|go back)$")) -> XrPlayerAction.GoBack
            extractVolumeAdjustment(text) != null -> extractVolumeAdjustment(text)
            extractQualityAdjustment(text) != null -> extractQualityAdjustment(text)
            playbackActionsAllowed && extractSeekToPosition(text) != null -> XrPlayerAction.SeekTo(extractSeekToPosition(text)!!)
            playbackActionsAllowed && text.matches(Regex(".*(skip|fast[ -]?forward|go forward|jump ahead).*")) -> {
                XrPlayerAction.SeekForward(extractSeconds(text) ?: 15)
            }
            playbackActionsAllowed && text.matches(Regex(".*(rewind|go back|skip back|jump back).*")) -> {
                XrPlayerAction.SeekBackward(extractSeconds(text) ?: 10)
            }
            playbackActionsAllowed && text.matches(Regex(".*speed.*(\\d+\\.?\\d*).*")) -> {
                val speed = Regex("(\\d+\\.?\\d*)").find(text)?.value?.toFloatOrNull()
                if (speed != null && speed in 0.25f..4.0f) XrPlayerAction.SetSpeed(speed) else null
            }
            playbackActionsAllowed && text.matches(Regex(".*(normal speed|reset speed|one ?x|1x).*")) -> XrPlayerAction.SetSpeed(1.0f)
            playbackActionsAllowed && text.matches(Regex(".*(double speed|two ?x|2x).*")) -> XrPlayerAction.SetSpeed(2.0f)
            playbackActionsAllowed && text.matches(Regex(".*(half speed|0\\.5x).*")) -> XrPlayerAction.SetSpeed(0.5f)
            else -> null
        }
    }

    private fun buildModelPrompt(
        transcript: String,
        playerState: PlayerStateSnapshot,
        strictRetry: Boolean,
    ): String {
        val screenGuidance =
            when (playerState.screenContext) {
                VoiceScreenContext.HOME ->
                    "HOME screen: prefer search, chat, go_home, and go_back. Avoid playback-only actions unless explicit."
                VoiceScreenContext.PLAYER ->
                    "PLAYER screen: prefer playback, subtitle, audio, timeline, and recap actions when clearly requested."
            }
        val retryGuidance =
            if (strictRetry) {
                "Previous output was invalid or not parseable JSON. Return exactly one minified JSON object with no commentary."
            } else {
                ""
            }
        return """
            You convert XR media-player voice transcripts into a single JSON action.
            Return ONLY minified JSON.
            Supported action values:
            play,pause,toggle_play_pause,seek_forward,seek_backward,seek_to,skip_intro,skip_outro,next_episode,previous_episode,set_speed,set_quality,select_audio,select_subtitles,disable_subtitles,search,select_option,open_syncplay,create_syncplay,join_syncplay,leave_syncplay,refresh_syncplay,adjust_volume,adjust_scale,adjust_distance,go_home,close_app,go_back,show_controls,hide_controls,report_current_time,report_remaining_time,report_end_time,report_current_media,report_passthrough_status,set_passthrough,toggle_passthrough,chat,unrecognized

            Rules:
            - Prefer a direct player action when the transcript clearly asks for one.
            - Use "chat" for questions, recommendations, clarifications, recaps, or metadata questions.
            - Recommendation refinements like "shorter", "movie only", "show only", "funny", "not anime", "something new", "with english audio", or "more like the second one" are always "chat".
            - Use "search" for find/search/show me requests.
            - If unsure, use "chat" rather than "unrecognized".
            - Include only fields that matter for the chosen action.
            - $screenGuidance
            - $retryGuidance

            JSON field schema:
            {"action":"...","query":"...","seconds":15,"position_seconds":120,"speed":1.5,"max_bitrate":10000000,"language":"english","index":0,"percentage":0.5,"delta":0.1,"group_name":"friends","enabled":true,"reset":false}

            Current player state:
            screenContext=${playerState.screenContext}
            title=${playerState.currentItemTitle}
            series=${playerState.currentSeriesName ?: ""}
            overview=${playerState.currentOverview.take(600)}
            chapter=${playerState.currentChapterName ?: ""}
            genres=${playerState.currentGenres.joinToString(",")}
            audioTracks=${playerState.audioTrackNames.joinToString(",")}
            subtitleTracks=${playerState.subtitleTrackNames.joinToString(",")}
            currentAudio=${playerState.currentAudioTrack ?: ""}
            currentSubtitles=${playerState.currentSubtitleTrack ?: ""}
            controlsVisible=${playerState.controlsVisible}
            syncPlayActive=${playerState.syncPlayActive}
            syncPlayGroup=${playerState.syncPlayGroupName ?: ""}
            voiceSearchCount=${playerState.voiceSearchResultsCount}
            lastRecommendationQuery=${playerState.lastRecommendationQuery ?: ""}
            lastRecommendationCount=${playerState.lastRecommendationCount}
            lastRecommendationTitles=${playerState.lastRecommendationTitles.joinToString(",")}
            passthroughEnabled=${playerState.passthroughEnabled}

            Transcript:
            $transcript
        """.trimIndent()
    }

    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return raw.substring(start, end + 1)
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
            "select_audio" -> XrPlayerAction.SelectAudioTrack(
                language = payload.optString("language").ifBlank { null },
                index = payload.optInt("index").takeIf { it >= 0 },
            )
            "select_subtitles" -> XrPlayerAction.SelectSubtitleTrack(
                language = payload.optString("language").ifBlank { null },
                index = payload.optInt("index").takeIf { it >= 0 },
            )
            "disable_subtitles" -> XrPlayerAction.DisableSubtitles
            "search" -> XrPlayerAction.Search(
                query = payload.optString("query").ifBlank { transcript },
                autoPlay = payload.optBoolean("auto_play", false),
            )
            "select_option" -> XrPlayerAction.SelectOption(payload.optInt("index", 0))
            "open_syncplay" -> XrPlayerAction.OpenSyncPlay
            "create_syncplay" -> XrPlayerAction.CreateSyncPlayGroup
            "join_syncplay" -> XrPlayerAction.JoinSyncPlayGroup(
                groupName = payload.optString("group_name").ifBlank { null },
                selectionIndex = payload.optInt("index").takeIf { it >= 0 },
            )
            "leave_syncplay" -> XrPlayerAction.LeaveSyncPlayGroup
            "refresh_syncplay" -> XrPlayerAction.RefreshSyncPlay
            "adjust_volume" -> XrPlayerAction.AdjustVolume(
                percentage = payload.optDouble("percentage", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                delta = payload.optDouble("delta", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
            )
            "adjust_scale" -> XrPlayerAction.AdjustScale(
                delta = payload.optDouble("delta", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                reset = payload.optBoolean("reset", false),
            )
            "adjust_distance" -> XrPlayerAction.AdjustDistance(
                delta = payload.optDouble("delta", Double.NaN).takeUnless(Double::isNaN)?.toFloat(),
                reset = payload.optBoolean("reset", false),
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
            "report_passthrough_status" -> XrPlayerAction.ReportPassthroughStatus
            "set_passthrough" -> XrPlayerAction.SetPassthrough(payload.optBoolean("enabled", true))
            "toggle_passthrough" -> XrPlayerAction.TogglePassthrough
            "chat" -> XrPlayerAction.ChatQuery(payload.optString("query").ifBlank { transcript })
            else -> XrPlayerAction.Unrecognized(transcript)
        }
    }

    private fun extractSyncPlayAction(text: String): XrPlayerAction? {
        val selectionIndex = extractOrdinalIndex(text)
        if (
            selectionIndex != null &&
                (text.contains("syncplay") || text.contains("watch party") || text.contains("group")) &&
                text.contains("join")
        ) {
            return XrPlayerAction.JoinSyncPlayGroup(selectionIndex = selectionIndex)
        }

        return when {
            Regex(".*(create|start|make).*(syncplay|watch party|watch group).*").matches(text) ->
                XrPlayerAction.CreateSyncPlayGroup
            Regex(".*(leave|exit|quit).*(syncplay|watch party|watch group).*").matches(text) ->
                XrPlayerAction.LeaveSyncPlayGroup
            Regex(".*(refresh|reload|update).*(syncplay|watch party|watch group|groups).*").matches(text) ->
                XrPlayerAction.RefreshSyncPlay
            Regex(".*(open|show).*(syncplay|watch party|watch group|groups).*").matches(text) ->
                XrPlayerAction.OpenSyncPlay
            Regex("^join\\s+(syncplay|watch party|watch group|group)$").matches(text) ->
                XrPlayerAction.JoinSyncPlayGroup()
            Regex("^join\\s+(.+)$").matchEntire(text)?.groupValues?.getOrNull(1)
                ?.takeIf {
                    !it.contains("episode") &&
                        !it.contains("movie") &&
                        !it.contains("result") &&
                        !it.contains("one")
                } != null -> {
                val target = Regex("^join\\s+(.+)$").matchEntire(text)?.groupValues?.get(1).orEmpty()
                    .replace(Regex("^(the\\s+)?(syncplay|watch party|watch group|group)\\s+"), "")
                    .trim()
                if (target.isBlank()) XrPlayerAction.JoinSyncPlayGroup() else XrPlayerAction.JoinSyncPlayGroup(groupName = target)
            }
            text.contains("syncplay") || text.contains("watch party") || text.contains("watch group") ->
                XrPlayerAction.OpenSyncPlay
            else -> null
        }
    }

    private fun isLikelyChatQuery(text: String): Boolean {
        if (chatQuestionPhrases.any(text::contains)) return true
        if (text.contains("more like") || text.contains("similar to")) return true

        return listOf(
            "who ",
            "who s ",
            "who is ",
            "who are ",
            "who was ",
            "what ",
            "what s ",
            "what is ",
            "why ",
            "why s ",
            "why is ",
            "how ",
            "how s ",
            "how is ",
        ).any(text::startsWith)
    }

    private fun isRecommendationFollowUp(text: String): Boolean {
        return listOf(
            "more like",
            "similar to",
            "shorter",
            "movie only",
            "show only",
            "funny",
            "not anime",
            "something new",
            "with english audio",
        ).any(text::contains)
    }

    private fun shouldUseGemma(): Boolean =
        appPreferences.getValue(appPreferences.voiceAssistantGemmaEnabled)

    private fun hasGeminiApiKey(): Boolean =
        !appPreferences.getValue(appPreferences.voiceAssistantCloudApiKey).orEmpty().trim().isBlank()

    private fun shouldAttemptGemini(): Boolean = hasGeminiApiKey() && !shouldUseGemma()

    private fun extractSelectionAction(text: String): XrPlayerAction? {
        val selectionIndex = extractOrdinalIndex(text) ?: return null
        if (Regex(".*(play|watch|open).*(one|result|item|option).*").matches(text)) {
            return XrPlayerAction.SelectOption(selectionIndex)
        }
        if (Regex(".*(join).*(one|result|item|option|group|party).*").matches(text)) {
            return XrPlayerAction.JoinSyncPlayGroup(selectionIndex = selectionIndex)
        }
        if (Regex("^(the\\s+)?(first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th|sixth|6th|seventh|7th|eighth|8th|ninth|9th)(\\s+(one|result|item|option|group|party))?$").matches(text)) {
            return XrPlayerAction.SelectOption(selectionIndex)
        }
        return null
    }

    private fun extractPassthroughAction(text: String): XrPlayerAction? {
        val mentionsPassthrough =
            listOf("passthrough", "pass through", "see through", "mixed reality").any(text::contains)
        if (!mentionsPassthrough) return null
        return when {
            listOf("turn on", "enable", "show", "start").any(text::contains) -> XrPlayerAction.SetPassthrough(true)
            listOf("turn off", "disable", "hide", "stop").any(text::contains) -> XrPlayerAction.SetPassthrough(false)
            text.contains("toggle") || text.contains("switch") -> XrPlayerAction.TogglePassthrough
            text.contains("is") || text.contains("status") || text.contains("are we in") -> XrPlayerAction.ReportPassthroughStatus
            else -> null
        }
    }

    private fun extractStatusAction(text: String): XrPlayerAction? {
        return when {
            Regex(".*\\b(what time is it|current time|tell me the time)\\b.*").matches(text) ->
                XrPlayerAction.ReportCurrentTime
            Regex(".*\\b(how much time (is )?(left|remaining)|time left|time remaining|how long is left)\\b.*").matches(text) ->
                XrPlayerAction.ReportRemainingTime
            Regex(".*\\b(when does (this|it|the episode|the movie) end|what time does (this|it|the episode|the movie) end|when will (this|it) end)\\b.*").matches(text) ->
                XrPlayerAction.ReportEndTime
            Regex(".*\\b(what am i watching|what is this|which episode is this|what episode is this|what movie is this|what chapter is this)\\b.*").matches(text) ->
                XrPlayerAction.ReportCurrentMedia
            else -> null
        }
    }

    private fun isSubtitleDisableCommand(text: String): Boolean {
        val mentionsSubtitles =
            listOf("subtitle", "subtitles", "subs").any { token -> text.contains(token) }
        val mentionsDisableIntent =
            listOf("off", "disable", "remove", "hide", "without", "no").any { token ->
                text.contains(token)
            }
        return mentionsSubtitles && mentionsDisableIntent
    }

    private fun isSubtitleEnableCommand(text: String): Boolean {
        val mentionsSubtitles =
            listOf("subtitle", "subtitles", "subs", "captions").any { token -> text.contains(token) }
        val mentionsEnableIntent =
            listOf("on", "enable", "show", "turn on", "restore", "back on").any { token ->
                text.contains(token)
            }
        return mentionsSubtitles && mentionsEnableIntent
    }

    private fun isGenericAudioSwitchCommand(text: String): Boolean {
        return listOf(
            "switch audio",
            "change audio",
            "other audio",
            "next audio",
            "switch dub",
            "change dub",
        ).any(text::contains)
    }

    private fun extractSizeAdjustment(text: String): XrPlayerAction? {
        return when {
            text.matches(Regex(".*(make it|make the video|make screen|make the screen).*bigger.*")) ||
                text.matches(Regex(".*(increase size|zoom in|larger).*")) ->
                XrPlayerAction.AdjustScale(delta = 0.1f)
            text.matches(Regex(".*(make it|make the video|make screen|make the screen).*smaller.*")) ||
                text.matches(Regex(".*(decrease size|zoom out).*")) ->
                XrPlayerAction.AdjustScale(delta = -0.1f)
            text.matches(Regex(".*(reset size|reset screen size|normal size).*")) ->
                XrPlayerAction.AdjustScale(reset = true)
            text.matches(Regex(".*(make it|make the video|make screen|make the screen).*closer.*")) ||
                text.matches(Regex(".*(bring it closer|move closer).*")) ->
                XrPlayerAction.AdjustDistance(delta = -0.2f)
            text.matches(Regex(".*(make it|make the video|make screen|make the screen).*further.*")) ||
                text.matches(Regex(".*(push it back|move further|move farther|push back).*")) ->
                XrPlayerAction.AdjustDistance(delta = 0.2f)
            text.matches(Regex(".*(reset distance|normal distance).*")) ->
                XrPlayerAction.AdjustDistance(reset = true)
            else -> null
        }
    }

    private fun extractVolumeAdjustment(text: String): XrPlayerAction? {
        val mentionsVolumeIntent =
            text.contains("volume") ||
                text.contains("sound") ||
                volumeUpPhrases.any(text::contains) ||
                volumeDownPhrases.any(text::contains)
        if (mentionsVolumeIntent) {
            val percentageMatch = Regex("(\\d+)\\s*(?:percent|%)").find(text)
            if (percentageMatch != null) {
                val value = percentageMatch.groupValues[1].toFloatOrNull()
                if (value != null) return XrPlayerAction.AdjustVolume(percentage = value / 100f)
            }
            if (
                text.contains("up") ||
                    text.contains("increase") ||
                    text.contains("raise") ||
                    text.contains("louder") ||
                    volumeUpPhrases.any(text::contains)
            ) {
                return XrPlayerAction.AdjustVolume(delta = 0.1f)
            }
            if (
                text.contains("down") ||
                    text.contains("decrease") ||
                    text.contains("lower") ||
                    text.contains("quieter") ||
                    text.contains("softer") ||
                    volumeDownPhrases.any(text::contains)
            ) {
                return XrPlayerAction.AdjustVolume(delta = -0.1f)
            }
        }
        return null
    }

    private val bitrates = listOf(
        0L, 120_000_000L, 80_000_000L, 60_000_000L, 40_000_000L, 30_000_000L, 20_000_000L,
        15_000_000L, 10_000_000L, 8_000_000L, 6_000_000L, 5_000_000L, 4_000_000L, 3_000_000L,
        2_000_000L, 1_500_000L, 1_000_000L, 720_000L, 480_000L
    )

    private fun extractQualityAdjustment(text: String): XrPlayerAction? {
        if (!text.contains("quality") && !text.contains("bitrate")) return null

        if (text.contains("increase") || text.contains("higher") || text.contains("better") || text.contains("up")) {
            return XrPlayerAction.SetQuality(0L) // Set to Auto for best quality
        }
        if (text.contains("decrease") || text.contains("lower") || text.contains("worse") || text.contains("down")) {
            return XrPlayerAction.SetQuality(10_000_000L) // 10 Mbps as a safe lower default
        }

        if (text.contains("auto")) return XrPlayerAction.SetQuality(0L)

        val numberMatch = Regex("(\\d+)\\s*(mbps|kbps|m|k)?").find(text)
        if (numberMatch != null) {
            val value = numberMatch.groupValues[1].toLongOrNull() ?: return null
            val unit = numberMatch.groupValues[2]
            val bitrate = when (unit) {
                "mbps", "m" -> value * 1_000_000L
                "kbps", "k" -> value * 1_000L
                else -> if (value < 500) value * 1_000_000L else value // Assume Mbps if small number
            }
            // Find closest matching bitrate from our list
            val closest = bitrates.minByOrNull { Math.abs(it - bitrate) } ?: bitrate
            return XrPlayerAction.SetQuality(closest)
        }

        return null
    }

    private fun extractSearchQuery(text: String): String? {
        val query =
            text.replace(Regex("^(search for|search|find me|find|look up)\\s*"), "").trim()
        return if (query != text && query.isNotBlank()) query else null
    }

    private fun extractPlaySearchQuery(text: String): String? {
        val match = Regex("^(play|watch|open|start)\\s+(.+)$").matchEntire(text) ?: return null
        val query = match.groupValues[2].trim()
        return query.takeIf {
            it.isNotBlank() &&
                it !in setOf("resume", "pause", "next", "previous", "back", "intro", "outro", "controls")
        }
    }

    private fun extractLanguageTarget(text: String, target: String): String? {
        val match =
            Regex(
                    ".*(?:turn on|switch to|use|enable|set)\\s+([a-z\\- ]+)\\s+(?:$target).*"
                )
                .matchEntire(text)
                ?: Regex(".*(?:$target)\\s+(?:to\\s+)?([a-z\\- ]+).*").matchEntire(text)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun extractSeconds(text: String): Int? {
        Regex("(\\d+)\\s*(seconds?|secs?|s\\b)").find(text)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("(\\d+)\\s*(minutes?|mins?|m\\b)").find(text)?.let {
            return (it.groupValues[1].toIntOrNull() ?: return null) * 60
        }
        Regex("\\b(\\d{1,3})\\b").find(text)?.let {
            val value = it.groupValues[1].toIntOrNull() ?: return null
            if (value in 1..600) return value
        }
        return null
    }

    private fun extractSeekToPosition(text: String): Long? {
        if (!text.contains("seek to") && !text.contains("go to") && !text.contains("jump to")) {
            return null
        }
        val hours = Regex("(\\d+)\\s*(hours?|hrs?|h\\b)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("(\\d+)\\s*(minutes?|mins?|m\\b)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val seconds = Regex("(\\d+)\\s*(seconds?|secs?|s\\b)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val total = hours * 3600 + minutes * 60 + seconds
        return total.takeIf { it > 0L }
    }

    private fun extractOrdinalIndex(text: String): Int? {
        val wordMatch =
            Regex("\\b(first|1st|second|2nd|third|3rd|fourth|4th|fifth|5th|sixth|6th|seventh|7th|eighth|8th|ninth|9th)\\b")
                .find(text)
                ?.value
                ?.lowercase()
        if (wordMatch != null) {
            return when (wordMatch) {
                "first", "1st" -> 0
                "second", "2nd" -> 1
                "third", "3rd" -> 2
                "fourth", "4th" -> 3
                "fifth", "5th" -> 4
                "sixth", "6th" -> 5
                "seventh", "7th" -> 6
                "eighth", "8th" -> 7
                "ninth", "9th" -> 8
                else -> null
            }
        }

        return Regex("\\b([1-9])\\b").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)
    }
}
