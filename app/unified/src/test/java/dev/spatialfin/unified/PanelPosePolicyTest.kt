package dev.spatialfin.unified

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelPosePolicyTest {

    private val identity = Quaternion.Identity
    private fun pose(x: Float = 0f, y: Float = 0f, z: Float, rot: Quaternion = identity): Pose =
        Pose(Vector3(x, y, z), rot)

    // -----------------------------------------------------------------
    // approximatelyEqual
    // -----------------------------------------------------------------

    @Test
    fun `null b is never approximately equal`() {
        assertFalse(PanelPosePolicy.approximatelyEqual(pose(z = -2f), null))
    }

    @Test
    fun `identical poses are approximately equal`() {
        val a = pose(z = -2f)
        val b = pose(z = -2f)
        assertTrue(PanelPosePolicy.approximatelyEqual(a, b))
    }

    @Test
    fun `tiny differences below epsilon are still equal`() {
        val a = pose(z = -2f)
        val b = pose(z = -2.00001f)
        assertTrue(PanelPosePolicy.approximatelyEqual(a, b))
    }

    @Test
    fun `differences above epsilon are not equal`() {
        val a = pose(z = -2f)
        val b = pose(z = -2.001f)
        assertFalse(PanelPosePolicy.approximatelyEqual(a, b))
    }

    @Test
    fun `rotation difference above epsilon is not equal`() {
        val a = pose(z = -2f, rot = Quaternion(0f, 0f, 0f, 1f))
        val b = pose(z = -2f, rot = Quaternion(0.01f, 0f, 0f, 1f))
        assertFalse(PanelPosePolicy.approximatelyEqual(a, b))
    }

    // -----------------------------------------------------------------
    // migrateLegacyDefault
    // -----------------------------------------------------------------

    @Test
    fun `legacy -5m default migrates to current default depth`() {
        val migrated = PanelPosePolicy.migrateLegacyDefault(pose(z = -5f))
        assertEquals(PanelPosePolicy.DEFAULT_DEPTH_METERS, migrated.translation.z, 1e-6f)
        assertEquals(0f, migrated.translation.x, 1e-6f)
        assertEquals(0f, migrated.translation.y, 1e-6f)
    }

    @Test
    fun `each legacy default depth migrates to current default`() {
        for (legacyZ in listOf(-5f, -6f, -9f, -11f)) {
            val migrated = PanelPosePolicy.migrateLegacyDefault(pose(z = legacyZ))
            assertEquals(
                "legacy $legacyZ should migrate",
                PanelPosePolicy.DEFAULT_DEPTH_METERS,
                migrated.translation.z,
                1e-6f,
            )
        }
    }

    @Test
    fun `pose that user has actually moved is preserved`() {
        // Off-axis translation — user dragged the panel sideways.
        val moved = pose(x = 1.2f, y = 0.3f, z = -5f)
        val result = PanelPosePolicy.migrateLegacyDefault(moved)
        assertEquals(moved.translation.x, result.translation.x, 1e-6f)
        assertEquals(moved.translation.y, result.translation.y, 1e-6f)
        assertEquals(moved.translation.z, result.translation.z, 1e-6f)
    }

    @Test
    fun `non-identity rotation preserves the saved pose`() {
        val rotated = pose(z = -5f, rot = Quaternion(0.1f, 0f, 0f, 0.9f))
        val result = PanelPosePolicy.migrateLegacyDefault(rotated)
        assertEquals(rotated.translation.z, result.translation.z, 1e-6f)
        assertEquals(rotated.rotation.x, result.rotation.x, 1e-6f)
    }

    @Test
    fun `pose with current default depth is not migrated again`() {
        val current = pose(z = PanelPosePolicy.DEFAULT_DEPTH_METERS)
        val result = PanelPosePolicy.migrateLegacyDefault(current)
        // Same pose returned (no-op). Depth equals current default already.
        assertEquals(current.translation.z, result.translation.z, 1e-6f)
    }

    @Test
    fun `pose at unrelated depth is preserved`() {
        val custom = pose(z = -3.7f)
        val result = PanelPosePolicy.migrateLegacyDefault(custom)
        assertEquals(custom.translation.z, result.translation.z, 1e-6f)
        // Should not have been rewritten to the default.
        assertNotEquals(PanelPosePolicy.DEFAULT_DEPTH_METERS, result.translation.z)
    }
}
