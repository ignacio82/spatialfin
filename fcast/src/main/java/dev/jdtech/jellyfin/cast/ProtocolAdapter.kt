package dev.jdtech.jellyfin.cast

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single sender-facing contract every casting protocol implements. The session manager owns
 * one active adapter at a time and never reaches past this interface — protocol-specific behavior
 * lives entirely inside the implementation.
 *
 * Lifecycle: [connect] → [load] → control calls (play/pause/seek/etc.) → [disconnect]. Adapters
 * are **not** reusable after [disconnect]; construct a fresh adapter via [CastAdapterFactory].
 *
 * Threading: all suspending functions are safe to call from any dispatcher. Adapters are
 * responsible for marshalling onto their own I/O context.
 *
 * Failure model: every mutator returns [Result] so callers can surface protocol errors without
 * try/catch noise. [events] mirrors connection failures via
 * [CastSessionEvent.ConnectionStateChanged] / [CastSessionEvent.Error] so observers don't need to
 * subscribe to every Result.
 */
interface ProtocolAdapter {

    val receiver: CastReceiver

    /**
     * Post-handshake capability set. Starts as [CastReceiver.capabilities] (what discovery
     * could infer) and is widened once the adapter has talked to the peer — e.g. the FCast
     * adapter adds [CastCapability.NativeAss] / [CastCapability.EmbeddedFonts] when the
     * `InitialReceiverMessage` carries a SpatialFin `appName`. Callers that need to gate
     * behavior on what the receiver can actually render (subtitle policy, SplitAv toggle)
     * must observe this flow rather than reading [CastReceiver.capabilities] directly.
     */
    val currentCapabilities: StateFlow<Set<CastCapability>>

    val events: SharedFlow<CastSessionEvent>

    suspend fun connect(): Result<Unit>

    suspend fun disconnect()

    suspend fun load(media: CastMedia): Result<Unit>

    suspend fun play(): Result<Unit>

    suspend fun pause(): Result<Unit>

    suspend fun seek(positionMs: Long): Result<Unit>

    suspend fun stop(): Result<Unit>

    /** Linear volume 0..1; adapters that natively use dB (AirPlay) convert internally. */
    suspend fun setVolume(volume: Float): Result<Unit>

    /**
     * Playback rate multiplier. Adapters whose protocol can't express variable speed (AirPlay v1)
     * return [Result.failure] with [UnsupportedOperationException]; callers should check the
     * receiver's [CastCapability.Speed] before calling.
     */
    suspend fun setSpeed(speed: Float): Result<Unit>
}
