package dev.jdtech.jellyfin.player.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioDelayProcessorTest {

    @Test
    fun `zero delay passes PCM through without buffering`() {
        val processor = PcmAudioDelayProcessor { 0L }
        val format = AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT) // 2 bytes per frame
        processor.configure(format)
        processor.flush(StreamMetadata.DEFAULT)

        val input = byteArrayOf(10, 20, 30, 40, 50, 60)
        processor.queueInput(ByteBuffer.wrap(input).order(ByteOrder.nativeOrder()))

        val out = readAvailableBytes(processor)
        assertArrayEquals(input, out)
        assertEquals(0, processor.currentBufferedBytesForTesting())
    }

    @Test
    fun `positive delay inserts leading silence and preserves FIFO sample order`() {
        var delayMs = 2L // At 1000 Hz mono 16-bit (2 bytes/frame), 2 ms = 2 frames = 4 bytes
        val processor = PcmAudioDelayProcessor { delayMs }
        val format = AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush(StreamMetadata.DEFAULT)

        val chunk1 = byteArrayOf(1, 2, 3, 4) // 2 frames (4 bytes)
        processor.queueInput(ByteBuffer.wrap(chunk1).order(ByteOrder.nativeOrder()))
        // First 4 bytes out should be silence while chunk1 is buffered
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), readAvailableBytes(processor))
        assertEquals(4, processor.currentBufferedBytesForTesting())

        val chunk2 = byteArrayOf(5, 6, 7, 8)
        processor.queueInput(ByteBuffer.wrap(chunk2).order(ByteOrder.nativeOrder()))
        // Next 4 bytes out should be chunk1 while chunk2 stays buffered
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), readAvailableBytes(processor))

        processor.queueEndOfStream()
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), readAvailableBytes(processor))
    }

    @Test
    fun `reducing or resetting positive delay immediately trims buffered frames`() {
        var delayMs = 4L // 4 frames = 8 bytes at 1000 Hz mono 16-bit
        val processor = PcmAudioDelayProcessor { delayMs }
        val format = AudioFormat(1_000, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush(StreamMetadata.DEFAULT)

        val initial = byteArrayOf(1, 1, 2, 2, 3, 3, 4, 4) // 4 frames
        processor.queueInput(ByteBuffer.wrap(initial).order(ByteOrder.nativeOrder()))
        assertArrayEquals(ByteArray(8), readAvailableBytes(processor))
        assertEquals(8, processor.currentBufferedBytesForTesting())

        // Reduce delay from 4 ms (8 bytes) to 1 ms (2 bytes): oldest 3 frames (6 bytes) should be trimmed,
        // leaving only frame [4, 4] in the buffer.
        delayMs = 1L
        val nextFrame = byteArrayOf(5, 5)
        processor.queueInput(ByteBuffer.wrap(nextFrame).order(ByteOrder.nativeOrder()))
        assertArrayEquals(byteArrayOf(4, 4), readAvailableBytes(processor))
        assertEquals(2, processor.currentBufferedBytesForTesting())

        // Reset delay to 0 ms: buffer should clear immediately and output live frame [6, 6]
        delayMs = 0L
        val liveFrame = byteArrayOf(6, 6)
        processor.queueInput(ByteBuffer.wrap(liveFrame).order(ByteOrder.nativeOrder()))
        assertArrayEquals(byteArrayOf(6, 6), readAvailableBytes(processor))
        assertEquals(0, processor.currentBufferedBytesForTesting())
    }

    private fun readAvailableBytes(processor: PcmAudioDelayProcessor): ByteArray {
        val output = processor.output
        val bytes = ByteArray(output.remaining())
        output.get(bytes)
        return bytes
    }
}
