package dev.jdtech.jellyfin.repository

import timber.log.Timber

/**
 * Bundled software audio decoders that `MediaCodecList` knows nothing about.
 *
 * SpatialFin ships the Jellyfin build of the Media3 FFmpeg extension. Because every player's
 * `DefaultRenderersFactory` runs with `EXTENSION_RENDERER_MODE_ON`, `FfmpegAudioRenderer` is
 * appended after the platform renderers and picks up Dolby/DTS streams the device has no
 * hardware decoder for — on *every* form factor, since the extension is packaged into the one
 * unified APK. Without this registry the device profile only ever asked `MediaCodecList`, so a
 * Galaxy XR or a cheap TV box told Jellyfin it could not play AC-3/DTS and got a server-side
 * transcode for audio it could in fact decode locally.
 *
 * The probe lives behind an installable hook rather than a direct call because `:data` must not
 * depend on Media3 (it is the bottom of the module graph). `:player:core` owns the FFmpeg
 * dependency and installs the real implementation; `UnifiedApplication` wires it up on start.
 *
 * Installation registers a *lambda*, not a result: the first call is what loads the ~1.3 MB
 * native library, and it is deliberately deferred to the first device-profile build (already on
 * `Dispatchers.IO`, moments before the renderer would load it anyway) rather than paid at
 * process start on top of TV cold-start.
 */
object SoftwareAudioDecoders {

    /**
     * MIME types worth asking about, paired with the ffmpeg/Jellyfin codec names a positive
     * answer should add to the direct-play profile. Restricted to the surround formats that
     * are actually at risk of a needless transcode — everything else is either in
     * [AndroidCodecDetector.BASELINE_AUDIO_CODECS] already or not worth a native call.
     */
    private val PROBED_MIME_TYPES: List<Pair<String, List<String>>> = listOf(
        "audio/ac3" to listOf("ac3"),
        "audio/eac3" to listOf("eac3"),
        "audio/eac3-joc" to listOf("eac3"),
        "audio/true-hd" to listOf("truehd", "mlp"),
        "audio/vnd.dts" to listOf("dts", "dca"),
        "audio/vnd.dts.hd" to listOf("dts", "dca"),
        "audio/alac" to listOf("alac"),
    )

    @Volatile
    private var probe: ((String) -> Boolean)? = null

    @Volatile
    private var cached: Set<String>? = null

    /**
     * Installs the decoder probe. Call once per process, before playback; installing again
     * replaces the probe and drops the cache (the tests rely on that, production does not).
     */
    fun install(probe: (String) -> Boolean) {
        this.probe = probe
        cached = null
    }

    /** Test seam — restores the "no extension installed" state. */
    internal fun resetForTest() {
        probe = null
        cached = null
    }

    /**
     * Codec names the bundled software decoders can handle, or an empty set when no probe has
     * been installed (which is the safe answer: the profile then falls back to what
     * `MediaCodecList` reports, exactly as before this registry existed).
     */
    fun supportedCodecs(): Set<String> {
        cached?.let { return it }
        val activeProbe = probe ?: return emptySet()
        val detected = PROBED_MIME_TYPES
            .filter { (mimeType, _) ->
                runCatching { activeProbe(mimeType) }.getOrElse { e ->
                    Timber.w(e, "SoftwareAudioDecoders: probe failed for %s", mimeType)
                    false
                }
            }
            .flatMap { (_, codecs) -> codecs }
            .toSet()
        cached = detected
        Timber.i(
            "SoftwareAudioDecoders: bundled decoders cover [%s]",
            detected.sorted().joinToString(","),
        )
        return detected
    }

    /**
     * Whether a DTS stream can be decoded in-process. Used to decide whether the DTS Express
     * guard in [createPlaybackDeviceProfile] is needed — with a local decoder available there
     * is a safe landing spot when passthrough cannot carry the stream, so the guard would only
     * force a pointless server transcode.
     */
    fun canDecodeDts(): Boolean = "dts" in supportedCodecs()
}
