package dev.jdtech.jellyfin.player.xr

import android.content.Context
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackProvider
import androidx.xr.runtime.Session
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.PointSourceParams
import androidx.xr.scenecore.SpatialAudioTrack
import androidx.xr.scenecore.SpatialAudioTrackBuilder
import java.lang.ref.WeakReference
import timber.log.Timber

/**
 * Bridges Media3's [DefaultAudioSink.AudioTrackProvider] (constructed in `Activity.onCreate()`)
 * with Jetpack XR SceneCore's [SpatialAudioTrackBuilder] and [SpatialAudioTrack] APIs once the
 * XR [Session] and video [Entity] become available inside Compose.
 *
 * Decoded linear PCM tracks are anchored in 3D space to the cinema [SurfaceEntity] when playing
 * flat content, so dialogue and sound effects stay localized at the screen when the viewer turns
 * their head. If playback switches from flat video to `180°`/`360°` immersive projection (where
 * the video sphere is centered at `0 m` on the viewer's head), any already-spatialized
 * [AudioTrack] is re-bound to the forward horizon overlay entity (`2.0 m` depth) to prevent
 * in-head ear panning.
 */
@OptIn(UnstableApi::class)
@Suppress("DEPRECATION")
class XrSpatialAudioController : DefaultAudioSink.AudioTrackProvider {

    private val lock = Any()

    @Volatile
    private var spatialAudioCapable: Boolean = false

    private var sessionRef: WeakReference<Session>? = null
    private var targetEntityRef: WeakReference<Entity>? = null
    private var horizonEntityRef: WeakReference<Entity>? = null
    private var immersiveProjection: Boolean = false
    private var splitAvMuted: Boolean = false
    private var lastAudioTrackRef: WeakReference<AudioTrack>? = null
    private var lastConfig: AudioSink.AudioTrackConfig? = null
    private var trackBoundAsPointSource: Boolean = false

    override fun getAudioTrack(
        audioTrackConfig: AudioSink.AudioTrackConfig,
        audioAttributes: AudioAttributes,
        audioSessionId: Int,
        context: Context?,
    ): AudioTrack {
        val snapshot = synchronized(lock) {
            lastConfig = audioTrackConfig
            BindingSnapshot(
                session = sessionRef?.get(),
                targetEntity = targetEntityRef?.get(),
                horizonEntity = horizonEntityRef?.get(),
                immersiveProjection = immersiveProjection,
                splitAvMuted = splitAvMuted,
                spatialAudioCapable = spatialAudioCapable,
            )
        }

        val isPcm = Util.isEncodingLinearPcm(audioTrackConfig.encoding)
        val eligible = isSpatialPointSourceEligible(
            hasSpatialAudioCapability = snapshot.spatialAudioCapable && snapshot.session != null,
            hasTargetEntity = snapshot.targetEntity != null,
            immersiveProjection = snapshot.immersiveProjection,
            splitAvMuted = snapshot.splitAvMuted,
            isLinearPcm = isPcm,
            tunneling = audioTrackConfig.tunneling,
            offload = audioTrackConfig.offload,
        )

        var boundAtBuild = false
        val delegate = object : DefaultAudioTrackProvider() {
            override fun customizeAudioTrackBuilder(
                audioTrackBuilder: AudioTrack.Builder,
            ): AudioTrack.Builder {
                if (eligible) {
                    val activeSession = snapshot.session
                    val activeTarget = snapshot.targetEntity
                    if (activeSession != null && activeTarget != null) {
                        runCatching {
                            SpatialAudioTrackBuilder.setPointSourceParams(
                                activeSession,
                                audioTrackBuilder,
                                PointSourceParams(),
                                activeTarget,
                            )
                            boundAtBuild = true
                            Timber.i(
                                "XR_AUDIO: bound PointSourceParams at AudioTrack build encoding=%d sampleRate=%d",
                                audioTrackConfig.encoding,
                                audioTrackConfig.sampleRate,
                            )
                        }.onFailure { error ->
                            Timber.w(error, "XR_AUDIO: SpatialAudioTrackBuilder.setPointSourceParams failed")
                        }
                    }
                } else if (!isPcm) {
                    Timber.i("XR_AUDIO: skipped non-PCM encoding=%d", audioTrackConfig.encoding)
                } else if (snapshot.immersiveProjection) {
                    Timber.i("XR_AUDIO: skipped immersive projection at AudioTrack build")
                }
                return audioTrackBuilder
            }
        }

        val track = delegate.getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId, context)
        synchronized(lock) {
            lastAudioTrackRef = WeakReference(track)
            trackBoundAsPointSource = boundAtBuild
        }
        return track
    }

    /**
     * Updates the spatial audio target from the main Compose thread whenever the XR [Session],
     * video [SurfaceEntity], projection mode, Split-A/V role, or spatial audio capability changes.
     */
    fun updateTarget(
        session: Session?,
        targetEntity: Entity?,
        horizonEntity: Entity?,
        immersiveProjection: Boolean,
        splitAvMuted: Boolean,
        spatialAudioCapable: Boolean,
    ) {
        this.spatialAudioCapable = spatialAudioCapable
        val (track, config, wasBound) = synchronized(lock) {
            sessionRef = session?.let(::WeakReference)
            targetEntityRef = targetEntity?.let(::WeakReference)
            horizonEntityRef = horizonEntity?.let(::WeakReference)
            this.immersiveProjection = immersiveProjection
            this.splitAvMuted = splitAvMuted
            Triple(lastAudioTrackRef?.get(), lastConfig, trackBoundAsPointSource)
        }

        if (session == null || track == null || config == null) return
        if (runCatching { track.state != AudioTrack.STATE_INITIALIZED }.getOrDefault(true)) return

        val isPcm = Util.isEncodingLinearPcm(config.encoding)
        val eligibleForScreen = isSpatialPointSourceEligible(
            hasSpatialAudioCapability = spatialAudioCapable,
            hasTargetEntity = targetEntity != null,
            immersiveProjection = immersiveProjection,
            splitAvMuted = splitAvMuted,
            isLinearPcm = isPcm,
            tunneling = config.tunneling,
            offload = config.offload,
        )

        if (eligibleForScreen && targetEntity != null) {
            runCatching {
                SpatialAudioTrack.setPointSourceParams(
                    session,
                    track,
                    PointSourceParams(),
                    targetEntity,
                )
                synchronized(lock) {
                    trackBoundAsPointSource = true
                }
                Timber.i("XR_AUDIO: bound PointSourceParams to SurfaceEntity")
            }.onFailure { error ->
                Timber.w(error, "XR_AUDIO: SpatialAudioTrack.setPointSourceParams to SurfaceEntity failed")
            }
        } else if (wasBound && (immersiveProjection || splitAvMuted) && horizonEntity != null && spatialAudioCapable && isPcm) {
            // If an AudioTrack was already spatialized as a point source on videoEntity during flat
            // playback and the user switches to 180°/360° (moving videoEntity to 0m on the head),
            // re-bind the point source to the forward horizon overlay entity (2.0m depth).
            runCatching {
                SpatialAudioTrack.setPointSourceParams(
                    session,
                    track,
                    PointSourceParams(),
                    horizonEntity,
                )
                Timber.i(
                    "XR_AUDIO: bound PointSourceParams to horizon entity (immersive=%b splitAv=%b)",
                    immersiveProjection,
                    splitAvMuted,
                )
            }.onFailure { error ->
                Timber.w(error, "XR_AUDIO: SpatialAudioTrack.setPointSourceParams to horizon entity failed")
            }
        }
    }

    fun release() {
        spatialAudioCapable = false
        synchronized(lock) {
            sessionRef = null
            targetEntityRef = null
            horizonEntityRef = null
            lastAudioTrackRef = null
            lastConfig = null
            trackBoundAsPointSource = false
        }
    }

    private data class BindingSnapshot(
        val session: Session?,
        val targetEntity: Entity?,
        val horizonEntity: Entity?,
        val immersiveProjection: Boolean,
        val splitAvMuted: Boolean,
        val spatialAudioCapable: Boolean,
    )

    companion object {
        fun isSpatialPointSourceEligible(
            hasSpatialAudioCapability: Boolean,
            hasTargetEntity: Boolean,
            immersiveProjection: Boolean,
            splitAvMuted: Boolean,
            isLinearPcm: Boolean,
            tunneling: Boolean,
            offload: Boolean,
        ): Boolean =
            hasSpatialAudioCapability &&
                hasTargetEntity &&
                !immersiveProjection &&
                !splitAvMuted &&
                isLinearPcm &&
                !tunneling &&
                !offload
    }
}
