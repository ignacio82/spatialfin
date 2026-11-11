# Jetpack XR Spatial Placement, Orientation, and Headset Lifecycle Recovery

*   **Author:** ignacio
*   **Status:** Approved
*   **Date:** 2026-11-10

--------------------------------------------------------------------------------

## Overview

SpatialFin provides an Android XR video player (`XrPlayerActivity` for library playback and `XrFCastInboundPlayerActivity` for theater-only FCast network casting sessions) that runs in **Full Space** (Android XR's immersive mode where the application owns the 3D scene) using Jetpack XR SceneCore (`androidx.xr.scenecore`) and Jetpack Compose for XR (`androidx.xr.compose`). Today, the XR video player positions its cinema surface, subtitle plane, and control bar relative to the initial startup origin of the scene (`activitySpace`) and locks their rotation to the initial forward axis (`Quaternion.Identity`).

When a viewer takes a headset such as the Samsung Galaxy XR off and puts it back on, turns their chair, or drags the video screen to the side, the video panel either remains behind the viewer or sits at an oblique angle on the side. Because the player controls are projected along the same origin vector as the video surface, the controls also end up out of reach or behind the viewer, forcing the user to close and reopen SpatialFin to watch their media. This design replaces origin-locked, fixed-rotation placement with viewer-centric orientation, head-relative reset and recenter actions, and automatic lifecycle recovery when the headset is removed and put back on or when the system origin shifts.

## The Problem

Four defects in the current Jetpack XR implementation combine to strand the video panel behind the user or at a skewed angle:

1.  **Fixed Identity Rotation During Placement and Dragging**: Both the main XR player (`SpatialPlayerScreen.kt`) and the FCast receiver player (`XrFCastInboundPlayerScreen.kt`) set `Quaternion.Identity` on the video surface, subtitle root, and UI root during launch, depth changes, and custom move gestures. In `activitySpace`, `Quaternion.Identity` forces the panel surface normal to point along the initial `+Z` axis regardless of where the panel is moved. Moving the panel 45 degrees to the left or right leaves it parallel to the original front wall instead of rotating it to face the viewer, creating perspective skew.
2.  **Origin-Relative Projection and Depth Extraction Instead of Head-Relative Geometry**: When the user drags the screen or changes its distance, the player measures distance (`extractVideoDepth`) and projects rays and overlay offsets (`uiRoot` and `subtitleRoot`) from the `activitySpace` origin `(0, 0, 0)` rather than from the viewer's head position. If the user shifts position in the room or puts the headset back on at a different spot, the video, subtitles, and controls no longer align along the viewer's line of sight, and starting or ending a drag jumps the panel to its distance from `(0, 0, 0)`.
3.  **Unconfigured Device Tracking and Stale Reset Target**: `computeHeadCenteredPose` queries `ArDevice.getInstance(session).state.value.devicePose`, which requires `DeviceTrackingMode.SPATIAL` in the XR `Session` configuration (`androidx.xr.runtime:1.0.0-rc01`). Neither `SpatialPlayerScreen` nor `XrFCastInboundPlayerScreen` enables `DeviceTrackingMode.SPATIAL` on session startup. In addition, the screen reset action (`resetScreenPlacementToDefault`) places the screen at `(0, 0, -VIDEO_DEPTH_METERS)` with `Quaternion.Identity` in `activitySpace` instead of reading the viewer's current head pose and forward gaze direction. Even if the viewer turns around to press the reset button, the panel resets to the old startup direction behind them.
4.  **No Headset Doff/Don Lifecycle or Out-of-FOV Recovery**: Taking the headset off triggers `onPause` and `onStop`, and putting it back on triggers `onStart` and `onResume`. While the user removes the headset, hand-tracking or controller rays can accidentally grab the movable video surface and fling it to an arbitrary direction before the headset sleeps. When the user puts the headset back on facing a new direction—or when the XR runtime relocalizes its tracking map—SpatialFin does not subscribe to `ActivitySpace.addOriginChangedListener` or `Scene.setSpaceChangedListener`, and it does not validate whether the panel is inside the viewer's field of view upon `onResume`. Meanwhile, a legacy 8-second startup guard loop (`startupPoseGuardActive`) repeatedly re-applies the initial launch pose after startup.

## Goals

*   **Viewer-Facing Panel Orientation**: Orient flat video, subtitle, and control panels so their surface normal aligns with the viewer's head position at the moment of placement while remaining upright relative to world gravity (zero roll) whenever placed, dragged, or resized.
*   **Head-Relative Recenter and Reset**: Make the player reset/recenter action place the video panel, subtitle plane, and control bar directly in front of the viewer's current head position and gaze direction at the active viewing distance.
*   **Automatic Headset Resume Recovery**: When `XrPlayerActivity` or `XrFCastInboundPlayerActivity` launches, resumes after headset removal (`onStop` -> `onResume`), or receives an `ActivitySpace` origin or spatial-mode update, automatically recenter the unpinned cinema panel into the viewer's forward field of view across a 3-second SLAM stabilization window if the panel is behind the viewer or outside a comfortable viewing cone (greater than 35 degrees off-axis horizontally or skewed).
*   **Doffing Move Guard**: Cancel and roll back any in-progress `MovableComponent` drag gesture when the activity leaves the `RESUMED` lifecycle state (`ON_PAUSE` / `ON_STOP`) so removing the headset cannot fling the panel to a random coordinate.
*   **Consistent Jetpack XR Session Configuration**: Configure `DeviceTrackingMode.SPATIAL` alongside existing `HandTrackingMode` and `PlaneTrackingMode` settings on the player `Session` without clobbering active session flags.

## Non-Goals

*   **Redesigning TV, Phone, Tablet, or Browsing Shell Layouts**: Non-XR form factors (`:shell:tv`, `:shell:beam`, `:player:tv`, `:player:beam`) do not use `androidx.xr.scenecore`. Similarly, `UnifiedMainActivity` runs in a separate OS process from `:xrplayer` and renders a system-movable Compose XR `SpatialPanel` rather than a SceneCore `SurfaceEntity`, so it is outside the scope of this design.
*   **Continuous Head-Locked Follow Mode**: The cinema panel must not continuously chase head movements during normal playback. Once placed, dragged, or recentered in front of the viewer, the video surface, subtitle plane, and control bar remain world-stationary in `activitySpace` until the next discrete placement, drag, or recenter event.
*   **Replacing ARCore Wall Anchoring**: When a user pins the flat panel to a physical wall via `WallAnchorController`, the wall anchor continues to own the video and subtitle poses until unpinned.

## Open Questions

None. All architectural questions have been resolved:
*   **Orientation and Pitch Policy**: Use yaw + pitch viewer-facing orientation (`viewerFacingRotation` with zero roll, pitch clamped to `±75°`) during manual drags and explicit user-triggered recenters; use level-horizon (yaw-only, `pitch = 0°`) during automatic launch and `onResume` recenters unless the viewer's head pitch exceeds `45°` upward (reclined/ceiling posture).
*   **Out-of-FOV and Headset Recovery Policy**: Automatically recenter unpinned panels on launch, `onStop -> onResume`, `ActivitySpace.addOriginChangedListener`, and `Scene.setSpaceChangedListener` across a 3-second SLAM stabilization window when the panel is outside the viewer's comfortable FOV cone or skewed, and add a dedicated Recenter button to `StageControlsOrbiter` (the floating spatial pill below the transport bar that houses screen distance, size, passthrough, and lock controls) without spawning floating mid-playback gaze pills.

## Critical User Journeys (CUJs)

### CUJ 1: Taking the Galaxy XR Off and Putting It Back On During Playback

*   **Given**: A user is watching a movie in `XrPlayerActivity` (or `XrFCastInboundPlayerActivity`) in Full Space on a Samsung Galaxy XR.
*   **When**: The user takes the headset off (triggering `onPause`/`onStop`), sets it down, puts it back on facing 90 to 180 degrees away from the initial startup direction (triggering `onStart`/`onResume`), and looks forward.
*   **Then**: Any accidental drag triggered while lifting the headset off is rolled back on pause; upon resume and SLAM relocalization across the 3-second stabilization window, SpatialFin detects that the unpinned video panel is outside the viewer's forward viewing cone and repositions the video, subtitle, and control panels in front of the viewer's current horizontal gaze at their saved distance and scale, with the surface normal aligned toward the viewer's head position and zero roll.

### CUJ 2: Dragging the Cinema Panel to the Side or Ceiling

*   **Given**: A user is watching a flat video in `XrPlayerActivity` and grabs the video surface to move it 40 degrees to the left (or upward while reclined on a couch).
*   **When**: The user drags and releases the video panel at the new position.
*   **Then**: As the panel moves, the video panel, subtitle overlay, and control panel rotate in yaw and pitch around the viewer's head position so the screen normal points toward the viewer with zero roll, rather than staying parallel to the original startup wall at a skewed angle. Once released, the panel and overlays remain stationary in world space at that pose.

### CUJ 3: Manual Recenter from Any Viewing Angle

*   **Given**: A user turns their chair to a new angle in the room and wants the screen centered in front of their new seating direction.
*   **When**: The user triggers the Recenter Screen action via the dedicated Recenter icon button in `StageControlsOrbiter` or a voice command ("recenter screen" / "reset screen", routed through `PlayerSessionController.onResetScreenPlacement`, the session action callback for resetting screen placement).
*   **Then**: The video panel, subtitle plane, and control panel snap to the viewer's current 3D gaze vector (yaw and pitch) at the active viewing depth, with their surface normal aligned toward the viewer.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

### System Data Flow

```text
+-------------------------------------------------------------------------+
|                   Jetpack XR Runtime & SceneCore                        |
|  - ArDevice.getInstance(session).state.value.devicePose (Head Pose)     |
|  - session.scene.perceptionSpace -> transformPoseTo(activitySpace)      |
|  - session.scene.activitySpace.addOriginChangedListener               |
|  - session.scene.setSpaceChangedListener                          |
+------------------------------------+------------------------------------+
                                     |
                                     v
+-------------------------------------------------------------------------+
|            PlayerGeometry & Pure Pose Math (:player:xr)                 |
|  1. ensureHeadTrackingConfigured(session) -> DeviceTrackingMode         |
|  2. computeHeadCenteredPose(session) -> Pose in activitySpace           |
|  3. extractVideoDepth(pose, currentDepth, viewerPos) -> Float           |
|  4. viewerFacingRotation(panelPos, viewerPos, allowPitch) -> Quaternion |
|  5. computeViewerCenteredPanelPose(headPose, depth, manualPitch)        |
|  6. computeImmersiveViewerPoses(headPose, uiDepth) -> Pair<Pose, Pose>  |
|  7. computeDragPose(rayOrigin, rayDir, headPos, grabDepth)              |
|  8. projectOverlayPoseTowardViewer(videoPose, headPos, uiDepth)         |
|  9. isPanelOutsideComfortableFov(headPose, panelPose, maxAngleDeg=35f)  |
+------------------------------------+------------------------------------+
                                     |
         +---------------------------+---------------------------+
         |                                                       |
         v                                                       v
+-----------------------------------+   +-----------------------------------+
|  SpatialPlayerRecoveryEffect      |   |   Interactive Placement & Move    |
|  - ON_PAUSE / ON_STOP: cancel     |   |  - Custom MovableComponent drag   |
|    active drag, restore snapshot  |   |    rotates panel + overlays to    |
|  - Launch / ON_RESUME / origin /  |   |    face viewer (yaw + pitch)      |
|    spatialMode change:            |   |  - Stationary 100ms pose mirror   |
|    poll 5 passes over 3000ms;     |   |    uses lastAnchorHeadPos         |
|    if outside FOV cone, recenter  |   |  - StageControlsOrbiter Recenter  |
|    to viewer gaze                 |   |    button & voice command         |
+-----------------------------------+   +-----------------------------------+
```

### 1. Session Configuration (`DeviceTrackingMode.SPATIAL`)

`computeHeadCenteredPose(session)` in `PlayerGeometry.kt` reads `ArDevice.getInstance(session).state.value.devicePose` and transforms it from `perceptionSpace` into `activitySpace`. On Android XR (`androidx.xr.runtime:1.0.0-rc01`), `ArDevice` updates `devicePose` only when `DeviceTrackingMode.SPATIAL` is configured on the session.

*   Add `internal fun ensureHeadTrackingConfigured(session: Session): Boolean` in `PlayerGeometry.kt`. It checks `session.config.deviceTracking == DeviceTrackingMode.SPATIAL`; if not already enabled, it invokes `session.configure(Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build())` inside `runCatching` and checks `is SessionConfigureSuccess`.
*   Because `Config.Builder(session.config)` copies all existing configuration fields, enabling `DeviceTrackingMode.SPATIAL` preserves `HandTrackingMode.BOTH`, `PlaneTrackingMode.HORIZONTAL_AND_VERTICAL`, and `AnchorPersistenceMode.LOCAL`.
*   Invoke `ensureHeadTrackingConfigured(session)` in `SpatialPlayerScreen` and `XrFCastInboundPlayerScreen`.

### 2. Viewer-Centric Pose and Orientation Math (`PlayerGeometry.kt`)

Replace hardcoded `Quaternion.Identity` rotations and `(0, 0, 0)` origin assumptions in `SpatialPlayerScreen.kt` and `XrFCastInboundPlayerScreen.kt` with pure, JVM-testable geometry functions in `PlayerGeometry.kt`, and share the orthonormal-basis quaternion builder with `WallAnchor.lookRotation`:

*   **Head-Relative Depth Extraction (`extractVideoDepth`)**: Measures Euclidean distance from `viewerPos ?: Vector3.Zero` rather than `(0, 0, 0)` so starting or finishing a drag when the user is away from the room origin preserves the true head-to-panel distance.
*   **Viewer-Facing Orthonormal Basis (`viewerFacingRotation`)**: Orients the panel's front normal (`+Z`) toward `viewerPos` with zero roll relative to world up `(0, 1, 0)`. Supports horizontal yaw-only orientation (`allowPitch = false`) and pitch-enabled orientation (`allowPitch = true`) clamped to `±75°` so zenith/nadir angles never become colinear with world up.
*   **Viewer-Centered Panel & Immersive Placement (`computeViewerCenteredPanelPose`, `computeImmersiveViewerPoses`)**: Places flat panels at `depthMeters` in front of `headPose` using horizontal yaw (`pitch = 0°`) for automatic launch/resume recenters (switching to 3D pitch when `manualPitch == true` or when upward pitch exceeds `45°` for ceiling viewing). For 180°/360° immersive video, places the sphere at `headPose.translation` with un-inverted horizontal yaw rotation and places the control/subtitle overlays at `UI_DEPTH_METERS` along the same horizontal forward axis.
*   **Stationary Line-of-Sight Overlay Projection (`projectOverlayPoseTowardViewer`)**: Projects `uiRoot` and `subtitleRoot` along the vector from `lastAnchorHeadPos` (captured at the moment of placement, drag, or recenter) to `videoPose.translation`. The background 100ms pose-mirror loop (which periodically reapplies the target pose and scale to SceneCore entities to guard against async runtime resets) mirrors `lastReportedMovePose` and `lastAnchorHeadPos` without querying live head tracking on every tick, keeping the panel and overlays world-stationary during playback and skipping `wallPinActive || immersiveProjection`.
*   **Idempotent FOV Comfort Check (`isPanelOutsideComfortableFov`)**: Evaluates whether an unpinned flat panel is behind the viewer (`localZ >= -0.5f`), outside the `35°` horizontal azimuth cone, vertically displaced by more than `35°` from the target placement vector that `computeViewerCenteredPanelPose(headPose, dist, manualPitch = false)` produces, or skewed by more than `35°` in surface-normal angle. Measuring vertical displacement relative to the auto-recenter target vector guarantees that a freshly auto-recentered panel always satisfies `isPanelOutsideComfortableFov == false` across all head pitches.

### 3. Shared Headset Doff/Don Lifecycle Guard and 3-Second Stabilization (`SpatialPlayerRecoveryEffect`)

To avoid duplicating lifecycle and SceneCore listener state machines across `SpatialPlayerScreen.kt` and `XrFCastInboundPlayerScreen.kt`, extract a shared `@Composable internal fun SpatialPlayerRecoveryEffect(...)` in `PlayerGeometry.kt`:

1.  **Doffing Drag Cancellation (`ON_PAUSE` / `ON_STOP`)**:
    *   `onMoveStart` records `preMoveSnapshotPose` and `preMoveSnapshotHeadPos` and preserves existing viewer-facing rotation instead of snapping to `Quaternion.Identity`.
    *   `onMoveUpdate` and `onMoveEnd` guard against late main-thread callbacks with `if (!moveInProgress) return`.
    *   When `Lifecycle.Event.ON_PAUSE` or `Lifecycle.Event.ON_STOP` fires while `moveInProgress == true`, `SpatialPlayerRecoveryEffect` sets `moveInProgress = false` and invokes `onCancelMove()`, which restores `preMoveSnapshotPose` and `preMoveSnapshotHeadPos` onto the entities without detaching `movableComponent` (preserving drag capability upon `ON_RESUME`).
2.  **Unified 3-Second Startup and Resume Stabilization Window**:
    *   Replaces the legacy 8-second `startupPoseGuardActive` loop across both player screens.
    *   Seeds `recenterCheckTrigger = 1` on initial composition and increments `recenterCheckTrigger` on `ON_STOP -> ON_RESUME`, `session.scene.activitySpace.addOriginChangedListener`, and `session.scene.setSpaceChangedListener` (cleaning up both listeners in `DisposableEffect.onDispose` via `removeOriginChangedListener` and `clearSpaceChangedListener()`).
    *   Samples across 5 inter-sample step delays `listOf(0L, 250L, 500L, 750L, 1500L)` (cumulative timestamps `0ms, 250ms, 750ms, 1500ms, 3000ms`). It does **not** exit early at `0ms`, because `DeviceTrackingMode.SPATIAL` returns the cached pre-doff head pose at `0ms` before inside-out SLAM relocalizes the room at `250ms–3000ms`.

### 4. Stage Orbiter Recenter Control and Immersive Overlay Alignment

*   **`StageControlsOrbiter` (`PlayerSecondaryControls.kt`)**:
    *   Add an `onRecenter: (() -> Unit)? = null` parameter and render an `OrbiterIconButton` reusing `CoreR.drawable.ic_rotate_ccw` (already used for `"Recenter view"` in `ProjectionDialogContent`) with `contentDescription = "Recenter screen"`.
    *   Separate **Recenter** (`onRecenter`, which snaps the screen to the viewer's 3D gaze while keeping user `videoPanelScale` and `videoDepth`) from **Reset Size** (`onResetSize`, which resets scale and depth to defaults and recenters).
    *   Wire both callbacks in `SpatialPlayerScreen.kt` and `XrFCastInboundPlayerScreen.kt`.

--------------------------------------------------------------------------------

## Alternatives Considered

### Alternative A: Full 3D Gaze (Yaw and Pitch) on All Automatic Launch and Resume Recenters

*   **Pros**: Uses a single code path for all placement events (launch, `onResume`, manual recenter, and drag) and immediately tilts the panel to match any head posture on wakeup.
*   **Cons**: Users routinely tilt their head downward (`-15°` to `-25°` pitch) while pulling a headset over their eyes and adjusting the strap.
*   **Reason for Rejection**: Rejected by author: sampling full 3D pitch during automatic launch or `onResume` recovery would spawn the cinema panel angled downward toward the floor whenever a seated user looks down while putting the headset on. Using level-horizon (`pitch = 0°`) for automatic recenters (with a `>45°` upward pitch exception for ceiling viewing) avoids floor-tilted spawns while keeping full yaw + pitch on manual drags and manual recenters.

### Alternative B: Strictly Vertical (Yaw-Only) Orientation for All Placements and Drags

*   **Pros**: Guarantees the screen is always vertical like a physical theater wall and cannot tilt accidentally.
*   **Cons**: Fails when watching while reclined on a couch or lying in bed; dragging the screen upward toward the ceiling leaves the panel vertical rather than angled toward the viewer's face.
*   **Reason for Rejection**: Rejected by author: overhead and reclined viewing is a primary use case for headset media players, requiring pitch rotation during manual drags and manual recenters.

### Alternative C: Floating Gaze-Anchored "Recenter Screen" Pill on Sustained `OUTSIDE_FIELD_OF_VIEW`

*   **Pros**: Gives a user who swivels 180 degrees in a chair without removing the headset a visible button in front of their eyes without turning back toward the old screen position.
*   **Cons**: Requires an extra head-tracked `SpatialPanel` in the Subspace composition and intrudes into the user's vision whenever they intentionally look away from the movie for more than 2.5 seconds (such as talking to someone in the room with passthrough active).
*   **Reason for Rejection**: Rejected by author: automatic recentering across a 3-second SLAM stabilization window on `onResume` / origin changes combined with a dedicated Recenter icon button on `StageControlsOrbiter` and voice recenter commands solves the headset-removal bug without intrusive mid-playback popups.

### Alternative D: Hands-Free Auto-Recenter Whenever `OUTSIDE_FIELD_OF_VIEW` Persists for 3 Seconds

*   **Pros**: Requires zero button presses even if the user changes seats while wearing the headset.
*   **Cons**: Turning one's head for 3 seconds to look at the room or reach for a table causes the cinema screen to jump unprompted into the viewer's new gaze direction.
*   **Reason for Rejection**: Rejected by author: unprompted screen movement during active playback when the headset was never removed breaks world-locked spatial stability.

### Alternative E: Replacing `MovableComponent.createCustomMovable` with `MovableComponent.createSystemMovable`

*   **Pros**: Delegates drag translation and rotation math to the SceneCore runtime.
*   **Cons**: `createSystemMovable` snaps a 6-meter theater surface to hand/ray interaction distance (~1–2 meters) on grab start ("jump to nose") and introduces arbitrary roll rotation if the user twists their wrist during a drag.
*   **Reason for Rejection**: Rejected by author: keeping `createCustomMovable` with `computeDragPose` preserves the user's configured viewing depth (`grabDepth`) and enforces zero-roll viewer-facing orientation.

--------------------------------------------------------------------------------

## Risks & Mitigations

*   **Stale `DeviceTrackingMode.SPATIAL` Pose at `t = 0ms` on Resume**: When the headset wakes up on `ON_RESUME`, `ArDevice.state.value.devicePose` initially holds the pre-doff pose before inside-out SLAM relocalizes (`250ms–3000ms`). **Mitigation**: `SpatialPlayerRecoveryEffect` never exits early at `0ms`; it evaluates all 5 checkpoints across the 3-second window (`0L, 250L, 500L, 750L, 1500L`) so late SLAM relocalization snaps are caught and recentered.
*   **Zenith/Nadir Gimbal Lock During Overhead Placement**: Dragging or recentering a panel directly overhead (`90°` elevation) makes the panel-to-viewer vector colinear with world up `(0, 1, 0)`, making `worldUp × forward` degenerate. **Mitigation**: `viewerFacingRotation` clamps pitch to `±75°` (`MAX_PITCH_DEGREES`) and substitutes a fallback horizontal forward axis `(0, 0, 1)` when horizontal distance `< 1e-4f`.
*   **Late Main-Executor `onMoveUpdate`/`onMoveEnd` Callbacks After `ON_PAUSE`**: SceneCore dispatches `EntityMoveListener` callbacks asynchronously on the main executor, which can arrive after `ON_PAUSE` rollback. **Mitigation**: Both `onMoveUpdate` and `onMoveEnd` begin with `if (!moveInProgress) return`, and `ON_PAUSE` sets `moveInProgress = false` before restoring `preMoveSnapshotPose`.
*   **Coexistence with ARCore Wall Pinning and 180°/360° Spheres**: Running flat-panel pose mirroring or FOV checks during wall-pinned or 180°/360° playback would overwrite anchor poses or collapse overlays to `dist = 0m`. **Mitigation**: The 100ms pose-mirror loop and 300ms save loop skip when `wallPinActive || immersiveProjection`, and `projectOverlayPoseTowardViewer` guards `dist < 1e-3f`.
*   **SceneCore Listener Leaks Across Recomposition**: Registering origin or spatial-mode listeners without cleanup leaks references across player compositions. **Mitigation**: `SpatialPlayerRecoveryEffect` registers listeners inside `DisposableEffect(lifecycleOwner, session)` and removes them in `onDispose` via `removeOriginChangedListener` and `clearSpaceChangedListener()` wrapped in `runCatching`.

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Performance & Latency**:
    *   `viewerFacingRotation`, `computeDragPose`, and `projectOverlayPoseTowardViewer` run on every `onMoveUpdate` callback (~60–90 Hz on Galaxy XR). All math uses stack-allocated primitives and immutable `Vector3`/`Quaternion`/`Pose` value objects with zero heap allocations for collections or coroutines per frame.
    *   The 3-second SLAM stabilization coroutine runs 5 discrete checks (`0ms, 250ms, 750ms, 1500ms, 3000ms` cumulative) only on launch, `ON_STOP -> ON_RESUME`, or SceneCore origin/spatial-mode events, and performs zero background polling during steady-state playback.
*   **Rollout & Backward Compatibility**:
    *   Persisted player scale (`xrPlayerPanelScale`) and depth (`xrPlayerPanelDepth`) preferences remain compatible.
    *   All `ArDevice`, `Session.configure`, and `Scene` listener calls are wrapped in `runCatching` so runtimes or emulators without active perception tracking fall back cleanly to `(0, 0, -videoDepth)` with `Quaternion.Identity`.

--------------------------------------------------------------------------------

## Additional Resources

*   `GEMINI.md` — SpatialFin Technical Reference (XR Player Architecture, Wall Anchoring, and SceneCore conventions)
*   `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt`
*   `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerGeometry.kt`
*   `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/WallAnchorController.kt`
*   `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt`

--------------------------------------------------------------------------------

## Detailed Implementation Plan

### Target Files to Create/Modify

#### 1. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerGeometry.kt` (Modify)

*   **Type / Scope**: XR Spatial Geometry, Session Configuration, & Lifecycle Stabilization Helpers
*   **Proposed Changes**:
    *   Add constants:
        *   `internal const val MAX_PITCH_DEGREES = 75f`
        *   `internal const val AUTO_RECENTER_CEILING_PITCH_DEGREES = 45f`
        *   `internal const val COMFORTABLE_FOV_MAX_ANGLE_DEGREES = 35f`
        *   `internal val SLAM_STABILIZATION_STEP_DELAYS_MS = listOf(0L, 250L, 500L, 750L, 1500L)` (cumulative timestamps `0, 250, 750, 1500, 3000ms`).
    *   Add `internal fun ensureHeadTrackingConfigured(session: Session): Boolean`:
        *   Inside `runCatching`, checks if `session.config.deviceTracking == DeviceTrackingMode.SPATIAL`; if so, returns `true`.
        *   Otherwise builds `val updated = Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()` and returns `session.configure(updated) is SessionConfigureSuccess`.
    *   Update `computeHeadCenteredPose(session: Session): Pose?`:
        *   Remove the outdated comment claiming `ArDevice` is unavailable, call `ensureHeadTrackingConfigured(session)`, and transform `ArDevice.getInstance(session).state.value.devicePose` from `session.scene.perceptionSpace` to `session.scene.activitySpace`.
    *   Update `internal fun extractVideoDepth(pose: Pose, currentDepth: Float, viewerPos: Vector3? = null): Float`:
        *   Compute `delta = pose.translation - (viewerPos ?: Vector3.Zero)` and Euclidean distance `sqrt(delta.x * delta.x + delta.y * delta.y + delta.z * delta.z)`, returning `distance.coerceIn(MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS)` when finite and `> 1e-3f`, else `currentDepth.coerceIn(MIN_VIDEO_DEPTH_METERS, MAX_VIDEO_DEPTH_METERS)`.
    *   Add `internal fun viewerFacingRotation(panelPos: Vector3, viewerPos: Vector3, allowPitch: Boolean): Quaternion`:
        *   Computes `toViewer = viewerPos - panelPos`.
        *   Computes horizontal distance `horizDist = sqrt(toViewer.x * toViewer.x + toViewer.z * toViewer.z)`.
        *   If `horizDist < 1e-4f`: when `!allowPitch || abs(toViewer.y) < 1e-4f`, returns `Quaternion.Identity`; when `allowPitch && abs(toViewer.y) >= 1e-4f` (exact `90°` zenith/nadir), uses fallback horizontal direction `(0f, 0f, 1f)` with `y = copySign(tan(Math.toRadians(MAX_PITCH_DEGREES.toDouble())).toFloat(), toViewer.y)`.
        *   When `horizDist >= 1e-4f`: if `!allowPitch`, sets `clampedY = 0f`; if `allowPitch`, clamps `toViewer.y` to `[-horizDist * tan(75°), +horizDist * tan(75°)]`.
        *   Constructs orthonormal basis with `forward = normalize(Vector3(hx, clampedY, hz))` (local `+Z`), `right = normalize(worldUp × forward)` (local `+X`, zero roll), `up = forward × right` (local `+Y`), and converts to `Quaternion` via Shepperd's method.
    *   Add `internal fun computeViewerCenteredPanelPose(headPose: Pose?, depthMeters: Float, manualPitch: Boolean): Pose`:
        *   When `headPose == null`, returns `Pose(Vector3(0f, 0f, -depthMeters), Quaternion.Identity)`.
        *   Computes `forward = headPose.rotation * Vector3(0f, 0f, -1f)` and `pitchDeg = Math.toDegrees(asin(forward.y.coerceIn(-1f, 1f)).toDouble()).toFloat()`.
        *   Sets `usePitch = manualPitch || (pitchDeg > AUTO_RECENTER_CEILING_PITCH_DEGREES)`.
        *   If `!usePitch`: projects `forward` onto the `XZ` plane (`horiz = Vector3(forward.x, 0f, forward.z)`, defaulting to `Vector3(0f, 0f, -1f)` if length `< 1e-4f`), computes `panelPos = headPose.translation + normalize(horiz) * depthMeters`, and returns `Pose(panelPos, viewerFacingRotation(panelPos, headPose.translation, allowPitch = false))`.
        *   If `usePitch`: clamps `forward` elevation to `±MAX_PITCH_DEGREES`, computes `panelPos = headPose.translation + clampedForward * depthMeters`, and returns `Pose(panelPos, viewerFacingRotation(panelPos, headPose.translation, allowPitch = true))`.
    *   Add `internal fun computeImmersiveViewerPoses(headPose: Pose?, uiDepthMeters: Float = UI_DEPTH_METERS): Pair<Pose, Pose>`:
        *   Extracts `headPos = headPose?.translation ?: Vector3.Zero` and horizontal forward unit vector `horizForward` from `headPose?.rotation ?: Quaternion.Identity` (defaulting to `Vector3(0f, 0f, -1f)`).
        *   Computes `yawRotation = viewerFacingRotation(panelPos = headPos + horizForward, viewerPos = headPos, allowPitch = false)` (un-inverted, since local `+Z = -horizForward` means local `-Z` points along `+horizForward`).
        *   Returns `Pair(Pose(headPos, yawRotation), Pose(headPos + horizForward * uiDepthMeters, yawRotation))`.
    *   Add `internal fun computeDragPose(rayOrigin: Vector3, rayDirection: Vector3, headPos: Vector3?, grabDepthMeters: Float): Pose`:
        *   Uses `anchorOrigin = headPos ?: rayOrigin`, computes `dir = normalize(rayDirection)` (defaulting to `Vector3(0f, 0f, -1f)` if degenerate), `panelPos = anchorOrigin + dir * grabDepthMeters`, and returns `Pose(panelPos, viewerFacingRotation(panelPos, anchorOrigin, allowPitch = true))`.
    *   Add `internal fun projectOverlayPoseTowardViewer(videoPose: Pose, headPos: Vector3?, uiDepthMeters: Float = UI_DEPTH_METERS): Pose` and `internal fun syncViewerProjectedOverlayRoots(videoPose: Pose, videoScale: Float, headPos: Vector3?, uiRoot: Entity, subtitleRoot: Entity, uiDepthMeters: Float = UI_DEPTH_METERS)`:
        *   Computes `viewerOrigin = headPos ?: Vector3.Zero`, `v = videoPose.translation - viewerOrigin`, and `dist = length(v)`.
        *   If `dist < 1e-3f`, falls back to projecting `uiDepthMeters` along `videoPose.rotation * Vector3(0f, 0f, -1f)` from `viewerOrigin` with `videoPose.rotation`.
        *   Otherwise computes `effectiveUiDepth = min(uiDepthMeters, dist * 0.85f)` and returns `Pose(viewerOrigin + (v / dist) * effectiveUiDepth, videoPose.rotation)`.
    *   Add `internal fun isPanelOutsideComfortableFov(headPose: Pose, panelPose: Pose, maxAngleDeg: Float = COMFORTABLE_FOV_MAX_ANGLE_DEGREES): Boolean`:
        *   Computes `toPanel = panelPose.translation - headPose.translation` and `dist = length(toPanel)`. If `dist < 0.5f`, returns `true`.
        *   Transforms `toPanel` into the viewer's head frame (`localVec = headPose.rotation.inverse * toPanel`); if `localVec.z >= -0.5f` (behind viewer), returns `true`.
        *   Computes horizontal azimuth `abs(atan2(localVec.x, -localVec.z))` in degrees; if `> maxAngleDeg`, returns `true`.
        *   Computes reference auto-recenter pose `expectedPose = computeViewerCenteredPanelPose(headPose, dist, manualPitch = false)` and angle between `normalize(toPanel)` and `normalize(expectedPose.translation - headPose.translation)`; if `> maxAngleDeg`, returns `true` (ensuring idempotency with `computeViewerCenteredPanelPose(..., manualPitch = false)` across all head pitches).
        *   Computes angle between panel front normal (`panelPose.rotation * Vector3(0f, 0f, 1f)`) and `expectedPose.rotation * Vector3(0f, 0f, 1f)` (or `-normalize(toPanel)`); if both exceed `maxAngleDeg`, returns `true`.
    *   Add `@Composable internal fun SpatialPlayerRecoveryEffect(session: Session, lifecycleOwner: LifecycleOwner, enabled: Boolean, moveInProgress: Boolean, onCancelMove: () -> Unit, onStabilizationTick: (Pose) -> Unit)`:
        *   Seeds `var recenterCheckTrigger by remember { mutableIntStateOf(1) }` and tracks `var stoppedOnce by remember { mutableStateOf(false) }`.
        *   Inside `DisposableEffect(lifecycleOwner, session)`:
            *   Attaches a `LifecycleEventObserver`: on `ON_PAUSE` or `ON_STOP`, if `moveInProgress` is `true`, calls `onCancelMove()`, and on `ON_STOP` sets `stoppedOnce = true`; on `ON_RESUME`, if `stoppedOnce` is `true`, sets `stoppedOnce = false` and increments `recenterCheckTrigger`.
            *   Registers `originListener = Runnable { recenterCheckTrigger++ }` via `runCatching { session.scene.activitySpace.addOriginChangedListener(originListener) }` and `spatialModeListener = Consumer<SpatialModeChangeEvent> { recenterCheckTrigger++ }` via `runCatching { session.scene.setSpaceChangedListener(spatialModeListener) }`.
            *   In `onDispose`, removes the lifecycle observer and calls `runCatching { session.scene.activitySpace.removeOriginChangedListener(originListener) }` and `runCatching { session.scene.clearSpaceChangedListener() }`.
        *   Inside `LaunchedEffect(recenterCheckTrigger, enabled)`: when `enabled && recenterCheckTrigger > 0`, iterates `for (stepDelayMs in SLAM_STABILIZATION_STEP_DELAYS_MS)` (`0L, 250L, 500L, 750L, 1500L`), delays `stepDelayMs`, and if `enabled && !moveInProgress`, reads `computeHeadCenteredPose(session)` and invokes `onStabilizationTick(headPose)` on each pass without early exit.

#### 2. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/WallAnchorController.kt` (Modify)

*   **Type / Scope**: ARCore Wall Pinning Helper
*   **Proposed Changes**:
    *   Delegate `WallAnchor.lookRotation(forward: Vector3): Quaternion` to `viewerFacingRotation(panelPos = Vector3.Zero, viewerPos = forward, allowPitch = true)` in `PlayerGeometry.kt` so existing 3D `lookRotation` callers retain pitch while `WallAnchor.facingTowardViewer` (which zeroes `forward.y`) produces yaw-only rotation.

#### 3. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerSecondaryControls.kt` (Modify)

*   **Type / Scope**: XR Player Orbiter UI Component
*   **Proposed Changes**:
    *   Add `onRecenter: (() -> Unit)? = null` parameter to `StageControlsOrbiter`.
    *   When `!isLocked && onRecenter != null`, render an `OrbiterIconButton` using `CoreR.drawable.ic_rotate_ccw` with `contentDescription = "Recenter screen"` immediately after the `+` (`Bigger screen`) button and before the passthrough toggle button.

#### 4. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt` (Modify)

*   **Type / Scope**: Main Immersive XR Player Composable
*   **Proposed Changes**:
    *   Add `val lastAnchorHeadPos = remember { mutableStateOf<Vector3?>(null) }` and `var preMoveSnapshotPose by remember { mutableStateOf<Pose?>(null) }` / `var preMoveSnapshotHeadPos by remember { mutableStateOf<Vector3?>(null) }`.
    *   Call `ensureHeadTrackingConfigured(session)` in `LaunchedEffect(session)` at startup.
    *   Replace `resetScreenPlacementToDefault()`, `applyCurrentLaunchPose()`, and the 8-second `startupPoseGuardActive` loop with `recenterScreenToViewer(manualPitch: Boolean, resetScaleAndDepth: Boolean = false)`:
        *   If `immersiveProjection` is `true`, delegates to `recenterImmersiveSurface()` and returns.
        *   When `resetScaleAndDepth == true`, resets `videoDepth = VIDEO_DEPTH_METERS` and `videoPanelScale = DEFAULT_VIDEO_PANEL_SCALE`.
        *   Reads `headPose = computeHeadCenteredPose(session)` and updates `lastAnchorHeadPos.value = headPose?.translation`.
        *   Computes `targetPose = computeViewerCenteredPanelPose(headPose, videoDepth, manualPitch)` and `overlayPose = projectOverlayPoseTowardViewer(targetPose, lastAnchorHeadPos.value, UI_DEPTH_METERS)`.
        *   Applies `targetPose` and `videoPanelScale` to `videoRootEntity` and `videoEntity`, and `overlayPose` and `videoPanelScale` to `uiRootEntity` and `subtitleRootEntity`, updates `lastReportedMovePose.value = targetPose`, persists pose/scale/depth when `manualPitch || resetScaleAndDepth`, and calls `libass.bumpOverlayAttachment()` (the subtitle renderer helper that forces the libass bitmap plane to rebind after entity pose changes).
    *   Update `recenterImmersiveSurface()`, `DisposableEffect(session)`, and `LaunchedEffect(reportedVideoSize, ...)` to use `computeImmersiveViewerPoses(headPose, UI_DEPTH_METERS)`:
        *   Sets `videoRootEntity` and `videoEntity` to `spherePose` (scale `1.0f`), sets `uiRootEntity` and `subtitleRootEntity` to `overlayPose` (scale `videoPanelScale`), updates `lastReportedMovePose.value = spherePose` and `lastAnchorHeadPos.value = headPose.translation`, and calls `libass.bumpOverlayAttachment()`.
    *   Update `LaunchedEffect(videoDepth, videoPanelScale, ...)`:
        *   Compute `headPos = computeHeadCenteredPose(session)?.translation ?: lastAnchorHeadPos.value ?: Vector3.Zero` and store `lastAnchorHeadPos.value = headPos`.
        *   Project `newVideoPose` along `normalize(currentVideoPose.translation - headPos) * videoDepth` with `viewerFacingRotation(newPos, headPos, allowPitch = true)` and sync overlays via `syncViewerProjectedOverlayRoots(newVideoPose, videoPanelScale, headPos, ui, sub, UI_DEPTH_METERS)`.
    *   Update the 100ms pose-mirror loop and 300ms debounced save loop:
        *   Skip when `wallPinActive || immersiveProjection`.
        *   In the 100ms pose-mirror loop, mirror `poseToMirror = lastReportedMovePose.value` as-is without querying live `computeHeadCenteredPose(session)` or recomputing rotation, and pass `lastAnchorHeadPos.value` to `syncViewerProjectedOverlayRoots`.
    *   In `MovableComponent.createCustomMovable`:
        *   `onMoveStart`: set `moveInProgress = true`, capture `preMoveSnapshotPose = lastReportedMovePose.value ?: initialPose` and `preMoveSnapshotHeadPos = lastAnchorHeadPos.value`, compute `headPos = computeHeadCenteredPose(session)?.translation ?: lastAnchorHeadPos.value`, `grabDepth = extractVideoDepth(preMoveSnapshotPose!!, videoDepth, headPos)`, keep `preMoveSnapshotPose!!` (never resetting rotation to `Quaternion.Identity`), and call `syncViewerProjectedOverlayRoots`.
        *   `onMoveUpdate`: start with `if (!moveInProgress) return`; compute `headPos = computeHeadCenteredPose(session)?.translation ?: lastAnchorHeadPos.value`, update `lastAnchorHeadPos.value = headPos`, compute `depthPreservedPose = computeDragPose(currentInputRay.origin, currentInputRay.direction, headPos, grabDepth)`, and sync overlays via `syncViewerProjectedOverlayRoots`.
        *   `onMoveEnd`: start with `if (!moveInProgress) return`; preserve `savedPose = lastReportedMovePose.value ?: finalPose` (including its viewer-facing `Quaternion`), compute `effectiveDepth = extractVideoDepth(savedPose, videoDepth, lastAnchorHeadPos.value)`, set `moveInProgress = false`, and persist `savedPose` and `effectiveDepth`.
    *   Invoke `SpatialPlayerRecoveryEffect`:
        *   `enabled = !wallPinActive`
        *   `onCancelMove`: sets `moveInProgress = false`, restores `preMoveSnapshotPose` and `preMoveSnapshotHeadPos` onto `lastReportedMovePose`, `lastAnchorHeadPos`, `videoRootEntity`, `videoEntity`, `uiRootEntity`, and `subtitleRootEntity`.
        *   `onStabilizationTick(headPose)`:
            *   If `immersiveProjection`: if `lastAnchorHeadPos.value == null`, `length(headPose.translation - lastReportedMovePose.value.translation) > 0.15f`, or horizontal yaw diverges by `> COMFORTABLE_FOV_MAX_ANGLE_DEGREES`, calls `recenterImmersiveSurface()` and `revealControls("headset-resume-recenter")`.
            *   If flat projection: if `lastAnchorHeadPos.value == null` or `isPanelOutsideComfortableFov(headPose, lastReportedMovePose.value)`, calls `recenterScreenToViewer(manualPitch = false)` and `revealControls("headset-resume-recenter")`.
    *   Wire `StageControlsOrbiter` with `onRecenter = { recenterScreenToViewer(manualPitch = true); resetAutoHide() }` and `onResetSize = { recenterScreenToViewer(manualPitch = true, resetScaleAndDepth = true); resetAutoHide() }`, and wire `PlayerSessionController.onResetScreenPlacement` to `{ recenterScreenToViewer(manualPitch = true) }`.

#### 5. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt` (Modify)

*   **Type / Scope**: External FCast Receiver XR Player Composable
*   **Proposed Changes**:
    *   Add `var moveInProgress by remember { mutableStateOf(false) }`, `val lastAnchorHeadPos = remember { mutableStateOf<Vector3?>(null) }`, `var preMoveSnapshotPose by remember { mutableStateOf<Pose?>(null) }`, and `var preMoveSnapshotHeadPos by remember { mutableStateOf<Vector3?>(null) }`.
    *   Call `ensureHeadTrackingConfigured(session)` in `LaunchedEffect(session)`.
    *   Replace the legacy `startupPoseGuardActive` loop with `recenterScreenToViewer(manualPitch: Boolean, resetScaleAndDepth: Boolean = false)` and `SpatialPlayerRecoveryEffect` (identical to `SpatialPlayerScreen.kt` flat-panel recovery).
    *   Update `DisposableEffect(session)` initial `uiEntity`/`subEntity` creation and the 100ms mirror loop (`LaunchedEffect(panelScale, videoEntity, uiRoot, subtitleRoot)`) to use `projectOverlayPoseTowardViewer` / `syncViewerProjectedOverlayRoots` with `lastAnchorHeadPos.value`.
    *   Update `onMoveStart`, `onMoveUpdate`, and `onMoveEnd` to use `extractVideoDepth(..., headPos)`, `computeDragPose`, `syncViewerProjectedOverlayRoots`, and `if (!moveInProgress) return` guards without `Quaternion.Identity`.
    *   Wire `StageControlsOrbiter` with `onRecenter = { recenterScreenToViewer(manualPitch = true); revealControls() }` and `onResetSize = { recenterScreenToViewer(manualPitch = true, resetScaleAndDepth = true); revealControls() }`.

#### 6. `player/xr/src/test/java/dev/jdtech/jellyfin/player/xr/PlayerGeometryTest.kt` (Create)

*   **Type / Scope**: JVM Unit Test Suite
*   **Proposed Changes**:
    *   Test `extractVideoDepth`: verifies distance is measured from `viewerPos` when non-null and falls back to `(0, 0, 0)` when `viewerPos == null`.
    *   Test `viewerFacingRotation`:
        *   Verifies identity rotation when panel is at `(0, 0, -6)` and viewer is at `(0, 0, 0)`.
        *   Verifies 45-degree and 90-degree lateral offsets produce a quaternion whose local `+Z` axis points at `viewerPos` and whose local `+X` axis has `y == 0` (zero roll).
        *   Verifies `allowPitch = false` keeps local `+Z.y == 0` even when panel is above viewer, while `allowPitch = true` tilts local `+Z` toward `viewerPos` and clamps extreme angles including exact `90°` zenith (`panelPos = (0, 6, 0), viewerPos = (0, 0, 0)`) to `75°` without NaN or roll.
    *   Test `computeViewerCenteredPanelPose` and `computeImmersiveViewerPoses`:
        *   Verifies `headPose == null` falls back to `(0, 0, -depth)` with `Quaternion.Identity`.
        *   Verifies a viewer turned 135 degrees in yaw and looking `-20°` downward gets a panel 6m along the 135-degree horizontal azimuth at eye level (`y = head.y`, `pitch = 0°`) when `manualPitch = false`, and at `-20°` pitch when `manualPitch = true`.
        *   Verifies a reclined viewer looking `+60°` upward (`> 45°` ceiling threshold) gets a ceiling-angled panel even when `manualPitch = false`.
        *   Verifies `computeImmersiveViewerPoses` places the sphere at `headPos` facing forward along the horizontal gaze (`local -Z == +horizForward`) and places overlays at `UI_DEPTH_METERS` along `+horizForward`.
    *   Test `computeDragPose` and `projectOverlayPoseTowardViewer`:
        *   Verifies dragging from a non-origin head position `(1.5, 1.2, 2.0)` places the panel at `grabDepth` from `headPos` with its normal aligned toward `headPos`, places overlays at `UI_DEPTH_METERS` along the `headPos -> videoPose` line of sight, and safely handles `dist < 1e-3f`.
    *   Test `isPanelOutsideComfortableFov`:
        *   Verifies idempotency: `isPanelOutsideComfortableFov(headPose, computeViewerCenteredPanelPose(headPose, 6f, manualPitch = false))` returns `false` across all head pitches in `[-80°, +80°]` (including `36°–45°`).
        *   Returns `true` when viewer turns 180 degrees (`localZ >= -0.5f`), >35 degrees horizontally, >35 degrees vertically from the expected placement vector, or when panel surface normal is skewed >35 degrees away from the viewer.

#### 7. `GEMINI.md` (Modify)

*   **Type / Scope**: Technical Reference Documentation
*   **Proposed Changes**:
    *   Update the XR Player entry points and SceneCore technical notes to document viewer-facing panel orientation (`viewerFacingRotation`), `DeviceTrackingMode.SPATIAL` configuration, stationary `lastAnchorHeadPos` overlay projection, and `SpatialPlayerRecoveryEffect` (`ON_PAUSE` drag rollback and 3-second SLAM stabilization recovery window).

### Verification Plan

*   **Compilation/Build**: `./gradlew :player:xr:compileDebugKotlin :app:unified:compileLibreDebugKotlin`
*   **Unit Tests**: `./gradlew :player:xr:testDebugUnitTest --tests "dev.jdtech.jellyfin.player.xr.PlayerGeometryTest" --tests "dev.jdtech.jellyfin.player.xr.PlayerPoseStorageTest"`
*   **Static Analysis & Deprecation Check**: `./gradlew :player:xr:lintDebug && ./.github/scripts/check-deprecations.sh`
*   **Unit Test Scope**: Unit tests in `PlayerGeometryTest.kt` must exercise nominal, fallback (`headPose == null`, `dist < 1e-3f`), pitch-clamping (`±75°`, `90°` zenith, `>45°` ceiling posture), idempotency (`36°–45°` head pitch), and FOV boundary (`35°`, `localZ >= -0.5f`) branches in `PlayerGeometry.kt`.
*   **E2E/Integration**: On Samsung Galaxy XR hardware, start playback in `XrPlayerActivity`, remove the headset, turn 90–180 degrees, and put the headset back on; verify the cinema panel and controls automatically recenter in front of the new gaze direction and that dragging the screen to the side rotates it so its surface normal aligns toward `headPos` with zero roll.

--------------------------------------------------------------------------------

<!-- Provenance: Indicates initial structural drafting via EGM. -->

*Drafted with EGM*
