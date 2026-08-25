package dev.jdtech.jellyfin.repository

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import timber.log.Timber

/**
 * How SpatialFin should treat *bitstream* (passthrough) audio — handing an encoded
 * Dolby/DTS stream straight to an AV receiver or soundbar instead of decoding it to PCM
 * on the device.
 *
 * This is a two-sided decision and both sides must agree, which is why the mode is read
 * in two places:
 *
 * 1. **Server side** — [createPlaybackDeviceProfile] advertises the bitstream codecs to
 *    Jellyfin so it direct-plays them instead of transcoding to AAC. Without this the
 *    device profile only lists codecs `MediaCodecList` has a *decoder* for, so a TV box
 *    with no DTS/TrueHD decoder but a perfectly capable receiver behind it gets a
 *    needless server-side transcode and loses the surround mix.
 * 2. **Player side** — `AudioPassthroughSinks` in `:player:core` matches the sink's
 *    [android.media.AudioTrack] capabilities to the same decision, so local, SMB/NFS and
 *    downloaded files behave the same way as Jellyfin streams.
 */
enum class AudioPassthroughMode {
    /** Bitstream whatever the current audio route reports it can accept. The default. */
    AUTO,

    /** Never bitstream; always decode to PCM locally. */
    OFF,

    /**
     * Bitstream the full Dolby/DTS set regardless of what the route reports. For HDMI
     * chains (a TV between the player and the receiver, some eARC splitters) that
     * under-report the receiver's real capabilities. Produces silence if the chain
     * genuinely cannot decode the format.
     */
    FORCE,
    ;

    companion object {
        /** Parses the persisted preference value; unknown/absent values fall back to [AUTO]. */
        fun fromPreference(value: String?): AudioPassthroughMode =
            when (value?.lowercase()) {
                "off" -> OFF
                "force" -> FORCE
                else -> AUTO
            }
    }
}

/**
 * Probes the *currently routed* audio output for the encoded formats it can accept
 * directly, and maps them onto the ffmpeg/Jellyfin codec names used in a
 * [org.jellyfin.sdk.model.api.DirectPlayProfile].
 *
 * Routing matters: the answer changes when HDMI is plugged in, when the user changes the
 * Android TV "surround sound formats" setting, or when a Bluetooth headset takes over.
 * Detection is therefore deliberately *not* cached — it is a handful of static
 * `AudioTrack` queries, run once per playback start and once per audio-sink build.
 */
object AudioPassthroughDetector {

    /**
     * Probe format. The encoding is the only part being tested — sample rate and channel
     * mask just have to be legal, and this pair is what Media3's own `AudioCapabilities`
     * uses for the same query.
     */
    private const val PROBE_SAMPLE_RATE_HZ = 48_000
    private const val PROBE_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO

    /**
     * Encoded formats worth probing, paired with the codec names ffprobe (and therefore
     * Jellyfin's `MediaStream.Codec`) reports for them.
     *
     * The mapping tracks Media3's **degradation ladder**, which is the thing that makes a
     * single `dts`/`eac3` token safe to advertise for a whole family of streams.
     * `AudioCapabilities.getEncodingAndChannelConfigForPassthrough` rewrites the encoding
     * before asking whether the route supports it:
     *
     * - `E_AC3_JOC` → `E_AC3` when JOC is unsupported (Atmos objects are metadata on top of a
     *   plain E-AC3 stream, so the base stream still plays),
     * - `DTS_HD` → `DTS` and `DTS_UHD_P2` (DTS:X) → `DTS` when the HD encodings are
     *   unsupported (DTS-HD MA and DTS:X embed a backward-compatible DTS core).
     *
     * So the *base* encoding is the one that decides whether the family is playable, and it is
     * the only one mapped to a token here. Mapping `DTS_HD` to `dts` as well would let a route
     * that somehow reports DTS-HD but not DTS core claim `dts`, and a plain DTS stream would
     * then find no encoding to fall back to.
     *
     * The one member of the DTS family with no core to degrade to is **DTS Express** (LBR);
     * see the guard in [createPlaybackDeviceProfile].
     */
    private val PROBED_ENCODINGS: List<Pair<Int, List<String>>> = listOf(
        AudioFormat.ENCODING_AC3 to listOf("ac3"),
        // Also covers Atmos-carrying E-AC3 (JOC), which degrades to this encoding.
        AudioFormat.ENCODING_E_AC3 to listOf("eac3"),
        // Also covers DTS-HD HRA/MA and DTS:X, which degrade to this encoding.
        AudioFormat.ENCODING_DTS to listOf("dts"),
        AudioFormat.ENCODING_DOLBY_TRUEHD to listOf("truehd", "mlp"),
        AudioFormat.ENCODING_AC4 to listOf("ac4"),
    )

    /**
     * Encodings probed only to answer "does this route carry the *full* format, or just the
     * core it degrades to?". Not mapped to codec tokens — they share `dts`/`eac3` with their
     * base encoding — but [needsDtsExpressGuard] depends on the distinction.
     */
    private val EXTENDED_ENCODINGS: List<Int> = listOf(
        AudioFormat.ENCODING_E_AC3_JOC,
        AudioFormat.ENCODING_DTS_HD,
    )

    /** Everything [AudioPassthroughMode.FORCE] advertises, detection notwithstanding. */
    val ALL_BITSTREAM_CODECS: Set<String> =
        PROBED_ENCODINGS.flatMap { (_, codecs) -> codecs }.toSet()

    /**
     * Set once a passthrough attempt has actually failed at the [AudioTrack] layer, so the
     * rest of the process stops advertising and stops configuring bitstream output. Reset
     * on process death only — a chain that failed once will keep failing until the user
     * changes something, and re-probing would just reproduce the silence.
     */
    @Volatile
    private var disabledForSession: Boolean = false

    /** Called by the player when an audio track fails to initialise in a bitstream format. */
    fun disableForSession() {
        if (!disabledForSession) {
            disabledForSession = true
            Timber.w("Audio passthrough disabled for the rest of this process after an AudioTrack failure")
        }
    }

    /** Whether [disableForSession] has fired. Exposed for diagnostics and tests. */
    fun isDisabledForSession(): Boolean = disabledForSession

    /** Test seam — production code never re-enables passthrough within a process. */
    internal fun resetSessionDisableForTest() {
        disabledForSession = false
    }

    /**
     * The encoded formats the current route accepts, as raw [AudioFormat] encoding
     * constants. Empty on a device with no digital passthrough path (a headset, or a
     * phone on its own speakers) — which is the normal case on Galaxy XR.
     */
    fun detectSupportedEncodings(): Set<Int> {
        val attributes = runCatching {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        }.getOrElse { e ->
            Timber.w(e, "AudioPassthroughDetector: no AudioAttributes available; assuming no passthrough")
            return emptySet()
        }
        val candidates = PROBED_ENCODINGS.map { it.first } + EXTENDED_ENCODINGS
        return candidates.mapNotNull { encoding ->
            val supported = runCatching {
                val format = AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(PROBE_SAMPLE_RATE_HZ)
                    .setChannelMask(PROBE_CHANNEL_MASK)
                    .build()
                AudioTrack.isDirectPlaybackSupported(format, attributes)
            }.getOrElse { e ->
                Timber.w(e, "AudioPassthroughDetector: probe failed for encoding %d", encoding)
                false
            }
            encoding.takeIf { supported }
        }.toSet()
    }

    /**
     * The codec names to advertise to Jellyfin for [mode].
     *
     * ffprobe names every DTS variant `dts`, so advertising it because the route accepts DTS
     * core also invites DTS-HD MA and DTS:X. That is fine and intended — see the degradation
     * ladder on [PROBED_ENCODINGS]: Media3 bitstreams those as their embedded core, which is
     * strictly better than the server-side AAC transcode the alternative would produce.
     * **DTS Express is the exception** (it is LBR-only, with no core to fall back to) and is
     * excluded by a codec profile rather than by withholding the whole `dts` token; see
     * [needsDtsExpressGuard].
     */
    fun supportedCodecs(mode: AudioPassthroughMode): Set<String> {
        if (disabledForSession) return emptySet()
        return when (mode) {
            AudioPassthroughMode.OFF -> emptySet()
            AudioPassthroughMode.FORCE -> ALL_BITSTREAM_CODECS
            AudioPassthroughMode.AUTO -> {
                val encodings = detectSupportedEncodings()
                PROBED_ENCODINGS
                    .filter { (encoding, _) -> encoding in encodings }
                    .flatMap { (_, codecs) -> codecs }
                    .toSet()
            }
        }
    }

    /**
     * Whether the profile must exclude **DTS Express** from direct play.
     *
     * DTS Express (DTS-HD LBR) is the only DTS variant that carries no backward-compatible
     * core, so Media3's `DTS_HD → DTS` degradation writes LBR frames into an `AudioTrack`
     * configured for DTS core and the receiver renders silence or noise. Every other DTS
     * variant survives that rewrite.
     *
     * The guard is needed only when *both* are true:
     * - the route takes DTS core but not DTS-HD, so the lossy rewrite is what would happen; and
     * - no in-process decoder can handle DTS, so there is no safe landing spot.
     *
     * With a bundled FFmpeg decoder present the second condition fails and no guard is emitted
     * — restricting direct play there would force a server transcode of a stream the device
     * can decode perfectly well.
     */
    fun needsDtsExpressGuard(mode: AudioPassthroughMode): Boolean {
        if ("dts" !in supportedCodecs(mode)) return false
        if (SoftwareAudioDecoders.canDecodeDts()) return false
        return when (mode) {
            // FORCE claims DTS-HD as well, so the rewrite never happens.
            AudioPassthroughMode.FORCE -> false
            AudioPassthroughMode.OFF -> false
            AudioPassthroughMode.AUTO ->
                AudioFormat.ENCODING_DTS_HD !in detectSupportedEncodings()
        }
    }

    /**
     * ffmpeg's `profile` string for DTS Express, as Jellyfin reports it in
     * `MediaStream.Profile`. Matched case-sensitively by the server's condition processor.
     */
    const val DTS_EXPRESS_PROFILE = "DTS Express"
}
