package dev.jdtech.jellyfin.player.xr.voice

import dev.jdtech.jellyfin.core.llm.LlmInferenceProfile
import dev.jdtech.jellyfin.core.llm.ParsedToolCall
import dev.jdtech.jellyfin.core.llm.VoiceAiEngine
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.VoiceScreenContext
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

/**
 * Maps voice transcripts into [XrPlayerAction] via the on-device LLM.
 *
 * Primary path: **typed tool calling**. LiteRT-LM's `ConversationConfig.tools`
 * lets us register an `OpenApiTool` with a constrained enum of actions and a
 * typed parameter list; the model replies with a structured
 * [ParsedToolCall] whose `arguments` is `Map<String, Any?>`. No JSON substring
 * extraction, no retry for truncated responses, no salvaging.
 *
 * Fallback path: **JSON in free text**. AICore / Gemini Nano
 * (`mlkit-genai-prompt:1.0.0-beta2`) doesn't expose tools or response schemas
 * yet, so its engine returns null from `runToolCall` and we parse JSON from a
 * prompt-engineered plain-text response. One strict retry is kept for the
 * AICore path; the tool-calling path doesn't need it.
 */
class GemmaCommandParser(private val engine: VoiceAiEngine) {

    suspend fun parse(
        transcript: String,
        playerState: PlayerStateSnapshot,
    ): XrPlayerAction = withContext(Dispatchers.IO) {
        // --- Primary: typed tool call (LiteRT) ----------------------------------
        try {
            val toolCall = withTimeoutOrNull(TOOL_CALL_TIMEOUT_MS) {
                engine.runToolCall(
                    prompt = buildToolCallPrompt(transcript, playerState),
                    toolDescriptionJson = INTERPRET_COMMAND_TOOL,
                    profile = LlmInferenceProfile.COMMAND,
                )
            }
            if (toolCall != null && toolCall.name == "interpret_command") {
                Timber.d(
                    "GemmaCommandParser: tool-call action=%s argsKeys=%s",
                    toolCall.arguments["action"],
                    toolCall.arguments.keys.joinToString(","),
                )
                return@withContext mapToolCall(toolCall, transcript)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.w(
                e,
                "GemmaCommandParser: tool-call path failed — falling back to JSON parse transcript=%s",
                transcript,
            )
        }

        // --- Fallback: JSON-in-text (AICore, or LiteRT when the model gave up) --
        val prompts = listOf(
            buildCommandPrompt(transcript, playerState, strictRetry = false),
            buildCommandPrompt(transcript, playerState, strictRetry = true),
        )

        prompts.forEachIndexed { index, prompt ->
            try {
                val responseText = engine.runInference(
                    prompt = prompt,
                    profile = LlmInferenceProfile.COMMAND,
                )
                Timber.d(
                    "GemmaCommandParser: raw response retry=%s response=%s",
                    index > 0,
                    responseText,
                )
                val json = extractJson(responseText) ?: return@forEachIndexed
                return@withContext mapModelAction(JSONObject(json), transcript)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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

    // ---------------------------------------------------------------------------
    // Tool-call prompt + tool description (primary path)
    // ---------------------------------------------------------------------------

    private fun buildToolCallPrompt(
        transcript: String,
        playerState: PlayerStateSnapshot,
    ): String {
        val screenGuidance = when (playerState.screenContext) {
            VoiceScreenContext.HOME ->
                "Screen is HOME; prefer search / chat / go_home / go_back / select_option."
            VoiceScreenContext.PLAYER ->
                "Screen is PLAYER; prefer playback / subtitle / audio / timeline actions when clearly requested."
        }
        return """
            You are the command parser for an XR media player. Call the
            `interpret_command` tool exactly once with the best-matching `action`
            and only the fields relevant to that action. $screenGuidance

            Disambiguation hints:
            - Questions about media, recommendations, recaps, or "what should I watch"
              → action="chat" with the full transcript in query.
            - "shorter" / "movie only" / "show only" / "funny" / "not anime" / "with
              english audio" / "more like the second one" → action="chat".
            - "play the first one" / "open the second result" when results are visible
              → action="select_option" with `index`.
            - "the 3D one" / "the smaller one" → action="select_version" with `query`.
            - "recenter the screen" / "default cinema position" → action="reset_screen_placement".
            - If audio AND subtitles are requested, pick audio as primary and put
              subtitles in `secondary_action`.

            Context:
            screenContext=${playerState.screenContext}
            title=${playerState.currentItemTitle}
            syncPlayActive=${playerState.syncPlayActive}
            voiceResultsCount=${playerState.voiceSearchResultsCount}
            lastRecommendationQuery=${playerState.lastRecommendationQuery ?: ""}
            lastRecommendationCount=${playerState.lastRecommendationCount}
            lastRecommendationTitles=${playerState.lastRecommendationTitles.joinToString(", ")}

            Transcript: "$transcript"
        """.trimIndent()
    }

    // ---------------------------------------------------------------------------
    // JSON fallback prompt (AICore, or LiteRT when tool call returns no call)
    // ---------------------------------------------------------------------------

    private fun buildCommandPrompt(
        transcript: String,
        playerState: PlayerStateSnapshot,
        strictRetry: Boolean,
    ): String {
        val screenGuidance = when (playerState.screenContext) {
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
        val retryGuidance = if (strictRetry) {
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
            - If the request is a question about the media, metadata, recommendations, summaries, or recaps, use action "chat".
            - If the user is refining recommendations, use action "chat".
            - If recommendation results are visible and the user says "play the first one" or "open the second result", use action "select_option" with the matching index.
            - If the request is for version selection (e.g. "the 3D one", "the smaller one"), use "select_version" with a query.
            - If the user asks to recenter the screen, use action "reset_screen_placement".
            - If the request specifies both audio and subtitles, put one in "secondary_action".
            - $screenGuidance
            $retryGuidance

            JSON schema:
            {"action":"...","query":"...","seconds":0,"position_seconds":0,"speed":0,"max_bitrate":0,"language":"...","index":0,"percentage":0,"delta":0,"enabled":false,"reset":false,"secondary_action":{"action":"...","language":"..."}}

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
        val jsonStr = raw.substring(start, end + 1)
        try {
            JSONObject(jsonStr)
            return jsonStr
        } catch (_: Exception) {
            // Multi-object response — return the first balanced JSON object.
            var depth = 0
            for (i in start..end) {
                when (raw[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // Argument coercion
    // ---------------------------------------------------------------------------

    /** [Map]-based accessor used by the tool-call path. */
    private fun Map<String, Any?>.str(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.int(key: String): Int? =
        when (val v = this[key]) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }

    private fun Map<String, Any?>.long(key: String): Long? =
        when (val v = this[key]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }

    private fun Map<String, Any?>.float(key: String): Float? =
        when (val v = this[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }

    private fun Map<String, Any?>.double(key: String): Double? =
        when (val v = this[key]) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

    private fun Map<String, Any?>.bool(key: String): Boolean? =
        when (val v = this[key]) {
            is Boolean -> v
            is String -> v.toBooleanStrictOrNull()
            is Number -> v.toInt() != 0
            else -> null
        }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.nested(key: String): Map<String, Any?>? =
        this[key] as? Map<String, Any?>

    // ---------------------------------------------------------------------------
    // Mappers
    // ---------------------------------------------------------------------------

    /** Route a typed tool call into an [XrPlayerAction]. Mirror of [mapModelAction]. */
    private fun mapToolCall(call: ParsedToolCall, transcript: String): XrPlayerAction {
        val args = call.arguments
        val actionStr = args.str("action")?.lowercase() ?: return XrPlayerAction.Unrecognized(transcript)

        if (actionStr == "select_version") {
            return XrPlayerAction.ResolveDisambiguation(
                query = args.str("query") ?: transcript,
                originalTranscript = transcript,
            )
        }

        val secondary = args.nested("secondary_action")?.let { nested ->
            mapToolCall(
                ParsedToolCall(name = "interpret_command", arguments = nested),
                transcript,
            )
        }

        return when (actionStr) {
            "play" -> XrPlayerAction.Play
            "pause" -> XrPlayerAction.Pause
            "toggle_play_pause" -> XrPlayerAction.TogglePlayPause
            "seek_forward" -> XrPlayerAction.SeekForward(args.int("seconds") ?: 15)
            "seek_backward" -> XrPlayerAction.SeekBackward(args.int("seconds") ?: 10)
            "seek_to" -> XrPlayerAction.SeekTo(args.long("position_seconds") ?: 0L)
            "skip_intro" -> XrPlayerAction.SkipIntro
            "skip_outro" -> XrPlayerAction.SkipOutro
            "next_episode" -> XrPlayerAction.NextEpisode
            "previous_episode" -> XrPlayerAction.PreviousEpisode
            "set_speed" -> XrPlayerAction.SetSpeed(args.float("speed") ?: 1.0f)
            "set_quality" -> XrPlayerAction.SetQuality(args.long("max_bitrate") ?: 0L)
            "select_audio" -> XrPlayerAction.SelectAudioTrack(
                language = args.str("language"),
                index = args.int("index")?.takeIf { it >= 0 },
                secondaryAction = secondary,
            )
            "select_subtitles" -> XrPlayerAction.SelectSubtitleTrack(
                language = args.str("language"),
                index = args.int("index")?.takeIf { it >= 0 },
                secondaryAction = secondary,
            )
            "disable_subtitles" -> XrPlayerAction.DisableSubtitles
            "search" -> XrPlayerAction.Search(
                query = args.str("query") ?: transcript,
                autoPlay = args.bool("auto_play") ?: false,
            )
            "select_option" -> XrPlayerAction.SelectOption(args.int("index") ?: 0)
            "open_syncplay" -> XrPlayerAction.OpenSyncPlay
            "join_syncplay" -> XrPlayerAction.JoinSyncPlayGroup(
                groupName = args.str("group_name"),
                selectionIndex = args.int("index")?.takeIf { it >= 0 },
            )
            "adjust_volume" -> XrPlayerAction.AdjustVolume(
                percentage = args.float("percentage"),
                delta = args.float("delta"),
            )
            "adjust_scale" -> XrPlayerAction.AdjustScale(
                delta = args.float("delta"),
                reset = args.bool("reset") ?: false,
            )
            "adjust_distance" -> XrPlayerAction.AdjustDistance(
                delta = args.float("delta"),
                reset = args.bool("reset") ?: false,
            )
            "reset_screen_placement" -> XrPlayerAction.ResetScreenPlacement
            "go_home" -> XrPlayerAction.GoHome
            "close_app" -> XrPlayerAction.CloseApp
            "go_back" -> XrPlayerAction.GoBack
            "show_controls" -> XrPlayerAction.ShowControls
            "hide_controls" -> XrPlayerAction.HideControls
            "report_current_time" -> XrPlayerAction.ReportCurrentTime
            "report_remaining_time" -> XrPlayerAction.ReportRemainingTime
            "report_current_media" -> XrPlayerAction.ReportCurrentMedia
            "set_passthrough" -> XrPlayerAction.SetPassthrough(args.bool("enabled") ?: true)
            "toggle_passthrough" -> XrPlayerAction.TogglePassthrough
            "chat" -> XrPlayerAction.ChatQuery(args.str("query") ?: transcript)
            else -> XrPlayerAction.Unrecognized(transcript)
        }
    }

    private fun mapModelAction(payload: JSONObject, transcript: String): XrPlayerAction {
        val actionStr = payload.optString("action").lowercase()

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
            "reset_screen_placement" -> XrPlayerAction.ResetScreenPlacement
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

    companion object {
        // Deadline for a single LLM tool-call round trip. Matches
        // ChatToolRegistry; a hung NPU run should never freeze the parser.
        private const val TOOL_CALL_TIMEOUT_MS = 8_000L

        /**
         * Single-tool OpenAPI description passed to LiteRT-LM's
         * `ConversationConfig.tools`. `action` is a constrained string enum so the
         * model can't invent an unknown action name; all other parameters are
         * optional and are only consulted when the matching action is selected.
         */
        private const val INTERPRET_COMMAND_TOOL = """
        {
          "name": "interpret_command",
          "description": "Convert a spoken voice transcript into exactly one media-player action. Fill only the fields relevant to the chosen action.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "description": "Which action to perform.",
                "enum": [
                  "play", "pause", "toggle_play_pause",
                  "seek_forward", "seek_backward", "seek_to",
                  "skip_intro", "skip_outro",
                  "next_episode", "previous_episode",
                  "set_speed", "set_quality",
                  "select_audio", "select_subtitles", "disable_subtitles",
                  "search", "select_option", "select_version",
                  "open_syncplay", "join_syncplay",
                  "adjust_volume", "adjust_scale", "adjust_distance",
                  "reset_screen_placement",
                  "go_home", "close_app", "go_back",
                  "show_controls", "hide_controls",
                  "report_current_time", "report_remaining_time", "report_current_media",
                  "set_passthrough", "toggle_passthrough",
                  "chat"
                ]
              },
              "query":             { "type": "string",  "description": "For search / chat / select_version." },
              "seconds":           { "type": "integer", "description": "Seek delta for seek_forward / seek_backward." },
              "position_seconds":  { "type": "integer", "description": "Absolute seek position for seek_to." },
              "speed":             { "type": "number",  "description": "Playback speed multiplier for set_speed." },
              "max_bitrate":       { "type": "integer", "description": "Maximum bitrate bps for set_quality." },
              "language":          { "type": "string",  "description": "Audio/subtitle language name or code." },
              "index":             { "type": "integer", "description": "0-based selection index for select_option / join_syncplay." },
              "percentage":        { "type": "number",  "description": "Absolute volume 0.0-1.0 for adjust_volume." },
              "delta":             { "type": "number",  "description": "Relative change for adjust_volume / adjust_scale / adjust_distance." },
              "enabled":           { "type": "boolean", "description": "Target state for set_passthrough." },
              "reset":             { "type": "boolean", "description": "Reset-to-default flag for adjust_scale / adjust_distance." },
              "group_name":        { "type": "string",  "description": "SyncPlay group name for join_syncplay." },
              "auto_play":         { "type": "boolean", "description": "Auto-play the first search result." },
              "secondary_action": {
                "type": "object",
                "description": "Optional second action (e.g. subtitles while selecting audio).",
                "properties": {
                  "action":   { "type": "string" },
                  "language": { "type": "string" }
                }
              }
            },
            "required": ["action"]
          }
        }
        """
    }
}
