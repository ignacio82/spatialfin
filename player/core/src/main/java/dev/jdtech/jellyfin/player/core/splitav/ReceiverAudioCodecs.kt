package dev.jdtech.jellyfin.player.core.splitav

import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * Single source of truth for "can this receiver render this audio codec without a server
 * transcode?" — used on the receiver to advertise its capabilities and on the sender to pick
 * direct-stream vs transcode for split-A/V.
 *
 * The decision is **capability-driven, never a hardcoded codec table**, so one build behaves
 * correctly across every setup:
 *  - a full TrueHD/DTS-HD AVR over eARC → keeps lossless TrueHD/DTS-HD direct-streamed,
 *  - a Dolby-Digital-Plus/Atmos soundbar (e.g. the Google TV Streamer chain) → direct-streams
 *    E-AC-3 / E-AC-3-JOC (Atmos) but transcodes TrueHD/DTS-HD it can't passthrough,
 *  - a PCM-only TV / phone speaker → transcodes everything that needs passthrough.
 *
 * Two codec classes:
 *  - [UNIVERSAL_SOFTWARE] — Android always has an in-process decoder, so they render
 *    regardless of the HDMI chain. Never need a passthrough capability.
 *  - passthrough-gated (AC-3, E-AC-3, E-AC-3-JOC, TrueHD, DTS, DTS-HD) — no reliable Android
 *    software decoder; renderable only if the chain advertises passthrough for them. These
 *    are exactly what the receiver reports in [fromCapabilities].
 *
 * Tokens are lowercase and match Jellyfin's `MediaStream.codec` so the sender can compare the
 * source codec directly without a translation table.
 */
@OptIn(UnstableApi::class)
object ReceiverAudioCodecs {

    /** Codecs Android decodes in software on every device — safe to direct-stream anywhere. */
    val UNIVERSAL_SOFTWARE: Set<String> =
        dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens.UNIVERSAL_SOFTWARE

    /**
     * Android `AudioFormat` encoding → Jellyfin codec token. Only the passthrough-gated
     * encodings: their presence in [AudioCapabilities] is the *only* signal that the chain
     * can render them. PCM is the floor (always present) and implies the universal-software
     * set is renderable; it is reported so the sender can tell a real beacon from "unknown".
     */
    /**
     * Every passthrough-gated token this object can report — i.e. [ENCODING_TO_TOKEN] minus
     * the PCM floor. Used by `AudioPassthroughSinks.advertisedCodecs` when the user forces
     * passthrough on for a chain that under-reports itself.
     */
    val ALL_PASSTHROUGH_TOKENS: Set<String> =
        dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens.ALL_PASSTHROUGH_TOKENS

    private val ENCODING_TO_TOKEN: List<Pair<Int, String>> = listOf(
        AudioFormat.ENCODING_AC3 to "ac3",
        AudioFormat.ENCODING_E_AC3 to "eac3",
        AudioFormat.ENCODING_E_AC3_JOC to "eac3-joc",
        AudioFormat.ENCODING_DOLBY_TRUEHD to "truehd",
        AudioFormat.ENCODING_DTS to "dts",
        AudioFormat.ENCODING_DTS_HD to "dts-hd",
        AudioFormat.ENCODING_PCM_16BIT to "pcm",
    )

    /**
     * The receiver's passthrough-capable codec tokens, derived from the live audio sink. This
     * is what goes on the beacon's `supportedAudioCodecs`.
     */
    fun fromCapabilities(caps: AudioCapabilities): List<String> =
        ENCODING_TO_TOKEN.filter { (enc, _) -> caps.supportsEncoding(enc) }.map { it.second }

    /** Normalize Jellyfin / Media3 codec spellings to the canonical tokens above. */
    fun normalize(codec: String?): String =
        dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens.normalize(codec)

    /**
     * Can the receiver render [sourceCodec] directly (no server transcode)?
     *
     * @param receiverPassthrough tokens from a beacon, or null when the receiver hasn't
     *   reported yet. When null we are conservative: only the universal-software set is
     *   considered safe, so a never-before-seen receiver gets correct audio on the first cast
     *   and the sender self-corrects (widens to direct-stream) once a real beacon arrives.
     */
    fun canRenderDirect(sourceCodec: String?, receiverPassthrough: List<String>?): Boolean =
        dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens.canRenderDirect(
            sourceCodec = sourceCodec,
            receiverPassthrough = receiverPassthrough,
        )

    /**
     * Best→worst server-transcode target list, constrained to what the receiver can render,
     * for the "best possible audio when a transcode is unavoidable" path. Prefers multichannel
     * Dolby (E-AC-3 → AC-3) over stereo-ish AAC; AAC is the always-present floor so the result
     * is never silent. (Jellyfin's transcoder cannot emit E-AC-3-JOC/Atmos, so the best a
     * transcode can do is multichannel E-AC-3 — full Atmos requires a direct-stream of a codec
     * the chain passes through.)
     */
    fun preferredTranscodeCodecs(receiverPassthrough: List<String>?): List<String> =
        dev.jdtech.jellyfin.cast.splitav.AudioCodecTokens.preferredTranscodeCodecs(
            receiverPassthrough = receiverPassthrough,
        )
}
