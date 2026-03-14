package dev.jdtech.jellyfin.player.xr.voice

import android.content.Context
import com.google.ai.edge.aicore.DownloadCallback
import com.google.ai.edge.aicore.DownloadConfig
import com.google.ai.edge.aicore.GenerativeAIException
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

data class PlayerStateSnapshot(
    val isPlaying: Boolean = false,
    val positionSeconds: Long = 0,
    val durationSeconds: Long = 0,
    val audioTrackNames: List<String> = emptyList(),
    val subtitleTrackNames: List<String> = emptyList(),
    val currentAudioTrack: String? = null,
    val currentSubtitleTrack: String? = null,
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
    ): XrPlayerAction {
        val normalized =
            transcript.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        if (normalized.isBlank()) return XrPlayerAction.Unrecognized(transcript)

        keywordMatch(normalized)?.let { return it }

        if (isModelAvailable) {
            val nanoResult =
                withTimeoutOrNull(3_000L) { nanoInfer(transcript = transcript, playerState = playerState) }
            if (nanoResult != null) return nanoResult
        }

        return XrPlayerAction.Unrecognized(transcript)
    }

    fun destroy() {
        generativeModel?.close()
        generativeModel = null
        isModelAvailable = false
    }

    private fun keywordMatch(text: String): XrPlayerAction? {
        val searchQuery = extractSearchQuery(text)
        if (searchQuery != null) return XrPlayerAction.Search(searchQuery)

        val subtitleLanguage = extractLanguageTarget(text, "subtitles?|subs?")
        if (subtitleLanguage != null) return XrPlayerAction.SelectSubtitleTrack(language = subtitleLanguage)

        val audioLanguage = extractLanguageTarget(text, "audio|dub|track")
        if (audioLanguage != null) return XrPlayerAction.SelectAudioTrack(language = audioLanguage)

        if (isSubtitleDisableCommand(text)) return XrPlayerAction.DisableSubtitles

        return when {
            text.matches(Regex("^(play|resume|start|unpause)$")) -> XrPlayerAction.Play
            text.matches(Regex("^(pause|stop|hold)$")) -> XrPlayerAction.Pause
            text.matches(Regex("^(toggle|play pause)$")) -> XrPlayerAction.TogglePlayPause
            text.matches(Regex(".*(skip intro|skip opening).*")) -> XrPlayerAction.SkipIntro
            text.matches(Regex(".*(skip outro|skip ending|skip credits).*")) -> XrPlayerAction.SkipOutro
            text.matches(Regex(".*(next episode|play next|next one).*")) -> XrPlayerAction.NextEpisode
            text.matches(Regex(".*(previous episode|last episode|go back one).*")) -> {
                XrPlayerAction.PreviousEpisode
            }
            text.matches(Regex(".*(show controls|open controls).*")) -> XrPlayerAction.ShowControls
            text.matches(Regex(".*(hide controls|close controls).*")) -> XrPlayerAction.HideControls
            text.matches(Regex("^(back|go back|exit)$")) -> XrPlayerAction.GoBack
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

    private fun isSubtitleDisableCommand(text: String): Boolean {
        val mentionsSubtitles =
            listOf("subtitle", "subtitles", "subs").any { token -> text.contains(token) }
        val mentionsDisableIntent =
            listOf("off", "disable", "remove", "hide", "without", "no").any { token ->
                text.contains(token)
            }
        return mentionsSubtitles && mentionsDisableIntent
    }

    private fun extractSearchQuery(text: String): String? {
        val query =
            text.replace(Regex("^(search for|search|find me|find|look up)\\s*"), "").trim()
        return if (query != text && query.isNotBlank()) query else null
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
        - Audio tracks: ${state.audioTrackNames.joinToString(", ").ifBlank { "unknown" }}
        - Subtitle tracks: ${state.subtitleTrackNames.joinToString(", ").ifBlank { "unknown" }}
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
        {"action":"SELECT_AUDIO","language":"English","index":null}
        {"action":"SELECT_SUBTITLE","language":"Japanese","index":null}
        {"action":"DISABLE_SUBTITLES"}
        {"action":"SEARCH","query":"Cowboy Bebop"}
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
            "SEARCH" -> json.optString("query").takeIf { it.isNotBlank() }?.let(XrPlayerAction::Search)
            "GO_BACK" -> XrPlayerAction.GoBack
            "SHOW_CONTROLS" -> XrPlayerAction.ShowControls
            "HIDE_CONTROLS" -> XrPlayerAction.HideControls
            else -> null
        }
}
