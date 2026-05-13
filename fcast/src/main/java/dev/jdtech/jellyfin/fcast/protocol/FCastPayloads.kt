package dev.jdtech.jellyfin.fcast.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON payload schemas for FCast messages. Field optionality follows the v3 spec — peers MUST
 * tolerate unknown fields, so we serialize with `ignoreUnknownKeys = true` (see [FCastJson]).
 *
 * Numeric times are seconds (Double); volumes are 0..1.
 */

@Serializable
data class PlayMessage(
    val container: String,
    val url: String? = null,
    val content: String? = null,
    val time: Double? = null,
    val volume: Double? = null,
    val speed: Double? = null,
    val headers: Map<String, String>? = null,
    val metadata: MetadataObject? = null,
)

@Serializable
data class MetadataObject(
    val type: Int? = null,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    @SerialName("custom") val custom: JsonElement? = null,
)

@Serializable
data class SeekMessage(val time: Double)

@Serializable
data class PlaybackUpdateMessage(
    val generationTime: Long,
    val state: Int,
    val time: Double? = null,
    val duration: Double? = null,
    val speed: Double? = null,
    val itemIndex: Int? = null,
    /**
     * SpatialFin extension (not in the FCast v3 spec). Receiver populates with the audio
     * format ExoPlayer is actually decoding. Non-SpatialFin senders/receivers ignore via
     * `ignoreUnknownKeys = true`. Null = receiver hasn't probed yet or non-SpatialFin peer.
     */
    val audioFormat: AudioFormatInfo? = null,
) {
    val playbackState: PlaybackState? get() = PlaybackState.fromCode(state)
}

/**
 * SpatialFin-only beacon extension describing what ExoPlayer's actually decoding right now.
 * Atmos detection: `mimeType == "audio/eac3-joc"` is the Joint Object Coding MIME that signals
 * Dolby Atmos in an E-AC-3 bitstream. TrueHD Atmos has no dedicated MIME — sender infers from
 * source metadata as a fallback when it sees `audio/true-hd` here.
 */
@Serializable
data class AudioFormatInfo(
    /** Media3 `Format.sampleMimeType` (e.g. `audio/eac3-joc`, `audio/aac`, `audio/true-hd`). */
    val mimeType: String? = null,
    /** Decoded channel count (1=mono, 2=stereo, 6=5.1, 8=7.1). */
    val channelCount: Int? = null,
    /** Decoded sample rate in Hz (typical: 44_100, 48_000, 96_000). */
    val sampleRateHz: Int? = null,
    /** Average bitrate in kbps if known; null when the source doesn't expose it. */
    val bitrateKbps: Int? = null,
    /** Receiver-side pretty-printed label (e.g. `Dolby Atmos · 7.1`); fallback for the UI. */
    val label: String? = null,
)

enum class PlaybackState(val code: Int) {
    Idle(0),
    Playing(1),
    Paused(2),
    ;

    companion object {
        fun fromCode(code: Int): PlaybackState? = entries.firstOrNull { it.code == code }
    }
}

@Serializable
data class VolumeUpdateMessage(
    val generationTime: Long,
    val volume: Double,
)

@Serializable
data class SetVolumeMessage(val volume: Double)

@Serializable
data class SetSpeedMessage(val speed: Double)

@Serializable
data class PlaybackErrorMessage(val message: String)

@Serializable
data class VersionMessage(val version: Int)

@Serializable
data class InitialSenderMessage(
    val displayName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class InitialReceiverMessage(
    val displayName: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val playData: PlayMessage? = null,
)

@Serializable
data class PlayUpdateMessage(
    val generationTime: Long,
    val playData: PlayMessage? = null,
)

@Serializable
data class SetPlaylistItemMessage(val itemIndex: Int)

@Serializable
data class SubscribeEventMessage(val event: EventSubscribeObject)

@Serializable
data class UnsubscribeEventMessage(val event: EventSubscribeObject)

@Serializable
data class EventSubscribeObject(
    val type: Int,
    val key: String? = null,
)

@Serializable
data class EventMessage(
    val generationTime: Long,
    val event: EventObject,
)

@Serializable
data class EventObject(
    val type: Int,
    val key: String? = null,
    val itemIndex: Int? = null,
)

enum class FCastEventType(val code: Int) {
    MediaItemStart(0),
    MediaItemEnd(1),
    MediaItemChange(2),
    KeyDown(3),
    KeyUp(4),
    ;

    companion object {
        fun fromCode(code: Int): FCastEventType? = entries.firstOrNull { it.code == code }
    }
}
