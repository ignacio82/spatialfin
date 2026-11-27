package dev.jdtech.jellyfin.player.xr

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import dev.jdtech.jellyfin.player.core.splitav.TheaterScreenPlaneContract
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrLcrScreenLockPolicyTest {

    @Test
    fun `LCR Screen Lock takes precedence over standalone WallAnchor and enforces FLAT curve`() {
        val activeLocked = TheaterSessionSnapshot(
            active = true,
            lcrScreenLockEnabled = true,
        )
        val activeFree = TheaterSessionSnapshot(
            active = true,
            lcrScreenLockEnabled = false,
        )

        assertTrue(XrLcrScreenLockPolicy.isLcrScreenLockActive(activeLocked, immersiveProjection = false))
        assertFalse(XrLcrScreenLockPolicy.isLcrScreenLockActive(activeLocked, immersiveProjection = true))
        assertFalse(XrLcrScreenLockPolicy.isLcrScreenLockActive(activeFree, immersiveProjection = false))

        assertTrue(XrLcrScreenLockPolicy.shouldSuppressStandaloneWallAnchor(activeLocked))
        assertFalse(XrLcrScreenLockPolicy.shouldSuppressStandaloneWallAnchor(activeFree))

        assertEquals(
            XrScreenCurvePreset.FLAT,
            XrLcrScreenLockPolicy.effectiveCurvePreset(
                requested = XrScreenCurvePreset.CINEMA,
                wallPinActive = false,
                lcrScreenLockActive = true,
            ),
        )
        assertEquals(
            XrScreenCurvePreset.DEEP,
            XrLcrScreenLockPolicy.effectiveCurvePreset(
                requested = XrScreenCurvePreset.DEEP,
                wallPinActive = false,
                lcrScreenLockActive = false,
            ),
        )
        assertTrue(
            XrLcrScreenLockPolicy.shouldSuppressRecoveryEffect(
                wallPinActive = false,
                lcrScreenLockActive = true,
            ),
        )
    }

    @Test
    fun `manual recenter NEVER unpersists standalone WallAnchor during Theater Audio`() {
        val activeTheater = TheaterSessionSnapshot(active = true, lcrScreenLockEnabled = true)
        val activeTheaterFree = TheaterSessionSnapshot(active = true, lcrScreenLockEnabled = false)

        assertFalse(
            XrLcrScreenLockPolicy.shouldUnpersistStandaloneWallAnchorOnRecenter(
                manualPitch = true,
                wallPinActive = true,
                wallPinRequested = true,
                theaterSnapshot = activeTheater,
            ),
        )
        assertFalse(
            XrLcrScreenLockPolicy.shouldUnpersistStandaloneWallAnchorOnRecenter(
                manualPitch = true,
                wallPinActive = true,
                wallPinRequested = true,
                theaterSnapshot = activeTheaterFree,
            ),
        )
        assertTrue(
            XrLcrScreenLockPolicy.shouldUnpersistStandaloneWallAnchorOnRecenter(
                manualPitch = true,
                wallPinActive = true,
                wallPinRequested = true,
                theaterSnapshot = null,
            ),
        )
    }

    @Test
    fun `0 lux frame-0 layout derives depth and scale from lcrScreenPlane and controls dark-room banner`() {
        val plane = TheaterScreenPlaneContract(
            centerM = listOf(0.0, 0.0, -3.6),
            widthM = 3.2,
            heightM = 1.8,
            normalVec = listOf(0.0, 0.0, 1.0),
        )
        val layout = XrLcrScreenLockPolicy.resolveFrame0ScreenLayout(plane, baseWidthMeters = 2.667f)
        assertEquals(3.2f, layout.widthMeters, 0.01f)
        assertEquals(1.8f, layout.heightMeters, 0.01f)
        assertEquals(3.6f, layout.depthMeters, 0.01f)
        assertEquals(3.2f / 2.667f, layout.scale, 0.01f)

        assertTrue(
            XrLcrScreenLockPolicy.resolveDarkRoomBannerVisible(
                lcrScreenLockActive = true,
                opticalState = XrLcrScreenLockPolicy.OpticalAnchorState.DARK_ROOM_FALLBACK,
            ),
        )
        assertFalse(
            XrLcrScreenLockPolicy.resolveDarkRoomBannerVisible(
                lcrScreenLockActive = true,
                opticalState = XrLcrScreenLockPolicy.OpticalAnchorState.ANCHORED,
            ),
        )
        assertFalse(
            XrLcrScreenLockPolicy.resolveDarkRoomBannerVisible(
                lcrScreenLockActive = false,
                opticalState = XrLcrScreenLockPolicy.OpticalAnchorState.DARK_ROOM_FALLBACK,
            ),
        )

        val head = Pose(Vector3(0f, 1.2f, 0f), Quaternion.Identity)
        val snapPose = XrLcrScreenLockPolicy.computeCenterSnapPose(head, layout)
        assertEquals(0f, snapPose.translation.x, 0.01f)
        assertEquals(1.2f, snapPose.translation.y, 0.01f)
        assertEquals(-3.6f, snapPose.translation.z, 0.01f)
    }

    @Test
    fun `T_anchor_to_mlp round-trips screen pose and computes right-handed MLP speaker coordinates`() {
        val anchorPose = Pose(Vector3(0f, 1.5f, -3.5f), Quaternion.Identity)
        val mlpHeadPose = Pose(Vector3(0f, 1.2f, 0f), Quaternion.Identity)
        val transform = XrLcrScreenLockPolicy.computeGravityAnchorToMlpTransform(
            anchorActivityPose = anchorPose,
            mlpHeadPose = mlpHeadPose,
        )
        assertEquals(7, transform.size)

        val plane = TheaterScreenPlaneContract(
            centerM = listOf(0.0, 0.0, -3.5),
            widthM = 3.0,
            heightM = 1.6875,
            normalVec = listOf(0.0, 0.0, 1.0),
        )
        val restoredScreenPose = XrLcrScreenLockPolicy.computeAnchorRelativeScreenPose(
            anchorActivityPose = anchorPose,
            anchorToMlpTransform = transform,
            plane = plane,
        )
        assertEquals(0f, restoredScreenPose.translation.x, 0.02f)
        assertEquals(1.2f, restoredScreenPose.translation.y, 0.02f)
        assertEquals(-3.5f, restoredScreenPose.translation.z, 0.02f)

        // With MLP rotated 90 degrees left (facing -X), a speaker at (-2, 1.2, 0) is straight ahead (-Z = -2.0).
        val yaw90Left = Quaternion(0f, sin((PI / 4.0).toFloat()), 0f, cos((PI / 4.0).toFloat()))
        val rotatedMlp = Pose(Vector3(0f, 1.2f, 0f), yaw90Left)
        val fcCoord = XrLcrScreenLockPolicy.computeMlpRelativeSpeakerCoord(
            mlpHeadPose = rotatedMlp,
            speakerActivityPos = Vector3(-2.0f, 1.2f, 0f),
        )
        assertEquals(0.0, fcCoord[0], 0.02)
        assertEquals(0.0, fcCoord[1], 0.02)
        assertEquals(-2.0, fcCoord[2], 0.02)
    }
}
