package dev.jdtech.jellyfin.player.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.jdtech.jellyfin.player.core.sync.AudioSubtitleSyncController
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Media3 [BaseAudioProcessor] that delays linear PCM audio (`C.ENCODING_PCM_16BIT` and
 * `C.ENCODING_PCM_FLOAT`) when a positive audio synchronization offset (`audioOffsetMs > 0L`)
 * is active.
 *
 * - When `delayMs <= 0L` and the internal ring buffer is empty, acts as a zero-allocation
 *   pass-through.
 * - When `delayMs > 0L`, lazily allocates a circular byte buffer sized to the target delay capacity
 *   (capped at [AudioSubtitleSyncController.MAX_POSITIVE_PCM_DELAY_MS]) and emits leading silence
 *   while buffering incoming PCM samples in FIFO order.
 * - When `delayMs` decreases mid-playback (or is reset to `0L`), immediately advances [readIndex]
 *   past excess buffered frames so the reduced delay takes effect on the very next audio buffer.
 */
@UnstableApi
class PcmAudioDelayProcessor(
    private val delayMsProvider: () -> Long,
) : BaseAudioProcessor() {

    private var ringBuffer: ByteArray = ByteArray(0)
    private var readIndex: Int = 0
    private var writeIndex: Int = 0
    private var bufferedBytes: Int = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.bytesPerFrame <= 0) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        val requestedDelayMs = delayMsProvider()
            .coerceIn(0L, AudioSubtitleSyncController.MAX_POSITIVE_PCM_DELAY_MS)
        val targetDelayFrames = ((requestedDelayMs * sampleRate) / 1_000L).toInt()
        val targetDelayBytes = targetDelayFrames * bytesPerFrame

        if (targetDelayBytes <= 0) {
            // If the user reset or reduced delay to 0 ms, drop any stale delayed samples so live
            // audio catches up immediately.
            if (bufferedBytes > 0) {
                clearRingBuffer()
            }
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }

        ensureCapacity(targetDelayBytes + remaining)

        // If the user reduced positive delay mid-playback, trim excess oldest frames immediately.
        if (bufferedBytes > targetDelayBytes) {
            val excessBytes = ((bufferedBytes - targetDelayBytes) / bytesPerFrame) * bytesPerFrame
            if (excessBytes > 0) {
                advanceReadIndex(excessBytes)
            }
        }

        val output = replaceOutputBuffer(remaining)
        var bytesLeft = remaining
        while (bytesLeft > 0) {
            val deficitBytes = (targetDelayBytes - bufferedBytes).coerceAtLeast(0)
            if (deficitBytes > 0) {
                // Fill ring buffer while emitting leading silence 1-for-1 with input frames.
                val silenceChunk = min(bytesLeft, deficitBytes)
                writeToRingFromBuffer(inputBuffer, silenceChunk)
                writeSilence(output, silenceChunk)
                bytesLeft -= silenceChunk
            } else {
                // Ring buffer is at target delay capacity: emit oldest buffered bytes, then append
                // new input bytes.
                val chunk = min(bytesLeft, targetDelayBytes.coerceAtLeast(bytesPerFrame))
                readFromRingToBuffer(output, chunk)
                writeToRingFromBuffer(inputBuffer, chunk)
                bytesLeft -= chunk
            }
        }
        output.flip()
    }

    override fun onQueueEndOfStream() {
        if (bufferedBytes > 0) {
            val output = replaceOutputBuffer(bufferedBytes)
            readFromRingToBuffer(output, bufferedBytes)
            output.flip()
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        clearRingBuffer()
    }

    override fun onReset() {
        clearRingBuffer()
        ringBuffer = ByteArray(0)
    }

    internal fun currentBufferedBytesForTesting(): Int = bufferedBytes

    private fun clearRingBuffer() {
        readIndex = 0
        writeIndex = 0
        bufferedBytes = 0
    }

    private fun ensureCapacity(requiredCapacity: Int) {
        if (ringBuffer.size >= requiredCapacity) return
        val newCapacity = maxOf(requiredCapacity, ringBuffer.size * 2, 4096)
        val newBuffer = ByteArray(newCapacity)
        if (bufferedBytes > 0) {
            val firstPart = min(bufferedBytes, ringBuffer.size - readIndex)
            System.arraycopy(ringBuffer, readIndex, newBuffer, 0, firstPart)
            val secondPart = bufferedBytes - firstPart
            if (secondPart > 0) {
                System.arraycopy(ringBuffer, 0, newBuffer, firstPart, secondPart)
            }
        }
        ringBuffer = newBuffer
        readIndex = 0
        writeIndex = bufferedBytes
    }

    private fun advanceReadIndex(bytesToDrop: Int) {
        val drop = min(bytesToDrop, bufferedBytes)
        if (drop <= 0 || ringBuffer.isEmpty()) return
        readIndex = (readIndex + drop) % ringBuffer.size
        bufferedBytes -= drop
    }

    private fun writeToRingFromBuffer(source: ByteBuffer, count: Int) {
        var remainingToWrite = count
        while (remainingToWrite > 0) {
            val contiguous = min(remainingToWrite, ringBuffer.size - writeIndex)
            source.get(ringBuffer, writeIndex, contiguous)
            writeIndex = (writeIndex + contiguous) % ringBuffer.size
            bufferedBytes += contiguous
            remainingToWrite -= contiguous
        }
    }

    private fun readFromRingToBuffer(dest: ByteBuffer, count: Int) {
        var remainingToRead = min(count, bufferedBytes)
        while (remainingToRead > 0) {
            val contiguous = min(remainingToRead, ringBuffer.size - readIndex)
            dest.put(ringBuffer, readIndex, contiguous)
            readIndex = (readIndex + contiguous) % ringBuffer.size
            bufferedBytes -= contiguous
            remainingToRead -= contiguous
        }
    }

    private fun writeSilence(dest: ByteBuffer, count: Int) {
        var remainingSilence = count
        while (remainingSilence > 0) {
            val chunk = min(remainingSilence, SILENCE_SCRATCH.size)
            dest.put(SILENCE_SCRATCH, 0, chunk)
            remainingSilence -= chunk
        }
    }

    companion object {
        private val SILENCE_SCRATCH = ByteArray(1024)
    }
}
