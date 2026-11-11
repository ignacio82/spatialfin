package dev.jdtech.jellyfin.player.xr

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGeometryTest {

    private fun quatFromYawPitchDeg(yawDeg: Float, pitchDeg: Float): Quaternion {
        val halfYaw = Math.toRadians(yawDeg.toDouble() * 0.5)
        val halfPitch = Math.toRadians(pitchDeg.toDouble() * 0.5)
        val qYaw = Quaternion(0f, sin(halfYaw).toFloat(), 0f, cos(halfYaw).toFloat())
        val qPitch = Quaternion(sin(halfPitch).toFloat(), 0f, 0f, cos(halfPitch).toFloat())
        return qYaw * qPitch
    }

    private fun assertUnitQuaternion(q: Quaternion) {
        assertFalse("Quaternion has NaN component: $q", q.x.isNaN() || q.y.isNaN() || q.z.isNaN() || q.w.isNaN())
        val norm = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
        assertEquals(1f, norm, 1e-3f)
    }

    @Test
    fun `viewerFacingRotation returns Identity when panel is directly in front along negative Z`() {
        val rot = viewerFacingRotation(
            panelPos = Vector3(0f, 0f, -6f),
            viewerPos = Vector3.Zero,
            allowPitch = true,
        )
        assertUnitQuaternion(rot)
        assertEquals(0f, rot.x, 1e-4f)
        assertEquals(0f, rot.y, 1e-4f)
        assertEquals(0f, rot.z, 1e-4f)
        assertEquals(1f, abs(rot.w), 1e-4f)
    }

    @Test
    fun `viewerFacingRotation faces viewer with zero roll at lateral offsets`() {
        val viewerPos = Vector3(1f, 1.6f, -0.5f)
        val panelPositions = listOf(
            Vector3(1f + 4.24f, 1.6f, -0.5f - 4.24f), // 45 deg right
            Vector3(1f + 6f, 1.6f, -0.5f),            // 90 deg right
            Vector3(1f, 1.6f, -0.5f + 6f),            // 180 deg behind
            Vector3(1f - 4.24f, 2.8f, -0.5f - 4.24f), // 45 deg left and elevated
        )

        for (panelPos in panelPositions) {
            val rot = viewerFacingRotation(panelPos, viewerPos, allowPitch = true)
            assertUnitQuaternion(rot)

            // Local +X (right axis) must have zero world Y component (zero roll).
            val localRight = rot * Vector3(1f, 0f, 0f)
            assertEquals("Expected zero roll for panelPos=$panelPos", 0f, localRight.y, 1e-4f)

            // Local +Z (surface normal) must point toward viewerPos - panelPos.
            val normal = rot * Vector3(0f, 0f, 1f)
            val dx = viewerPos.x - panelPos.x
            val dy = viewerPos.y - panelPos.y
            val dz = viewerPos.z - panelPos.z
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val dot = normal.x * (dx / len) + normal.y * (dy / len) + normal.z * (dz / len)
            assertEquals("Expected normal to face viewer for panelPos=$panelPos", 1f, dot, 1e-3f)
        }
    }

    @Test
    fun `viewerFacingRotation suppresses pitch when allowPitch is false`() {
        val rot = viewerFacingRotation(
            panelPos = Vector3(0f, 3f, -6f),
            viewerPos = Vector3.Zero,
            allowPitch = false,
        )
        assertUnitQuaternion(rot)
        val normal = rot * Vector3(0f, 0f, 1f)
        assertEquals(0f, normal.y, 1e-4f)
        assertEquals(1f, normal.z, 1e-4f)
    }

    @Test
    fun `viewerFacingRotation clamps extreme zenith pitch without NaN`() {
        val rot = viewerFacingRotation(
            panelPos = Vector3(0f, 6f, 0f),
            viewerPos = Vector3.Zero,
            allowPitch = true,
        )
        assertUnitQuaternion(rot)
        val localRight = rot * Vector3(1f, 0f, 0f)
        assertEquals(0f, localRight.y, 1e-4f)
        // Normal should pitch downward by 75 degrees (y = -sin(75 deg)).
        val normal = rot * Vector3(0f, 0f, 1f)
        val expectedY = -sin(Math.toRadians(75.0)).toFloat()
        assertEquals(expectedY, normal.y, 1e-3f)
    }

    @Test
    fun `computeViewerCenteredPanelPose returns default pose when headPose is null`() {
        val pose = computeViewerCenteredPanelPose(
            headPose = null,
            depthMeters = 6f,
            manualPitch = false,
        )
        assertEquals(0f, pose.translation.x, 1e-4f)
        assertEquals(0f, pose.translation.y, 1e-4f)
        assertEquals(-6f, pose.translation.z, 1e-4f)
        assertEquals(Quaternion.Identity, pose.rotation)
    }

    @Test
    fun `computeViewerCenteredPanelPose respects manualPitch flag for rotated viewer`() {
        val headPos = Vector3(0.5f, 1.5f, -0.2f)
        val headPose = Pose(headPos, quatFromYawPitchDeg(yawDeg = 135f, pitchDeg = -20f))

        val autoPose = computeViewerCenteredPanelPose(headPose, depthMeters = 6f, manualPitch = false)
        assertEquals(headPos.y, autoPose.translation.y, 1e-4f)
        assertEquals(6f, extractVideoDepth(autoPose, 6f, headPos), 1e-3f)

        val manualPose = computeViewerCenteredPanelPose(headPose, depthMeters = 6f, manualPitch = true)
        assertTrue("Expected manualPitch pose to follow downward pitch", manualPose.translation.y < headPos.y - 1f)
        assertEquals(6f, extractVideoDepth(manualPose, 6f, headPos), 1e-3f)
    }

    @Test
    fun `computeViewerCenteredPanelPose supports bedtime ceiling posture when manualPitch is true`() {
        val headPos = Vector3(0f, 1.0f, 0f)
        val headPose = Pose(headPos, quatFromYawPitchDeg(yawDeg = 0f, pitchDeg = 60f))

        val ceilingPose = computeViewerCenteredPanelPose(headPose, depthMeters = 6f, manualPitch = true)
        assertUnitQuaternion(ceilingPose.rotation)
        assertTrue("Expected panel overhead for +60 deg pitch", ceilingPose.translation.y > headPos.y + 4.5f)
        assertEquals(6f, extractVideoDepth(ceilingPose, 6f, headPos), 1e-3f)
    }

    @Test
    fun `computeImmersiveViewerPoses centers sphere on head and projects overlay along horizontal yaw`() {
        val headPos = Vector3(1.2f, 1.6f, -0.8f)
        val headPose = Pose(headPos, quatFromYawPitchDeg(yawDeg = -90f, pitchDeg = 15f))

        val (spherePose, overlayPose) = computeImmersiveViewerPoses(headPose, uiDepthMeters = 2.0f)
        assertEquals(headPos.x, spherePose.translation.x, 1e-4f)
        assertEquals(headPos.y, spherePose.translation.y, 1e-4f)
        assertEquals(headPos.z, spherePose.translation.z, 1e-4f)
        assertUnitQuaternion(spherePose.rotation)
        assertEquals(overlayPose.rotation, spherePose.rotation)

        // -90 deg yaw about +Y rotates (0, 0, -1) to (+1, 0, 0).
        assertEquals(headPos.x + 2.0f, overlayPose.translation.x, 1e-3f)
        assertEquals(headPos.y, overlayPose.translation.y, 1e-3f)
        assertEquals(headPos.z, overlayPose.translation.z, 1e-3f)
    }

    @Test
    fun `computeDragPose anchors depth from headPos rather than rayOrigin`() {
        val headPos = Vector3(1.0f, 1.6f, -0.5f)
        val rayOrigin = Vector3(1.3f, 1.2f, -0.9f) // hand offset from head
        val rayDirection = Vector3(1f, 0f, -1f)

        val dragPose = computeDragPose(
            rayOrigin = rayOrigin,
            rayDirection = rayDirection,
            headPos = headPos,
            grabDepthMeters = 6.0f,
        )
        assertEquals(6.0f, extractVideoDepth(dragPose, 6.0f, headPos), 1e-3f)
        val localRight = dragPose.rotation * Vector3(1f, 0f, 0f)
        assertEquals(0f, localRight.y, 1e-4f)
    }

    @Test
    fun `projectOverlayPoseTowardViewer places overlay at uiDepth from viewer and handles degenerate distance`() {
        val headPos = Vector3(1f, 1.5f, 0f)
        val videoPos = Vector3(1f, 1.5f, -6f)
        val videoRot = viewerFacingRotation(videoPos, headPos, allowPitch = true)
        val videoPose = Pose(videoPos, videoRot)

        val overlayPose = projectOverlayPoseTowardViewer(videoPose, headPos, uiDepthMeters = 2.0f)
        assertEquals(1f, overlayPose.translation.x, 1e-4f)
        assertEquals(1.5f, overlayPose.translation.y, 1e-4f)
        assertEquals(-2.0f, overlayPose.translation.z, 1e-4f)
        assertEquals(videoRot, overlayPose.rotation)

        // Degenerate case: videoPose coincides with headPos
        val degeneratePose = projectOverlayPoseTowardViewer(Pose(headPos, videoRot), headPos, uiDepthMeters = 2.0f)
        assertEquals(1f, degeneratePose.translation.x, 1e-4f)
        assertEquals(1.5f, degeneratePose.translation.y, 1e-4f)
        assertEquals(-2.0f, degeneratePose.translation.z, 1e-4f)
    }

    @Test
    fun `isPanelOutsideComfortableFov is idempotent with computeViewerCenteredPanelPose across head pitches and yaws`() {
        val headPos = Vector3(0.4f, 1.55f, -0.3f)
        val yaws = listOf(0f, 45f, 90f, 135f, 180f, -90f, -135f)
        val pitches = listOf(0f, -20f, 20f, -45f, 45f, -75f, 75f)

        for (yaw in yaws) {
            for (pitch in pitches) {
                val headPose = Pose(headPos, quatFromYawPitchDeg(yaw, pitch))
                val recentered = computeViewerCenteredPanelPose(headPose, depthMeters = 6f, manualPitch = false)
                assertFalse(
                    "Expected recentered pose to be inside comfortable FOV for yaw=$yaw pitch=$pitch",
                    isPanelOutsideComfortableFov(headPose, recentered),
                )
            }
        }
    }

    @Test
    fun `isPanelOutsideComfortableFov detects behind-viewer lateral elevation and normal-skewed panels`() {
        val headPos = Vector3.Zero
        val headPose = Pose(headPos, Quaternion.Identity)

        // 1. Straight ahead and facing viewer -> comfortable (false)
        val centered = Pose(Vector3(0f, 0f, -6f), Quaternion.Identity)
        assertFalse(isPanelOutsideComfortableFov(headPose, centered))

        // 2. Behind viewer -> outside FOV (true)
        val behind = Pose(Vector3(0f, 0f, 6f), viewerFacingRotation(Vector3(0f, 0f, 6f), headPos, false))
        assertTrue(isPanelOutsideComfortableFov(headPose, behind))

        // 3. 45 deg to the right (> 35 deg threshold) -> outside FOV (true)
        val right45Pos = Vector3(4.24f, 0f, -4.24f)
        val right45 = Pose(right45Pos, viewerFacingRotation(right45Pos, headPos, false))
        assertTrue(isPanelOutsideComfortableFov(headPose, right45))

        // 4. 45 deg elevated above horizontal (> 35 deg threshold) -> outside FOV (true)
        val high45Pos = Vector3(0f, 4.24f, -4.24f)
        val high45 = Pose(high45Pos, viewerFacingRotation(high45Pos, headPos, true))
        assertTrue(isPanelOutsideComfortableFov(headPose, high45))

        // 5. Panel directly in front of a 90-deg rotated viewer, but panel rotation is still Identity -> outside FOV (true)
        val rotatedViewer = Pose(headPos, quatFromYawPitchDeg(yawDeg = -90f, pitchDeg = 0f))
        val unrotatedPanelInFrontOfRotatedViewer = Pose(Vector3(6f, 0f, 0f), Quaternion.Identity)
        assertTrue(isPanelOutsideComfortableFov(rotatedViewer, unrotatedPanelInFrontOfRotatedViewer))
    }

    @Test
    fun `shouldAutoRestorePersistedAnchor accepts front wall in range and rejects behind, far, close, or null poses`() {
        val headPose = Pose(Vector3.Zero, Quaternion.Identity)

        // Front wall 3.0m ahead -> true
        val frontWallPose = Pose(Vector3(0f, 0f, -3.0f), Quaternion.Identity)
        assertTrue(WallAnchor.shouldAutoRestorePersistedAnchor(headPose, frontWallPose))

        // Wall behind viewer (180 deg) -> false
        val behindWallPose = Pose(Vector3(0f, 0f, 3.0f), Quaternion.Identity)
        assertFalse(WallAnchor.shouldAutoRestorePersistedAnchor(headPose, behindWallPose))

        // Wall > 15.0m away in another room -> false
        val farWallPose = Pose(Vector3(0f, 0f, -20.0f), Quaternion.Identity)
        assertFalse(WallAnchor.shouldAutoRestorePersistedAnchor(headPose, farWallPose))

        // Wall too close (< 0.75m) -> false
        val closeWallPose = Pose(Vector3(0f, 0f, -0.4f), Quaternion.Identity)
        assertFalse(WallAnchor.shouldAutoRestorePersistedAnchor(headPose, closeWallPose))

        // Null inputs -> false
        assertFalse(WallAnchor.shouldAutoRestorePersistedAnchor(null, frontWallPose))
        assertFalse(WallAnchor.shouldAutoRestorePersistedAnchor(headPose, null))
    }
}
