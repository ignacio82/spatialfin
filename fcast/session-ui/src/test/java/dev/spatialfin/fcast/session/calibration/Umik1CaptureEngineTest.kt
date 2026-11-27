package dev.spatialfin.fcast.session.calibration

import android.content.Context
import android.media.AudioFormat
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationSweepStartResponse
import dev.jdtech.jellyfin.core.audio.VoiceCaptureSuspensionRegistry
import io.mockk.mockk
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Umik1CaptureEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `computeCaptureDurationUs adds safety lead and 1_5s tail to relative sweep span`() {
        val durationUs = Umik1CaptureEngine.computeCaptureDurationUs(
            t0RefAvrUs = 100_000_000L,
            tendRefAvrUs = 108_000_000L,
        )
        // 8s span + 0.5s lead + 1.5s tail = 10s
        assertEquals(10_000_000L, durationUs)
    }

    @Test
    fun `StreamingWav24Writer patches RIFF and data chunk sizes and packs 24-bit PCM`() {
        val outFile = File(tempFolder.root, "sweep24.wav")
        WavWriter.StreamingWav24Writer(outFile, sampleRateHz = 48_000).use { writer ->
            writer.writeFloatSamples(floatArrayOf(0.0f, 1.0f, -1.0f, 0.5f))
        }
        val bytes = outFile.readBytes()
        assertEquals(WavWriter.byteSize24(4L).toInt(), bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 12, bb.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(48_000, bb.getInt(24))
        assertEquals(48_000 * 3, bb.getInt(28))
        assertEquals(3.toShort(), bb.getShort(32))
        assertEquals(24.toShort(), bb.getShort(34))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(12, bb.getInt(40))
    }

    @Test
    fun `recordAndProcessSweep pre-opens mic suspends voice cleans temp WAV and aborts on negative read`() = runBlocking {
        var nowUs = 1_000_000L
        val abortedSweeps = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()
        var observedMicSuspendedDuringStart = false
        var capturedWavPath: File? = null

        val engine = Umik1CaptureEngine(
            context = mockk<Context>(relaxed = true),
            clockBoottimeUs = { nowUs },
        )

        // 1. Happy path: pre-open happens BEFORE startServerSweep, voice lease held, temp file deleted in finally.
        val goodBackend = object : Umik1CaptureEngine.AudioCaptureBackend {
            override val sampleRateHz: Int = 48_000
            override val encoding: Int = AudioFormat.ENCODING_PCM_FLOAT
            override val usedUnprocessedSource: Boolean = true
            override fun startRecording() {
                events += "audio_start"
            }
            override fun readFloat(buffer: FloatArray, offset: Int, size: Int): Int {
                buffer[offset] = 0.25f
                buffer[offset + 1] = -0.25f
                nowUs += 2_000_000L
                return 2
            }
            override fun stopAndRelease() {
                events += "audio_stop"
            }
        }

        val samplesCount = engine.recordAndProcessSweep(
            preferredDevice = null,
            cacheDir = tempFolder.root,
            backendFactory = { goodBackend },
            startServerSweep = {
                events += "server_start"
                observedMicSuspendedDuringStart = VoiceCaptureSuspensionRegistry.isSuspended.value
                CalibrationSweepStartResponse(
                    sweepId = "sweep-1",
                    calibrationId = "cal-1",
                    t0RefAvrUs = 50_000_000L,
                    channelSchedule = emptyList(),
                    tendRefAvrUs = 52_000_000L,
                    sampleRateHz = 48_000,
                )
            },
            onAbortSweep = { abortedSweeps += it },
            onCapturedWavReady = { _, outcome ->
                capturedWavPath = outcome.wavFile
                assertTrue(outcome.wavFile.exists())
                outcome.samplesWritten
            },
        )

        assertEquals(listOf("audio_start", "server_start", "audio_stop"), events.toList())
        assertTrue(observedMicSuspendedDuringStart)
        assertFalse(VoiceCaptureSuspensionRegistry.isSuspended.value)
        assertTrue(samplesCount >= 2L)
        assertFalse(capturedWavPath!!.exists())
        assertTrue(abortedSweeps.isEmpty())

        // 2. Negative AudioRecord.read() error aborts server sweep cleanly and releases voice lease.
        val failingBackend = object : Umik1CaptureEngine.AudioCaptureBackend {
            override val sampleRateHz: Int = 48_000
            override val encoding: Int = AudioFormat.ENCODING_PCM_FLOAT
            override val usedUnprocessedSource: Boolean = false
            override fun startRecording() = Unit
            override fun readFloat(buffer: FloatArray, offset: Int, size: Int): Int = -6 // ERROR_DEAD_OBJECT
            override fun stopAndRelease() = Unit
        }
        try {
            engine.recordAndProcessSweep(
                preferredDevice = null,
                cacheDir = tempFolder.root,
                backendFactory = { failingBackend },
                startServerSweep = {
                    CalibrationSweepStartResponse(
                        sweepId = "sweep-err-2",
                        calibrationId = "cal-2",
                        t0RefAvrUs = 50_000_000L,
                        channelSchedule = emptyList(),
                        tendRefAvrUs = 52_000_000L,
                        sampleRateHz = 48_000,
                    )
                },
                onAbortSweep = { abortedSweeps += it },
                onCapturedWavReady = { _, _ -> Unit },
            )
            fail("Expected negative AudioRecord.read() to throw")
        } catch (_: IllegalStateException) {
            // Expected
        }
        assertEquals(listOf("sweep-err-2"), abortedSweeps.toList())
        assertFalse(VoiceCaptureSuspensionRegistry.isSuspended.value)
    }
}
