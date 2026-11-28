package dev.spatialfin.fcast.session.calibration

import android.content.Context
import android.media.AudioManager
import android.os.Build
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.CalibrationLipSyncChirpPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.HelloRequestPayload
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.RoomProfileResponse
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrHttpClient
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.SendspinAvrTransport
import dev.jdtech.jellyfin.cast.adapter.sendspinavr.TheaterAudioProtocol
import dev.jdtech.jellyfin.core.audio.VoiceCaptureSuspensionRegistry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Acoustic lip-sync auto-calibrator for `spatialfin-avr` rooms.
 *
 * Uses a transient Spec 0 `/ws/v1/control` WebSocket connection to run 5-RTT `clock.ping`
 * synchronization, schedules a single 5-chirp `calibration.lip_sync_chirp` burst with
 * `bypass_hw_delay = true` and `lead_in_ms = 1500` (waking cold HDMI soundbars), records once
 * via [CalibrationCapture] (`TIMEBASE_BOOTTIME`, suspending XR voice capture), correlates with
 * [ChirpDetector.detectEnvelopeMatched], subtracts stored MLP-to-speaker acoustic Time-of-Flight,
 * and returns a verified median pure hardware audio delay (`fixed_hw_delay_us`).
 */
class TheaterLipSyncCalibrator(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    data class PulseMeasurement(
        val pulseIndex: Int,
        val scheduledAvrUs: Long,
        val measuredHwDelayUs: Long,
        val snrDb: Double,
        val kept: Boolean,
    )

    data class MeasurementResult(
        val medianHwDelayUs: Long,
        val iqrUs: Long,
        val acousticTofUs: Long,
        val speakerDistanceMeters: Double,
        val clockUncertaintyUs: Long,
        val pulses: List<PulseMeasurement>,
        val validPulseCount: Int,
        val usedUnprocessedMic: Boolean = false,
    )

    suspend fun measureRoomHwDelay(
        endpoint: SendspinAvrHttpClient.Endpoint,
        bearerToken: String,
        clientId: String,
        roomProfile: RoomProfileResponse,
        gainDbfs: Double = DEFAULT_GAIN_DBFS,
        mode: String = DEFAULT_MODE,
        onStatus: (String) -> Unit = {},
    ): Result<MeasurementResult> = withContext(Dispatchers.IO) {
        val targetRoles = resolveCalibrationTargetRoles(roomProfile)
        if (targetRoles.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Assign at least one speaker to this room before calibrating lip-sync."),
            )
        }
        val speakerDistanceM = computeFrontSpeakerDistanceMeters(roomProfile, targetRoles)
        val lease = VoiceCaptureSuspensionRegistry.acquire("theater_lipsync_calibration")
        val transport = SendspinAvrTransport(okHttpClient = okHttpClient)
        try {
            onStatus("Syncing clock with spatialfin-avr…")
            transport.connect(
                endpoint = endpoint,
                bearerToken = bearerToken,
                hello = HelloRequestPayload(
                    clientId = clientId,
                    clientName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "SpatialFin",
                    formFactor = "calibration_tool",
                    appVersion = "2.7.74",
                    minProtocolVersion = TheaterAudioProtocol.VERSION,
                    maxProtocolVersion = TheaterAudioProtocol.VERSION,
                ),
                initialClockSamples = CLOCK_PING_SAMPLES,
                pingIntervalMs = 0L,
            )
            val nowUs = SendspinAvrTransport.DEFAULT_BOOTTIME_CLOCK_US()
            val clockSnap = transport.clockSync.snapshot(nowUs)
            val deltaClockUs = clockSnap.thetaUs

            val audioManager = runCatching {
                context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            }.getOrNull()

            onStatus("Listening for 5 calibration chirps… keep the room quiet.")
            val (captureResult, chirpResp) = coroutineScope {
                val captureDeferred = async {
                    CalibrationCapture(
                        sampleRateHz = SAMPLE_RATE_HZ,
                        durationSeconds = CAPTURE_DURATION_SECONDS,
                        audioManager = audioManager,
                    ).capture()
                }
                // Brief warmup delay so AudioRecord is already running before pulse #1 begins.
                delay(250L)
                val resp = transport.lipSyncChirp(
                    sessionId = null,
                    payload = CalibrationLipSyncChirpPayload(
                        roles = targetRoles,
                        mode = mode,
                        startAvrUs = 0L,
                        repeatCount = PULSE_COUNT,
                        intervalMs = PULSE_INTERVAL_MS,
                        gainDbfs = gainDbfs.coerceIn(-36.0, -12.0),
                        bypassHwDelay = true,
                        leadInMs = HDMI_WAKE_LEAD_IN_MS,
                    ),
                    timeoutMs = 5_000L,
                )
                captureDeferred.await() to resp
            }

            if (!captureResult.usedAudioRecordTimestamp) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Device microphone did not provide AudioTimestamp(TIMEBASE_BOOTTIME); cannot guarantee accurate lip-sync measurement.",
                    ),
                )
            }

            onStatus("Analyzing captured chirps…")
            solveLipSyncDelay(
                samples = captureResult.samples,
                sampleRateHz = SAMPLE_RATE_HZ,
                captureStartBoottimeUs = captureResult.captureStartBoottimeUs,
                scheduledAvrUs = chirpResp.scheduledAvrUs,
                deltaClockUs = deltaClockUs,
                clockUncertaintyUs = clockSnap.uncertaintyUs,
                speakerDistanceMeters = speakerDistanceM,
                mode = mode,
                usedUnprocessedMic = captureResult.usedUnprocessedSource,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            transport.disconnect()
            lease.release()
        }
    }

    /**
     * Plays a 3-pulse flash+chirp confirmation preview using the server's currently saved
     * `fixed_hw_delay_us` (`bypassHwDelay = false`), invoking [onFlashPulse] at each scheduled
     * acoustic arrival instant so the user can visually verify synchronization.
     */
    suspend fun playConfirmationPreview(
        endpoint: SendspinAvrHttpClient.Endpoint,
        bearerToken: String,
        clientId: String,
        roomProfile: RoomProfileResponse,
        gainDbfs: Double = DEFAULT_GAIN_DBFS,
        onFlashPulse: suspend (Boolean) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetRoles = resolveCalibrationTargetRoles(roomProfile)
        if (targetRoles.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No speakers configured."))
        }
        val transport = SendspinAvrTransport(okHttpClient = okHttpClient)
        try {
            transport.connect(
                endpoint = endpoint,
                bearerToken = bearerToken,
                hello = HelloRequestPayload(
                    clientId = clientId,
                    clientName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "SpatialFin",
                    formFactor = "calibration_tool",
                    appVersion = "2.7.74",
                    minProtocolVersion = TheaterAudioProtocol.VERSION,
                    maxProtocolVersion = TheaterAudioProtocol.VERSION,
                ),
                initialClockSamples = CLOCK_PING_SAMPLES,
                pingIntervalMs = 0L,
            )
            val clockSnap = transport.clockSync.snapshot(SendspinAvrTransport.DEFAULT_BOOTTIME_CLOCK_US())
            val resp = transport.lipSyncChirp(
                sessionId = null,
                payload = CalibrationLipSyncChirpPayload(
                    roles = targetRoles,
                    mode = DEFAULT_MODE,
                    startAvrUs = 0L,
                    repeatCount = 3,
                    intervalMs = 1_000,
                    gainDbfs = gainDbfs.coerceIn(-36.0, -12.0),
                    bypassHwDelay = false,
                    leadInMs = HDMI_WAKE_LEAD_IN_MS,
                ),
                timeoutMs = 5_000L,
            )
            for (scheduledAvrUs in resp.scheduledAvrUs) {
                val targetLocalUs = scheduledAvrUs - clockSnap.thetaUs
                val waitMs = ((targetLocalUs - SendspinAvrTransport.DEFAULT_BOOTTIME_CLOCK_US()) / 1_000L)
                    .coerceAtLeast(0L)
                if (waitMs > 0L) delay(waitMs)
                onFlashPulse(true)
                delay(90L)
                onFlashPulse(false)
            }
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { onFlashPulse(false) }
            transport.disconnect()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 48_000
        const val PULSE_COUNT: Int = 5
        const val PULSE_INTERVAL_MS: Int = 1_500
        const val HDMI_WAKE_LEAD_IN_MS: Int = 1_500
        const val CAPTURE_DURATION_SECONDS: Double = 9.5
        const val CLOCK_PING_SAMPLES: Int = 5
        const val DEFAULT_GAIN_DBFS: Double = -18.0
        const val DEFAULT_MODE: String = "midband"
        const val SPEED_OF_SOUND_M_S: Double = 343.0
        const val MIN_SNR_DB: Double = 12.0
        const val OUTLIER_TOLERANCE_US: Long = 25_000L
        const val MAX_IQR_US: Long = 10_000L
        const val MIN_VALID_PULSES: Int = 3
        const val MIN_HW_DELAY_US: Long = 0L
        const val MAX_HW_DELAY_US: Long = 500_000L

        /**
         * Generates the exact 40 ms Hann-windowed log chirp synthesized by `spatialfin-avr`'s
         * `generate_lipsync_chirp_signal` (`calibration_audio.rs`).
         */
        fun generateServerLipSyncChirpTemplate(
            sampleRateHz: Int = SAMPLE_RATE_HZ,
            mode: String = DEFAULT_MODE,
            gainDbfs: Double = DEFAULT_GAIN_DBFS,
        ): ShortArray {
            val fs = sampleRateHz.coerceAtLeast(1).toDouble()
            val durS = 0.040
            val totalSamples = (fs * durS).roundToInt().coerceAtLeast(8)
            val amp = 10.0.pow(gainDbfs.coerceIn(-60.0, -12.0) / 20.0)
            val (f0, f1) = if (mode.equals("lowband", ignoreCase = true)) {
                200.0 to 1_000.0
            } else {
                800.0 to 3_200.0
            }
            val k = ln(f1 / f0) / durS
            val denom = (totalSamples - 1).coerceAtLeast(1).toDouble()
            return ShortArray(totalSamples) { i ->
                val t = i.toDouble() / fs
                val phase = 2.0 * PI * f0 * (exp(k * t) - 1.0) / k
                val win = 0.5 * (1.0 - cos(2.0 * PI * i.toDouble() / denom))
                val sample = (sin(phase) * win * amp).coerceIn(-1.0, 1.0)
                (sample * Short.MAX_VALUE).roundToInt().toShort()
            }
        }

        fun resolveCalibrationTargetRoles(profile: RoomProfileResponse): List<String> {
            val preferred = listOf("FC", "FL", "FR", "MONO", "STEREO_L")
            for (role in preferred) {
                val found = profile.speakers.firstOrNull { it.role.equals(role, ignoreCase = true) }
                if (found != null) return listOf(found.role)
            }
            return profile.speakers.firstOrNull()?.let { listOf(it.role) }.orEmpty()
        }

        fun computeFrontSpeakerDistanceMeters(
            profile: RoomProfileResponse,
            targetRoles: List<String>,
        ): Double {
            val matching = profile.speakers.filter { sp ->
                targetRoles.any { r -> sp.role.equals(r, ignoreCase = true) }
            }.ifEmpty { profile.speakers }
            val dists = matching.mapNotNull { sp ->
                if (sp.coordM.size == 3) {
                    val d = sqrt(
                        sp.coordM[0] * sp.coordM[0] +
                            sp.coordM[1] * sp.coordM[1] +
                            sp.coordM[2] * sp.coordM[2],
                    )
                    if (d.isFinite() && d > 0.1) d else null
                } else {
                    null
                }
            }.sorted()
            if (dists.isEmpty()) return 2.5
            val mid = dists.size / 2
            return if (dists.size % 2 == 1) dists[mid] else (dists[mid - 1] + dists[mid]) / 2.0
        }

        fun computeAcousticTofUs(distanceMeters: Double): Long =
            ((distanceMeters.coerceAtLeast(0.0) / SPEED_OF_SOUND_M_S) * 1_000_000.0).roundToLong()

        /**
         * Pure deterministic solver converting a captured mono PCM buffer + server chirp timestamps
         * into a verified median pure audio hardware delay (`fixed_hw_delay_us`).
         */
        fun solveLipSyncDelay(
            samples: ShortArray,
            sampleRateHz: Int,
            captureStartBoottimeUs: Long,
            scheduledAvrUs: List<Long>,
            deltaClockUs: Long,
            clockUncertaintyUs: Long,
            speakerDistanceMeters: Double,
            mode: String = DEFAULT_MODE,
            usedUnprocessedMic: Boolean = false,
        ): Result<MeasurementResult> {
            if (scheduledAvrUs.isEmpty()) {
                return Result.failure(IllegalArgumentException("No scheduled chirp timestamps returned by server."))
            }
            val tofUs = computeAcousticTofUs(speakerDistanceMeters)
            val template = generateServerLipSyncChirpTemplate(sampleRateHz = sampleRateHz, mode = mode)
            val detected = ChirpDetector.detectEnvelopeMatched(
                samples = samples,
                sampleRateHz = sampleRateHz,
                template = template,
                thresholdDb = MIN_SNR_DB,
                minSpacingMs = 400,
            )

            data class Candidate(
                val pulseIndex: Int,
                val scheduledAvrUs: Long,
                val measuredHwDelayUs: Long,
                val snrDb: Double,
            )

            val matched = mutableListOf<Candidate>()
            for ((idx, schedAvr) in scheduledAvrUs.withIndex()) {
                val expectedLocalUs = schedAvr - deltaClockUs
                // Look for an onset inside [-50 ms, +600 ms] after scheduled emission (covering
                // 0..500 ms hardware delay + acoustic ToF without colliding with the next pulse).
                val best = detected.onsets
                    .map { onset ->
                        val heardUs = captureStartBoottimeUs + (onset.timeSeconds * 1_000_000.0).roundToLong()
                        val rawOffsetUs = heardUs - expectedLocalUs
                        val snrDb = if (onset.snr.isInfinite()) 60.0 else 20.0 * log10(onset.snr.coerceAtLeast(1e-9))
                        Triple(onset, rawOffsetUs, snrDb)
                    }
                    .filter { (_, rawOffsetUs, snrDb) ->
                        rawOffsetUs in -50_000L..600_000L && snrDb >= MIN_SNR_DB
                    }
                    .maxByOrNull { (_, _, snrDb) -> snrDb }
                if (best != null) {
                    val (_, rawOffsetUs, snrDb) = best
                    val hwDelayUs = rawOffsetUs - tofUs
                    matched += Candidate(
                        pulseIndex = idx + 1,
                        scheduledAvrUs = schedAvr,
                        measuredHwDelayUs = hwDelayUs,
                        snrDb = snrDb,
                    )
                }
            }

            if (matched.size < MIN_VALID_PULSES) {
                return Result.failure(
                    IllegalStateException(
                        "Only detected ${matched.size}/${scheduledAvrUs.size} chirps above ${MIN_SNR_DB.toInt()} dB SNR. Raise test chime gain or quiet room noise and retry.",
                    ),
                )
            }

            val initialMedian = medianLong(matched.map { it.measuredHwDelayUs })
            val pulses = matched.map { c ->
                val kept = abs(c.measuredHwDelayUs - initialMedian) <= OUTLIER_TOLERANCE_US
                PulseMeasurement(
                    pulseIndex = c.pulseIndex,
                    scheduledAvrUs = c.scheduledAvrUs,
                    measuredHwDelayUs = c.measuredHwDelayUs,
                    snrDb = c.snrDb,
                    kept = kept,
                )
            }
            val inliers = pulses.filter { it.kept }.map { it.measuredHwDelayUs }.sorted()
            if (inliers.size < MIN_VALID_PULSES) {
                return Result.failure(
                    IllegalStateException(
                        "Too few consistent chirps (${inliers.size}/${scheduledAvrUs.size} within ±25 ms). Hold the device steady at the listening position and retry.",
                    ),
                )
            }

            val iqrUs = interquartileRangeUs(inliers)
            if (iqrUs > MAX_IQR_US) {
                return Result.failure(
                    IllegalStateException(
                        "Chirp spread too high (IQR ${iqrUs / 1_000.0} ms > 10.0 ms). Hold steady in a quiet room and retry.",
                    ),
                )
            }

            val finalMedian = medianLong(inliers).coerceIn(MIN_HW_DELAY_US, MAX_HW_DELAY_US)
            return Result.success(
                MeasurementResult(
                    medianHwDelayUs = finalMedian,
                    iqrUs = iqrUs,
                    acousticTofUs = tofUs,
                    speakerDistanceMeters = speakerDistanceMeters,
                    clockUncertaintyUs = clockUncertaintyUs,
                    pulses = pulses,
                    validPulseCount = inliers.size,
                    usedUnprocessedMic = usedUnprocessedMic,
                ),
            )
        }

        private fun medianLong(values: List<Long>): Long {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2L
        }

        private fun interquartileRangeUs(sorted: List<Long>): Long {
            if (sorted.size < 2) return 0L
            val q1 = percentile(sorted, 0.25)
            val q3 = percentile(sorted, 0.75)
            return (q3 - q1).coerceAtLeast(0L)
        }

        private fun percentile(sorted: List<Long>, p: Double): Long {
            val pos = p * (sorted.size - 1)
            val lo = pos.toInt().coerceIn(0, sorted.lastIndex)
            val hi = (lo + 1).coerceIn(0, sorted.lastIndex)
            val frac = pos - lo
            return (sorted[lo] + (sorted[hi] - sorted[lo]) * frac).roundToLong()
        }
    }
}
