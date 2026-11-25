package dev.jdtech.jellyfin.player.core.extractor.mp4

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import timber.log.Timber

/**
 * Streaming [DataSource] wrapper that sanitizes negative (`baseMediaDecodeTime` top bit set)
 * version-1 `tfdt` boxes in fragmented MP4 streams (`moof -> traf -> tfdt`) in-place before
 * Media3's [androidx.media3.extractor.mp4.FragmentedMp4Extractor] sees them.
 *
 * Background: When Jellyfin/FFmpeg transcodes audio with encoder priming delay (such as AAC
 * `-2048` or Opus `-312`) into fMP4 HLS (`movflags=+frag_discont`), segment 0's audio `tfdt` v1
 * box carries a two's-complement negative 64-bit value (e.g. `0xFFFFFFFFFFFFF800`). Media3's
 * `FragmentedMp4Extractor.parseTfdt()` calls `ParsableByteArray.readUnsignedLongToLong()`, which
 * throws `IllegalStateException: Top bit not zero` when bit 63 is set. Clamping negative
 * `tfdt` v1 values in-place to `0` preserves box sizes/offsets and leaves non-fMP4 / direct-play
 * streams byte-for-byte untouched.
 */
@UnstableApi
class Fmp4NegativeTfdtSanitizingDataSource(
    private val delegate: DataSource,
) : DataSource {

    class Factory(
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            Fmp4NegativeTfdtSanitizingDataSource(upstream.createDataSource())
    }

    private var currentUriForLog: String? = null
    private var passthrough = false
    private var seenFirstBox = false
    private var state = STATE_READING_HEADER

    private var streamPos = 0L
    private val headerBuf = ByteArray(8)
    private var headerPos = 0
    private val largeSizeBuf = ByteArray(8)
    private var largeSizePos = 0

    private var currentBoxStart = 0L
    private var currentBoxType = 0
    private var skipRemaining = 0L

    private val containerEnd = LongArray(MAX_CONTAINER_DEPTH)
    private val containerType = IntArray(MAX_CONTAINER_DEPTH)
    private var stackDepth = 0

    private var tfdtPos = 0
    private var tfdtVersion = 0
    private var tfdtFlags = 0
    private var clampNegativeTfdt = false
    private var originalNegativeTfdt = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        resetParser(dataSpec.uri.toString())
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = delegate.read(buffer, offset, length)
        if (passthrough || bytesRead <= 0) {
            return bytesRead
        }
        sanitizeChunk(buffer, offset, bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        try {
            delegate.close()
        } finally {
            resetParser(null)
        }
    }

    private fun resetParser(uri: String?) {
        currentUriForLog = uri
        passthrough = false
        seenFirstBox = false
        state = STATE_READING_HEADER
        streamPos = 0L
        headerPos = 0
        largeSizePos = 0
        currentBoxStart = 0L
        currentBoxType = 0
        skipRemaining = 0L
        stackDepth = 0
        tfdtPos = 0
        tfdtVersion = 0
        tfdtFlags = 0
        clampNegativeTfdt = false
        originalNegativeTfdt = 0L
    }

    private fun sanitizeChunk(buffer: ByteArray, offset: Int, bytesRead: Int) {
        var i = offset
        val end = offset + bytesRead
        while (i < end && !passthrough) {
            when (state) {
                STATE_READING_HEADER -> {
                    if (headerPos == 0) {
                        currentBoxStart = streamPos
                    }
                    while (i < end && headerPos < 8) {
                        headerBuf[headerPos++] = buffer[i++]
                        streamPos++
                    }
                    if (headerPos == 8) {
                        val size32 = readUint32(headerBuf, 0)
                        currentBoxType = readInt32(headerBuf, 4)
                        when (size32) {
                            0L -> {
                                // Box extends to EOF (or invalid zero size): nothing further to parse.
                                passthrough = true
                                return
                            }
                            1L -> {
                                largeSizePos = 0
                                state = STATE_READING_LARGE_SIZE
                            }
                            else -> onBoxHeaderComplete(boxSize = size32, headerSize = 8L)
                        }
                    }
                }

                STATE_READING_LARGE_SIZE -> {
                    while (i < end && largeSizePos < 8) {
                        largeSizeBuf[largeSizePos++] = buffer[i++]
                        streamPos++
                    }
                    if (largeSizePos == 8) {
                        val largeSize = readInt64(largeSizeBuf, 0)
                        if (largeSize < 16L) {
                            passthrough = true
                            return
                        }
                        onBoxHeaderComplete(boxSize = largeSize, headerSize = 16L)
                    }
                }

                STATE_SKIPPING_PAYLOAD -> {
                    val avail = (end - i).toLong()
                    val step = minOf(skipRemaining, avail)
                    i += step.toInt()
                    streamPos += step
                    skipRemaining -= step
                    if (skipRemaining == 0L) {
                        popCompletedContainers()
                        headerPos = 0
                        state = STATE_READING_HEADER
                    }
                }

                STATE_IN_TFDT_PAYLOAD -> {
                    while (i < end && tfdtPos < TFDT_V1_PAYLOAD_SIZE) {
                        val b = buffer[i].toInt() and 0xFF
                        when (tfdtPos) {
                            0 -> {
                                tfdtVersion = b
                                tfdtFlags = 0
                                clampNegativeTfdt = false
                                originalNegativeTfdt = 0L
                            }
                            1, 2, 3 -> {
                                tfdtFlags = (tfdtFlags shl 8) or b
                            }
                            4 -> {
                                if (tfdtVersion == 1 && tfdtFlags == 0 && (b and 0x80) != 0) {
                                    clampNegativeTfdt = true
                                    originalNegativeTfdt = b.toLong()
                                    buffer[i] = 0
                                }
                            }
                            else -> {
                                if (clampNegativeTfdt) {
                                    originalNegativeTfdt = (originalNegativeTfdt shl 8) or b.toLong()
                                    buffer[i] = 0
                                }
                            }
                        }
                        tfdtPos++
                        i++
                        streamPos++
                    }
                    if (tfdtPos == TFDT_V1_PAYLOAD_SIZE) {
                        if (clampNegativeTfdt) {
                            Timber.w(
                                "Fmp4NegativeTfdtSanitizer: clamped negative tfdt v1 (%d -> 0) for %s",
                                originalNegativeTfdt,
                                currentUriForLog?.substringBefore('?'),
                            )
                        }
                        popCompletedContainers()
                        headerPos = 0
                        state = STATE_READING_HEADER
                    }
                }
            }
        }
    }

    private fun onBoxHeaderComplete(boxSize: Long, headerSize: Long) {
        if (boxSize < headerSize) {
            passthrough = true
            return
        }
        val boxEnd = currentBoxStart + boxSize
        if (boxEnd < currentBoxStart) {
            passthrough = true
            return
        }
        if (stackDepth > 0 && boxEnd > containerEnd[stackDepth - 1]) {
            passthrough = true
            return
        }
        if (!seenFirstBox) {
            seenFirstBox = true
            if (!isTopLevelIsoBmffBox(currentBoxType)) {
                passthrough = true
                return
            }
            if ((currentBoxType == BOX_STYP || currentBoxType == BOX_FTYP) &&
                boxSize > MAX_FTYP_STYP_BYTES
            ) {
                passthrough = true
                return
            }
        }

        val payloadSize = boxSize - headerSize
        when {
            stackDepth == 0 && currentBoxType == BOX_MOOF -> {
                if (boxSize > MAX_MOOF_BYTES) {
                    passthrough = true
                    return
                }
                containerEnd[0] = boxEnd
                containerType[0] = BOX_MOOF
                stackDepth = 1
                popCompletedContainers()
                headerPos = 0
                state = STATE_READING_HEADER
            }

            stackDepth == 1 && containerType[0] == BOX_MOOF && currentBoxType == BOX_TRAF -> {
                containerEnd[1] = boxEnd
                containerType[1] = BOX_TRAF
                stackDepth = 2
                popCompletedContainers()
                headerPos = 0
                state = STATE_READING_HEADER
            }

            stackDepth == 2 &&
                containerType[0] == BOX_MOOF &&
                containerType[1] == BOX_TRAF &&
                currentBoxType == BOX_TFDT &&
                headerSize == 8L &&
                payloadSize == TFDT_V1_PAYLOAD_SIZE.toLong() -> {
                tfdtPos = 0
                tfdtVersion = 0
                tfdtFlags = 0
                clampNegativeTfdt = false
                originalNegativeTfdt = 0L
                state = STATE_IN_TFDT_PAYLOAD
            }

            payloadSize == 0L -> {
                popCompletedContainers()
                headerPos = 0
                state = STATE_READING_HEADER
            }

            else -> {
                skipRemaining = payloadSize
                state = STATE_SKIPPING_PAYLOAD
            }
        }
    }

    private fun popCompletedContainers() {
        while (stackDepth > 0 && streamPos >= containerEnd[stackDepth - 1]) {
            if (streamPos > containerEnd[stackDepth - 1]) {
                passthrough = true
                return
            }
            stackDepth--
        }
    }

    private companion object {
        private const val STATE_READING_HEADER = 0
        private const val STATE_READING_LARGE_SIZE = 1
        private const val STATE_SKIPPING_PAYLOAD = 2
        private const val STATE_IN_TFDT_PAYLOAD = 3

        private const val MAX_CONTAINER_DEPTH = 2
        private const val TFDT_V1_PAYLOAD_SIZE = 12
        private const val MAX_FTYP_STYP_BYTES = 1L * 1024L * 1024L
        private const val MAX_MOOF_BYTES = 16L * 1024L * 1024L

        private const val BOX_STYP = 0x73747970 // 'styp'
        private const val BOX_FTYP = 0x66747970 // 'ftyp'
        private const val BOX_MOOF = 0x6d6f6f66 // 'moof'
        private const val BOX_TRAF = 0x74726166 // 'traf'
        private const val BOX_TFDT = 0x74666474 // 'tfdt'
        private const val BOX_MOOV = 0x6d6f6f76 // 'moov'
        private const val BOX_SIDX = 0x73696478 // 'sidx'
        private const val BOX_PRFT = 0x70726674 // 'prft'
        private const val BOX_EMSG = 0x656d7367 // 'emsg'
        private const val BOX_FREE = 0x66726565 // 'free'
        private const val BOX_SKIP = 0x736b6970 // 'skip'
        private const val BOX_MDAT = 0x6d646174 // 'mdat'
        private const val BOX_UUID = 0x75756964 // 'uuid'
        private const val BOX_META = 0x6d657461 // 'meta'
        private const val BOX_PDIN = 0x7064696e // 'pdin'

        private fun isTopLevelIsoBmffBox(type: Int): Boolean =
            when (type) {
                BOX_STYP,
                BOX_FTYP,
                BOX_MOOF,
                BOX_MOOV,
                BOX_SIDX,
                BOX_PRFT,
                BOX_EMSG,
                BOX_FREE,
                BOX_SKIP,
                BOX_MDAT,
                BOX_UUID,
                BOX_META,
                BOX_PDIN -> true
                else -> false
            }

        private fun readUint32(buf: ByteArray, off: Int): Long =
            ((buf[off].toLong() and 0xFFL) shl 24) or
                ((buf[off + 1].toLong() and 0xFFL) shl 16) or
                ((buf[off + 2].toLong() and 0xFFL) shl 8) or
                (buf[off + 3].toLong() and 0xFFL)

        private fun readInt32(buf: ByteArray, off: Int): Int =
            ((buf[off].toInt() and 0xFF) shl 24) or
                ((buf[off + 1].toInt() and 0xFF) shl 16) or
                ((buf[off + 2].toInt() and 0xFF) shl 8) or
                (buf[off + 3].toInt() and 0xFF)

        private fun readInt64(buf: ByteArray, off: Int): Long =
            ((buf[off].toLong() and 0xFFL) shl 56) or
                ((buf[off + 1].toLong() and 0xFFL) shl 48) or
                ((buf[off + 2].toLong() and 0xFFL) shl 40) or
                ((buf[off + 3].toLong() and 0xFFL) shl 32) or
                ((buf[off + 4].toLong() and 0xFFL) shl 24) or
                ((buf[off + 5].toLong() and 0xFFL) shl 16) or
                ((buf[off + 6].toLong() and 0xFFL) shl 8) or
                (buf[off + 7].toLong() and 0xFFL)
    }
}
