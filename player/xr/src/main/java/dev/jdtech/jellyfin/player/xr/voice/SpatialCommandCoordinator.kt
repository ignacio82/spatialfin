package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import dev.jdtech.jellyfin.player.session.voice.PlayerStateSnapshot
import dev.jdtech.jellyfin.player.session.voice.XrPlayerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

enum class VoiceParseStrategy {
    KEYWORD,
    MODEL,
    FALLBACK,
}

data class VoiceParseResult(
    val action: XrPlayerAction,
    val strategy: VoiceParseStrategy,
    val normalizedTranscript: String,
)

class SpatialCommandCoordinator(private val appContext: Context) {
    private var generativeModel: GenerativeModel? = null
    private var isModelAvailable = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            val generationConfig =
                generationConfig {
                    this.context = appContext.applicationContext
                    temperature = 0.1f
                    maxOutputTokens = 128
                    candidateCount = 1
                }
            val downloadConfig =
                DownloadConfig(
                    object : DownloadCallback {
                        override fun onDownloadDidNotStart(e: GenerativeAIException) {
                            Timber.w(e, "VOICE: Gemini Nano download did not start")
                        }

                        override fun onDownloadFailed(
                            failureStatus: String,
                            e: GenerativeAIException,
                        ) {
                            Timber.w(e, "VOICE: Gemini Nano download failed: %s", failureStatus)
                        }
                    }
                )
            generativeModel = GenerativeModel(generationConfig, downloadConfig)
            isModelAvailable = true
        } catch (e: Exception) {
            Timber.w(e, "VOICE: Gemini Nano unavailable, using keyword parsing only")
            generativeModel = null
            isModelAvailable = false
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
            )
        }

        keywordMatch(normalized, playerState)?.let {
            return VoiceParseResult(it, VoiceParseStrategy.KEYWORD, normalized)
        }

        if (isModelAvailable) {
            val nanoResult =
                withTimeoutOrNull(3_000L) { nanoInfer(transcript = transcript, playerState = playerState) }
            if (nanoResult != null) {
                return VoiceParseResult(nanoResult, VoiceParseStrategy.MODEL, normalized)
            }
        }

        return VoiceParseResult(
            action = XrPlayerAction.Unrecognized(transcript),
            strategy = VoiceParseStrategy.FALLBACK,
            normalizedTranscript = normalized,
        )
    }

    fun destroy() {
        generativeModel?.close()
        generativeModel = null
        isModelAvailable = false
    }

    private fun keywordMatch(text: String, playerState: PlayerStateSnapshot): XrPlayerAction? {
        extractSyncPlayAction(text)?.let { return it }
        extractSelectionAction(text)?.let { return it }

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
            text.matches(Regex("^(play|resume|start|unpause)$")) -> XrPlayerAction.Play
            text.matches(Regex("^(pause|stop|hold)$")) -> XrPlayerAction.Pause
            text.matches(Regex("^(toggle|play pause)$")) -> XrPlayerAction.TogglePlayPause
            text.matches(Regex(".*(next chapter|skip chapter).*")) -> {
                playerState.chapterNames
                    .takeIf { it.isNotEmpty() }
                    ?.let { XrPlayerAction.SeekForward(60) }
            }
            text.matches(Regex(".*(skip intro|skip opening).*")) -> XrPlayerAction.SkipIntro
            text.matches(Regex(".*(skip outro|skip ending|skip credits).*")) -> XrPlayerAction.SkipOutro
            text.matches(Regex(".*(next episode|play next|next one).*")) -> XrPlayerAction.NextEpisode
            text.matches(Regex(".*(previous episode|last episode|go back one).*")) -> {
                XrPlayerAction.PreviousEpisode
            }
            text.matches(Regex(".*(show controls|open controls).*")) -> XrPlayerAction.ShowControls
            text.matches(Regex(".*(hide controls|close controls).*")) -> XrPlayerAction.HideControls
            text.matches(Regex(".*(go home|home screen|library|back to home).*")) -> XrPlayerAction.GoHome
            text.matches(
                Regex(
                    ".*(close app|exit app|quit app|shut down|close spatial ?fin|exit spatial ?fin|close (the )?application|exit (the )?application).*"
                )
            ) -> XrPlayerAction.CloseApp
            text.matches(Regex("^(exit|quit)$")) -> XrPlayerAction.CloseApp
            text.matches(Regex("^(back|go back)$")) -> XrPlayerAction.GoBack
            extractVolumeAdjustment(text) != null -> extractVolumeAdjustment(text)
            extractQualityAdjustment(text) != null -> extractQualityAdjustment(text)
            extractSeekToPosition(text) != null -> XrPlayerAction.SeekTo(extractSeekToPosition(text)!!)
            text.matches(Regex(".*(skip|fast[ -]?forward|go forward|jump ahead).*")) -> {
                XrPlayerAction.SeekForward(extractSeconds(text) ?: 15)
            }
            text.matches(Regex(".*(rewind|go back|skip back|jump back).*")) -> {
                XrPlayerAction.SeekBackward(extractSeconds(text) ?: 10)
            }
            text.matches(Regex(".*speed.*(\\d+\\.?\\d*).*")) -> {
                val speed = Regex("(\\d+\\.?\\d*)").find(text)?.value?.toFloatOrNull()
                if (speed != null && speed in 0.25f..4.0f) XrPlayerAction.SetSpeed(speed) else null
            }
            text.matches(Regex(".*(normal speed|reset speed|one ?x|1x).*")) -> XrPlayerAction.SetSpeed(1.0f)
            text.matches(Regex(".*(double speed|two ?x|2x).*")) -> XrPlayerAction.SetSpeed(2.0f)
            text.matches(Regex(".*(half speed|0\\.5x).*")) -> XrPlayerAction.SetSpeed(0.5f)
            else -> null
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

    private fun extractVolumeAdjustment(text: String): XrPlayerAction? {
        if (text.contains("volume")) {
            val percentageMatch = Regex("(\\d+)\\s*(?:percent|%)").find(text)
            if (percentageMatch != null) {
                val value = percentageMatch.groupValues[1].toFloatOrNull()
                if (value != null) return XrPlayerAction.AdjustVolume(percentage = value / 100f)
            }
            if (text.contains("up") || text.contains("increase") || text.contains("louder")) {
                return XrPlayerAction.AdjustVolume(delta = 0.1f)
            }
            if (text.contains("down") || text.contains("decrease") || text.contains("quieter")) {
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

    private suspend fun nanoInfer(
        transcript: String,
        playerState: PlayerStateSnapshot,
    ): XrPlayerAction? = withContext(Dispatchers.IO) {
        val model = generativeModel ?: return@withContext null
        return@withContext try {
            val raw = model.generateContent(buildPrompt(transcript, playerState)).text?.trim().orEmpty()
            val clean = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            mapJsonToAction(JSONObject(clean))
        } catch (e: Exception) {
            Timber.w(e, "VOICE: Nano inference failed for: %s", transcript)
            null
        }
    }

    private fun buildPrompt(transcript: String, state: PlayerStateSnapshot): String =
        """
        You are a JSON-only intent parser for a media player app on an XR headset.
        Output exactly one JSON object and nothing else.

        Current state:
        - Playing: ${state.isPlaying}
        - Position: ${state.positionSeconds}s / ${state.durationSeconds}s
        - Controls visible: ${state.controlsVisible}
        - Current item: ${state.currentItemTitle}
        - Current segment: ${state.currentSegmentType ?: "none"}
        - Current chapter: ${state.currentChapterName ?: "unknown"}
        - Next episode: ${state.nextEpisodeTitle ?: "unknown"}
        - Audio tracks: ${state.audioTrackNames.joinToString(", ").ifBlank { "unknown" }}
        - Subtitle tracks: ${state.subtitleTrackNames.joinToString(", ").ifBlank { "unknown" }}
        - Chapters: ${state.chapterNames.joinToString(", ").ifBlank { "unknown" }}
        - Current audio: ${state.currentAudioTrack ?: "unknown"}
        - Current subtitle: ${state.currentSubtitleTrack ?: "none"}

        JSON schema:
        {"action":"PLAY"}
        {"action":"PAUSE"}
        {"action":"TOGGLE_PLAY_PAUSE"}
        {"action":"SEEK_FORWARD","seconds":15}
        {"action":"SEEK_BACKWARD","seconds":10}
        {"action":"SEEK_TO","position_seconds":0}
        {"action":"SKIP_INTRO"}
        {"action":"SKIP_OUTRO"}
        {"action":"NEXT_EPISODE"}
        {"action":"PREVIOUS_EPISODE"}
        {"action":"SET_SPEED","speed":1.0}
        {"action":"SET_QUALITY","max_bitrate":0}
        {"action":"SELECT_AUDIO","language":"English","index":null}
        {"action":"SELECT_SUBTITLE","language":"Japanese","index":null}
        {"action":"DISABLE_SUBTITLES"}
        {"action":"SEARCH","query":"Cowboy Bebop"}
        {"action":"SELECT_OPTION","index":0}
        {"action":"OPEN_SYNCPLAY"}
        {"action":"CREATE_SYNCPLAY_GROUP"}
        {"action":"JOIN_SYNCPLAY_GROUP","group_name":"Friday Night","index":null}
        {"action":"LEAVE_SYNCPLAY_GROUP"}
        {"action":"REFRESH_SYNCPLAY"}
        {"action":"ADJUST_VOLUME","percentage":0.5,"delta":null}
        {"action":"GO_HOME"}
        {"action":"CLOSE_APP"}
        {"action":"GO_BACK"}
        {"action":"SHOW_CONTROLS"}
        {"action":"HIDE_CONTROLS"}
        {"action":"UNRECOGNIZED"}

        Command: "$transcript"
        """.trimIndent()

    private fun mapJsonToAction(json: JSONObject): XrPlayerAction? =
        when (json.optString("action").uppercase()) {
            "PLAY" -> XrPlayerAction.Play
            "PAUSE" -> XrPlayerAction.Pause
            "TOGGLE_PLAY_PAUSE" -> XrPlayerAction.TogglePlayPause
            "SEEK_FORWARD" -> XrPlayerAction.SeekForward(json.optInt("seconds", 15))
            "SEEK_BACKWARD" -> XrPlayerAction.SeekBackward(json.optInt("seconds", 10))
            "SEEK_TO" -> XrPlayerAction.SeekTo(json.optLong("position_seconds", 0))
            "SKIP_INTRO" -> XrPlayerAction.SkipIntro
            "SKIP_OUTRO" -> XrPlayerAction.SkipOutro
            "NEXT_EPISODE" -> XrPlayerAction.NextEpisode
            "PREVIOUS_EPISODE" -> XrPlayerAction.PreviousEpisode
            "SET_SPEED" -> XrPlayerAction.SetSpeed(json.optDouble("speed", 1.0).toFloat())
            "SET_QUALITY" -> XrPlayerAction.SetQuality(json.optLong("max_bitrate", 0L))
            "SELECT_AUDIO" ->
                XrPlayerAction.SelectAudioTrack(
                    language = json.optString("language").takeIf { it.isNotBlank() },
                    index = if (json.has("index") && !json.isNull("index")) json.optInt("index") else null,
                )
            "SELECT_SUBTITLE" ->
                XrPlayerAction.SelectSubtitleTrack(
                    language = json.optString("language").takeIf { it.isNotBlank() },
                    index = if (json.has("index") && !json.isNull("index")) json.optInt("index") else null,
                )
            "DISABLE_SUBTITLES" -> XrPlayerAction.DisableSubtitles
            "SEARCH" ->
                json.optString("query").takeIf { it.isNotBlank() }?.let {
                    XrPlayerAction.Search(
                        query = it,
                        autoPlay = json.optBoolean("auto_play", false),
                    )
                }
            "SELECT_OPTION" -> XrPlayerAction.SelectOption(json.optInt("index", 0).coerceAtLeast(0))
            "OPEN_SYNCPLAY" -> XrPlayerAction.OpenSyncPlay
            "CREATE_SYNCPLAY_GROUP" -> XrPlayerAction.CreateSyncPlayGroup
            "JOIN_SYNCPLAY_GROUP" ->
                XrPlayerAction.JoinSyncPlayGroup(
                    groupName = json.optString("group_name").takeIf { it.isNotBlank() },
                    selectionIndex = if (json.has("index") && !json.isNull("index")) json.optInt("index") else null,
                )
            "LEAVE_SYNCPLAY_GROUP" -> XrPlayerAction.LeaveSyncPlayGroup
            "REFRESH_SYNCPLAY" -> XrPlayerAction.RefreshSyncPlay
            "ADJUST_VOLUME" ->
                XrPlayerAction.AdjustVolume(
                    percentage = if (json.has("percentage") && !json.isNull("percentage")) json.optDouble("percentage").toFloat() else null,
                    delta = if (json.has("delta") && !json.isNull("delta")) json.optDouble("delta").toFloat() else null,
                )
            "GO_HOME" -> XrPlayerAction.GoHome
            "CLOSE_APP" -> XrPlayerAction.CloseApp
            "GO_BACK" -> XrPlayerAction.GoBack
            "SHOW_CONTROLS" -> XrPlayerAction.ShowControls
            "HIDE_CONTROLS" -> XrPlayerAction.HideControls
            else -> null
        }
}
