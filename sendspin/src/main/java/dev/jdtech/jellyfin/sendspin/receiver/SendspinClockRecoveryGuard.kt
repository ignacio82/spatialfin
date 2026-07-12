package dev.jdtech.jellyfin.sendspin.receiver

import com.sendspin.protocol.ClockSync

/**
 * Public [ClockSync] diagnostics that change when a clock measurement is processed.
 *
 * sendspin-jvm keeps one ClockSync for the lifetime of a SendSpinClient, including across
 * socket reconnects. A non-zero RTT therefore only proves that *some previous connection*
 * synchronized. Offset and drift are included so a fresh measurement is still detectable when
 * two connections happen to produce the same integer RTT.
 */
internal data class SendspinClockReading(
    val rttMicros: Long,
    val offsetMicros: Double,
    val driftPpm: Double,
) {
    val hasMeasurement: Boolean
        get() = rttMicros > 0L
}

internal fun ClockSync.recoveryReading(): SendspinClockReading =
    SendspinClockReading(
        rttMicros = lastRttMicros,
        offsetMicros = lastOffsetMicros,
        driftPpm = lastDriftPpm,
    )

/**
 * Arms clock-discontinuity recovery only after the active socket has synchronized its clock.
 *
 * [connectionToken] is compared by identity. The service uses the parsed `ServerHello` instance,
 * which is new for every accepted/reconnected socket even when the same server sends identical
 * hello contents. The clock reading at that hello is the generation baseline; chunks cannot count
 * as recovery outliers until a later reading proves that this connection processed a ServerTime.
 */
internal class SendspinClockRecoveryGuard(
    private val requiredOutliers: Int,
) {
    private var activeConnectionToken: Any? = null
    private var connectionBaseline: SendspinClockReading? = null
    private var clockReady = false
    private var consecutiveOutliers = 0

    init {
        require(requiredOutliers > 0) { "requiredOutliers must be positive" }
    }

    @Synchronized
    fun beginConnection(
        connectionToken: Any,
        baseline: SendspinClockReading,
    ) {
        if (connectionToken === activeConnectionToken) return
        activeConnectionToken = connectionToken
        connectionBaseline = baseline
        clockReady = false
        consecutiveOutliers = 0
    }

    /**
     * Returns whether [connectionToken] has a measurement newer than its hello-time baseline.
     * Also provides a synchronous fallback for an audio chunk that races the flow collector.
     */
    @Synchronized
    fun isClockReady(
        connectionToken: Any,
        reading: SendspinClockReading,
    ): Boolean {
        beginConnection(connectionToken, reading)
        if (!clockReady) {
            val baseline = connectionBaseline ?: return false
            clockReady = reading.hasMeasurement && reading != baseline
        }
        return clockReady
    }

    /** Records one chunk after readiness was checked, and returns true on the rebuild threshold. */
    @Synchronized
    fun recordChunk(
        connectionToken: Any,
        isOutlier: Boolean,
    ): Boolean {
        if (connectionToken !== activeConnectionToken || !clockReady) return false
        if (!isOutlier) {
            consecutiveOutliers = 0
            return false
        }
        consecutiveOutliers++
        if (consecutiveOutliers < requiredOutliers) return false
        consecutiveOutliers = 0
        return true
    }

    @Synchronized
    fun clearConnection() {
        activeConnectionToken = null
        connectionBaseline = null
        clockReady = false
        consecutiveOutliers = 0
    }
}
