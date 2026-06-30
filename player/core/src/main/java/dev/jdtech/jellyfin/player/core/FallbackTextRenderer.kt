package dev.jdtech.jellyfin.player.core

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.common.util.UnstableApi

@UnstableApi
class FallbackTextRenderer(private val delegate: TextRenderer) : Renderer by delegate {
    override fun getCapabilities(): RendererCapabilities {
        val delegateCaps = delegate.capabilities
        return object : RendererCapabilities by delegateCaps {
            override fun supportsFormat(format: Format): Int {
                val mimeType = format.sampleMimeType
                if (mimeType == MimeTypes.TEXT_SSA || mimeType == "text/x-ssa" ||
                    mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == MimeTypes.TEXT_VTT) {
                    return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                }
                // Jellyfin HLS transcodes can expose a subtitle stream whose codec the
                // muxer never mapped to a real MIME — ExoPlayer surfaces it as
                // `text/x-unknown`. The delegate TextRenderer reports it as
                // FORMAT_UNSUPPORTED_SUBTYPE, which still lets a forced track override
                // enable the renderer, and SubtitleDecoderFactory then throws
                // IllegalArgumentException ("unsupported MIME type: text/x-unknown"),
                // killing playback with ERROR_CODE_FAILED_RUNTIME_CHECK. Demote it to
                // FORMAT_UNSUPPORTED_TYPE (same as the libass-routed formats above) so
                // ExoPlayer never tries to decode it. There is nothing to fall back to —
                // an unknown format can't be rendered — so the track is simply inert.
                if (mimeType == null || mimeType == MimeTypes.TEXT_UNKNOWN || mimeType == "text/x-unknown") {
                    return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
                }
                return delegateCaps.supportsFormat(format)
            }
            override fun getName(): String = "FallbackTextRenderer(${delegateCaps.name})"
        }
    }
}
