package dev.jdtech.jellyfin.fcast.protocol

import kotlinx.serialization.json.Json

/**
 * Typed FCast messages — one variant per opcode that carries a body, plus body-less variants for
 * the control opcodes (Pause, Resume, Stop, Ping, Pong, None).
 *
 * Keep this in lock-step with [FCastOpcode]: every opcode must have a representation here so the
 * decoder is total.
 */
sealed class FCastMessage(val opcode: FCastOpcode) {
    data object None : FCastMessage(FCastOpcode.None)
    data class Play(val payload: PlayMessage) : FCastMessage(FCastOpcode.Play)
    data object Pause : FCastMessage(FCastOpcode.Pause)
    data object Resume : FCastMessage(FCastOpcode.Resume)
    data object Stop : FCastMessage(FCastOpcode.Stop)
    data class Seek(val payload: SeekMessage) : FCastMessage(FCastOpcode.Seek)
    data class PlaybackUpdate(val payload: PlaybackUpdateMessage) : FCastMessage(FCastOpcode.PlaybackUpdate)
    data class VolumeUpdate(val payload: VolumeUpdateMessage) : FCastMessage(FCastOpcode.VolumeUpdate)
    data class SetVolume(val payload: SetVolumeMessage) : FCastMessage(FCastOpcode.SetVolume)
    data class PlaybackError(val payload: PlaybackErrorMessage) : FCastMessage(FCastOpcode.PlaybackError)
    data class SetSpeed(val payload: SetSpeedMessage) : FCastMessage(FCastOpcode.SetSpeed)
    data class Version(val payload: VersionMessage) : FCastMessage(FCastOpcode.Version)
    data object Ping : FCastMessage(FCastOpcode.Ping)
    data object Pong : FCastMessage(FCastOpcode.Pong)

    /**
     * Initial body shape depends on direction (sender vs receiver). The codec decodes into
     * [InitialReceiverMessage] (a strict superset of [InitialSenderMessage]) and lets the caller
     * narrow if needed.
     */
    data class Initial(val payload: InitialReceiverMessage) : FCastMessage(FCastOpcode.Initial)
    data class PlayUpdate(val payload: PlayUpdateMessage) : FCastMessage(FCastOpcode.PlayUpdate)
    data class SetPlaylistItem(val payload: SetPlaylistItemMessage) : FCastMessage(FCastOpcode.SetPlaylistItem)
    data class SubscribeEvent(val payload: SubscribeEventMessage) : FCastMessage(FCastOpcode.SubscribeEvent)
    data class UnsubscribeEvent(val payload: UnsubscribeEventMessage) : FCastMessage(FCastOpcode.UnsubscribeEvent)
    data class Event(val payload: EventMessage) : FCastMessage(FCastOpcode.Event)
}

/**
 * Shared JSON configuration. `ignoreUnknownKeys` is mandatory for forward-compat with
 * future protocol versions; `encodeDefaults = false` keeps optional fields out of wire bodies
 * unless explicitly set, matching the spec's optional semantics.
 */
internal val FCastJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = false
}
