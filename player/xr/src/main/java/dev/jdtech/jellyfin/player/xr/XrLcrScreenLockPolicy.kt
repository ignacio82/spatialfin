package dev.jdtech.jellyfin.player.xr

import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import dev.jdtech.jellyfin.player.core.splitav.TheaterScreenPlaneContract
import dev.jdtech.jellyfin.player.core.splitav.TheaterSessionSnapshot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin policy and geometry math for Galaxy XR Theater Audio `LCR Screen Lock`,
 * single-anchor `T_anchor_to_mlp` calibration, and `0 lux` dark-room `Center Snap` fallback.
 */
object XrLcrScreenLockPolicy {

    /** Maximum one-shot startup optical restore budget (`<= 2_500 ms` per Spec 1). */
    const val THEATER_OPTICAL_RESTORE_TIMEOUT_MS: Long = 2_500L

    /** Default flat screen width (m) when the room profile omits `lcr_screen_plane`. */
    const val DEFAULT_THEATER_SCREEN_WIDTH_M: Float = 2.667f

    /** Default flat screen height (m) when the room profile omits `lcr_screen_plane`. */
    const val DEFAULT_THEATER_SCREEN_HEIGHT_M: Float = 1.5f

    /** Default viewing distance (m) when the room profile omits `lcr_screen_plane`. */
    const val DEFAULT_THEATER_SCREEN_DEPTH_M: Float = 3.0f

    enum class OpticalAnchorState {
        IDLE,
        RESTORING,
        ANCHORED,
        DARK_ROOM_FALLBACK,
    }

    data class TheaterScreenLayout(
        val widthMeters: Float,
        val heightMeters: Float,
        val depthMeters: Float,
        val scale: Float,
    )

    /**
     * `LCR Screen Lock` is active whenever a Theater Audio session is active, the user has
     * `pref_xr_theater_lcr_screen_lock` enabled (forwarded via [TheaterSessionSnapshot]), and
     * playback is flat (non-180°/360°).
     */
    fun isLcrScreenLockActive(
        theaterSnapshot: TheaterSessionSnapshot?,
        immersiveProjection: Boolean,
    ): Boolean = theaterSnapshot?.active == true &&
        theaterSnapshot.lcrScreenLockEnabled &&
        !immersiveProjection

    /**
     * Standalone `WallAnchor` stays flush-flat against a physical wall (`XrScreenCurvePreset.FLAT`).
     * Theater Audio `LCR Screen Lock` locks the screen's world pose/alignment to the room LCR
     * anchor while preserving the user's chosen flat or curved cinema preset (the cylindrical mesh
     * is tangent to `z = 0` at center and curves forward toward the viewer, so it stays centered
     * on `FC` without clipping behind the anchor plane).
     */
    fun effectiveCurvePreset(
        requested: XrScreenCurvePreset,
        wallPinActive: Boolean,
        lcrScreenLockActive: Boolean,
    ): XrScreenCurvePreset = if (wallPinActive && !lcrScreenLockActive) {
        XrScreenCurvePreset.FLAT
    } else {
        requested
    }

    /**
     * Theater Audio `LCR Screen Lock` takes precedence over standalone `WallAnchor` auto-restore
     * and manual wall pinning.
     */
    fun shouldSuppressStandaloneWallAnchor(theaterSnapshot: TheaterSessionSnapshot?): Boolean =
        theaterSnapshot?.active == true && theaterSnapshot.lcrScreenLockEnabled

    /**
     * `SpatialPlayerRecoveryEffect` must be disabled while `LCR Screen Lock` or standalone
     * `WallAnchor` is active so headset doff/don recovery never fights world-locked LCR placement.
     */
    fun shouldSuppressRecoveryEffect(
        wallPinActive: Boolean,
        lcrScreenLockActive: Boolean,
    ): Boolean = wallPinActive || lcrScreenLockActive

    /**
     * Never invoke `WallAnchor.unpersist()` on manual recenter while a Theater Audio session is
     * active (persisted standalone wall anchors and per-room `RememberedReceiversStore` anchors
     * remain completely isolated).
     */
    fun shouldUnpersistStandaloneWallAnchorOnRecenter(
        manualPitch: Boolean,
        wallPinActive: Boolean,
        wallPinRequested: Boolean,
        theaterSnapshot: TheaterSessionSnapshot?,
    ): Boolean {
        if (theaterSnapshot?.active == true) return false
        return manualPitch && (wallPinActive || wallPinRequested)
    }

    /**
     * Visible when `LCR Screen Lock` is active and startup optical anchor restoration timed out or
     * was unavailable (e.g. `0 lux` dark room).
     */
    fun resolveDarkRoomBannerVisible(
        lcrScreenLockActive: Boolean,
        opticalState: OpticalAnchorState,
    ): Boolean = lcrScreenLockActive && opticalState == OpticalAnchorState.DARK_ROOM_FALLBACK

    /**
     * Computes frame-0 flat cinema screen dimensions, depth, and scale from the room profile's
     * optional `lcr_screen_plane` (falling back to living-room cinema defaults).
     */
    fun resolveFrame0ScreenLayout(
        plane: TheaterScreenPlaneContract?,
        baseWidthMeters: Float = DEFAULT_THEATER_SCREEN_WIDTH_M,
    ): TheaterScreenLayout {
        val widthM = plane?.widthM?.toFloat()
            ?.takeIf { it.isFinite() && it >= 0.5f && it <= 12.0f }
            ?: DEFAULT_THEATER_SCREEN_WIDTH_M
        val heightM = plane?.heightM?.toFloat()
            ?.takeIf { it.isFinite() && it >= 0.3f && it <= 8.0f }
            ?: (widthM * 9f / 16f)
        val depthM = plane?.centerM?.takeIf { it.size >= 3 }?.let { c ->
            val dx = c[0].toFloat()
            val dz = c[2].toFloat()
            val horiz = sqrt(dx * dx + dz * dz)
            horiz.takeIf { it.isFinite() && it >= MIN_VIDEO_DEPTH_METERS }
                ?.coerceIn(MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS)
        } ?: DEFAULT_THEATER_SCREEN_DEPTH_M
        val safeBase = if (baseWidthMeters > 0.1f) baseWidthMeters else DEFAULT_THEATER_SCREEN_WIDTH_M
        val scale = (widthM / safeBase).coerceIn(0.15f, 4.0f)
        return TheaterScreenLayout(
            widthMeters = widthM,
            heightMeters = heightM,
            depthMeters = depthM,
            scale = scale,
        )
    }

    /**
     * Computes a flat, gravity-aligned screen pose along the viewer's current horizontal facing
     * ray at [layout.depthMeters] (`frame-0` spawn and `0 lux` `Lock Here` action).
     */
    fun computeCenterSnapPose(
        headPose: Pose?,
        layout: TheaterScreenLayout,
    ): Pose {
        val hp = headPose?.translation ?: Vector3.Zero
        val rawForward = headPose?.rotation?.let { it * Vector3(0f, 0f, -1f) } ?: Vector3(0f, 0f, -1f)
        val horizLen = sqrt(rawForward.x * rawForward.x + rawForward.z * rawForward.z)
        val fwdX = if (horizLen > 1e-3f) rawForward.x / horizLen else 0f
        val fwdZ = if (horizLen > 1e-3f) rawForward.z / horizLen else -1f

        val panelPos = Vector3(
            hp.x + fwdX * layout.depthMeters,
            hp.y,
            hp.z + fwdZ * layout.depthMeters,
        )
        // Screen +Z normal faces back toward the viewer (-fwdX, 0, -fwdZ), strictly upright.
        val rot = viewerFacingRotation(panelPos = panelPos, viewerPos = hp, allowPitch = false)
        return Pose(panelPos, rot)
    }

    /**
     * Computes the gravity-aligned rigid offset `T_anchor_to_mlp` as 7 floats
     * `[dx, dy, dz, qx, qy, qz, qw]`:
     * - `(dx, dy, dz)` is the MLP offset from the front-wall anchor in the anchor's gravity-aligned
     *   horizontal frame (where anchor `-Z` points along the viewer's seated gaze toward the wall).
     * - `(qx, qy, qz, qw)` is the pure yaw-only quaternion encoding the seated MLP's horizontal
     *   facing yaw relative to the anchor's yaw.
     */
    fun computeGravityAnchorToMlpTransform(
        anchorActivityPose: Pose,
        mlpHeadPose: Pose,
    ): List<Float> {
        val anchorYaw = extractHorizontalYawRadians(anchorActivityPose.rotation)
        val mlpYaw = extractHorizontalYawRadians(mlpHeadPose.rotation)
        val relYaw = normalizeAngleRadians(mlpYaw - anchorYaw)

        // Transform world-space delta (mlp - anchor) into anchor's horizontal yaw frame.
        val wx = mlpHeadPose.translation.x - anchorActivityPose.translation.x
        val wy = mlpHeadPose.translation.y - anchorActivityPose.translation.y
        val wz = mlpHeadPose.translation.z - anchorActivityPose.translation.z
        val cosA = cos(-anchorYaw)
        val sinA = sin(-anchorYaw)
        val dx = (wx * cosA + wz * sinA)
        val dz = (-wx * sinA + wz * cosA)

        val halfYaw = relYaw * 0.5f
        val qx = 0f
        val qy = sin(halfYaw)
        val qz = 0f
        val qw = cos(halfYaw)
        return listOf(dx, wy, dz, qx, qy, qz, qw)
    }

    /**
     * Reconstructs the activity-space flat cinema screen pose from the restored front-wall
     * [anchorActivityPose], persisted 7-float [anchorToMlpTransform], and optional
     * [lcrScreenPlane]. Falls back cleanly to facing the viewer from the anchor if transform
     * metadata is incomplete.
     */
    fun computeAnchorRelativeScreenPose(
        anchorActivityPose: Pose,
        anchorToMlpTransform: List<Float>?,
        plane: TheaterScreenPlaneContract?,
        fallbackHeadPos: Vector3? = null,
    ): Pose {
        val anchorPos = anchorActivityPose.translation
        if (anchorToMlpTransform == null || anchorToMlpTransform.size < 7) {
            val forward = WallAnchor.facingTowardViewer(anchorPos, fallbackHeadPos)
            val panelPos = Vector3(
                anchorPos.x + forward.x * WallAnchor.PANEL_STANDOFF_METERS,
                anchorPos.y,
                anchorPos.z + forward.z * WallAnchor.PANEL_STANDOFF_METERS,
            )
            return Pose(panelPos, WallAnchor.lookRotation(forward))
        }
        val anchorYaw = extractHorizontalYawRadians(anchorActivityPose.rotation)
        val cosA = cos(anchorYaw)
        val sinA = sin(anchorYaw)
        val localDx = anchorToMlpTransform[0]
        val localDy = anchorToMlpTransform[1]
        val localDz = anchorToMlpTransform[2]
        // Reconstruct MLP world position in activity space.
        val mlpWorld = Vector3(
            anchorPos.x + (localDx * cosA + localDz * sinA),
            anchorPos.y + localDy,
            anchorPos.z + (-localDx * sinA + localDz * cosA),
        )
        val relYaw = 2f * atan2(anchorToMlpTransform[4], anchorToMlpTransform[6])
        val mlpYaw = normalizeAngleRadians(anchorYaw + relYaw)

        // Screen center in MLP frame defaults to (0, 0, -depth) where -Z is toward physical FC.
        val centerM = plane?.centerM?.takeIf { it.size >= 3 }
        val screenMlpX = centerM?.get(0)?.toFloat() ?: 0f
        val screenMlpY = centerM?.get(1)?.toFloat() ?: 0f
        val screenMlpZ = centerM?.get(2)?.toFloat() ?: -DEFAULT_THEATER_SCREEN_DEPTH_M

        // Rotate MLP-local vector into activity space using mlpYaw (+Z right-handed rotation around +Y).
        val cosM = cos(mlpYaw)
        val sinM = sin(mlpYaw)
        val screenWorld = Vector3(
            mlpWorld.x + (screenMlpX * cosM + screenMlpZ * sinM),
            mlpWorld.y + screenMlpY,
            mlpWorld.z + (-screenMlpX * sinM + screenMlpZ * cosM),
        )
        val rot = viewerFacingRotation(panelPos = screenWorld, viewerPos = mlpWorld, allowPitch = false)
        return Pose(screenWorld, rot)
    }

    /**
     * Converts a 3D speaker position in `activitySpace` into Spec 0's canonical right-handed
     * MLP-rooted Room Coordinate Frame (`+X` right, `+Y` up, `-Z` forward toward `FC`, meters).
     */
    fun computeMlpRelativeSpeakerCoord(
        mlpHeadPose: Pose,
        speakerActivityPos: Vector3,
    ): List<Double> {
        val mlpYaw = extractHorizontalYawRadians(mlpHeadPose.rotation)
        val wx = speakerActivityPos.x - mlpHeadPose.translation.x
        val wy = speakerActivityPos.y - mlpHeadPose.translation.y
        val wz = speakerActivityPos.z - mlpHeadPose.translation.z
        val cosM = cos(-mlpYaw)
        val sinM = sin(-mlpYaw)
        val x = (wx * cosM + wz * sinM).toDouble()
        val y = wy.toDouble()
        val z = (-wx * sinM + wz * cosM).toDouble()
        return listOf(round3(x), round3(y), round3(z))
    }

    internal fun extractHorizontalYawRadians(rotation: Quaternion): Float {
        val fwd = rotation * Vector3(0f, 0f, -1f)
        // Yaw angle around +Y where yaw=0 maps local -Z -> (0, 0, -1) and +X -> (1, 0, 0).
        return atan2(-fwd.x, -fwd.z)
    }

    private fun normalizeAngleRadians(angle: Float): Float {
        var a = angle
        val twoPi = (2.0 * Math.PI).toFloat()
        val pi = Math.PI.toFloat()
        while (a > pi) a -= twoPi
        while (a < -pi) a += twoPi
        return a
    }

    private fun round3(value: Double): Double = kotlin.math.round(value * 1000.0) / 1000.0
}
