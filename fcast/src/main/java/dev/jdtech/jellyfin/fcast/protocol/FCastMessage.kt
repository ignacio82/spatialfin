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

    /**
     * Resume. [payload] is the SpatialFin v4 synchronized-start extension; null = a legacy
     * resume-now (body-less on the wire, byte-identical to v2/v3).
     */
    data class Resume(val payload: ResumeMessage? = null) : FCastMessage(FCastOpcode.Resume)
    data object Stop : FCastMessage(FCastOpcode.Stop)
    data class Seek(val payload: SeekMessage) : FCastMessage(FCastOpcode.Seek)
    data class PlaybackUpdate(val payload: PlaybackUpdateMessage) : FCastMessage(FCastOpcode.PlaybackUpdate)
    data class VolumeUpdate(val payload: VolumeUpdateMessage) : FCastMessage(FCastOpcode.VolumeUpdate)
    data class SetVolume(val payload: SetVolumeMessage) : FCastMessage(FCastOpcode.SetVolume)
    data class PlaybackError(val payload: PlaybackErrorMessage) : FCastMessage(FCastOpcode.PlaybackError)
    data class SetSpeed(val payload: SetSpeedMessage) : FCastMessage(FCastOpcode.SetSpeed)
    data class Version(val payload: VersionMessage) : FCastMessage(FCastOpcode.Version)
    /**
     * Ping. [payload] is the SpatialFin v4 NTP-timestamp extension; null = a legacy body-less
     * Ping (byte-identical to v2/v3).
     */
    data class Ping(val payload: PingMessage? = null) : FCastMessage(FCastOpcode.Ping)

    /** Pong. [payload] is the SpatialFin v4 NTP four-timestamp echo; null = legacy body-less. */
    data class Pong(val payload: PongMessage? = null) : FCastMessage(FCastOpcode.Pong)

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
    
    // SpatialFin Custom Extensions
    data class SpatialFinTracksUpdate(val payload: SpatialFinTracksUpdateMessage) : FCastMessage(FCastOpcode.SpatialFinTracksUpdate)
    data class SpatialFinSetTrack(val payload: SpatialFinSetTrackMessage) : FCastMessage(FCastOpcode.SpatialFinSetTrack)
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
