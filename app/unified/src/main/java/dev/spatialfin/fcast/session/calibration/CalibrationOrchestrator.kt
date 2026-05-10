package dev.spatialfin.fcast.session.calibration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.jellyfin.fcast.protocol.PlaybackUpdateMessage
import dev.jdtech.jellyfin.fcast.protocol.SplitAvMetadata
import dev.jdtech.jellyfin.fcast.protocol.SplitAvRole
import dev.jdtech.jellyfin.fcast.protocol.withSplitAv
import dev.jdtech.jellyfin.fcast.sender.FCastCastingController
import dev.jdtech.jellyfin.fcast.sender.FCastReceiver
import dev.jdtech.jellyfin.fcast.sender.PlayMessageBuilder
import dev.spatialfin.fcast.session.RememberedReceiversStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * End-to-end driver for the audio-latency calibration wizard.
 *
 * Sequence:
 *  1. Build the chirp PCM and wrap as a WAV byte array.
 *  2. Start a one-shot HTTP server on the LAN-facing interface.
 *  3. FCast-Play `http://<xr-ip>:<port>/calibration.wav` to [receiver] tagged with
 *     `splitAv.role=AUDIO` so the receiver disables video and emits 10 Hz PlaybackUpdate beacons.
 *  4. Concurrently capture mic audio for ~6 s and collect every PlaybackUpdate that arrives.
 *  5. Run [ChirpDetector] on the captured PCM; the orchestrator already knows where each chirp
 *     lives in the source file ([ChirpGenerator.chirpOnsetsSeconds]).
 *  6. For each detected onset, pick the nearest-in-time beacon and compute the per-chirp
 *     audio-path latency from this identity (network one-way assumed 0 in v1):
 *
 *     ```
 *     audioLatencyMs = T_cap_xr_ms - s_i_ms + t_beacon_ms - T_recv_beacon_xr_ms
 *     ```
 *
 *  7. Take the median across the per-chirp latencies. Persist on success via
 *     [RememberedReceiversStore].
 *
 * The orchestrator does NOT manage RECORD_AUDIO permission — caller must request it before
 * invoking [calibrate].
 */
@Singleton
class CalibrationOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val castingController: FCastCastingController,
    private val rememberedReceiversStore: RememberedReceiversStore,
) {

    sealed interface Result {
        data class Success(
            val audioLatencyMs: Int,
            val samplesUsed: Int,
            val noiseFloor: Double,
        ) : Result

        data class Failure(val reason: String) : Result
    }

    private data class TimedBeacon(
        val beacon: PlaybackUpdateMessage,
        val recvWallMs: Long,
    )

    /**
     * Run the wizard against [receiver]. Suspends for [ChirpGenerator.totalSeconds] +
     * a buffer (typically ~6 s total). Throws if AudioRecord init fails — caller surfaces that
     * to the user as a "no microphone available" message.
     */
    suspend fun calibrate(receiver: FCastReceiver): Result = coroutineScope {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            return@coroutineScope Result.Failure(
                "Microphone permission is required to calibrate audio sync. Grant the " +
                    "RECORD_AUDIO permission in System Settings and try again.",
            )
        }

        val wavBytes = WavWriter.toByteArray(
            ChirpGenerator.generatePcm(),
            ChirpGenerator.SAMPLE_RATE_HZ,
        )
        val server = CalibrationServer(wavBytes)
        val collectedBeacons = mutableListOf<TimedBeacon>()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            val rawUrl = server.start()
            // Bust any HTTP intermediary cache that might collapse repeat calibrations.
            val url = "$rawUrl?n=${System.currentTimeMillis()}"

            // Start the beacon collector BEFORE Play so we don't miss the first beacon.
            collectorScope.launch {
                castingController.remoteState
                    .filterNotNull()
                    .collect { update ->
                        synchronized(collectedBeacons) {
                            collectedBeacons += TimedBeacon(update, System.currentTimeMillis())
                        }
                    }
            }

            val play = PlayMessageBuilder.build(
                url = url,
                container = "audio/wav",
            ).withSplitAv(
                SplitAvMetadata(
                    role = SplitAvRole.AUDIO,
                    syncCadenceHz = SplitAvMetadata.DEFAULT_SYNC_CADENCE_HZ,
                ),
            )

            try {
                castingController.startCast(receiver, play)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "calibration startCast failed")
                return@coroutineScope Result.Failure("Could not connect to receiver: ${e.message}")
            }

            // Run capture concurrently with playback. Slight head start to AudioRecord so it's
            // already buffering by the time TV starts playing the lead silence.
            val captureDeferred = async(Dispatchers.IO) {
                CalibrationCapture().capture()
            }
            val capture = captureDeferred.await()

            // Stop the cast — calibration play is over.
            try { castingController.stopCast() } catch (_: Exception) {}

            // Wait briefly for any straggler beacon that arrived while we were stopping.
            delay(200)
            collectorScope.cancel()

            val beaconsSnapshot = synchronized(collectedBeacons) { collectedBeacons.toList() }
            if (beaconsSnapshot.isEmpty()) {
                return@coroutineScope Result.Failure(
                    "No PlaybackUpdate beacons received — receiver may not have started playback",
                )
            }

            val detection = ChirpDetector.detect(
                samples = capture.samples,
                sampleRateHz = ChirpGenerator.SAMPLE_RATE_HZ,
            )
            val minRequired = ChirpGenerator.CHIRP_COUNT - 1  // tolerate one missing detection
            if (detection.onsets.size < minRequired) {
                return@coroutineScope Result.Failure(
                    "Detected only ${detection.onsets.size} of ${ChirpGenerator.CHIRP_COUNT} " +
                        "chirps — try again in a quieter environment or move closer to the speaker",
                )
            }

            // Match detected onsets to the expected ones in temporal order. If we got fewer
            // than CHIRP_COUNT we still take them in the order detected — they correspond to
            // the first N source chirps because the WAV plays sequentially.
            val perChirpLatencies = detection.onsets.mapIndexedNotNull { i, onset ->
                if (i >= ChirpGenerator.chirpOnsetsSeconds.size) return@mapIndexedNotNull null
                val sourceOnsetMs = (ChirpGenerator.chirpOnsetsSeconds[i] * 1_000.0).toLong()
                val captureXrWallMs = capture.captureStartWallMs +
                    (onset.timeSeconds * 1_000.0).toLong()
                val nearest = beaconsSnapshot.minByOrNull {
                    kotlin.math.abs(it.recvWallMs - captureXrWallMs)
                } ?: return@mapIndexedNotNull null
                val tBeaconMs = ((nearest.beacon.time ?: 0.0) * 1_000.0).toLong()
                // audioLatencyMs = T_cap - s_i + t_beacon - T_recv_beacon  (network ≈ 0)
                captureXrWallMs - sourceOnsetMs + tBeaconMs - nearest.recvWallMs
            }
            if (perChirpLatencies.isEmpty()) {
                return@coroutineScope Result.Failure("Could not align detected onsets with beacons")
            }

            val median = ChirpDetector.medianLatencyMs(perChirpLatencies).toInt()
            val sanitized = median.coerceIn(MIN_PLAUSIBLE_LATENCY_MS, MAX_PLAUSIBLE_LATENCY_MS)
            if (sanitized != median) {
                Timber.tag(TAG).w(
                    "Calibration result %d ms outside plausible band — clamping to %d",
                    median, sanitized,
                )
            }

            // Persist on success.
            rememberedReceiversStore.setAudioLatency(
                host = receiver.host,
                port = receiver.port,
                audioLatencyMs = sanitized,
            )
            Timber.tag(TAG).i(
                "Calibration: %d ms (%d samples, noiseFloor=%.0f)",
                sanitized, perChirpLatencies.size, detection.noiseFloor,
            )
            Result.Success(
                audioLatencyMs = sanitized,
                samplesUsed = perChirpLatencies.size,
                noiseFloor = detection.noiseFloor,
            )
        } finally {
            collectorScope.cancel()
            server.stop()
            // Best-effort make sure the cast is stopped even on cancellation.
            withTimeoutOrNull(500) {
                runCatching { castingController.stopCast() }
            }
        }
    }

    companion object {
        private const val TAG = "SplitAvCalibration"

        /**
         * Plausibility band for the resulting audioLatencyMs. Below this we assume detection
         * noise; above this we assume the user/network/AVR is in a pathological state and
         * clamp rather than persist a value the drift loop can't actually compensate for.
         */
        const val MIN_PLAUSIBLE_LATENCY_MS: Int = 0
        const val MAX_PLAUSIBLE_LATENCY_MS: Int = 800
    }
}
