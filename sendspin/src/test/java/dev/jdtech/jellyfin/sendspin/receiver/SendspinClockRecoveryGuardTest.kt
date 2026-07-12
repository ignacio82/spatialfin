package dev.jdtech.jellyfin.sendspin.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendspinClockRecoveryGuardTest {
    private data class HelloToken(val serverId: String)

    private val unsynchronized = SendspinClockReading(0L, 0.0, 0.0)
    private val firstMeasurement = SendspinClockReading(2_000L, 125_000.0, 0.25)

    @Test
    fun `first connection waits for a clock measurement before counting outliers`() {
        val guard = SendspinClockRecoveryGuard(requiredOutliers = 3)
        val connection = Any()

        guard.beginConnection(connection, unsynchronized)

        assertFalse(guard.isClockReady(connection, unsynchronized))
        assertFalse(guard.recordChunk(connection, isOutlier = true))
        assertTrue(guard.isClockReady(connection, firstMeasurement))
        assertFalse(guard.recordChunk(connection, isOutlier = true))
        assertFalse(guard.recordChunk(connection, isOutlier = true))
        assertTrue(guard.recordChunk(connection, isOutlier = true))
    }

    @Test
    fun `reconnect rejects stale clock outliers until this generation measures`() {
        val guard = SendspinClockRecoveryGuard(requiredOutliers = 3)
        val oldConnection = Any()
        val newConnection = Any()

        guard.beginConnection(oldConnection, unsynchronized)
        assertTrue(guard.isClockReady(oldConnection, firstMeasurement))
        guard.beginConnection(newConnection, firstMeasurement)

        repeat(10) {
            assertFalse(guard.isClockReady(newConnection, firstMeasurement))
            assertFalse(guard.recordChunk(newConnection, isOutlier = true))
        }

        val newMeasurement = firstMeasurement.copy(offsetMicros = 125_100.0)
        assertTrue(guard.isClockReady(newConnection, newMeasurement))
        assertFalse(guard.recordChunk(newConnection, isOutlier = true))
        assertFalse(guard.recordChunk(newConnection, isOutlier = true))
        assertTrue(guard.recordChunk(newConnection, isOutlier = true))
    }

    @Test
    fun `same rtt is recognized when offset or drift changes`() {
        val guard = SendspinClockRecoveryGuard(requiredOutliers = 1)
        val offsetConnection = Any()
        val driftConnection = Any()

        guard.beginConnection(offsetConnection, firstMeasurement)
        assertTrue(
            guard.isClockReady(
                offsetConnection,
                firstMeasurement.copy(offsetMicros = firstMeasurement.offsetMicros + 1.0),
            ),
        )

        guard.beginConnection(driftConnection, firstMeasurement)
        assertTrue(
            guard.isClockReady(
                driftConnection,
                firstMeasurement.copy(driftPpm = firstMeasurement.driftPpm + 0.001),
            ),
        )
    }

    @Test
    fun `new connection clears a prior generation outlier streak`() {
        val guard = SendspinClockRecoveryGuard(requiredOutliers = 2)
        // Same-server reconnects commonly produce value-equal ServerHello objects. Generation
        // tracking must use the new parsed object's identity, not data-class equality.
        val oldConnection = HelloToken(serverId = "same-server")
        val newConnection = HelloToken(serverId = "same-server")
        val secondMeasurement = firstMeasurement.copy(offsetMicros = 125_200.0)

        guard.beginConnection(oldConnection, unsynchronized)
        assertTrue(guard.isClockReady(oldConnection, firstMeasurement))
        assertFalse(guard.recordChunk(oldConnection, isOutlier = true))

        guard.beginConnection(newConnection, firstMeasurement)
        assertTrue(guard.isClockReady(newConnection, secondMeasurement))
        assertFalse(guard.recordChunk(newConnection, isOutlier = true))
        assertTrue(guard.recordChunk(newConnection, isOutlier = true))
        assertFalse(guard.recordChunk(oldConnection, isOutlier = true))
    }

    @Test
    fun `inlier resets the current generation outlier streak`() {
        val guard = SendspinClockRecoveryGuard(requiredOutliers = 2)
        val connection = Any()

        guard.beginConnection(connection, unsynchronized)
        assertTrue(guard.isClockReady(connection, firstMeasurement))
        assertFalse(guard.recordChunk(connection, isOutlier = true))
        assertFalse(guard.recordChunk(connection, isOutlier = false))
        assertFalse(guard.recordChunk(connection, isOutlier = true))
        assertTrue(guard.recordChunk(connection, isOutlier = true))
    }
}
