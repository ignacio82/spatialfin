package dev.jdtech.jellyfin.player.core.extractor.mkv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * ExtractorsFactory wrapper that drops every text track exposed by MatroskaExtractor.
 *
 * Subtitles are sideloaded as separate [androidx.media3.common.MediaItem.SubtitleConfiguration]
 * media sources built from Jellyfin's per-stream delivery URL (or, for offline downloads, from
 * the pre-downloaded .ass/.srt alongside the media file). The embedded MKV subtitle tracks are
 * therefore redundant — and for files whose ContentEncoding declares an empty ContentCompression
 * element, Media3 1.10.0 forwards raw zlib-compressed block payloads as subtitle samples, which
 * renders as garbage on screen. Dropping the track at the extractor layer keeps only the clean
 * sideloaded track visible to the player.
 */
@UnstableApi
class ZlibSubtitleExtractorsFactory(
    subtitleParserFactory: SubtitleParser.Factory? = null,
) : ExtractorsFactory {

    private val delegate: DefaultExtractorsFactory = DefaultExtractorsFactory().apply {
        if (subtitleParserFactory != null) setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): ZlibSubtitleExtractorsFactory {
        delegate.setSubtitleParserFactory(factory)
        return this
    }

    @Deprecated("See Media3 DefaultExtractorsFactory", level = DeprecationLevel.WARNING)
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean): ZlibSubtitleExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
        return this
    }

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: android.net.Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        val name = extractor.javaClass.simpleName
        return if (name.contains("Matroska", ignoreCase = true)) {
            DropTextTracksExtractor(extractor)
        } else {
            extractor
        }
    }
}

@UnstableApi
internal class DropTextTracksExtractor(private val delegate: Extractor) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(DropTextExtractorOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate
}

@UnstableApi
private class DropTextExtractorOutput(private val delegate: ExtractorOutput) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        return if (type == C.TRACK_TYPE_TEXT) NoOpTrackOutput else delegate.track(id, type)
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@UnstableApi
private object NoOpTrackOutput : TrackOutput {
    override fun format(format: Format) = Unit

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
        val skip = ByteArray(length)
        val n = input.read(skip, 0, length)
        if (n == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw java.io.EOFException()
        }
        return n
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int = sampleData(input, length, allowEndOfInput)

    override fun sampleData(data: ParsableByteArray, length: Int) {
        data.skipBytes(length)
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        data.skipBytes(length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) = Unit
}
