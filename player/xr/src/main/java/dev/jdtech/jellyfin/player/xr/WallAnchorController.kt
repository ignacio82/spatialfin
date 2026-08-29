package dev.jdtech.jellyfin.player.xr

import androidx.xr.arcore.Anchor
import androidx.xr.arcore.AnchorCreateSuccess
import androidx.xr.arcore.Plane
import androidx.xr.arcore.PlaneType
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.AnchorPersistenceMode
import androidx.xr.runtime.Config
import androidx.xr.runtime.PlaneTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.math.FloatSize2d
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.scenecore.AnchorSpace
import androidx.xr.scenecore.PlaneOrientation
import androidx.xr.scenecore.PlaneSemanticType
import androidx.xr.scenecore.scene
import kotlinx.coroutines.delay
import timber.log.Timber
import java.time.Duration
import java.util.UUID

/**
 * Wall-pinning for the flat XR cinema panel.
 *
 * Today the flat player lives as a [androidx.xr.scenecore.SurfaceEntity] parented to
 * `activitySpace`, dragged laterally at a locked depth by a `MovableComponent` and
 * "recentered" only to the head. This helper layers on an *optional* world-locked
 * placement: the movie is parented to an ARCore [AnchorSpace] sitting on a real
 * **wall**, so it stays put across head movement and across sessions (the anchor
 * UUID is persisted). Because children inherit the anchor's pose, the panel becomes
 * coplanar with the wall and inherits the wall's orientation automatically — a side
 * wall therefore faces perpendicular into the room ("orthogonal to the front wall")
 * with no manual rotation math.
 *
 * Scope: flat projection only. Immersive 180/360 surfaces are head-centred and must
 * never be wall-pinned. All entry points are failure-tolerant (alpha SceneCore /
 * ARCore, no plane-tracking on some runtimes) and return null/false rather than throw,
 * so the default free-move path is never disturbed when anchoring is unavailable.
 *
 * See GEMINI.md → "Wall anchoring (pin-to-wall)".
 */
internal object WallAnchor {

    /** Runtime permission required for plane detection + anchor persistence. */
    const val SCENE_UNDERSTANDING_PERMISSION = "android.permission.SCENE_UNDERSTANDING_COARSE"

    /** How long the plane-finder hunts for a wall before giving up. */
    private val SEARCH_TIMEOUT: Duration = Duration.ofSeconds(20)

    /** Poll budget (ms) while waiting for the anchor to leave UNANCHORED. */
    const val ANCHOR_WAIT_MS: Long = 22_000L

    /**
     * Minimum vertical-plane extent (m) accepted as a wall. Must be large enough to
     * reject nearby clutter (a bed, a blanket, a pillow) that Galaxy XR scene
     * understanding may otherwise label as a small vertical plane ~0.4 m away.
     */
    private const val MIN_WALL_SIZE_METERS = 1.0f

    /** Physical width (m) the movie panel is scaled to when flat on a wall. */
    private const val PANEL_TARGET_WIDTH_METERS = 2.6f

    /** Tiny standoff (m) toward the viewer so the panel doesn't clip into the wall. */
    const val PANEL_STANDOFF_METERS = 0.05f

    /** How far in front of the movie (toward the viewer) the controls/subtitles float. */
    const val OVERLAY_FORWARD_METERS = 1.2f

    /**
     * Horizontal unit direction from the wall anchor toward the viewer's head. The panel
     * is oriented to face this so it is always user-facing and upright, regardless of the
     * anchor's own (arbitrary, often tilted) frame. Falls back to activity-space forward
     * (-Z) when the head pose isn't available.
     */
    fun facingTowardViewer(anchorPos: Vector3, headPos: Vector3?): Vector3 {
        if (headPos == null) return Vector3(0f, 0f, -1f)
        val dx = headPos.x - anchorPos.x
        val dz = headPos.z - anchorPos.z
        val len = kotlin.math.sqrt(dx * dx + dz * dz)
        if (len < 1e-3f) return Vector3(0f, 0f, -1f)
        return Vector3(dx / len, 0f, dz / len)
    }

    /**
     * Build a world-space rotation whose local **+Z** (a SceneCore panel's content
     * normal) points along [forward] and whose local **+Y** is world up — i.e. an
     * upright, user-facing panel. Constructed via an orthonormal basis → quaternion
     * (Shepperd) so there is no dependence on the anchor's orientation or on any
     * degree/radian convention.
     */
    fun lookRotation(forward: Vector3): Quaternion {
        val fl = kotlin.math.sqrt(forward.x * forward.x + forward.y * forward.y + forward.z * forward.z)
        if (fl < 1e-4f) return Quaternion.Identity
        val fx = forward.x / fl; val fy = forward.y / fl; val fz = forward.z / fl
        // right = worldUp(0,1,0) × forward
        var rx = 1f * fz - 0f * fy
        var ry = 0f * fx - 0f * fz
        var rz = 0f * fy - 1f * fx
        val rl = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        if (rl < 1e-4f) return Quaternion.Identity
        rx /= rl; ry /= rl; rz /= rl
        // up = forward × right
        val ux = fy * rz - fz * ry
        val uy = fz * rx - fx * rz
        val uz = fx * ry - fy * rx
        // Rotation matrix columns: X=right, Y=up, Z=forward.
        val m00 = rx; val m01 = ux; val m02 = fx
        val m10 = ry; val m11 = uy; val m12 = fy
        val m20 = rz; val m21 = uz; val m22 = fz
        val trace = m00 + m11 + m22
        return when {
            trace > 0f -> {
                val s = kotlin.math.sqrt(trace + 1f) * 2f
                Quaternion((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            }
            m00 > m11 && m00 > m22 -> {
                val s = kotlin.math.sqrt(1f + m00 - m11 - m22) * 2f
                Quaternion(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            }
            m11 > m22 -> {
                val s = kotlin.math.sqrt(1f + m11 - m00 - m22) * 2f
                Quaternion((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            }
            else -> {
                val s = kotlin.math.sqrt(1f + m22 - m00 - m11) * 2f
                Quaternion((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }
        }
    }

    /** Log the anchor's activity-space pose so the wall-normal convention can be verified on-device. */
    fun logAnchorPose(entity: AnchorSpace) {
        runCatching {
            val pose = entity.getPose(androidx.xr.scenecore.Space.ACTIVITY)
            val euler = pose.rotation.eulerAngles
            Timber.i(
                "WALL_ANCHOR: anchor activitypose t=(%.2f,%.2f,%.2f) q=(%.3f,%.3f,%.3f,%.3f) euler=(%.1f,%.1f,%.1f)",
                pose.translation.x, pose.translation.y, pose.translation.z,
                pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w,
                euler.x, euler.y, euler.z,
            )
        }
    }

    /** Scale that maps the [baseWidthMeters]-wide base panel to [PANEL_TARGET_WIDTH_METERS]. */
    fun panelScale(baseWidthMeters: Float): Float =
        if (baseWidthMeters <= 0f) 1f else (PANEL_TARGET_WIDTH_METERS / baseWidthMeters).coerceIn(0.05f, 5f)

    /**
     * Turn on plane tracking + local anchor persistence without clobbering the session's
     * existing (hand-tracking) config. Idempotent. Returns true if the session is
     * configured for wall anchoring afterwards.
     */
    fun enableSceneUnderstanding(session: Session): Boolean = runCatching {
        val current = session.config
        if (current.planeTracking == PlaneTrackingMode.HORIZONTAL_AND_VERTICAL &&
            current.anchorPersistence == AnchorPersistenceMode.LOCAL
        ) {
            return true
        }
        val updated = Config.Builder(current)
            .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)
            .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
            .build()
        (session.configure(updated) is SessionConfigureSuccess).also {
            Timber.i("WALL_ANCHOR: scene understanding configure success=%b", it)
        }
    }.getOrElse {
        Timber.w(it, "WALL_ANCHOR: failed to enable scene understanding")
        false
    }

    /**
     * Reattach a previously-persisted wall anchor by UUID, or null if there is none /
     * it can't be loaded yet. Lets a re-launch drop the movie back onto the same wall.
     */
    fun loadPersisted(session: Session, uuid: String?): AnchorSpace? {
        val parsed = uuid?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        return runCatching {
            when (val result = Anchor.load(session, parsed)) {
                is AnchorCreateSuccess -> AnchorSpace.create(session, result.anchor)
                else -> {
                    Timber.i("WALL_ANCHOR: persisted anchor not loadable yet (%s)", result.javaClass.simpleName)
                    null
                }
            }
        }.getOrElse {
            Timber.w(it, "WALL_ANCHOR: load persisted anchor failed")
            null
        }
    }

    /**
     * Kick off an asynchronous search for a vertical wall plane and return the
     * (initially UNANCHORED) [AnchorSpace]. Poll [awaitAnchored] to learn the outcome.
     *
     * NOTE: this lets the runtime choose *any* matching wall (it picked one behind the
     * viewer on Galaxy XR). Prefer [findWallInFront], which selects the wall the user is
     * actually looking at; this remains only as a fallback.
     */
    fun findWall(session: Session): AnchorSpace? = runCatching {
        AnchorSpace.create(
            session,
            FloatSize2d(MIN_WALL_SIZE_METERS, MIN_WALL_SIZE_METERS),
            setOf(PlaneOrientation.VERTICAL),
            setOf(PlaneSemanticType.WALL),
            SEARCH_TIMEOUT,
        )
    }.getOrElse {
        Timber.w(it, "WALL_ANCHOR: wall plane-find failed to start")
        null
    }

    /**
     * Find the vertical wall plane the viewer is **looking at** and anchor to it.
     *
     * SceneCore's plane-find ([findWall]) returns an arbitrary wall — on Galaxy XR it
     * chose one behind the viewer. Instead we enumerate tracked vertical planes from
     * [Plane.subscribe], transform each plane centre into activity space, and pick the
     * one whose direction from the head best matches the head's forward vector (largest
     * dot product), then create an anchor on it. Polls until a front-facing wall is
     * found or [timeoutMs] elapses (planes take a moment to populate after plane
     * tracking is enabled). Returns null if no wall is in front.
     */
    suspend fun findWallInFront(session: Session, timeoutMs: Long): AnchorSpace? {
        val planesFlow = runCatching { Plane.subscribe(session) }.getOrNull() ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val head = computeHeadCenteredPose(session)
            if (head != null) {
                val hp = head.translation
                val fwd = head.rotation * Vector3(0f, 0f, -1f)
                var best: Plane? = null
                var bestScore = 0.2f // require the wall to be roughly in front (cos angle)
                for (plane in runCatching { planesFlow.value }.getOrDefault(emptyList())) {
                    if (plane.type != PlaneType.VERTICAL) continue
                    val state = runCatching { plane.state.value }.getOrNull() ?: continue
                    if (state.trackingState != TrackingState.TRACKING) continue
                    if (state.extents.width < MIN_WALL_SIZE_METERS &&
                        state.extents.height < MIN_WALL_SIZE_METERS
                    ) continue
                    val centerAct = runCatching {
                        session.scene.perceptionSpace.transformPoseTo(state.centerPose, session.scene.activitySpace)
                    }.getOrNull() ?: continue
                    val dx = centerAct.translation.x - hp.x
                    val dy = centerAct.translation.y - hp.y
                    val dz = centerAct.translation.z - hp.z
                    val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                    if (len < 1e-3f) continue
                    val score = (dx / len) * fwd.x + (dy / len) * fwd.y + (dz / len) * fwd.z
                    if (score > bestScore) {
                        bestScore = score
                        best = plane
                    }
                }
                if (best != null) {
                    val anchored = runCatching {
                        val state = best.state.value
                        when (val result = best.createAnchor(state.centerPose)) {
                            is AnchorCreateSuccess -> AnchorSpace.create(session, result.anchor)
                            else -> {
                                Timber.i("WALL_ANCHOR: createAnchor on front wall failed (%s)", result.javaClass.simpleName)
                                null
                            }
                        }
                    }.getOrElse { Timber.w(it, "WALL_ANCHOR: front-wall anchor failed"); null }
                    if (anchored != null) {
                        Timber.i("WALL_ANCHOR: anchored to front wall (score=%.2f)", bestScore)
                        return anchored
                    }
                }
            }
            delay(300L)
        }
        Timber.i("WALL_ANCHOR: no wall found in front within timeout")
        return null
    }

    /**
     * Suspend until [entity] leaves [AnchorSpace.State.UNANCHORED] or [timeoutMs]
     * elapses, returning the final state (TIMED_OUT on a timeout we can't read).
     */
    suspend fun awaitAnchored(entity: AnchorSpace, timeoutMs: Long): AnchorSpace.State {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = runCatching { entity.state }.getOrNull()
            if (state != null && state != AnchorSpace.State.UNANCHORED) return state
            delay(120L)
        }
        return runCatching { entity.state }.getOrNull() ?: AnchorSpace.State.TIMED_OUT
    }

    /** Persist [entity]'s anchor and return its UUID string, or null on failure. */
    suspend fun persist(entity: AnchorSpace): String? = runCatching {
        entity.anchor?.persist()?.toString()
    }.getOrElse {
        Timber.w(it, "WALL_ANCHOR: persist failed")
        null
    }

    /** Best-effort removal of a persisted anchor (e.g. when the user permanently unpins). */
    fun unpersist(session: Session, uuid: String?) {
        val parsed = uuid?.takeIf { it.isNotBlank() }
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        runCatching { Anchor.unpersist(session, parsed) }
            .onFailure { Timber.w(it, "WALL_ANCHOR: unpersist failed") }
    }
}
