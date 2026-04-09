package dev.jdtech.jellyfin.player.xr.voice

import com.google.ai.edge.litertlm.ExperimentalApi
import dev.jdtech.jellyfin.core.llm.LlmChatModelHelper
import dev.jdtech.jellyfin.core.llm.LlmInferenceProfile
import dev.jdtech.jellyfin.core.llm.LlmModelInstance
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Dedicated parser that uses a private Gemma conversation to map voice transcripts
 * into structured JSON commands.
 */
class GemmaCommandParser(private val llmInstance: LlmModelInstance) {

    @OptIn(ExperimentalApi::class)
    suspend fun parse(
        transcript: String,
        playerState: PlayerStateSnapshot
    ): XrPlayerAction = withContext(Dispatchers.IO) {
        val prompts =
            listOf(
                buildCommandPrompt(transcript, playerState, strictRetry = false),
                buildCommandPrompt(transcript, playerState, strictRetry = true),
            )

        prompts.forEachIndexed { index, prompt ->
            try {
                val responseText = suspendCoroutine<String> { continuation ->
                    val sb = StringBuilder()
                    LlmChatModelHelper.runInference(
                        instance = llmInstance,
                        prompt = prompt,
                        profile = LlmInferenceProfile.COMMAND,
                    ) { text, isDone ->
                        if (isDone) {
                            continuation.resume(sb.toString())
                        } else {
                            sb.append(text)
                        }
                    }
                }
                Timber.d(
                    "GemmaCommandParser: raw response retry=%s response=%s",
                    index > 0,
                    responseText,
                )
                val json = extractJson(responseText)
                if (json != null) {
                    val payload = JSONObject(json)
                    return@withContext mapModelAction(payload, transcript)
                }
            } catch (e: Exception) {
                Timber.e(
                    e,
                    "GemmaCommandParser: parse attempt failed retry=%s transcript=%s",
                    index > 0,
                    transcript,
                )
            }
        }

        XrPlayerAction.Unrecognized(transcript)
    }

    private fun buildCommandPrompt(
        transcript: String,
        playerState: PlayerStateSnapshot,
        strictRetry: Boolean,
    ): String {
        val screenGuidance =
            when (playerState.screenContext) {
                VoiceScreenContext.HOME ->
                    """
                    Screen context is HOME.
                    Prefer search, chat, go_home, or go_back intents.
                    Do not choose playback-only actions unless the user explicitly asks for one.
                    """.trimIndent()

                VoiceScreenContext.PLAYER ->
                    """
                    Screen context is PLAYER.
                    Prefer playback, subtitle, audio, timeline, and recap actions when clearly requested.
                    """.trimIndent()
            }
        val retryGuidance =
            if (strictRetry) {
                """
                Previous output was invalid or truncated.
                Return EXACTLY one minified JSON object on a single line.
                Never include markdown, quotes around the whole object, or commentary.
                If you are uncertain, use {"action":"chat","query":"$transcript"}.
                """.trimIndent()
            } else {
                ""
            }
        return """
            You are a high-precision command parser for an XR media player.
            Your task is to convert the user's voice transcript into a single, valid JSON object.
            
            Rules:
            - Return ONLY the JSON object. Do not include any other text or explanation.
            - If the request is a question about the media, metadata, recommendations, summaries, or recaps (e.g. "what happened", "what did they say", "what should I watch"), use action "chat".
            - If the user is refining recommendations with phrases like "shorter", "movie only", "show only", "funny", "not anime", "something new", "with english audio", or "more like the second one", use action "chat".
            - If recommendation results are visible and the user says "play the first one" or "open the second result", use action "select_option" with the matching index.
            - If the request is for version selection (e.g. "the 3D one", "the smaller one"), use "select_version" with a query.
            - If the request specifies both audio and subtitles, pick one as the primary action and put the other in "secondary_action".
            - $screenGuidance
            $retryGuidance
            
            JSON schema:
            {
              "action": "play|pause|toggle_play_pause|seek_forward|seek_backward|seek_to|skip_intro|skip_outro|next_episode|previous_episode|set_speed|set_quality|select_audio|select_subtitles|disable_subtitles|search|select_option|open_syncplay|join_syncplay|adjust_volume|adjust_scale|adjust_distance|go_home|close_app|go_back|show_controls|hide_controls|report_current_time|report_remaining_time|report_current_media|set_passthrough|toggle_passthrough|chat|select_version|unrecognized",
              "query": "string (for search, chat, or version selection)",
              "seconds": number,
              "position_seconds": number,
              "speed": number,
              "max_bitrate": number,
              "language": "string",
              "index": number,
              "percentage": number,
              "delta": number,
              "enabled": boolean,
              "reset": boolean,
              "secondary_action": { "action": "...", "language": "..." }
            }

            Examples:
            - "summarize the last 10 seconds" -> {"action": "chat", "query": "summarize the last 10 seconds"}
            - "recommend something for me" -> {"action": "chat", "query": "recommend something for me"}
            - "what should i watch next" -> {"action": "chat", "query": "what should i watch next"}
            - "play the 3D one" -> {"action": "select_version", "query": "3d"}
            - "pause the video" -> {"action": "pause"}
            - "seek forward 30 seconds" -> {"action": "seek_forward", "seconds": 30}
            - "switch to japanese with english subtitles" -> {"action": "select_audio", "language": "japanese", "secondary_action": {"action": "select_subtitles", "language": "english"}}

            Current context:
            screenContext=${playerState.screenContext}
            title=${playerState.currentItemTitle}
            syncPlayActive=${playerState.syncPlayActive}
            voiceResultsCount=${playerState.voiceSearchResultsCount}
            lastRecommendationQuery=${playerState.lastRecommendationQuery ?: ""}
            lastRecommendationCount=${playerState.lastRecommendationCount}
            lastRecommendationTitles=${playerState.lastRecommendationTitles.joinToString(", ")}

            Transcript:
            "$transcript"
        """.trimIndent()
    }

    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        
        // Gemma sometimes returns markdown code blocks or conversational filler.
        // And sometimes it returns multiple JSON objects if it's confused. We want the first valid-looking one.
        val jsonStr = raw.substring(start, end + 1)
        
        // If there's an inner closing brace that balances the first opening brace, 
        // we might have concatenated JSONs. Let's try to parse it safely.
        try {
            // Test parse
            JSONObject(jsonStr)
            return jsonStr
        } catch (e: Exception) {
            // It might be something like {"action": "chat"} \n {"action": "chat"}
            // Let's try to find the FIRST balanced JSON object.
            var depth = 0
            var firstValidEnd = -1
            for (i in start..end) {
                if (raw[i] == '{') depth++
                else if (raw[i] == '}') {
                    depth--
                    if (depth == 0) {
                        firstValidEnd = i
                        break
                    }
                }
            }
            if (firstValidEnd != -1 && firstValidEnd > start) {
                return raw.substring(start, firstValidEnd + 1)
            }
        }
        return null
    }

    private fun mapModelAction(payload: JSONObject, transcript: String): XrPlayerAction {
        val actionStr = payload.optString("action").lowercase()
        
        // Handle select_version as a special case for disambiguation
        if (actionStr == "select_version") {
            return XrPlayerAction.ResolveDisambiguation(
                query = payload.optString("query").ifBlank { transcript },
                originalTranscript = transcript,
            )
        }

        val secondary = payload.optJSONObject("secondary_action")?.let { 
            mapModelAction(it, transcript) 
        }

        return when (actionStr) {
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
                index = payload.optInt("index", -1).takeIf { it >= 0 },
                secondaryAction = secondary,
            )
            "select_subtitles" -> XrPlayerAction.SelectSubtitleTrack(
                language = payload.optString("language").ifBlank { null },
                index = payload.optInt("index", -1).takeIf { it >= 0 },
                secondaryAction = secondary,
            )
            "disable_subtitles" -> XrPlayerAction.DisableSubtitles
            "search" -> XrPlayerAction.Search(
                query = payload.optString("query").ifBlank { transcript },
                autoPlay = payload.optBoolean("auto_play", false),
            )
            "select_option" -> XrPlayerAction.SelectOption(payload.optInt("index", 0))
            "open_syncplay" -> XrPlayerAction.OpenSyncPlay
            "join_syncplay" -> XrPlayerAction.JoinSyncPlayGroup(
                groupName = payload.optString("group_name").ifBlank { null },
                selectionIndex = payload.optInt("index", -1).takeIf { it >= 0 },
            )
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
            "report_current_media" -> XrPlayerAction.ReportCurrentMedia
            "set_passthrough" -> XrPlayerAction.SetPassthrough(payload.optBoolean("enabled", true))
            "toggle_passthrough" -> XrPlayerAction.TogglePassthrough
            "chat" -> XrPlayerAction.ChatQuery(payload.optString("query").ifBlank { transcript })
            else -> XrPlayerAction.Unrecognized(transcript)
        }
    }
}
