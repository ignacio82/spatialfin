package dev.jdtech.jellyfin.player.core.audio

import android.content.Context
import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.jdtech.jellyfin.repository.AudioPassthroughDetector
import dev.jdtech.jellyfin.repository.AudioPassthroughMode
import dev.jdtech.jellyfin.player.core.splitav.ReceiverAudioCodecs
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import timber.log.Timber

/**
 * Player-side half of the audio-passthrough decision.
 *
 * The Jellyfin device profile (`createPlaybackDeviceProfile` in `:data`) decides what the
 * *server* is allowed to send us; this decides what the [AudioSink] is allowed to hand to
 * the hardware. They must agree, and both read
 * `AppPreferences.playerAudioPassthrough`.
 *
 * Media3 already does the right thing on its own for [AudioPassthroughMode.AUTO] — a sink
 * built from a `Context` picks up the real route capabilities and bitstreams whatever the
 * route accepts — so AUTO returns `null` and the caller keeps `DefaultRenderersFactory`'s
 * own sink. The other two modes exist because the route's own answer is sometimes wrong in
 * both directions, and because a preference that only steered Jellyfin streams would leave
 * local, SMB/NFS and downloaded files behaving differently from everything else.
 */
object AudioPassthroughSinks {

    /**
     * Builds a capability-overriding [AudioSink] for the current preference, or `null` when
     * the caller should keep `DefaultRenderersFactory.buildAudioSink`'s default.
     *
     * Call from a `DefaultRenderersFactory.buildAudioSink` override, passing that method's
     * own arguments through:
     * ```
     * override fun buildAudioSink(context: Context, floatOut: Boolean, playbackParams: Boolean) =
     *     AudioPassthroughSinks.buildOverride(context, appPreferences, floatOut, playbackParams)
     *         ?: super.buildAudioSink(context, floatOut, playbackParams)
     * ```
     */
    @OptIn(UnstableApi::class)
    fun buildOverride(
        context: Context,
        appPreferences: AppPreferences,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        val mode = AudioPassthroughMode.fromPreference(
            appPreferences.getValue(appPreferences.playerAudioPassthrough),
        )
        val effectiveMode = if (AudioPassthroughDetector.isDisabledForSession()) {
            AudioPassthroughMode.OFF
        } else {
            mode
        }
        val capabilities = capabilitiesFor(context, effectiveMode) ?: return null
        Timber.i(
            "Audio sink: passthrough mode=%s (requested=%s) maxChannels=%d",
            effectiveMode,
            mode,
            capabilities.maxChannelCount,
        )
        return DefaultAudioSink.Builder(context)
            .setAudioCapabilities(capabilities)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    /**
     * Applies the same preference to the passthrough codec list an FCast receiver puts on its
     * split-A/V beacon.
     *
     * The beacon is the sender's only input when deciding direct-stream vs server transcode
     * (see [ReceiverAudioCodecs.canRenderDirect]). If this receiver has been told not to
     * bitstream, advertising AC-3 would invite a stream it now has to decode locally — and on
     * a TV box with no Dolby decoder, "locally" means "not at all". So the two sides agree
     * here for the same reason the device profile and the audio sink agree above.
     *
     * @param detected the live result of [ReceiverAudioCodecs.fromCapabilities].
     */
    fun advertisedCodecs(
        appPreferences: AppPreferences,
        detected: List<String>,
    ): List<String> {
        val mode = AudioPassthroughMode.fromPreference(
            appPreferences.getValue(appPreferences.playerAudioPassthrough),
        )
        if (AudioPassthroughDetector.isDisabledForSession()) return detected - ReceiverAudioCodecs.ALL_PASSTHROUGH_TOKENS
        return when (mode) {
            AudioPassthroughMode.AUTO -> detected
            AudioPassthroughMode.OFF -> detected - ReceiverAudioCodecs.ALL_PASSTHROUGH_TOKENS
            AudioPassthroughMode.FORCE ->
                (detected + ReceiverAudioCodecs.ALL_PASSTHROUGH_TOKENS).distinct()
        }
    }

    @OptIn(UnstableApi::class)
    private fun capabilitiesFor(context: Context, mode: AudioPassthroughMode): AudioCapabilities? =
        when (mode) {
            // Media3's own context-derived capabilities are exactly what AUTO wants, and
            // they refresh on HDMI plug/unplug — something a snapshot taken here would not.
            AudioPassthroughMode.AUTO -> null

            // Strip every encoded format but keep the route's real channel count, so
            // "off" means "decode locally", not "downmix to stereo".
            AudioPassthroughMode.OFF -> AudioCapabilities(
                intArrayOf(AudioFormat.ENCODING_PCM_16BIT),
                routeMaxChannelCount(context),
            )

            // Claim the full Dolby/DTS set on top of whatever the route admits to, for
            // HDMI chains that under-report the receiver behind them.
            AudioPassthroughMode.FORCE -> {
                val forced = FORCED_ENCODINGS + AudioFormat.ENCODING_PCM_16BIT +
                    AudioPassthroughDetector.detectSupportedEncodings()
                AudioCapabilities(
                    forced.toIntArray(),
                    maxOf(routeMaxChannelCount(context), FORCED_MAX_CHANNEL_COUNT),
                )
            }
        }

    @OptIn(UnstableApi::class)
    private fun routeMaxChannelCount(context: Context): Int =
        runCatching { AudioCapabilities.getCapabilities(context).maxChannelCount }
            .getOrElse { e ->
                Timber.w(e, "AudioPassthroughSinks: could not read route capabilities")
                AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES.maxChannelCount
            }

    /** 7.1 — the widest layout any of the bitstream formats below carries in practice. */
    private const val FORCED_MAX_CHANNEL_COUNT = 8

    private val FORCED_ENCODINGS = setOf(
        AudioFormat.ENCODING_AC3,
        AudioFormat.ENCODING_E_AC3,
        AudioFormat.ENCODING_E_AC3_JOC,
        AudioFormat.ENCODING_DTS,
        AudioFormat.ENCODING_DTS_HD,
        AudioFormat.ENCODING_DOLBY_TRUEHD,
        AudioFormat.ENCODING_AC4,
    )
}
