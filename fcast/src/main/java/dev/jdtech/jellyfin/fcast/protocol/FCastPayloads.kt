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
) {
    val playbackState: PlaybackState? get() = PlaybackState.fromCode(state)
}

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
