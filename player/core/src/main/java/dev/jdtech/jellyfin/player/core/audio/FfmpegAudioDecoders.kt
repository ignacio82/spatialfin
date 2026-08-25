package dev.jdtech.jellyfin.player.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import dev.jdtech.jellyfin.repository.SoftwareAudioDecoders
import timber.log.Timber

/**
 * Installs the bundled FFmpeg extension as the answer to
 * [SoftwareAudioDecoders]'s "what else can this process decode?" question.
 *
 * `:data` builds the Jellyfin device profile but sits below Media3 in the module graph, so it
 * cannot ask `FfmpegLibrary` directly. This is the other end of that seam: `:player:core` owns
 * the FFmpeg dependency and hands `:data` a probe.
 *
 * Every player's `DefaultRenderersFactory` runs with `EXTENSION_RENDERER_MODE_ON`, which appends
 * `FfmpegAudioRenderer` *after* the platform renderers — so MediaCodec still wins wherever it
 * can, and the extension only picks up what the hardware cannot handle. That ordering is what
 * makes it safe to advertise these codecs to the server.
 */
object FfmpegAudioDecoders {

    /**
     * Registers the probe. Cheap and synchronous: it stores a lambda and does **not** touch the
     * native library. The first actual probe — and therefore the ~1.3 MB `libffmpegJNI.so` load
     * — happens on the first device-profile build, which already runs on `Dispatchers.IO`
     * moments before the renderer would have loaded it anyway. Doing it here instead would put
     * a native load on the cold-start path of every process, including TV.
     */
    @OptIn(UnstableApi::class)
    fun install() {
        SoftwareAudioDecoders.install { mimeType ->
            runCatching { FfmpegLibrary.supportsFormat(mimeType) }
                .getOrElse { e ->
                    Timber.w(e, "FfmpegAudioDecoders: supportsFormat(%s) failed", mimeType)
                    false
                }
        }
    }
}
