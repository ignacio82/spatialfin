package dev.jdtech.jellyfin.player.xr

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.decoder.DecoderInputBuffer
import java.nio.ByteBuffer

import androidx.media3.exoplayer.source.MediaSource

/**
 * Custom Media3 renderer that intercepts raw ASS/SSA subtitle data
 * and forwards it to LibassRenderer instead of using the built-in parser.
 *
 * For non-ASS subtitle formats, this renderer should not be used —
 * the default TextRenderer handles SRT, VTT, PGS, etc.
 */
@UnstableApi
class LibassTextRenderer(
    private val libassRenderer: LibassRenderer,
    private val onTrackInitialized: () -> Unit,
) : BaseRenderer(C.TRACK_TYPE_TEXT) {

    private var inputFormatReceived = false
    private val formatHolder = FormatHolder()
    private val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun getName(): String = "LibassTextRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        return if (mimeType == MimeTypes.TEXT_SSA || mimeType == "text/x-ssa") {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        val format = formats[0]
        if (!inputFormatReceived && format.initializationData.isNotEmpty()) {
            // Format.initializationData[0] contains the ASS codec private (header)
            val codecPrivate = format.initializationData[0]
            libassRenderer.setTrackData(codecPrivate)
            inputFormatReceived = true
            onTrackInitialized()
        }
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Read available samples from the SampleStream
        // Each sample is a raw ASS dialogue line
        while (true) {
            val result = readSource(formatHolder, buffer, /* readFlags= */ 0)
            if (result == C.RESULT_NOTHING_READ || result == C.RESULT_FORMAT_READ) break
            if (buffer.isEndOfStream) break

            val data = buffer.data ?: continue
            val bytes = ByteArray(data.remaining())
            data.get(bytes)

            val startMs = buffer.timeUs / 1000
            val durationMs = 5000L // default duration for ASS chunks in ExoPlayer

            libassRenderer.processChunk(bytes, startMs, durationMs)
            buffer.clear()
        }
    }

    override fun isReady(): Boolean = true
    override fun isEnded(): Boolean = false
}