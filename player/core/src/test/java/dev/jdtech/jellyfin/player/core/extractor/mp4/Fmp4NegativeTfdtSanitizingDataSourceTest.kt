package dev.jdtech.jellyfin.player.core.extractor.mp4

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import dev.jdtech.jellyfin.player.core.external.PluginHttpDataSourceFactory
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Fmp4NegativeTfdtSanitizingDataSourceTest {

    @Test
    fun fragmentedMp4ExtractorFailsRawAndSucceedsThroughSanitizingDataSource() {
        val init = buildInitSegment()
        val videoPayload = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x65, 0x00)
        val audioPayload = byteArrayOf(0x21, 0x10, 0x04, 0x60)
        val seg0 = buildMediaSegment(
            track1TfdtV1 = 1001L,
            track2TfdtV1 = -2048L,
            videoPayload = videoPayload,
            audioPayload = audioPayload,
        )

        // 1. Raw bytes fail inside Media3 FragmentedMp4Extractor with "Top bit not zero".
        try {
            extractWithMedia3(listOf(init, seg0))
            fail("Expected IllegalStateException: Top bit not zero on raw negative tfdt")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Unexpected error message: ${e.message}",
                e.message?.contains("Top bit not zero") == true,
            )
        }

        // 2. Through Fmp4NegativeTfdtSanitizingDataSource, extraction succeeds and preserves data.
        val sanitizedSeg0 = readAllViaSanitizer(seg0, chunkSize = 4096)
        val extracted = extractWithMedia3(listOf(init, sanitizedSeg0))

        // Track 1 (video): tfdt=1001 at 24000 timescale + 0 composition offset -> 41708 us
        assertEquals(1, extracted.trackPtsUs[0]?.size)
        assertEquals(41_708L, extracted.trackPtsUs[0]?.first())
        // Track 2 (audio): clamped negative tfdt -2048 -> 0 us
        assertEquals(1, extracted.trackPtsUs[1]?.size)
        assertEquals(0L, extracted.trackPtsUs[1]?.first())

        // mdat bytes at the tail remain 100% identical
        val mdatLen = 8 + videoPayload.size + audioPayload.size
        assertArrayEquals(
            seg0.copyOfRange(seg0.size - mdatLen, seg0.size),
            sanitizedSeg0.copyOfRange(sanitizedSeg0.size - mdatLen, sanitizedSeg0.size),
        )
    }

    @Test
    fun chunkBoundaryAlignmentDoesNotAffectSanitization() {
        val seg0 = buildMediaSegment(
            track1TfdtV1 = 1001L,
            track2TfdtV1 = -2048L,
            videoPayload = ByteArray(16) { 0x11 },
            audioPayload = ByteArray(16) { 0x22 },
        )
        val baseline = readAllViaSanitizer(seg0, chunkSize = seg0.size)

        for (chunkSize in listOf(1, 2, 3, 5, 7, 13, 64, 4096)) {
            val result = readAllViaSanitizer(seg0, chunkSize = chunkSize)
            assertArrayEquals("Mismatch for chunkSize=$chunkSize", baseline, result)
        }
    }

    @Test
    fun nonNegativeTfdtV1AndTopBitSetTfdtV0AreUntouched() {
        for (positiveTfdt in listOf(0L, 1001L, 144_376L)) {
            val seg = buildMediaSegment(
                track1TfdtV1 = positiveTfdt,
                track2TfdtV1 = positiveTfdt + 512L,
                videoPayload = ByteArray(8) { 0x33 },
                audioPayload = ByteArray(8) { 0x44 },
            )
            for (chunkSize in listOf(1, 17, 4096)) {
                assertArrayEquals(
                    "Changed positive tfdt=$positiveTfdt with chunkSize=$chunkSize",
                    seg,
                    readAllViaSanitizer(seg, chunkSize = chunkSize),
                )
            }
        }

        // tfdt v0 has a 32-bit unsigned decode time (Media3 uses readUnsignedInt(), safe with bit 31 set).
        val tfdtV0Box = fullBox(
            "tfdt",
            version = 0,
            flags = 0,
            payload = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0x80000000.toInt()).array(),
        )
        val v0Moof = box("moof", box("mfhd", ByteArray(4)), box("traf", tfdtV0Box))
        val v0Segment = box("styp", "msdh".toByteArray() + ByteArray(8)) + v0Moof + box("mdat", ByteArray(8))
        assertArrayEquals(v0Segment, readAllViaSanitizer(v0Segment, chunkSize = 5))
    }

    @Test
    fun nonFmp4AndDirectPlayStreamsContainingTfdtBytesInsidePayloadRemainByteIdentical() {
        val negativeTfdtSignature = byteArrayOf(
            0x00, 0x00, 0x00, 0x14,
            't'.code.toByte(), 'f'.code.toByte(), 'd'.code.toByte(), 't'.code.toByte(),
            0x01, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xF8.toByte(), 0x00,
        )

        // MKV EBML header + embedded tfdt pattern
        val mkvStream = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x86.toByte(), 0x81.toByte()) +
            ByteArray(32) { 0x55 } + negativeTfdtSignature + ByteArray(32) { 0x66 }
        assertArrayEquals(mkvStream, readAllViaSanitizer(mkvStream, chunkSize = 7))

        // MPEG-TS sync byte + embedded tfdt pattern
        val tsStream = ByteArray(188) { idx -> if (idx == 0) 0x47 else 0x1F } +
            negativeTfdtSignature + ByteArray(168) { 0x2A }
        assertArrayEquals(tsStream, readAllViaSanitizer(tsStream, chunkSize = 11))

        // HLS m3u8 playlist + embedded tfdt bytes
        val m3u8Stream = "#EXTM3U\n#EXT-X-VERSION:7\n".toByteArray() + negativeTfdtSignature
        assertArrayEquals(m3u8Stream, readAllViaSanitizer(m3u8Stream, chunkSize = 3))

        // Progressive MP4 (ftyp + moov + mdat) where mdat contains the exact negative tfdt bytes
        val progressiveMp4 = box("ftyp", "isom".toByteArray() + ByteArray(8)) +
            box("moov", box("mvhd", ByteArray(96))) +
            box("mdat", ByteArray(12) { 0x77 } + negativeTfdtSignature + ByteArray(12) { 0x78 })
        for (chunkSize in listOf(1, 7, 1024)) {
            assertArrayEquals(
                "Progressive MP4 mdat modified with chunkSize=$chunkSize",
                progressiveMp4,
                readAllViaSanitizer(progressiveMp4, chunkSize = chunkSize),
            )
        }
    }

    @Test
    fun multiFragmentLargeSizeBoxAndReusableOpenCloseSanitizeCleanly() {
        val seg0Neg = buildMediaSegment(
            track1TfdtV1 = 1001L,
            track2TfdtV1 = -312L,
            videoPayload = ByteArray(6) { 0x01 },
            audioPayload = ByteArray(4) { 0x02 },
        )
        val expectedSeg0 = buildMediaSegment(
            track1TfdtV1 = 1001L,
            track2TfdtV1 = 0L,
            videoPayload = ByteArray(6) { 0x01 },
            audioPayload = ByteArray(4) { 0x02 },
        )
        val seg1Pos = buildMediaSegment(
            track1TfdtV1 = 73_073L,
            track2TfdtV1 = 144_376L,
            videoPayload = ByteArray(6) { 0x03 },
            audioPayload = ByteArray(4) { 0x04 },
        )

        // Reuse the same DataSource across open(init) -> close -> open(seg0) -> close -> open(seg1) -> close.
        val reusableDelegate = SwitchableDataSource()
        val sanitizer = Fmp4NegativeTfdtSanitizingDataSource(reusableDelegate)
        val init = buildInitSegment()

        reusableDelegate.payload = init
        assertArrayEquals(init, drainOpened(sanitizer, chunkSize = 13, url = "http://192.168.1.90:8096/init.mp4?ApiKey=secret"))

        reusableDelegate.payload = seg0Neg
        assertArrayEquals(expectedSeg0, drainOpened(sanitizer, chunkSize = 5, url = "http://192.168.1.90:8096/0.mp4?ApiKey=secret"))

        reusableDelegate.payload = seg1Pos
        assertArrayEquals(seg1Pos, drainOpened(sanitizer, chunkSize = 7, url = "http://192.168.1.90:8096/1.mp4?ApiKey=secret"))

        // Two-fragment stream with a 64-bit largesize (size32 == 1) top-level free box in between,
        // and tfdt placed as the last box in traf after a zero-payload empty leaf box.
        val largeFreeBox = largeBox("free", ByteArray(8) { 0x5A })
        val negTfdtLast = fullBox(
            "tfdt",
            version = 1,
            flags = 0,
            payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(-2048L).array(),
        )
        val clampedTfdtLast = fullBox(
            "tfdt",
            version = 1,
            flags = 0,
            payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(0L).array(),
        )
        val emptyLeaf = box("skip", ByteArray(0))
        val fragA = box("moof", box("traf", emptyLeaf, negTfdtLast))
        val expectedFragA = box("moof", box("traf", emptyLeaf, clampedTfdtLast))
        val combined = seg0Neg + largeFreeBox + fragA
        val expectedCombined = expectedSeg0 + largeFreeBox + expectedFragA
        assertArrayEquals(expectedCombined, readAllViaSanitizer(combined, chunkSize = 3))
    }

    @Test
    fun pluginHttpDataSourceFactoryCreatesSanitizingDataSource() {
        PluginHttpDataSourceFactory.resetForTests()
        val ds = PluginHttpDataSourceFactory.create().createDataSource()
        assertTrue(
            "Expected Fmp4NegativeTfdtSanitizingDataSource, got ${ds::class.java.name}",
            ds is Fmp4NegativeTfdtSanitizingDataSource,
        )
    }

    // ---- Helpers ----

    private class ChunkedByteArrayDataSource(
        private val bytes: ByteArray,
        private val maxReadBytes: Int,
    ) : DataSource {
        private val delegate = ByteArrayDataSource(bytes)
        private var currentUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long {
            currentUri = dataSpec.uri
            return delegate.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, minOf(length, maxReadBytes))
        override fun getUri(): Uri? = currentUri
        override fun close() = delegate.close()
    }

    private class SwitchableDataSource : DataSource {
        var payload: ByteArray = ByteArray(0)
        private var delegate: ByteArrayDataSource? = null
        private var currentUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            currentUri = dataSpec.uri
            return ByteArrayDataSource(payload).also { delegate = it }.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate!!.read(buffer, offset, length)
        override fun getUri(): Uri? = currentUri
        override fun close() {
            delegate?.close()
            delegate = null
        }
    }

    private fun readAllViaSanitizer(bytes: ByteArray, chunkSize: Int): ByteArray {
        val sanitizer = Fmp4NegativeTfdtSanitizingDataSource(ChunkedByteArrayDataSource(bytes, chunkSize))
        return drainOpened(sanitizer, chunkSize = chunkSize, url = "http://192.168.1.90:8096/seg.mp4?ApiKey=test")
    }

    private fun drainOpened(dataSource: DataSource, chunkSize: Int, url: String): ByteArray {
        dataSource.open(DataSpec(Uri.parse(url)))
        try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(chunkSize)
            while (true) {
                val n = dataSource.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            dataSource.close()
        }
    }

    private data class ExtractedResult(
        val trackPtsUs: Map<Int, List<Long>>,
    )

    private fun extractWithMedia3(segments: List<ByteArray>): ExtractedResult {
        val extractor = FragmentedMp4Extractor(
            SubtitleParser.Factory.UNSUPPORTED,
            FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
        )
        val trackPts = linkedMapOf<Int, MutableList<Long>>()
        extractor.init(
            object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    val pts = trackPts.getOrPut(id) { mutableListOf() }
                    return object : TrackOutput {
                        override fun format(format: Format) = Unit
                        override fun sampleData(
                            input: DataReader,
                            length: Int,
                            allowEndOfInput: Boolean,
                            sampleDataPart: Int,
                        ): Int {
                            val skip = ByteArray(minOf(length, 4096))
                            val n = input.read(skip, 0, skip.size)
                            if (n == C.RESULT_END_OF_INPUT) {
                                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                                throw EOFException()
                            }
                            return n
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
                        ) {
                            pts.add(timeUs)
                        }
                    }
                }
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) = Unit
            },
        )
        val posHolder = PositionHolder()
        for (segment in segments) {
            val input = DefaultExtractorInput(
                ByteArrayReader(segment),
                0L,
                segment.size.toLong(),
            )
            while (extractor.read(input, posHolder) == Extractor.RESULT_CONTINUE) {
                // parse entire segment
            }
        }
        return ExtractedResult(trackPts)
    }

    private class ByteArrayReader(private val data: ByteArray) : DataReader {
        private var pos = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    private fun buildInitSegment(): ByteArray {
        val ftyp = box("ftyp", "iso5".toByteArray() + intBytes(512) + "iso5iso6mp41".toByteArray())
        val mvhd = fullBox(
            "mvhd",
            version = 0,
            flags = 0,
            payload = ByteBuffer.allocate(96).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0) // creation
                putInt(0) // modification
                putInt(1000) // timescale
                putInt(0) // duration
                putInt(0x00010000) // rate 1.0
                putShort(0x0100) // volume 1.0
                position(position() + 10)
                // unity matrix (9 ints)
                putInt(0x00010000); putInt(0); putInt(0)
                putInt(0); putInt(0x00010000); putInt(0)
                putInt(0); putInt(0); putInt(0x40000000)
                position(position() + 24) // pre_defined
                putInt(3) // next_track_ID
            }.array(),
        )
        val videoTrak = buildVideoTrak(trackId = 1, timescale = 24_000, width = 1920, height = 1080)
        val audioTrak = buildAudioTrak(trackId = 2, timescale = 48_000, channels = 6, sampleRate = 48_000)
        val mvex = box("mvex", buildTrex(1), buildTrex(2))
        val moov = box("moov", mvhd, videoTrak, audioTrak, mvex)
        return ftyp + moov
    }

    private fun buildVideoTrak(trackId: Int, timescale: Int, width: Int, height: Int): ByteArray {
        val tkhd = buildTkhd(trackId, isAudio = false, width = width, height = height)
        val mdhd = buildMdhd(timescale)
        val hdlr = buildHdlr("vide", "VideoHandler")
        val vmhd = fullBox("vmhd", 0, 1, ByteArray(8))
        val dinf = buildDinf()
        val avcC = box(
            "avcC",
            byteArrayOf(
                0x01, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(),
                0xE1.toByte(), 0x00, 0x0A,
                0x67, 0x42, 0xC0.toByte(), 0x1E, 0xF4.toByte(), 0x03, 0xC0.toByte(), 0x11, 0x3F, 0x2A,
                0x01, 0x00, 0x04,
                0x68, 0xCE.toByte(), 0x38, 0x80.toByte(),
            ),
        )
        val avc1 = box(
            "avc1",
            ByteBuffer.allocate(78).order(ByteOrder.BIG_ENDIAN).apply {
                position(6)
                putShort(1) // data_reference_index
                position(24)
                putShort(width.toShort())
                putShort(height.toShort())
                putInt(0x00480000)
                putInt(0x00480000)
                position(position() + 4)
                putShort(1) // frame_count
                position(position() + 32) // compressorname
                putShort(0x0018) // depth
                putShort(-1)
            }.array() + avcC,
        )
        val stsd = fullBox("stsd", 0, 0, intBytes(1) + avc1)
        val stbl = box("stbl", stsd, emptyFullBox("stts"), emptyFullBox("stsc"), emptyStsz(), emptyFullBox("stco"))
        val minf = box("minf", vmhd, dinf, stbl)
        return box("trak", tkhd, box("mdia", mdhd, hdlr, minf))
    }

    private fun buildAudioTrak(trackId: Int, timescale: Int, channels: Int, sampleRate: Int): ByteArray {
        val tkhd = buildTkhd(trackId, isAudio = true, width = 0, height = 0)
        val mdhd = buildMdhd(timescale)
        val hdlr = buildHdlr("soun", "SoundHandler")
        val smhd = fullBox("smhd", 0, 0, ByteArray(4))
        val dinf = buildDinf()
        // Minimal ESDS with AudioSpecificConfig for 48kHz 5.1ch AAC-LC (0x11, 0xB0)
        val esds = fullBox(
            "esds",
            0,
            0,
            byteArrayOf(
                0x03, 0x19, 0x00, 0x02, 0x00,
                0x04, 0x11, 0x40, 0x15, 0x00, 0x00, 0x00, 0x00, 0x09, 0xC4.toByte(), 0x00, 0x00, 0x09, 0xC4.toByte(), 0x00,
                0x05, 0x02, 0x11, 0xB0.toByte(),
                0x06, 0x01, 0x02,
            ),
        )
        val mp4a = box(
            "mp4a",
            ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).apply {
                position(6)
                putShort(1) // data_reference_index
                position(position() + 8)
                putShort(channels.toShort())
                putShort(16) // sampleSize
                position(position() + 4)
                putInt(sampleRate shl 16)
            }.array() + esds,
        )
        val stsd = fullBox("stsd", 0, 0, intBytes(1) + mp4a)
        val stbl = box("stbl", stsd, emptyFullBox("stts"), emptyFullBox("stsc"), emptyStsz(), emptyFullBox("stco"))
        val minf = box("minf", smhd, dinf, stbl)
        return box("trak", tkhd, box("mdia", mdhd, hdlr, minf))
    }

    private fun buildMediaSegment(
        track1TfdtV1: Long,
        track2TfdtV1: Long,
        videoPayload: ByteArray,
        audioPayload: ByteArray,
    ): ByteArray {
        val styp = box("styp", "msdh".toByteArray() + intBytes(0) + "msdhmsix".toByteArray())
        val mfhd = fullBox("mfhd", 0, 0, intBytes(1))

        val videoTfhd = fullBox("tfhd", 0, 0x020000, intBytes(1)) // default-base-is-moof
        val videoTfdt = fullBox(
            "tfdt",
            version = 1,
            flags = 0,
            payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(track1TfdtV1).array(),
        )
        // trun flags 0x0301: data-offset-present | sample-duration-present | sample-size-present
        val videoTrunPlaceholder = buildSingleSampleTrun(dataOffset = 0, duration = 1001, size = videoPayload.size)

        val audioTfhd = fullBox("tfhd", 0, 0x020000, intBytes(2))
        val audioTfdt = fullBox(
            "tfdt",
            version = 1,
            flags = 0,
            payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(track2TfdtV1).array(),
        )
        val audioTrunPlaceholder = buildSingleSampleTrun(dataOffset = 0, duration = 1024, size = audioPayload.size)

        val moofSize = box("moof", mfhd, box("traf", videoTfhd, videoTfdt, videoTrunPlaceholder), box("traf", audioTfhd, audioTfdt, audioTrunPlaceholder)).size
        val videoDataOffset = moofSize + 8
        val audioDataOffset = moofSize + 8 + videoPayload.size

        val videoTraf = box("traf", videoTfhd, videoTfdt, buildSingleSampleTrun(videoDataOffset, 1001, videoPayload.size))
        val audioTraf = box("traf", audioTfhd, audioTfdt, buildSingleSampleTrun(audioDataOffset, 1024, audioPayload.size))
        val moof = box("moof", mfhd, videoTraf, audioTraf)
        val mdat = box("mdat", videoPayload + audioPayload)
        return styp + moof + mdat
    }

    private fun buildSingleSampleTrun(dataOffset: Int, duration: Int, size: Int): ByteArray =
        fullBox(
            "trun",
            version = 0,
            flags = 0x000301,
            payload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(1) // sample_count
                putInt(dataOffset)
                putInt(duration)
                putInt(size)
            }.array(),
        )

    private fun buildTkhd(trackId: Int, isAudio: Boolean, width: Int, height: Int): ByteArray =
        fullBox(
            "tkhd",
            version = 0,
            flags = 3,
            payload = ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0); putInt(0)
                putInt(trackId)
                putInt(0); putInt(0)
                putLong(0)
                putShort(0); putShort(0)
                putShort(if (isAudio) 0x0100 else 0)
                putShort(0)
                putInt(0x00010000); putInt(0); putInt(0)
                putInt(0); putInt(0x00010000); putInt(0)
                putInt(0); putInt(0); putInt(0x40000000)
                putInt(width shl 16)
                putInt(height shl 16)
            }.array(),
        )

    private fun buildMdhd(timescale: Int): ByteArray =
        fullBox(
            "mdhd",
            version = 0,
            flags = 0,
            payload = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0); putInt(0); putInt(timescale); putInt(0); putShort(0x55C4); putShort(0)
            }.array(),
        )

    private fun buildHdlr(handlerType: String, name: String): ByteArray =
        fullBox(
            "hdlr",
            0,
            0,
            ByteArray(4) + handlerType.toByteArray() + ByteArray(12) + name.toByteArray() + byteArrayOf(0),
        )

    private fun buildDinf(): ByteArray =
        box("dinf", fullBox("dref", 0, 0, intBytes(1) + fullBox("url ", 0, 1, ByteArray(0))))

    private fun buildTrex(trackId: Int): ByteArray =
        fullBox(
            "trex",
            0,
            0,
            ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(trackId); putInt(1); putInt(0); putInt(0); putInt(0)
            }.array(),
        )

    private fun emptyFullBox(type: String): ByteArray = fullBox(type, 0, 0, intBytes(0))
    private fun emptyStsz(): ByteArray = fullBox("stsz", 0, 0, intBytes(0) + intBytes(0))

    private fun fullBox(type: String, version: Int, flags: Int, payload: ByteArray): ByteArray {
        val vf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(((version and 0xFF) shl 24) or (flags and 0xFFFFFF))
            .array()
        return box(type, vf + payload)
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val contentLen = children.sumOf { it.size }
        val total = 8 + contentLen
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(total)
            put(type.toByteArray(Charsets.US_ASCII))
            for (c in children) put(c)
        }.array()
    }

    private fun largeBox(type: String, payload: ByteArray): ByteArray {
        val total = 16L + payload.size
        return ByteBuffer.allocate(total.toInt()).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1)
            put(type.toByteArray(Charsets.US_ASCII))
            putLong(total)
            put(payload)
        }.array()
    }

    private fun intBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
}
