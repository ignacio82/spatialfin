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
 * ExtractorsFactory that drops embedded MKV text tracks so only sideloaded subtitle
 * tracks (from Jellyfin's Subtitles endpoint) reach the player.
 *
 * Media3 1.10's MatroskaExtractor has two broken interactions with ASS/SSA blocks:
 *
 *   1. It wraps each block in a synthetic "Dialogue: H:MM:SS:CC,H:MM:SS:CC," prefix
 *      without first applying the MKV ContentEncoding. For tracks with zlib
 *      ContentCompAlgo=0, the result is `Dialogue: ...,<raw zlib bytes>` — the zlib
 *      stream survives but is sometimes truncated where the prefix synthesis bumps
 *      against the block-size budget.
 *   2. For files whose ContentEncoding declares an empty ContentCompression, Media3
 *      forwards raw zlib-compressed block payloads as subtitle samples.
 *
 * Both paths produce garbage samples. Rather than decompressing on the fly (which
 * requires reaching inside Media3's private extractor output), we drop the text
 * tracks and rely on Jellyfin's `/Subtitles/{streamIndex}/{startTimeTicks}/Stream.ass`
 * endpoint, which serves fully-decompressed ASS.
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
    override fun init(output: ExtractorOutput) = delegate.init(DropTextExtractorOutput(output))
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
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        sampleData(input, length, allowEndOfInput)
    override fun sampleData(data: ParsableByteArray, length: Int) { data.skipBytes(length) }
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) = Unit
}
