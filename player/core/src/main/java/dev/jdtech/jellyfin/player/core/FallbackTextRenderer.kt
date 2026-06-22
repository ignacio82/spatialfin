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
                return delegateCaps.supportsFormat(format)
            }
            override fun getName(): String = "FallbackTextRenderer(${delegateCaps.name})"
        }
    }
}
