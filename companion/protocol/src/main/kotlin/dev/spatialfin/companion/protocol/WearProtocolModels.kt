package dev.spatialfin.companion.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object WearProtocolPaths {
    const val PATH_ACTION = "/command/action"
    const val PATH_ACTION_RESPONSE = "/command/response"
    const val PATH_STATE_NOW_PLAYING = "/state/now_playing"
    const val PATH_STATE_VITALS = "/state/vitals"
    const val PATH_STATE_CREDENTIALS = "/state/credentials"
    const val PATH_STATE_NEXT_UP = "/state/next_up"
    /**
     * Transcript, not PCM. The host's `SpatialVoiceService` wraps Android's
     * `SpeechRecognizer`, which has no API for injecting an external audio buffer, so
     * a raw PCM stream had nowhere to land. Recognition therefore runs on the watch
     * and only the text crosses the link; every LLM step (`SpatialCommandCoordinator`,
     * Gemini/Gemma) still runs on the paired host.
     */
    const val PATH_VOICE_QUERY = "/voice/query"
    const val PATH_PAIRING_REQUEST = "/pairing/request"
    const val PATH_PAIRING_APPROVE = "/pairing/approve"

    const val CAPABILITY_HOST = "spatialfin_host"
    const val CAPABILITY_WEAR = "spatialfin_wear"

    const val ASSET_KEY_COVER_ART = "cover_art"

    /**
     * Every state DataItem carries its serialized body under this DataMap key.
     * Never write a state item with a raw `PutDataRequest.setData(...)` body:
     * `DataMapItem.fromDataItem` throws `IllegalStateException` on anything that is
     * not a serialized DataMap, which takes down the watch's whole listener loop.
     */
    const val DATA_KEY_PAYLOAD = "payload"
    const val DATA_KEY_TIMESTAMP = "timestamp"
}

@Serializable
sealed interface WearPlayerAction {
    @Serializable
    @SerialName("play")
    data object Play : WearPlayerAction

    @Serializable
    @SerialName("pause")
    data object Pause : WearPlayerAction

    @Serializable
    @SerialName("toggle_play_pause")
    data object TogglePlayPause : WearPlayerAction

    @Serializable
    @SerialName("seek_forward")
    data class SeekForward(val seconds: Int = 10) : WearPlayerAction

    @Serializable
    @SerialName("seek_backward")
    data class SeekBackward(val seconds: Int = 10) : WearPlayerAction

    @Serializable
    @SerialName("seek_to")
    data class SeekTo(val positionSeconds: Long) : WearPlayerAction

    @Serializable
    @SerialName("skip_intro")
    data object SkipIntro : WearPlayerAction

    @Serializable
    @SerialName("skip_outro")
    data object SkipOutro : WearPlayerAction

    @Serializable
    @SerialName("next_episode")
    data object NextEpisode : WearPlayerAction

    @Serializable
    @SerialName("previous_episode")
    data object PreviousEpisode : WearPlayerAction

    @Serializable
    @SerialName("set_speed")
    data class SetSpeed(val speed: Float) : WearPlayerAction

    @Serializable
    @SerialName("select_audio_track")
    data class SelectAudioTrack(
        val language: String? = null,
        val index: Int? = null,
    ) : WearPlayerAction

    @Serializable
    @SerialName("select_subtitle_track")
    data class SelectSubtitleTrack(
        val language: String? = null,
        val index: Int? = null,
    ) : WearPlayerAction

    @Serializable
    @SerialName("disable_subtitles")
    data object DisableSubtitles : WearPlayerAction

    @Serializable
    @SerialName("adjust_volume")
    data class AdjustVolume(
        val percentage: Float? = null,
        val delta: Float? = null,
    ) : WearPlayerAction

    @Serializable
    @SerialName("adjust_scale")
    data class AdjustScale(
        val delta: Float? = null,
        val reset: Boolean = false,
    ) : WearPlayerAction

    @Serializable
    @SerialName("adjust_distance")
    data class AdjustDistance(
        val delta: Float? = null,
        val reset: Boolean = false,
    ) : WearPlayerAction

    @Serializable
    @SerialName("reset_screen_placement")
    data object ResetScreenPlacement : WearPlayerAction

    @Serializable
    @SerialName("go_home")
    data object GoHome : WearPlayerAction

    @Serializable
    @SerialName("close_app")
    data object CloseApp : WearPlayerAction

    @Serializable
    @SerialName("go_back")
    data object GoBack : WearPlayerAction

    @Serializable
    @SerialName("cast_to_fcast_receiver")
    data class CastToFCastReceiver(
        val name: String? = null,
        val host: String? = null,
        val port: Int? = null,
    ) : WearPlayerAction

    @Serializable
    @SerialName("stop_fcast_casting")
    data object StopFCastCasting : WearPlayerAction

    @Serializable
    @SerialName("music_play_pause")
    data object MusicPlayPause : WearPlayerAction

    @Serializable
    @SerialName("music_pause")
    data object MusicPause : WearPlayerAction

    @Serializable
    @SerialName("music_resume")
    data object MusicResume : WearPlayerAction

    @Serializable
    @SerialName("music_next")
    data object MusicNext : WearPlayerAction

    @Serializable
    @SerialName("music_previous")
    data object MusicPrevious : WearPlayerAction

    @Serializable
    @SerialName("music_adjust_volume")
    data class MusicAdjustVolume(
        val percentage: Float? = null,
        val delta: Float? = null,
    ) : WearPlayerAction

    @Serializable
    @SerialName("play_media_item")
    data class PlayMediaItem(
        val itemId: String,
        val mediaType: String? = null,
        val startPositionMs: Long = 0L,
    ) : WearPlayerAction

    @Serializable
    @SerialName("unrecognized")
    data class Unrecognized(val raw: String) : WearPlayerAction
}

@Serializable
data class WearStreamInfo(
    val index: Int,
    val name: String,
    val language: String? = null,
    val isSelected: Boolean = false,
    val isForced: Boolean = false,
)

@Serializable
data class WearChapterInfo(
    val name: String,
    val startPositionSeconds: Long,
)

@Serializable
data class WearNowPlayingState(
    val isPlaying: Boolean = false,
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val title: String = "",
    val overview: String = "",
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val segmentType: String? = null,
    val currentChapterName: String? = null,
    val audioTracks: List<WearStreamInfo> = emptyList(),
    val subtitleTracks: List<WearStreamInfo> = emptyList(),
    val chapters: List<WearChapterInfo> = emptyList(),
    val currentAudioTrack: String? = null,
    val currentSubtitleTrack: String? = null,
    val currentAudioLanguageCode: String? = null,
    val currentSubtitleLanguageCode: String? = null,
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val targetDeviceName: String = "SpatialFin",
    val hasCoverArtAsset: Boolean = false,
    val streamUrl: String? = null,
    val mediaContainer: String? = null,
    val itemId: String? = null,
    val timestampEpochMs: Long = 0L,
)

@Serializable
data class WearVoiceQuery(
    val transcript: String,
    val spokenAtEpochMs: Long = 0L,
)

@Serializable
data class WearNextUpItem(
    val id: String,
    val title: String,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val overview: String = "",
    val mediaType: String = "Movie",
    val durationSeconds: Long = 0L,
    val playbackPositionSeconds: Long = 0L,
    val primaryImageUrl: String? = null,
)

@Serializable
data class WearNextUpState(
    val items: List<WearNextUpItem> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
)

@Serializable
data class WearVitalsState(
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val thermalStatus: Int = 0,
    val wifiSpeedMbps: Int = -1,
    val deviceName: String = "",
    val isHeadset: Boolean = false,
)

@Serializable
data class WearCredentials(
    val serverUrl: String,
    val accessToken: String,
    val userId: String,
    val deviceId: String,
    val serverId: String,
    val serverName: String? = null,
    val username: String? = null,
)

@Serializable
data class WearTvPairingRequest(
    val deviceName: String,
    val pairingToken: String,
    val manualCode: String,
    val receiverUrl: String,
    val expiresAtEpochMs: Long,
)

@Serializable
data class WearTvPairingApproval(
    val pairingToken: String,
    val approved: Boolean,
    val setupToken: String? = null,
    val companionConfigJson: String? = null,
)

object WearProtocolCodec {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encodeAction(action: WearPlayerAction): ByteArray =
        json.encodeToString(WearPlayerAction.serializer(), action).encodeToByteArray()

    fun decodeAction(bytes: ByteArray): WearPlayerAction =
        runCatching {
            json.decodeFromString(WearPlayerAction.serializer(), bytes.decodeToString())
        }.getOrElse {
            WearPlayerAction.Unrecognized(bytes.decodeToString())
        }

    fun encodeNowPlaying(state: WearNowPlayingState): ByteArray =
        json.encodeToString(WearNowPlayingState.serializer(), state).encodeToByteArray()

    fun decodeNowPlaying(bytes: ByteArray): WearNowPlayingState =
        json.decodeFromString(WearNowPlayingState.serializer(), bytes.decodeToString())

    fun encodeVoiceQuery(query: WearVoiceQuery): ByteArray =
        json.encodeToString(WearVoiceQuery.serializer(), query).encodeToByteArray()

    fun decodeVoiceQuery(bytes: ByteArray): WearVoiceQuery =
        json.decodeFromString(WearVoiceQuery.serializer(), bytes.decodeToString())

    fun encodeNextUp(state: WearNextUpState): ByteArray =
        json.encodeToString(WearNextUpState.serializer(), state).encodeToByteArray()

    fun decodeNextUp(bytes: ByteArray): WearNextUpState =
        json.decodeFromString(WearNextUpState.serializer(), bytes.decodeToString())

    fun encodeVitals(state: WearVitalsState): ByteArray =
        json.encodeToString(WearVitalsState.serializer(), state).encodeToByteArray()

    fun decodeVitals(bytes: ByteArray): WearVitalsState =
        json.decodeFromString(WearVitalsState.serializer(), bytes.decodeToString())

    fun encodeCredentials(credentials: WearCredentials): ByteArray =
        json.encodeToString(WearCredentials.serializer(), credentials).encodeToByteArray()

    fun decodeCredentials(bytes: ByteArray): WearCredentials =
        json.decodeFromString(WearCredentials.serializer(), bytes.decodeToString())

    fun encodePairingRequest(request: WearTvPairingRequest): ByteArray =
        json.encodeToString(WearTvPairingRequest.serializer(), request).encodeToByteArray()

    fun decodePairingRequest(bytes: ByteArray): WearTvPairingRequest =
        json.decodeFromString(WearTvPairingRequest.serializer(), bytes.decodeToString())

    fun encodePairingApproval(approval: WearTvPairingApproval): ByteArray =
        json.encodeToString(WearTvPairingApproval.serializer(), approval).encodeToByteArray()

    fun decodePairingApproval(bytes: ByteArray): WearTvPairingApproval =
        json.decodeFromString(WearTvPairingApproval.serializer(), bytes.decodeToString())
}
