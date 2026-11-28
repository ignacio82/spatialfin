package dev.spatialfin.fcast.session.calibration

import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
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

class CalibrationTimingTest {

    @Test
    fun `v4 monotonic formula uses receiver audio sink sample mapped by clock offset`() {
        val latency = CalibrationOrchestrator.latencyFromBeacon(
            captureOnsetMonotonicMs = 10_500L,
            captureOnsetWallMs = 999_999L,
            sourceOnsetMs = 1_000L,
            beacon = PlaybackUpdateMessage(
                generationTime = 1L,
                state = 1,
                time = 1.2,
                audioSinkPositionUs = 1_240_000L,
                audioSinkSampleMonotonicMs = 8_000L,
            ),
            beaconReceivedWallMs = 999_000L,
            clockOffsetMs = 200L,
        )

        assertEquals(2_940L, latency)
    }

    @Test
    fun `v4 calibration ignores implausible audio sink epoch and uses player time`() {
        val latency = CalibrationOrchestrator.latencyFromBeacon(
            captureOnsetMonotonicMs = 10_500L,
            captureOnsetWallMs = 999_999L,
            sourceOnsetMs = 1_000L,
            beacon = PlaybackUpdateMessage(
                generationTime = 1L,
                state = 1,
                time = 1.240,
                duration = 10.0,
                monotonicSampleMs = 8_100L,
                audioSinkPositionUs = 1_000_007_834_989L,
                audioSinkSampleMonotonicMs = 8_000L,
            ),
            beaconReceivedWallMs = 999_000L,
            clockOffsetMs = 200L,
        )

        assertEquals(2_840L, latency)
    }

    @Test
    fun `calibration falls back to legacy wall clock math when v4 telemetry is absent`() {
        val latency = CalibrationOrchestrator.latencyFromBeacon(
            captureOnsetMonotonicMs = 10_500L,
            captureOnsetWallMs = 50_500L,
            sourceOnsetMs = 1_000L,
            beacon = PlaybackUpdateMessage(
                generationTime = 1L,
                state = 1,
                time = 1.240,
            ),
            beaconReceivedWallMs = 49_000L,
            clockOffsetMs = null,
        )

        assertEquals(1_740L, latency)
    }

    @Test
    fun `AudioRecord timestamp maps later frame timestamp back to first sample`() {
        val first = CalibrationCapture.firstSampleMonotonicMs(
            timestampFramePosition = 4_800L,
            timestampNanoTime = 1_100_000_000L,
            sampleRateHz = 48_000,
        )

        assertEquals(1_000L, first)

        val firstUs = CalibrationCapture.firstSampleBoottimeUs(
            timestampFramePosition = 4_800L,
            timestampNanoTime = 1_100_000_000L,
            sampleRateHz = 48_000,
        )
        assertEquals(1_000_000L, firstUs)
    }
}

class LipSyncAutoCalibrationTest {

    @Test
    fun `envelope-matched detector recovers 5 phase-inverted chirps in noise within 2 ms`() {
        val sampleRate = 48_000
        val template = TheaterLipSyncCalibrator.generateServerLipSyncChirpTemplate(sampleRate)
        val totalSamples = sampleRate * 4
        val rng = java.util.Random(42L)
        val capture = ShortArray(totalSamples) {
            // ~-44 dBFS ambient room noise floor (~18 dB below -18 dBFS chirp RMS)
            (rng.nextGaussian() * 140.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        val expectedOnsetSec = List(5) { idx -> 0.5 + idx * 0.5 }
        expectedOnsetSec.forEachIndexed { idx, onsetSec ->
            val startIdx = (onsetSec * sampleRate).toInt()
            // Alternate polarity to confirm phase-invariance across polarity inversions
            val polarity = if (idx % 2 == 0) -1 else 1
            for (i in template.indices) {
                val pos = startIdx + i
                if (pos < capture.size) {
                    val mixed = capture[pos].toInt() + polarity * template[i].toInt()
                    capture[pos] = mixed.coerceIn(-32768, 32767).toShort()
                }
            }
        }

        val detected = ChirpDetector.detectEnvelopeMatched(
            samples = capture,
            sampleRateHz = sampleRate,
            template = template,
            thresholdDb = 12.0,
            minSpacingMs = 250,
        )
        assertEquals(5, detected.onsets.size)
        expectedOnsetSec.forEachIndexed { i, expected ->
            val errMs = kotlin.math.abs(detected.onsets[i].timeSeconds - expected) * 1_000.0
            assertTrue("chirp $i error was ${errMs}ms", errMs <= 2.5)
            val snrDb = 20.0 * kotlin.math.log10(detected.onsets[i].snr)
            assertTrue("chirp $i SNR $snrDb dB < 12 dB", snrDb >= 12.0)
        }
    }

    @Test
    fun `solveLipSyncDelay recovers injected fixed_hw_delay_us within 5 ms after ToF subtraction`() {
        val sampleRate = 48_000
        val template = TheaterLipSyncCalibrator.generateServerLipSyncChirpTemplate(sampleRate)
        val injectedHwDelayUs = 142_000L
        val distanceM = 3.0
        val tofUs = TheaterLipSyncCalibrator.computeAcousticTofUs(distanceM)
        val clockOffsetUs = 25_000_000L // server - client
        val captureStartClientUs = 100_000_000L

        val scheduledServerTimesUs = List(5) { idx ->
            captureStartClientUs + clockOffsetUs + 600_000L + idx * 800_000L
        }

        val totalSamples = sampleRate * 5
        val rng = java.util.Random(7L)
        val capture = ShortArray(totalSamples) {
            (rng.nextGaussian() * 120.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        scheduledServerTimesUs.forEach { tSchedServerUs ->
            val tArriveClientUs = (tSchedServerUs - clockOffsetUs) + injectedHwDelayUs + tofUs
            val relUs = tArriveClientUs - captureStartClientUs
            val startIdx = ((relUs * sampleRate) / 1_000_000L).toInt()
            for (i in template.indices) {
                val pos = startIdx + i
                if (pos in capture.indices) {
                    val mixed = capture[pos].toInt() + template[i].toInt()
                    capture[pos] = mixed.coerceIn(-32768, 32767).toShort()
                }
            }
        }

        val result = TheaterLipSyncCalibrator.solveLipSyncDelay(
            samples = capture,
            sampleRateHz = sampleRate,
            captureStartBoottimeUs = captureStartClientUs,
            scheduledAvrUs = scheduledServerTimesUs,
            deltaClockUs = clockOffsetUs,
            clockUncertaintyUs = 500L,
            speakerDistanceMeters = distanceM,
        ).getOrThrow()

        assertEquals(5, result.validPulseCount)
        assertTrue("IQR ${result.iqrUs} us > 10 ms", result.iqrUs <= 10_000L)
        val errorUs = kotlin.math.abs(result.medianHwDelayUs - injectedHwDelayUs)
        assertTrue(
            "recovered ${result.medianHwDelayUs} us vs injected $injectedHwDelayUs us (err=$errorUs us)",
            errorUs <= 5_000L,
        )
    }

    @Test
    fun `solveLipSyncDelay rejects outlier pulse beyond 25 ms and fails on excessive IQR`() {
        val sampleRate = 48_000
        val template = TheaterLipSyncCalibrator.generateServerLipSyncChirpTemplate(sampleRate)
        val clockOffsetUs = 10_000_000L
        val captureStartClientUs = 50_000_000L
        val scheduled = List(5) { idx -> captureStartClientUs + clockOffsetUs + 500_000L + idx * 800_000L }

        // 4 pulses at +80ms, 1 corrupted outlier at +140ms (>25ms away from median)
        val offsetsUs = listOf(80_000L, 81_000L, 140_000L, 79_500L, 80_500L)
        val capture = ShortArray(sampleRate * 5)
        scheduled.forEachIndexed { idx, tSched ->
            val relUs = (tSched - clockOffsetUs) + offsetsUs[idx] - captureStartClientUs
            val startIdx = ((relUs * sampleRate) / 1_000_000L).toInt()
            for (i in template.indices) {
                if (startIdx + i in capture.indices) {
                    capture[startIdx + i] = template[i]
                }
            }
        }
        val cleanResult = TheaterLipSyncCalibrator.solveLipSyncDelay(
            samples = capture,
            sampleRateHz = sampleRate,
            captureStartBoottimeUs = captureStartClientUs,
            scheduledAvrUs = scheduled,
            deltaClockUs = clockOffsetUs,
            clockUncertaintyUs = 400L,
            speakerDistanceMeters = 0.0,
        ).getOrThrow()
        assertEquals(4, cleanResult.validPulseCount)
        assertTrue(kotlin.math.abs(cleanResult.medianHwDelayUs - 80_250L) <= 3_000L)

        // High-spread pulses (IQR > 10 ms) must fail validation
        val noisyOffsetsUs = listOf(60_000L, 72_000L, 84_000L, 96_000L, 108_000L)
        val noisyCapture = ShortArray(sampleRate * 5)
        scheduled.forEachIndexed { idx, tSched ->
            val relUs = (tSched - clockOffsetUs) + noisyOffsetsUs[idx] - captureStartClientUs
            val startIdx = ((relUs * sampleRate) / 1_000_000L).toInt()
            for (i in template.indices) {
                if (startIdx + i in noisyCapture.indices) {
                    noisyCapture[startIdx + i] = template[i]
                }
            }
        }
        val noisyResult = TheaterLipSyncCalibrator.solveLipSyncDelay(
            samples = noisyCapture,
            sampleRateHz = sampleRate,
            captureStartBoottimeUs = captureStartClientUs,
            scheduledAvrUs = scheduled,
            deltaClockUs = clockOffsetUs,
            clockUncertaintyUs = 400L,
            speakerDistanceMeters = 0.0,
        )
        assertTrue("Expected failure on high IQR burst", noisyResult.isFailure)
    }
}
