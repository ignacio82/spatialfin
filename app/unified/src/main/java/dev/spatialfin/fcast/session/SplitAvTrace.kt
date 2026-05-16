package dev.spatialfin.fcast.session

import timber.log.Timber

/**
 * One row of the split-A/V sync trace, emitted by [SplitAvController.handleBeacon] for every
 * beacon. The whole point of this type is offline tuning: [SplitAvPolicy] + [DriftEstimator]
 * are pure, so a captured trace can be replayed through them in a unit test to tune thresholds
 * and gains without burning a hardware session per iteration (see `SplitAvReplayTest`).
 *
 * Wire/log form is a single CSV line so a logcat capture can be `grep`'d straight into the
 * replay harness:
 * ```
 * adb logcat -s SplitAvTrace | sed 's/.*SplitAvTrace: //' > trace.csv
 * ```
 */
data class SplitAvTrace(
    /** Monotonic clock (ms) when the beacon was processed. */
    val atMs: Long,
    /** Raw per-beacon drift before smoothing (ms). */
    val rawDriftMs: Long,
    /** [DriftEstimator] smoothed drift the policy actually acted on (ms). */
    val smoothedDriftMs: Long,
    /** Estimated clock-skew slope (ms of drift growth per second). */
    val driftRateMsPerSec: Double,
    /** Smoothed RTT estimate, or null before the first Pong. */
    val rttMs: Int?,
    /** Codec ExoPlayer is decoding on the receiver, or null if not yet probed. */
    val codecMime: String?,
    /** Simple name of the [SplitAvPolicy.DriftAction], or "RejectedOutlier". */
    val action: String,
) {
    fun toCsvLine(): String =
        "$atMs,$rawDriftMs,$smoothedDriftMs," +
            "${"%.3f".format(driftRateMsPerSec)},${rttMs ?: ""},${codecMime ?: ""},$action"

    companion object {
        const val CSV_HEADER =
            "atMs,rawDriftMs,smoothedDriftMs,driftRateMsPerSec,rttMs,codecMime,action"

        /** Parse a line produced by [toCsvLine]; null on a malformed/blank/header line. */
        fun fromCsvLine(line: String): SplitAvTrace? {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("atMs")) return null
            val f = t.split(',')
            if (f.size < 7) return null
            return runCatching {
                SplitAvTrace(
                    atMs = f[0].toLong(),
                    rawDriftMs = f[1].toLong(),
                    smoothedDriftMs = f[2].toLong(),
                    driftRateMsPerSec = f[3].toDouble(),
                    rttMs = f[4].toIntOrNull(),
                    codecMime = f[5].ifEmpty { null },
                    action = f[6],
                )
            }.getOrNull()
        }
    }
}

/** Sink the controller pushes each [SplitAvTrace] to. Swappable for an in-memory recorder
 *  in tests. */
fun interface SplitAvTraceSink {
    fun record(trace: SplitAvTrace)
}

/** Default sink: one verbose logcat line per beacon under the `SplitAvTrace` tag. Verbose so
 *  it's stripped from release logs but capturable on demand via `adb logcat`. */
object LogcatSplitAvTraceSink : SplitAvTraceSink {
    override fun record(trace: SplitAvTrace) {
        Timber.tag("SplitAvTrace").v(trace.toCsvLine())
    }
}

/** In-memory recorder for tests / the replay harness. */
class RecordingSplitAvTraceSink : SplitAvTraceSink {
    private val _rows = mutableListOf<SplitAvTrace>()
    val rows: List<SplitAvTrace> get() = _rows
    override fun record(trace: SplitAvTrace) {
        _rows += trace
    }
}
