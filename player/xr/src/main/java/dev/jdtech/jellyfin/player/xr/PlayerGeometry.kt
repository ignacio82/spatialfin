package dev.jdtech.jellyfin.player.xr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.xr.arcore.ArDevice
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.SpaceChangeEvent
import androidx.xr.scenecore.scene
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.withSign
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * XR panel geometry, viewer-facing orientation, session device-tracking configuration,
 * and headset doff/don recovery helpers for the player's video/UI/subtitle root entities.
 */

// The default cinema-scale distance from the user to the video panel. Chosen
// to match Android XR design guidance for theater-scale content.
internal const val VIDEO_DEPTH_METERS = 6.0f

// UI (controls, dialogs, subtitles) sits much closer so text stays readable.
internal const val UI_DEPTH_METERS = 2.0f

// Default panel scale baseline — tuned so a DEFAULT_VIDEO_WIDTH_METERS-wide panel
// at VIDEO_DEPTH_METERS matches perceived size of a large living-room display.
internal const val DEFAULT_VIDEO_PANEL_SCALE = 1.39f

// Allowed viewing depth bounds (m) for the flat cinema panel.
internal const val MIN_VIDEO_DEPTH_METERS = 0.75f
internal const val MAX_VIDEO_DEPTH_METERS = 15.0f

// Maximum vertical pitch (degrees) when orienting a panel toward the viewer so
// zenith/nadir placements never become colinear with world up (0, 1, 0).
internal const val MAX_PITCH_DEGREES = 75f

// Upward head pitch threshold (degrees) above which automatic launch/resume
// recentering angles the panel overhead for reclined/ceiling viewing.
internal const val AUTO_RECENTER_CEILING_PITCH_DEGREES = 45f

// Maximum comfortable off-axis angle (degrees) before an unpinned flat panel is
// considered out-of-FOV or skewed on headset resume / origin updates.
internal const val COMFORTABLE_FOV_MAX_ANGLE_DEGREES = 35f

// Inter-sample step delays (ms) for the 3-second SLAM stabilization window on
// launch, ON_STOP -> ON_RESUME, and SceneCore origin/space updates
// (cumulative timestamps: 0ms, 250ms, 750ms, 1500ms, 3000ms).
internal val SLAM_STABILIZATION_STEP_DELAYS_MS = listOf(0L, 250L, 500L, 750L, 1500L)

// Radius (metres) for the 180° hemisphere / 360° sphere immersive video surface.
// 50 m is well inside the runtime's far clip while reading as effectively "at infinity".
internal const val VR_SPHERE_RADIUS_METERS = 50.0f

/**
 * Ensures [DeviceTrackingMode.SPATIAL] is active on [session] without clobbering
 * existing hand-tracking, plane-tracking, or anchor-persistence settings.
 */
internal fun ensureHeadTrackingConfigured(session: Session): Boolean = runCatching {
    val current = session.config
    if (current.deviceTracking == DeviceTrackingMode.SPATIAL) {
        return true
    }
    val updated = Config.Builder(current)
        .setDeviceTracking(DeviceTrackingMode.SPATIAL)
        .build()
    (session.configure(updated) is SessionConfigureSuccess).also {
        Timber.i("XR_VIDEO: device tracking configure success=%b", it)
    }
}.getOrElse {
    Timber.w(it, "XR_VIDEO: failed to enable device tracking")
    false
}

/**
 * Head-centered pose (in activity space) transformed from [ArDevice.state].
 * Ensures device tracking is configured on [session] before reading the pose.
 * Returns null if the runtime does not expose a valid head pose yet.
 */
internal fun computeHeadCenteredPose(session: Session): Pose? = runCatching {
    ensureHeadTrackingConfigured(session)
    val devicePose = ArDevice.getInstance(session).state.value.devicePose
    session.scene.perceptionSpace.transformPoseTo(devicePose, session.scene.activitySpace)
}.onFailure { Timber.w(it, "XR_VIDEO: failed to compute head-centered pose") }.getOrNull()

/**
 * Measures the Euclidean distance from [viewerPos] (or activity-space origin `(0, 0, 0)`
 * when null) to [pose], clamped to `[MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS]`.
 */
internal fun extractVideoDepth(
    pose: Pose,
    currentDepth: Float,
    viewerPos: Vector3? = null,
): Float {
    val origin = viewerPos ?: Vector3.Zero
    val dx = pose.translation.x - origin.x
    val dy = pose.translation.y - origin.y
    val dz = pose.translation.z - origin.z
    val distance = sqrt(dx * dx + dy * dy + dz * dz)
    val fallback = currentDepth.coerceIn(MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS)
    val finalDepth =
        if (!distance.isFinite() || distance <= 0.5f) {
            fallback
        } else {
            distance.coerceIn(MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS)
        }
    if (System.currentTimeMillis() % 1000 < 10) {
        Timber.v("extractVideoDepth: raw=%.3f clamped=%.3f fallback=%.3f", distance, finalDepth, currentDepth)
    }
    return finalDepth
}

/**
 * Builds a world-space rotation whose local **+Z** (a SceneCore flat panel's front
 * normal) points from [panelPos] toward [viewerPos] with zero roll relative to world
 * up `(0, 1, 0)`.
 *
 * When [allowPitch] is false, vertical elevation is zeroed so the panel stays
 * perpendicular to the horizontal `XZ` plane. When [allowPitch] is true, pitch is
 * clamped to `±MAX_PITCH_DEGREES` (`±75°`) so overhead placements never become
 * colinear with world up.
 */
internal fun viewerFacingRotation(
    panelPos: Vector3,
    viewerPos: Vector3,
    allowPitch: Boolean,
): Quaternion {
    val toViewerX = viewerPos.x - panelPos.x
    val toViewerY = viewerPos.y - panelPos.y
    val toViewerZ = viewerPos.z - panelPos.z
    val horizDist = sqrt(toViewerX * toViewerX + toViewerZ * toViewerZ)
    val maxTan = tan(Math.toRadians(MAX_PITCH_DEGREES.toDouble())).toFloat()

    val hx: Float
    val hz: Float
    val clampedY: Float
    if (horizDist < 1e-4f) {
        if (!allowPitch || abs(toViewerY) < 1e-4f) {
            return Quaternion.Identity
        }
        hx = 0f
        hz = 1f
        clampedY = maxTan.withSign(toViewerY)
    } else {
        hx = toViewerX
        hz = toViewerZ
        clampedY =
            if (allowPitch) {
                val limit = horizDist * maxTan
                toViewerY.coerceIn(-limit, limit)
            } else {
                0f
            }
    }

    val fl = sqrt(hx * hx + clampedY * clampedY + hz * hz)
    if (fl < 1e-4f) return Quaternion.Identity
    val fx = hx / fl
    val fy = clampedY / fl
    val fz = hz / fl

    // right = worldUp(0, 1, 0) × forward = (fz, 0, -fx)
    var rx = fz
    val ry = 0f
    var rz = -fx
    val rl = sqrt(rx * rx + rz * rz)
    if (rl < 1e-4f) return Quaternion.Identity
    rx /= rl
    rz /= rl

    // up = forward × right
    val ux = fy * rz
    val uy = fz * rx - fx * rz
    val uz = -fy * rx

    // Rotation matrix columns: X = right, Y = up, Z = forward
    val m00 = rx; val m01 = ux; val m02 = fx
    val m10 = ry; val m11 = uy; val m12 = fy
    val m20 = rz; val m21 = uz; val m22 = fz
    val trace = m00 + m11 + m22
    val raw =
        when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f
                Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quaternion(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            }
            m11 > m22 -> {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quaternion((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            }
            else -> {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }
        }
    val qLen = sqrt(raw.x * raw.x + raw.y * raw.y + raw.z * raw.z + raw.w * raw.w)
    return if (qLen > 1e-4f) {
        Quaternion(raw.x / qLen, raw.y / qLen, raw.z / qLen, raw.w / qLen)
    } else {
        Quaternion.Identity
    }
}

/**
 * Computes the target flat video panel [Pose] at [depthMeters] in front of [headPose].
 *
 * Uses level-horizon placement (`pitch = 0°`) when [manualPitch] is false unless the
 * viewer's upward head pitch exceeds [AUTO_RECENTER_CEILING_PITCH_DEGREES] (`45°`).
 * Uses clamped 3D gaze (`±75°` pitch) when [manualPitch] is true or when reclined.
 */
internal fun computeViewerCenteredPanelPose(
    headPose: Pose?,
    depthMeters: Float,
    manualPitch: Boolean,
): Pose {
    val clampedDepth = depthMeters.coerceIn(MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS)
    if (headPose == null) {
        return Pose(Vector3(0f, 0f, -clampedDepth), Quaternion.Identity)
    }
    val headPos = headPose.translation
    val rawForward = headPose.rotation * Vector3(0f, 0f, -1f)
    val rawLen = sqrt(rawForward.x * rawForward.x + rawForward.y * rawForward.y + rawForward.z * rawForward.z)
    val forward =
        if (rawLen > 1e-4f) {
            Vector3(rawForward.x / rawLen, rawForward.y / rawLen, rawForward.z / rawLen)
        } else {
            Vector3(0f, 0f, -1f)
        }

    val pitchDeg = Math.toDegrees(asin(forward.y.coerceIn(-1f, 1f)).toDouble()).toFloat()
    val usePitch = manualPitch || (pitchDeg > AUTO_RECENTER_CEILING_PITCH_DEGREES)

    val horizLen = sqrt(forward.x * forward.x + forward.z * forward.z)
    val horizForward =
        if (horizLen > 1e-4f) {
            Vector3(forward.x / horizLen, 0f, forward.z / horizLen)
        } else {
            Vector3(0f, 0f, -1f)
        }

    return if (!usePitch) {
        val panelPos = Vector3(
            headPos.x + horizForward.x * clampedDepth,
            headPos.y,
            headPos.z + horizForward.z * clampedDepth,
        )
        Pose(panelPos, viewerFacingRotation(panelPos, headPos, allowPitch = false))
    } else {
        val clampedPitchRad =
            Math.toRadians(pitchDeg.coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES).toDouble()).toFloat()
        val cosP = cos(clampedPitchRad)
        val sinP = sin(clampedPitchRad)
        val dir = Vector3(horizForward.x * cosP, sinP, horizForward.z * cosP)
        val panelPos = Vector3(
            headPos.x + dir.x * clampedDepth,
            headPos.y + dir.y * clampedDepth,
            headPos.z + dir.z * clampedDepth,
        )
        Pose(panelPos, viewerFacingRotation(panelPos, headPos, allowPitch = true))
    }
}

/**
 * Computes `(spherePose, overlayPose)` for 180°/360° immersive playback.
 *
 * The sphere is centered at `headPose.translation` with horizontal yaw-only orientation
 * (zero pitch and zero roll so a 180° hemisphere horizon stays level), and the control/subtitle
 * overlays sit at [uiDepthMeters] along the same horizontal forward axis facing the viewer.
 */
internal fun computeImmersiveViewerPoses(
    headPose: Pose?,
    uiDepthMeters: Float = UI_DEPTH_METERS,
): Pair<Pose, Pose> {
    val headPos = headPose?.translation ?: Vector3.Zero
    val rawForward = (headPose?.rotation ?: Quaternion.Identity) * Vector3(0f, 0f, -1f)
    val horizLen = sqrt(rawForward.x * rawForward.x + rawForward.z * rawForward.z)
    val horizForward =
        if (horizLen > 1e-4f) {
            Vector3(rawForward.x / horizLen, 0f, rawForward.z / horizLen)
        } else {
            Vector3(0f, 0f, -1f)
        }
    val overlayPos = Vector3(
        headPos.x + horizForward.x * uiDepthMeters,
        headPos.y,
        headPos.z + horizForward.z * uiDepthMeters,
    )
    val yawRotation = viewerFacingRotation(panelPos = overlayPos, viewerPos = headPos, allowPitch = false)
    return Pair(Pose(headPos, yawRotation), Pose(overlayPos, yawRotation))
}

/**
 * Projects a drag ray of length [grabDepthMeters] from `headPos ?: rayOrigin` and
 * orients the panel to face that anchor origin with yaw + pitch (`±75°` clamp) and zero roll.
 */
internal fun computeDragPose(
    rayOrigin: Vector3,
    rayDirection: Vector3,
    headPos: Vector3?,
    grabDepthMeters: Float,
): Pose {
    val anchorOrigin = headPos ?: rayOrigin
    val clampedDepth = grabDepthMeters.coerceIn(MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS)
    val dirLen = sqrt(
        rayDirection.x * rayDirection.x +
            rayDirection.y * rayDirection.y +
            rayDirection.z * rayDirection.z,
    )
    val dir =
        if (dirLen > 1e-4f) {
            Vector3(rayDirection.x / dirLen, rayDirection.y / dirLen, rayDirection.z / dirLen)
        } else {
            Vector3(0f, 0f, -1f)
        }
    val panelPos = Vector3(
        anchorOrigin.x + dir.x * clampedDepth,
        anchorOrigin.y + dir.y * clampedDepth,
        anchorOrigin.z + dir.z * clampedDepth,
    )
    return Pose(panelPos, viewerFacingRotation(panelPos, anchorOrigin, allowPitch = true))
}

/**
 * Projects `uiRoot` and `subtitleRoot` along the line of sight from `headPos ?: Vector3.Zero`
 * to `videoPose.translation` at `min(uiDepthMeters, dist * 0.85f)` with `videoPose.rotation`.
 */
internal fun projectOverlayPoseTowardViewer(
    videoPose: Pose,
    headPos: Vector3?,
    uiDepthMeters: Float = UI_DEPTH_METERS,
): Pose {
    val viewerOrigin = headPos ?: Vector3.Zero
    val vx = videoPose.translation.x - viewerOrigin.x
    val vy = videoPose.translation.y - viewerOrigin.y
    val vz = videoPose.translation.z - viewerOrigin.z
    val dist = sqrt(vx * vx + vy * vy + vz * vz)
    if (dist < 1e-3f) {
        val fallbackForward = videoPose.rotation * Vector3(0f, 0f, -1f)
        return Pose(
            Vector3(
                viewerOrigin.x + fallbackForward.x * uiDepthMeters,
                viewerOrigin.y + fallbackForward.y * uiDepthMeters,
                viewerOrigin.z + fallbackForward.z * uiDepthMeters,
            ),
            videoPose.rotation,
        )
    }
    val effectiveUiDepth = min(uiDepthMeters, dist * 0.85f)
    val scale = effectiveUiDepth / dist
    return Pose(
        Vector3(
            viewerOrigin.x + vx * scale,
            viewerOrigin.y + vy * scale,
            viewerOrigin.z + vz * scale,
        ),
        videoPose.rotation,
    )
}

internal fun syncViewerProjectedOverlayRoots(
    videoPose: Pose,
    videoScale: Float,
    headPos: Vector3?,
    uiRoot: Entity,
    subtitleRoot: Entity,
    uiDepthMeters: Float = UI_DEPTH_METERS,
) {
    val projectedPose = projectOverlayPoseTowardViewer(videoPose, headPos, uiDepthMeters)
    runCatching {
        uiRoot.setScale(videoScale)
        uiRoot.setPose(projectedPose)
    }
    runCatching {
        subtitleRoot.setScale(videoScale)
        subtitleRoot.setPose(projectedPose)
    }
}

/**
 * Evaluates whether an unpinned flat video panel at [panelPose] is behind the viewer,
 * outside the comfortable viewing cone ([maxAngleDeg]), or skewed relative to [headPose].
 *
 * Guaranteed to return `false` for any pose produced by
 * `computeViewerCenteredPanelPose(headPose, depth, manualPitch = false)`.
 */
internal fun isPanelOutsideComfortableFov(
    headPose: Pose,
    panelPose: Pose,
    maxAngleDeg: Float = COMFORTABLE_FOV_MAX_ANGLE_DEGREES,
): Boolean {
    val toPanelX = panelPose.translation.x - headPose.translation.x
    val toPanelY = panelPose.translation.y - headPose.translation.y
    val toPanelZ = panelPose.translation.z - headPose.translation.z
    val dist = sqrt(toPanelX * toPanelX + toPanelY * toPanelY + toPanelZ * toPanelZ)
    if (!dist.isFinite() || dist < 0.5f) return true

    val toPanel = Vector3(toPanelX, toPanelY, toPanelZ)
    val localVec = headPose.rotation.inverse * toPanel

    val expectedPose = computeViewerCenteredPanelPose(headPose, dist, manualPitch = false)
    val expectedVec = Vector3(
        expectedPose.translation.x - headPose.translation.x,
        expectedPose.translation.y - headPose.translation.y,
        expectedPose.translation.z - headPose.translation.z,
    )
    val expectedLen = sqrt(
        expectedVec.x * expectedVec.x +
            expectedVec.y * expectedVec.y +
            expectedVec.z * expectedVec.z,
    ).coerceAtLeast(1e-4f)
    val eDir = Vector3(expectedVec.x / expectedLen, expectedVec.y / expectedLen, expectedVec.z / expectedLen)
    val pDir = Vector3(toPanelX / dist, toPanelY / dist, toPanelZ / dist)

    // Panel is behind the viewer in both the pitched head frame and the auto-recenter reference frame.
    val projAlongExpected = toPanelX * eDir.x + toPanelY * eDir.y + toPanelZ * eDir.z
    if (localVec.z >= -0.5f && projAlongExpected <= 0.5f) return true

    // Horizontal azimuth in the viewer's gravity-aligned yaw frame.
    val rawForward = headPose.rotation * Vector3(0f, 0f, -1f)
    val horizLen = sqrt(rawForward.x * rawForward.x + rawForward.z * rawForward.z)
    val horizForward =
        if (horizLen > 1e-4f) {
            Vector3(rawForward.x / horizLen, 0f, rawForward.z / horizLen)
        } else {
            Vector3(0f, 0f, -1f)
        }
    val horizRight = Vector3(-horizForward.z, 0f, horizForward.x)
    val forwardProj = toPanelX * horizForward.x + toPanelZ * horizForward.z
    val rightProj = toPanelX * horizRight.x + toPanelZ * horizRight.z
    val horizAzimuthDeg = Math.toDegrees(atan2(abs(rightProj), forwardProj).toDouble()).toFloat()
    if (horizAzimuthDeg > maxAngleDeg) return true

    // Vertical / 3D angular deviation: allow either the viewer's direct 3D gaze cone
    // or the level-horizon/ceiling auto-recenter target cone.
    val localElevDeg =
        if (localVec.z < -1e-4f) {
            Math.toDegrees(atan2(abs(localVec.y), -localVec.z).toDouble()).toFloat()
        } else {
            180f
        }
    val expectedAngleDeg = angleBetweenUnitVectorsDegrees(pDir, eDir)
    if (min(localElevDeg, expectedAngleDeg) > maxAngleDeg) return true

    // Surface normal skew check: panel's +Z normal must align with either the
    // panel-to-viewer vector or the expected auto-recenter normal within maxAngleDeg.
    val panelNormal = normalizeVector(panelPose.rotation * Vector3(0f, 0f, 1f), Vector3(0f, 0f, 1f))
    val toViewerUnit = Vector3(-pDir.x, -pDir.y, -pDir.z)
    val expectedNormal = normalizeVector(expectedPose.rotation * Vector3(0f, 0f, 1f), toViewerUnit)
    val normalErrorDeg = min(
        angleBetweenUnitVectorsDegrees(panelNormal, toViewerUnit),
        angleBetweenUnitVectorsDegrees(panelNormal, expectedNormal),
    )
    return normalErrorDeg > maxAngleDeg
}

private fun normalizeVector(v: Vector3, fallback: Vector3): Vector3 {
    val len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    return if (len > 1e-4f) Vector3(v.x / len, v.y / len, v.z / len) else fallback
}

private fun angleBetweenUnitVectorsDegrees(a: Vector3, b: Vector3): Float {
    val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(dot.toDouble())).toFloat()
}

/**
 * Shared lifecycle and SceneCore recovery effect for `SpatialPlayerScreen` and
 * `XrFCastInboundPlayerScreen`:
 *  1. Cancels and rolls back any active drag on `ON_PAUSE` / `ON_STOP` so removing the
 *     headset cannot fling the cinema panel to a random coordinate.
 *  2. Polls `computeHeadCenteredPose(session)` across [SLAM_STABILIZATION_STEP_DELAYS_MS]
 *     (`0, 250, 750, 1500, 3000ms` cumulative) on initial launch, `ON_STOP -> ON_RESUME`,
 *     `ActivitySpace.addOriginChangedListener`, and `Scene.setSpaceChangedListener`
 *     without exiting early at `0ms`.
 */
@Composable
internal fun SpatialPlayerRecoveryEffect(
    session: Session,
    lifecycleOwner: LifecycleOwner,
    enabled: Boolean,
    moveInProgress: Boolean,
    onCancelMove: () -> Unit,
    onStabilizationTick: (Pose) -> Unit,
) {
    var recenterCheckTrigger by remember { mutableIntStateOf(1) }
    var stoppedOnce by remember { mutableStateOf(false) }
    val currentMoveInProgress by rememberUpdatedState(moveInProgress)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnCancelMove by rememberUpdatedState(onCancelMove)
    val currentOnStabilizationTick by rememberUpdatedState(onStabilizationTick)

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (currentMoveInProgress) {
                        Timber.i("XR_VIDEO: cancelling in-progress move on %s", event.name)
                        currentOnCancelMove()
                    }
                    if (event == Lifecycle.Event.ON_STOP) {
                        stoppedOnce = true
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (stoppedOnce) {
                        stoppedOnce = false
                        recenterCheckTrigger++
                        Timber.i("XR_VIDEO: scheduling post-stop SLAM stabilization pass #%d", recenterCheckTrigger)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        val originListener = Runnable {
            recenterCheckTrigger++
            Timber.i("XR_VIDEO: ActivitySpace origin changed; scheduling stabilization pass #%d", recenterCheckTrigger)
        }
        val spaceChangeListener = Consumer<SpaceChangeEvent> {
            recenterCheckTrigger++
            Timber.i("XR_VIDEO: Scene space changed; scheduling stabilization pass #%d", recenterCheckTrigger)
        }
        runCatching { session.scene.activitySpace.addOriginChangedListener(originListener) }
            .onFailure { Timber.d(it, "XR_VIDEO: unable to register ActivitySpace origin listener") }
        runCatching { session.scene.setSpaceChangedListener(spaceChangeListener) }
            .onFailure { Timber.d(it, "XR_VIDEO: unable to register Scene space change listener") }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { session.scene.activitySpace.removeOriginChangedListener(originListener) }
            runCatching { session.scene.clearSpaceChangedListener() }
        }
    }

    LaunchedEffect(recenterCheckTrigger, enabled) {
        if (!enabled || recenterCheckTrigger <= 0) return@LaunchedEffect
        for (stepDelayMs in SLAM_STABILIZATION_STEP_DELAYS_MS) {
            if (stepDelayMs > 0L) delay(stepDelayMs)
            if (!currentEnabled || currentMoveInProgress) continue
            val headPose = computeHeadCenteredPose(session) ?: continue
            currentOnStabilizationTick(headPose)
        }
    }
}

internal fun projectPoseFromOrigin(sourcePose: Pose, depthScale: Float): Pose {
    val translation = sourcePose.translation
    return Pose(
        Vector3(
            translation.x * depthScale,
            translation.y * depthScale,
            translation.z * depthScale,
        ),
        sourcePose.rotation,
    )
}

internal fun syncProjectedOverlayRoots(
    videoPose: Pose,
    videoScale: Float,
    uiRoot: Entity,
    subtitleRoot: Entity,
    depthScale: Float,
) {
    val projectedPose = projectPoseFromOrigin(videoPose, depthScale)
    runCatching {
        uiRoot.setScale(videoScale)
        uiRoot.setPose(projectedPose)
    }
    runCatching {
        subtitleRoot.setScale(videoScale)
        subtitleRoot.setPose(projectedPose)
    }
}

internal fun constrainPoseToDepth(
    sourcePose: Pose,
    targetDepth: Float,
    fallbackPose: Pose,
): Pose {
    val translation = sourcePose.translation
    val distance =
        sqrt(
            translation.x * translation.x +
                translation.y * translation.y +
                translation.z * translation.z,
        )
    val fallbackTranslation = fallbackPose.translation
    val fallbackDistance =
        sqrt(
            fallbackTranslation.x * fallbackTranslation.x +
                fallbackTranslation.y * fallbackTranslation.y +
                fallbackTranslation.z * fallbackTranslation.z,
        )
    val direction =
        when {
            distance > 0.001f -> Vector3(
                translation.x / distance,
                translation.y / distance,
                translation.z / distance,
            )
            fallbackDistance > 0.001f -> Vector3(
                fallbackTranslation.x / fallbackDistance,
                fallbackTranslation.y / fallbackDistance,
                fallbackTranslation.z / fallbackDistance,
            )
            else -> Vector3(0f, 0f, -1f)
        }
    return Pose(
        Vector3(
            direction.x * targetDepth,
            direction.y * targetDepth,
            direction.z * targetDepth,
        ),
        sourcePose.rotation,
    )
}

internal fun safeGetEntityPose(entity: Entity): Pose? {
    return runCatching { entity.getPose(Space.PARENT) }
        .onFailure { Timber.d("Skipping disposed XR player entity pose save") }
        .getOrNull()
}
