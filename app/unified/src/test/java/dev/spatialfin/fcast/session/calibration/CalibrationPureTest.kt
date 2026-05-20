package dev.spatialfin.fcast.session.calibration

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChirpGeneratorTest {

    @Test
    fun `produces a buffer of expected length`() {
        val pcm = ChirpGenerator.generatePcm()
        assertEquals(ChirpGenerator.totalSamples, pcm.size)
        // Total seconds matches lead + N chirps + N gaps + tail
        val expectedMs = ChirpGenerator.LEAD_SILENCE_MS +
            ChirpGenerator.CHIRP_COUNT * (ChirpGenerator.CHIRP_DURATION_MS + ChirpGenerator.CHIRP_GAP_MS) +
            ChirpGenerator.TAIL_SILENCE_MS
        assertEquals(expectedMs / 1_000.0, ChirpGenerator.totalSeconds, 0.001)
    }

    @Test
    fun `lead silence is actually silent`() {
        val pcm = ChirpGenerator.generatePcm()
        val leadSamples = ChirpGenerator.SAMPLE_RATE_HZ * ChirpGenerator.LEAD_SILENCE_MS / 1_000
        for (i in 0 until leadSamples) {
            assertEquals(0, pcm[i].toInt())
        }
    }

    @Test
    fun `chirp regions have substantially higher RMS than silence regions`() {
        val pcm = ChirpGenerator.generatePcm()
        val chirpSamples = ChirpGenerator.SAMPLE_RATE_HZ * ChirpGenerator.CHIRP_DURATION_MS / 1_000

        fun rms(start: Int, len: Int): Double {
            var sumSq = 0.0
            for (i in start until start + len) {
                val v = pcm[i].toDouble()
                sumSq += v * v
            }
            return kotlin.math.sqrt(sumSq / len)
        }

        // Lead silence has zero RMS
        val silenceRms = rms(0, ChirpGenerator.SAMPLE_RATE_HZ * ChirpGenerator.LEAD_SILENCE_MS / 1_000)
        assertEquals(0.0, silenceRms, 0.0001)

        // Each chirp region's RMS should be loud — Hann-windowed peak is ~16k, RMS is the
        // sine RMS times window factor, which gives roughly 5_000+ for AMPLITUDE=0.5.
        ChirpGenerator.chirpOnsetsSeconds.forEach { onset ->
            val onsetSample = (onset * ChirpGenerator.SAMPLE_RATE_HZ).toInt()
            val chirpRms = rms(onsetSample, chirpSamples)
            assertTrue("chirp RMS at $onset = $chirpRms", chirpRms > 4_000.0)
        }
    }

    @Test
    fun `chirp template has expected length`() {
        val tpl = ChirpGenerator.chirpTemplate()
        assertEquals(
            ChirpGenerator.SAMPLE_RATE_HZ * ChirpGenerator.CHIRP_DURATION_MS / 1_000,
            tpl.size,
        )
    }
}

class WavWriterTest {

    @Test
    fun `header reports correct sizes for a known sample buffer`() {
        val samples = ShortArray(48_000) { (it % 100).toShort() }  // 1 second at 48kHz
        val bytes = WavWriter.toByteArray(samples, 48_000)
        assertEquals(WavWriter.byteSize(samples.size), bytes.size)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals('R'.code.toByte(), buf.get(0))
        assertEquals('I'.code.toByte(), buf.get(1))
        assertEquals('F'.code.toByte(), buf.get(2))
        assertEquals('F'.code.toByte(), buf.get(3))
        // riffSize = 36 + dataSize
        val riffSize = buf.getInt(4)
        assertEquals(36 + samples.size * 2, riffSize)
        // sample rate
        assertEquals(48_000, buf.getInt(24))
        // bits per sample
        assertEquals(16, buf.getShort(34).toInt())
        // data size
        assertEquals(samples.size * 2, buf.getInt(40))
    }

    @Test
    fun `body is little-endian PCM matching input`() {
        val samples = shortArrayOf(0, 1, -1, 32_767, -32_768)
        val bytes = WavWriter.toByteArray(samples, 48_000)
        val body = bytes.copyOfRange(44, bytes.size)
        val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) assertEquals(s, buf.short)
    }
}

class ChirpDetectorTest {

    @Test
    fun `detects a single chirp in otherwise quiet capture`() {
        // 0.5s silence + 100ms chirp + 0.5s silence
        val sampleRate = 48_000
        val silence = sampleRate / 2
        val chirpLen = sampleRate / 10
        val capture = ShortArray(silence + chirpLen + silence)
        val template = ChirpGenerator.chirpTemplate()
        // Chirp template length and our chirpLen match by construction
        for (i in 0 until chirpLen) capture[silence + i] = template[i]

        val result = ChirpDetector.detect(capture, sampleRate)
        assertEquals(1, result.onsets.size)
        // Onset detected within ~10ms of the true position (silence)
        val expectedSec = silence.toDouble() / sampleRate
        assertEquals(expectedSec, result.onsets[0].timeSeconds, 0.015)
    }

    @Test
    fun `detects all 5 chirps in the full calibration pattern`() {
        val pcm = ChirpGenerator.generatePcm()
        val result = ChirpDetector.detect(pcm, ChirpGenerator.SAMPLE_RATE_HZ)
        assertEquals(ChirpGenerator.CHIRP_COUNT, result.onsets.size)
        // Each detection should land within 15ms of the corresponding source onset
        ChirpGenerator.chirpOnsetsSeconds.forEachIndexed { i, expected ->
            val detected = result.onsets[i].timeSeconds
            assertEquals("chirp $i", expected, detected, 0.015)
        }
    }

    @Test
    fun `respects threshold when the chirp is buried in noise comparable to it`() {
        // Construct samples that are uniform low-amplitude noise — no clear peaks
        val sampleRate = 48_000
        val capture = ShortArray(sampleRate) { (it % 2 * 200 - 100).toShort() }
        val result = ChirpDetector.detect(capture, sampleRate)
        // No transients above 8x median → no onsets
        assertTrue("got ${result.onsets.size} false positives", result.onsets.isEmpty())
    }

    @Test
    fun `medianLatencyMs returns middle value for odd-sized list`() {
        assertEquals(50L, ChirpDetector.medianLatencyMs(listOf(10L, 50L, 200L)))
    }

    @Test
    fun `medianLatencyMs averages two middle values for even-sized list`() {
        assertEquals(40L, ChirpDetector.medianLatencyMs(listOf(20L, 30L, 50L, 100L)))
    }
}


class CalibrationRouteSelectorTest {

    @Test
    fun `preferred local address favors wifi over vpn and ethernet`() {
        val selected = CalibrationServer(ByteArray(0)).choosePreferredLocalIpv4(
            listOf(
                CalibrationServer.LocalIpv4Candidate(
                    hostAddress = "100.64.0.2",
                    interfaceName = "tailscale0",
                    supportsMulticast = true,
                ),
                CalibrationServer.LocalIpv4Candidate(
                    hostAddress = "192.168.1.25",
                    interfaceName = "wlan0",
                    supportsMulticast = true,
                ),
                CalibrationServer.LocalIpv4Candidate(
                    hostAddress = "10.0.0.5",
                    interfaceName = "eth0",
                    supportsMulticast = true,
                ),
            ),
        )
        assertEquals("192.168.1.25", selected?.hostAddress)
    }

    @Test
    fun `preferred local address keeps vpn as last resort`() {
        val selected = CalibrationServer(ByteArray(0)).choosePreferredLocalIpv4(
            listOf(
                CalibrationServer.LocalIpv4Candidate(
                    hostAddress = "100.64.0.2",
                    interfaceName = "tailscale0",
                    supportsMulticast = true,
                ),
            ),
        )
        assertEquals("100.64.0.2", selected?.hostAddress)
    }
}
