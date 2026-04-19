package dev.jdtech.jellyfin.player.beam

import android.graphics.Bitmap
import dev.jdtech.jellyfin.player.xr.LibassRenderer as NativeLibassRenderer

data class RenderResult(
    val hasContent: Boolean,
    val bitmap: Bitmap? = null,
    val dirtyX: Int = 0,
    val dirtyY: Int = 0,
    val dirtyW: Int = 0,
    val dirtyH: Int = 0,
)

class LibassRenderer(
    width: Int,
    height: Int,
) {
    companion object {
        fun isAvailable(): Boolean = NativeLibassRenderer.isAvailable()
    }

    private val delegate = NativeLibassRenderer(width, height)

    /** Underlying XR libass renderer — exposed for direct use with xr.LibassTextRenderer. */
    val native: NativeLibassRenderer
        get() = delegate

    val hasActiveSubtitles: Boolean
        get() = delegate.hasActiveSubtitles

    fun init() = delegate.init()

    fun addFont(name: String, data: ByteArray) = delegate.addFont(name, data)

    fun setTrackData(codecPrivate: ByteArray) = delegate.setTrackData(codecPrivate)

    fun processChunk(data: ByteArray, startTimeMs: Long, durationMs: Long) =
        delegate.processChunk(data, startTimeMs, durationMs)

    fun clearCache() = delegate.clearCache()

    fun resize(newWidth: Int, newHeight: Int, storageW: Int = 0, storageH: Int = 0) =
        delegate.resize(newWidth, newHeight, storageW, storageH)

    fun renderFrame(timeMs: Long): RenderResult {
        val result = delegate.renderFrame(timeMs)
        return RenderResult(
            hasContent = result.hasContent,
            bitmap = result.bitmap,
            dirtyX = result.dirtyX,
            dirtyY = result.dirtyY,
            dirtyW = result.dirtyW,
            dirtyH = result.dirtyH,
        )
    }

    fun destroy() = delegate.destroy()
}
