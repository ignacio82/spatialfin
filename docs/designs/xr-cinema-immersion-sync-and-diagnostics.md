# XR Cinema Immersion, Curved Screen Geometry, A/V & Subtitle Sync Offsets, and Playback Diagnostics HUD

*   **Author:** ignacio
*   **Status:** Approved (Post-Review Resolved)
*   **Date:** 2026-11-10

--------------------------------------------------------------------------------

## Overview

SpatialFin delivers video playback across three runtime form factors from a single codebase (`:app:unified`): Android Extended Reality (`XR` headsets such as the Samsung Galaxy XR in `:player:xr`), handheld touch devices (`Beam` phones, tablets, and foldables in `:player:beam`), and Android Television (`TV` in `:player:tv`). While recent releases added 3D positional audio (`XrSpatialAudioController`), HDR color metadata (`XrSurfaceColorMetadata`), FOV-guarded wall-anchor persistence (`WallAnchorController`), and display frame-rate matching (`SurfaceFrameRateController`), four in-player capabilities remain missing when compared against specialized XR players (`4XVR`, `Skybox VR`, `Infuse`) and desktop/TV media players (`MPV`, `Wholphin`, `Plezy`, `Streamyfin`):

1.  **Graduated Passthrough Dimming in XR Full Space (`0%–100%` Opacity)**: The XR player offers only an all-or-nothing binary toggle between full room passthrough (`100%`) and complete blackout (`0%`), preventing users from dimming their physical surroundings to an intermediate theater level (such as `25%`, `35%`, or `50%`) where they can still see a drink, keyboard, or companion in the room.
2.  **Toggleable Curved Cinema Screen in XR Full Space (`SurfaceEntity.Shape.CustomMesh`)**: Non-180°/360° video in XR always renders onto a flat rectangular plane (`SurfaceEntity.Shape.Quad`). On wide virtual screens (`1.5x–2.5x` scale) or close viewing distances, the left and right edges of a flat plane sit farther from the viewer's eyes than the center, introducing peripheral viewing obliqueness that a curved cylindrical cinema wrap-around screen eliminates.
3.  **Live Subtitle Timing Offset (`±ms`) and Audio Sync Delay (`±ms`) Across All Form Factors**: None of SpatialFin's three players (`:player:xr`, `:player:beam`, `:player:tv`) allow the viewer to shift subtitle timing (`±ms`) when an external or embedded subtitle track is out of sync with the video cut, or adjust audio delay (`±ms`) when Bluetooth headphones, soundbars, or AV receivers introduce lip-sync lag.
4.  **Real-Time Playback Diagnostics ("Stats for Nerds") HUD Overlay Across All Form Factors**: When playback stutters, transcodes unexpectedly, or drops frames, users have no in-player diagnostic overlay to inspect whether the stream is Direct Playing or Server Transcoding, why the server chose to transcode, which hardware/software decoder is active, what the live bitrate and buffer health are, or whether **Split-A/V** (SpatialFin's dual-device mode where video renders locally while synchronized audio streams to a remote receiver over the **FCast** open wireless casting protocol; see [`GEMINI.md`](file:///home/ignacio/SpatialFin/GEMINI.md) and [`PlayerSplitAvAdapter.kt`](file:///home/ignacio/SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/splitav/PlayerSplitAvAdapter.kt)) is active.

This design specifies the user journeys, architecture, risks and mitigations, and phased file-level implementation plan to deliver all four capabilities across `:player:core`, `:player:local`, `:player:session`, `:player:xr`, `:player:beam`, `:player:tv`, and `:settings`.

## The Problem

Each of the four target areas corresponds to a concrete limitation in the current playback pipeline:

### 1. Binary Passthrough Switching in `SpatialPlayerScreen` and `XrFCastInboundPlayerScreen`

In [`SpatialPlayerScreen.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt#L452-L481) (`passthroughOverrideEnabled` / derived `passthroughEnabled`) and [`XrFCastInboundPlayerScreen.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt#L237-L246) (`LaunchedEffect(playing)`), passthrough switches `session.scene.spatialEnvironment.preferredSpatialEnvironment` between `null` and an empty `SpatialEnvironmentPreference(imageBasedLightingAsset = null, geometry = null)`. Neither screen sets `session.scene.spatialEnvironment.preferredPassthroughOpacity`, which Jetpack XR SceneCore (`androidx.xr.scenecore.SpatialEnvironment`) exposes as a continuous float in `[0.0f, 1.0f]`. As a result, users in Full Space are forced to choose between complete sensory isolation (`0.0f`) and un-dimmed room lighting (`1.0f`) that washes out dark HDR cinema scenes.

### 2. Planar-Only Geometry for Cinema Video in `PlayerTrackUtils`, `SpatialPlayerScreen`, and `XrFCastInboundPlayerScreen`

In [`PlayerTrackUtils.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerTrackUtils.kt#L39-L47), `mapProjectionShape` maps `PROJECTION_180` to `SurfaceEntity.Shape.Hemisphere`, `PROJECTION_360` to `SurfaceEntity.Shape.Sphere`, and all other cinema content (`mono`, `sbs`, `top_bottom`, `multiview`) to `SurfaceEntity.Shape.Quad(quad)`. Furthermore, both [`SpatialPlayerScreen.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt#L2041) and [`XrFCastInboundPlayerScreen.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt#L405) hardcode `entity.shape = SurfaceEntity.Shape.Quad(FloatSize2d(videoWidth, videoHeight))` in their flat-projection geometry update blocks. Although Jetpack XR SceneCore (`1.0.0-rc01`) provides `SurfaceEntity.Shape.CustomMesh(leftEye: TriangleMesh, rightEye: TriangleMesh, drawMode: DrawMode)` for arbitrary 3D surface geometry, SpatialFin has no mesh generator for cylindrical cinema curvature and no automatic guard to revert to a flat `Quad` when the screen is pinned to a physical flat wall (`wallPinActive == true` in [`WallAnchorController.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/WallAnchorController.kt)).

### 3. Unadjustable Subtitle and Audio Synchronization Across XR, Beam, and TV

When a user downloads a remote subtitle via `PlayerViewModel.searchRemoteSubtitles` or plays local/SMB/Jellyfin media whose subtitle track is timed to a different release cut (for example, `2.0 seconds` early or late), [`LibassRendererState.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/LibassRendererState.kt#L215), [`XrFCastInboundPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerActivity.kt#L274), [`BeamPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/BeamPlayerActivity.kt#L1443), and [`TvPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt#L1051) always pass the raw `player.currentPosition` to `libassRenderer.renderFrame(pos)`, and [`FallbackTextRenderer.kt`](file:///home/ignacio/SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/FallbackTextRenderer.kt) renders Media3 `CueGroup` events locked to the container timestamps. Similarly, when Bluetooth earbuds or an HDMI soundbar introduce `100–300 ms` of audio output latency (outside of Split-A/V's calibrated FCast pipeline), `ExoPlayer` has no user-adjustable audio synchronization offset in [`PlayerViewModel.kt`](file:///home/ignacio/SpatialFin/player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt). Users cannot fix out-of-sync dialogue or subtitles from inside any of the three players.

### 4. Opaque Playback Pipeline and Missing Diagnostics HUD

When a user experiences rebuffering, unexpected server transcoding, missing HDR signaling, or dropped frames across [`XrPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrPlayerActivity.kt), [`BeamPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/BeamPlayerActivity.kt), or [`TvPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt), the player UI only shows the selected resolution preset label (for example, `Auto` or `4K`). Diagnostic telemetry—such as whether the stream is Direct Playing or transcoding (`uiState.currentPlaybackInfoLabel`), the server's transcode reason (`uiState.currentTranscodeReason`), the active MediaCodec/FFmpeg video and audio decoder names, input vs. output resolution and frame rate, HDR transfer function (`PQ`/`HLG`/`Dolby Vision`/`SDR`) and CTA-861.3 `MaxCLL` nits, dropped video frame counter (`DecoderCounters.droppedBufferCount`), forward buffer duration (`player.totalBufferedDuration`), and in-process Split-A/V status ([`PlayerSplitAvAdapter.kt`](file:///home/ignacio/SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/splitav/PlayerSplitAvAdapter.kt))—is only visible by attaching `adb logcat`.

## Goals

*   **G1 — Graduated XR Passthrough Dimming (`0%–100%`)**: Support continuous and stepped passthrough opacity (quick-step button cycling `0% -> 35% -> 70% -> 100% -> 0%`, popover preset chips at `0%`, `25%`, `50%`, `75%`, `100%`, and continuous slider adjustment in `[0.0f, 1.0f]`) in [`SpatialPlayerScreen.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt) and [`XrFCastInboundPlayerScreen.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt) via `SpatialEnvironment.preferredPassthroughOpacity`, persisted in [`AppPreferences.kt`](file:///home/ignacio/SpatialFin/settings/src/main/java/dev/jdtech/jellyfin/settings/domain/AppPreferences.kt) (`xrPlayerPassthroughOpacity`) and controllable via [`StageControlsOrbiter`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerSecondaryControls.kt) and hands-free voice commands (for example, *"dim passthrough to 30 percent"*, *"turn off passthrough"*).
*   **G2 — Toggleable Curved XR Cinema Screen (`SurfaceEntity.Shape.CustomMesh`)**: Allow users in XR Full Space to toggle between a flat screen (`SurfaceEntity.Shape.Quad`) and a curved cylindrical cinema screen (`SurfaceEntity.Shape.CustomMesh` generated with a 48-segment horizontal arc and normalized `[0.0, 1.0]` UV coordinates compatible with `SurfaceEntity.stereoMode` for `mono`, `sbs`, `top_bottom`, and `multiview` modes), persisted in `AppPreferences.xrPlayerCurvedScreen`, while automatically forcing flat `Quad` geometry whenever the screen is pinned to a physical wall (`wallPinActive == true`), playing `180°`/`360°` VR projections, or running on an older firmware runtime that rejects `CustomMesh`.
*   **G3 — Live Subtitle Timing Offset (`±ms`) and Audio Sync Delay (`±ms`) Across XR, Beam, and TV**: Expose session-scoped `subtitleOffsetMs: StateFlow<Long>` (bounded to `[-10_000L, +10_000L]`) and `audioOffsetMs: StateFlow<Long>` (bounded to `[-5_000L, +5_000L]`, adjustable in `±50 ms` and `±250 ms` increments with a one-tap `0 ms` reset and voice command support) in [`PlayerViewModel.kt`](file:///home/ignacio/SpatialFin/player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt). Apply `subtitleOffsetMs` dynamically to both `libass` frame rendering and Media3 text cue rendering via [`FallbackTextRenderer.kt`](file:///home/ignacio/SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/FallbackTextRenderer.kt), and apply `audioOffsetMs` via `PcmAudioDelayProcessor` (for positive audio delay `audioOffsetMs > 0L` on linear PCM) and `AudioSyncOffsetSink.getCurrentPositionUs` (for negative audio delay `audioOffsetMs < 0L` across both linear PCM and HDMI bitstream passthrough) so lip-sync adjustments take effect without rebuffering or server transcoding.
*   **G4 — Cross-Platform Playback Diagnostics ("Stats for Nerds") HUD Overlay**: Provide a toggleable, semi-transparent diagnostic overlay across [`SpatialPlayerScreen.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt), [`BeamPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/BeamPlayerActivity.kt), and [`TvPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt) (accessible from the player quality/options menu and via voice command *"show stats for nerds"*) that updates at `1 Hz` with delivery method, transcode reasons, container/codecs, active decoder names, resolution/fps/HDR metadata, bitrate, buffered duration, dropped frames, A/V & subtitle offsets, and in-process Split-A/V status.

## Non-Goals

*   **NG1 — Bundled 3D Virtual Theater `.glb` Environments**: Shipping multi-megabyte 3D room or theater geometry assets inside `:app:unified` is out of scope; ambient environment control uses `SpatialEnvironment.preferredPassthroughOpacity`.
*   **NG2 — Curving the `libass` Bitmap Subtitle Plane (`PanelEntity`)**: Jetpack XR `PanelEntity` instances are always planar quads in SceneCore. When the video `SurfaceEntity` uses a curved `CustomMesh` at `VIDEO_DEPTH_METERS = 6.0 m`, the subtitle `PanelEntity` (`subtitleRoot`) remains a flat plane projected toward the viewer by `projectOverlayPoseTowardViewer` at `UI_DEPTH_METERS = 2.0 m` (`> 3.0 m` in front of the curved video mesh's leading edges), so `libass` text never intersects or clips behind the curved video mesh.
*   **NG3 — Positive Audio Delay (`audioOffsetMs > 0`) During Compressed HDMI Bitstream Passthrough**: When `AudioTrack` operates in compressed bitstream passthrough mode (Dolby TrueHD, DTS-HD MA, AC-3, E-AC-3 raw frames over HDMI to an AVR), linear PCM `AudioProcessor` instances are bypassed by Media3. While negative audio offsets (`audioOffsetMs < 0L`, the standard fix for wireless headphone or AVR lag) continue to work during bitstream passthrough via `AudioSyncOffsetSink.getCurrentPositionUs` clock shifting, positive audio delays (`audioOffsetMs > 0L`) are bypassed during bitstream passthrough and trigger an inline UI notice advising the user to use their AVR's lip-sync delay or switch passthrough off.
*   **NG4 — Persisting Subtitle/Audio Sync Offsets Globally Across Unrelated Movies**: Because subtitle and audio sync errors are specific to an individual file or wireless audio session, `subtitleOffsetMs` and `audioOffsetMs` reset to `0 ms` on new media items and playback sessions rather than skewing subsequent media.

## Open Questions

None. All scope boundaries for Overview & Goals are defined above.

## Critical User Journeys (CUJs)

### CUJ 1: Dimming Room Passthrough and Enabling Curved Screen in XR Full Space

*   **Given**: A user is watching a 2D or 3D (`sbs`/`top_bottom`) movie in `XrPlayerActivity` in Full Space on a Samsung Galaxy XR, unpinned from any physical wall (`wallPinActive == false`).
*   **When**: The user taps or adjusts the Passthrough control in `StageControlsOrbiter` (or says *"dim passthrough to 40 percent"*) and toggles the Curved Screen control (or says *"curve the screen"*).
*   **Then**: `session.scene.spatialEnvironment.preferredPassthroughOpacity` transitions to `0.40f` so the physical room remains dimly visible around the cinema screen without washing out HDR highlights, and the video `SurfaceEntity.shape` switches from `SurfaceEntity.Shape.Quad` to a cylindrical `SurfaceEntity.Shape.CustomMesh` whose horizontal arc wraps toward the viewer while keeping the `libass` subtitle overlay plane in front of the mesh. If the user subsequently pins the screen to a physical wall (`wallPinActive == true`), the video surface automatically flattens back to `SurfaceEntity.Shape.Quad` and passthrough opacity is raised so the physical wall is visible.

### CUJ 2: Correcting Out-of-Sync Subtitles and Bluetooth Audio Lag Mid-Playback

*   **Given**: A user is watching an episode or movie on XR, a Beam phone/tablet, or an Android TV where the subtitle track appears `1.5 seconds` too early and Bluetooth headphones lag the video by `200 ms`.
*   **When**: The user opens the A/V & Subtitle Synchronization dialog from the player controls (or says *"delay subtitles by 1.5 seconds"*), sets Subtitle Offset to `+1500 ms`, and sets Audio Offset to `-200 ms`.
*   **Then**: Both `libass` and Media3 fallback text cues render `1500 ms` later on the next frame without reloading the video stream, and `AudioSyncOffsetSink` holds video frame release by `200 ms` so video and Bluetooth audio land in sync. Tapping `Reset (0 ms)` immediately restores default timing.

### CUJ 3: Inspecting Playback Telemetry via the "Stats for Nerds" HUD Overlay

*   **Given**: A user on XR, Beam, or Android TV wants to verify whether a 4K Dolby Vision / TrueHD movie is direct-playing or server-transcoding and check whether frames are dropping.
*   **When**: The user toggles **Stats for Nerds** in the player quality/settings menu (or says *"show playback stats"* in XR).
*   **Then**: A compact, non-blocking monospace HUD overlay appears in the top-start corner of the video viewport showing the delivery mode (`Direct Play` vs. `Transcoding (AudioCodecNotSupported)`), container and codecs (`mkv / hevc -> c2.qti.hevc.decoder`, `truehd -> FfmpegAudioRenderer`), video resolution and frame rate (`3840x2160 @ 23.976 fps`), color metadata (`BT.2020 / PQ / MaxCLL 1000 nits`), dropped frames (`0`), forward buffer (`42.3 s`), active A/V & subtitle offsets (`Audio: -200 ms | Sub: +1500 ms`), and (if Split-A/V is active) in-process Split-A/V playback status.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

### System Data Flow

```text
+-----------------------------------------------------------------------------------+
|                         User Input & Persistence Layer                            |
|  - StageControlsOrbiter / TrackOptionsOrbiter / PlayerDialogs (:player:xr)        |
|  - BeamPlayerScreen (:player:beam) & TvPlayerScreen (:player:tv)                  |
|  - Voice Pipeline: SpatialCommandCoordinator / BeamCommandCoordinator             |
|                    -> PlayerSessionController.dispatch(...)                       |
|  - AppPreferences (:settings): xrPlayerPassthroughOpacity, xrPlayerCurvedScreen   |
+-----------------+-------------------------+-----------------------+---------------+
                  |                         |                       |
                  v                         v                       v
+-------------------------+   +---------------------------+   +---------------------+
| XR SceneCore Immersion  |   | A/V & Subtitle Sync Core  |   | Diagnostics Collector|
| (:player:xr)            |   | (:player:core & :local)   |   | (:player:local)     |
|                         |   |                           |   |                     |
| 1. XrPassthroughCtrl    |   | 1. PlayerViewModel        |   | PlaybackStats       |
|    preferredPassthrough |   |    - subtitleOffsetMs     |   | Collector           |
|    Opacity in [0f..1f]  |   |    - audioOffsetMs        |   |  - AnalyticsListener|
|                         |   |                           |   |    (decoder names)  |
| 2. CurvedSurfaceMesh    |   | 2. AudioSyncOffsetSink &  |   |  - UiState delivery |
|    Builder              |   |    PcmAudioDelayProcessor |   |    & transcode info |
|    48-seg cylindrical   |   |    - +ms: PCM ring buffer |   |  - Color / MaxCLL   |
|    CustomMesh w/ Quad   |   |    - -ms: MediaClock hold |   |  - Dropped Frames   |
|    fallback & wall/VR   |   |                           |   |  - Buffer & Offsets |
|    guards               |   | 3. LibassRendererState &  |   +----------+----------+
+-------------------------+   |    FallbackTextRenderer   |              |
                              |    pos - subtitleOffset   |              v
                              +---------------------------+   +---------------------+
                                                              | PlaybackStatsOverlay|
                                                              | (:player:xr, shared |
                                                              |  by :beam & :tv)    |
                                                              +---------------------+
```

### 1. Graduated XR Passthrough Dimming (`XrPassthroughController`)

We introduce `XrPassthroughController` in `:player:xr` and persist the user's preferred opacity in [`AppPreferences.kt`](file:///home/ignacio/SpatialFin/settings/src/main/java/dev/jdtech/jellyfin/player/xr/XrPassthroughController.kt) (`xrPlayerPassthroughOpacity`, default `0.0f` for full theater blackout):

*   **Precedence & Effective Opacity Policy (`resolveEffectiveOpacity`)**:
    *   When `immersiveProjection == true` (`180°` or `360°` VR video), effective passthrough opacity is forced to `0.0f` because the video sphere/hemisphere occupies the full surrounding environment.
    *   When `wallPinActive == true` ([`WallAnchorController.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/WallAnchorController.kt)), effective passthrough opacity is forced to `1.0f` when `preferredOpacity == 0.0f` (or `preferredOpacity.coerceAtLeast(0.85f)`) so the physical wall remains visible.
    *   Otherwise, effective opacity is `preferredOpacity.coerceIn(0.0f, 1.0f)` (replacing `NaN` with `0.0f`).
    *   Legacy boolean consumers (`buildPlayerStateSnapshot`, `McpBridge` `"toggle_passthrough"`, and `XrPlayerAction.SetPassthrough` / `TogglePassthrough`) map cleanly: `passthroughEnabled = effectiveOpacity > 0.0f`, `SetPassthrough(true)` sets `1.0f`, `SetPassthrough(false)` sets `0.0f`, and `TogglePassthrough` toggles between `0.0f` and `1.0f`.
*   **SceneCore Application (`applyPassthroughOpacity`)**:
    *   Sets `session.scene.spatialEnvironment.preferredPassthroughOpacity = clamped` and switches `preferredSpatialEnvironment` to `null` when `clamped >= 0.99f` or to `SpatialEnvironmentPreference(null, null)` when `clamped < 0.99f`.
    *   Restores `preferredPassthroughOpacity = SpatialEnvironment.NO_PASSTHROUGH_OPACITY_PREFERENCE` inside a `DisposableEffect` on player exit so Home Space is unaffected.
*   **UI & Voice Ergonomics**:
    *   In [`StageControlsOrbiter`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerSecondaryControls.kt#L78-L152), tapping the passthrough button cycles quick steps (`0% -> 35% -> 70% -> 100% -> 0%`) or opens a dimmer popover with preset chips (`0%`, `25%`, `50%`, `75%`, `100%`) and a continuous slider, while voice commands dispatch `XrPlayerAction.SetPassthroughOpacity`.

### 2. Cylindrical Curved XR Cinema Screen (`CurvedSurfaceMeshBuilder`)

We introduce `CurvedSurfaceMeshBuilder` in `:player:xr` to generate a cylindrical `SurfaceEntity.Shape.CustomMesh` when flat cinema projection is active:

*   **Eligibility & Wall-Pin / VR Guards (`shouldUseCurvedScreen`)**:
    *   Curvature is enabled if and only if `curvedPreferenceEnabled && !isImmersiveProjection(projection) && !wallPinActive`.
    *   Whenever `wallPinActive == true` or `projection` is `PROJECTION_180` / `PROJECTION_360`, [`PlayerTrackUtils.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerTrackUtils.kt#L39-L47) (`mapProjectionShape`) returns `SurfaceEntity.Shape.Quad(quad)` or the spherical shape.
*   **Geometry & Subtitle Overlay Separation**:
    *   Builds a 48-segment horizontal cylindrical arc ($\theta_{\text{arc}} = 46^\circ$, $R = W / \theta_{\text{arc}}$) whose center sits at $z = 0$ and whose left/right edges curve toward the viewer by sagitta $s \approx 0.099 \times W$. Because `subtitleRoot` and `uiRoot` are placed by `projectOverlayPoseTowardViewer` at `UI_DEPTH_METERS = 2.0 m` versus `VIDEO_DEPTH_METERS = 6.0 m` (`> 3.0 m` in front of the curved mesh), `libass` subtitle panels never clip or intersect the curved screen.
*   **Stereoscopic Compatibility & Firmware Fallback**:
    *   Generates normalized `[0.0, 1.0]` UV coordinates on the cylindrical `TriangleMesh` for both `leftEye` and `rightEye`, letting SceneCore's `SurfaceEntity.stereoMode` (`MONO`, `SIDE_BY_SIDE`, `TOP_BOTTOM`, `MULTIVIEW_LEFT_PRIMARY`) handle eye-texture subdivision in the surface shader without double-cropping.
    *   Wraps `CustomMesh` creation and `entity.shape` assignment in `runCatching`, automatically falling back to `SurfaceEntity.Shape.Quad(quad)` if an older device firmware runtime rejects `CustomMesh`.

### 3. Live Subtitle Timing Offset (`±ms`) & Audio Sync Delay (`±ms`)

Both offsets are owned by `AudioSubtitleSyncController` in `:player:core` and exposed on [`PlayerViewModel.kt`](file:///home/ignacio/SpatialFin/player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt) (plus an activity-local instance in theater-only [`XrFCastInboundPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerActivity.kt)):

*   **Bounds & Sign Convention (MPV / VLC / Jellyfin Standard)**:
    *   `subtitleOffsetMs` is bounded to `[-10_000L, +10_000L]`: `> 0` delays subtitles to appear later; `< 0` advances subtitles to appear earlier.
    *   `audioOffsetMs` is bounded to `[-5_000L, +5_000L]`: `> 0` delays audio relative to video; `< 0` advances audio relative to video (holds decoded video frames to match lagging Bluetooth headphones or AV receivers).
*   **Subtitle Offset Pipeline (`libass` + `FallbackTextRenderer`)**:
    *   **`libass` 60 fps Render Loops**: In [`LibassRendererState.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/LibassRendererState.kt), [`XrFCastInboundPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerActivity.kt), [`BeamPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/BeamPlayerActivity.kt), and [`TvPlayerActivity.kt`](file:///home/ignacio/SpatialFin/player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt), the render loop reads the latest offset via `rememberUpdatedState` (or `syncController.subtitleOffsetMs.value`) and passes `AudioSubtitleSyncController.effectiveLibassPositionMs(player.currentPosition, subtitleOffsetMs)` to `libassRenderer.renderFrame(...)`.
    *   **Media3 Fallback Subtitles (`FallbackTextRenderer.kt` in `:player:core`)**: Rather than delaying `onCues` in the UI layer, [`FallbackTextRenderer.kt`](file:///home/ignacio/SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/FallbackTextRenderer.kt) (used across XR, Beam, TV, and Multitask players when `useLibass == false`, including 3D/VR modes and PGS/TTML subtitles) overrides `render(positionUs: Long, elapsedRealtimeUs: Long)` to shift `positionUs` by `-subtitleOffsetMs * 1_000L`, supporting both positive (`+ms`) and negative (`-ms`) subtitle offsets across all form factors.
*   **Audio Offset Pipeline (`PcmAudioDelayProcessor` + `AudioSyncOffsetSink` via `AudioPassthroughSinks`)**:
    *   **`AudioPassthroughSinks.buildOverride` Integration**: Updates the early-return guard in [`AudioPassthroughSinks.kt`](file:///home/ignacio/SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/audio/AudioPassthroughSinks.kt#L62) so `buildOverride` does not return `null` when `syncController != null` (including under default `AudioPassthroughMode.AUTO`), and removes the outer `passthroughMode != AUTO` guards in `TvPlayerActivity` and `MultitaskPlayerActivity`.
    *   **Positive Audio Delay (`audioOffsetMs > 0L`) — `PcmAudioDelayProcessor`**: A `BaseAudioProcessor` in `:player:core` that delays linear PCM (`PCM_16BIT` / `PCM_FLOAT`) up to `+5_000 ms` using a lazy circular ring buffer, emitting leading silence when delay increases and immediately trimming/advancing past excess buffered frames when delay is reduced or reset to `0 ms` (or cleared on `onFlush()`).
    *   **Negative Audio Delay (`audioOffsetMs < 0L`) — `AudioSyncOffsetSink`**: A `ForwardingAudioSink` in `:player:core` that shifts `getCurrentPositionUs(sourceEnded)` by `audioOffsetMs * 1_000L` strictly when `audioOffsetMs < 0L` (leaving `basePosUs` unshifted when `audioOffsetMs >= 0L` because `PcmAudioDelayProcessor` preserves 1-to-1 frame throughput). Because `AudioSyncOffsetSink` adjusts the master audio clock for negative offsets, negative audio delay works across both linear PCM and compressed HDMI bitstream passthrough.

### 4. Real-Time Playback Diagnostics ("Stats for Nerds") HUD (`PlaybackStatsCollector` & `PlaybackStatsOverlay`)

To respect SpatialFin's module boundaries (`:player:local` is a pure Kotlin module with no Compose compiler dependency, while `:player:beam` and `:player:tv` both depend on `:player:xr`):

*   **Telemetry Collector (`PlaybackStatsCollector.kt` in `:player:local`)**:
    *   Defines `data class PlaybackStatsSnapshot` and `class PlaybackStatsCollector` in `:player:local` with zero Compose dependencies.
    *   Registered as a lightweight `AnalyticsListener` on `ExoPlayer` inside `PlayerViewModel.replacePlayer(newPlayer)` alongside `EventLogger` so one-shot `onVideoDecoderInitialized` and `onAudioDecoderInitialized` events are captured in `@Volatile` fields at track start with zero polling cost while the HUD is hidden.
    *   Reuses `PlayerViewModel.UiState.currentPlaybackInfoLabel` and `currentTranscodeReason` (computed by `PlayerViewModel.buildPlaybackInfo`), shared CTA-861.3 `MaxCLL` / `ColorInfo` formatting from `:player:core`, `ExoPlayer.videoDecoderCounters`, `player.totalBufferedDuration`, `AudioSubtitleSyncController`, and in-process `PlayerSplitAvAdapter` status (`splitAvActive` and local video timing).
*   **Shared Compose HUD (`PlaybackStatsOverlay.kt` in `:player:xr`, shared by `:player:beam` and `:player:tv`)**:
    *   Provides `@Composable fun rememberPlaybackStatsSnapshot(...)` (which runs a `1 Hz` `delay(1000L)` polling loop only while `enabled == true`) and `@Composable fun PlaybackStatsOverlay(...)` rendering a translucent monospace card in the top-start corner of XR (`SpatialPlayerScreen`), Beam (`BeamPlayerScreen`), and TV (`TvPlayerScreen`).

--------------------------------------------------------------------------------

## Alternatives Considered

### Alternative A: Scope Home-Screen Browsing, Multi-Server Aggregation, and Offline Audio Into the Same Design

*   **Pros**: Addresses home-screen row customization (`Wholphin` parity), multi-server aggregation (`Plezy` parity), offline music/audiobooks (`Finamp` parity), and player precision in one specification.
*   **Cons**: Mixes unrelated architectural layers—Jetpack XR SceneCore `SurfaceEntity` meshes, Media3 `AudioSink` clock/buffer manipulation, Room database schema migrations (`ServerDatabaseDao.kt`), and home-screen row state machines—into a single rollout, multiplying review complexity and regression risk across all three form-factor shells.
*   **Reason for Rejection**: Rejected by author: Combining player scene-graph/renderer changes with Room database migrations, home-screen layout refactoring, and audio services in one design creates excessive blast radius across `:app:unified`, `:data`, `:modes:film`, and `:modes:audio`.

### Alternative B: Prioritize Home-Screen Browsing Customization or Native Offline Audio Before In-Player Precision

*   **Pros**: Improves pre-playback home-screen organization (merging Continue Watching + Next Up, adding TV profile PINs) or adds offline music/audiobook caching for users who do not use Music Assistant (`:modes:music`).
*   **Cons**: Leaves the video playback experience across XR, Beam, and TV unable to dim room passthrough gradually, curve the XR cinema surface, correct out-of-sync subtitles/audio, or inspect live stream/decoder diagnostics.
*   **Reason for Rejection**: Rejected by author: SpatialFin's primary surface is its video player across XR, Beam, and TV; resolving in-player passthrough dimming, curved cinema geometry, A/V & subtitle sync offsets, and playback diagnostics directly improves active video playback sessions.

### Alternative C: Apply Subtitle and Audio Timing Offsets via Server-Side HLS Re-Transcoding or Subtitle Burn-In

*   **Pros**: Offloads timestamp shifting to the Jellyfin server's FFmpeg pipeline without requiring `PcmAudioDelayProcessor`, `AudioSyncOffsetSink`, or `FallbackTextRenderer` offset shifting.
*   **Cons**: Every `±50 ms` adjustment would tear down and restart the HTTP Live Streaming (HLS) session with a multi-second rebuffer penalty, force lossy video re-encoding when burning in subtitles, fail for offline downloads, local `MediaStore` videos, SMB/NFS shares, and external FCast streams, and increase server CPU/GPU load.
*   **Reason for Rejection**: Rejected by author: Server re-transcoding adds multi-second startup latency, breaks offline/local/SMB playback, and degrades video quality; client-side timestamp shifting in `LibassRendererState` / `FallbackTextRenderer` and `PcmAudioDelayProcessor` / `AudioSyncOffsetSink` takes effect on the next rendered frame or audio buffer without rebuffering.

### Alternative D: Coarse Tessellation (`8–12` Segments) or Custom Fragment-Shader Distortion for Curved XR Cinema

*   **Pros**: An `8–12` segment mesh reduces vertex count slightly (`~26` vertices vs. `98` vertices), while a fragment-shader warp keeps a 4-vertex `Quad`.
*   **Cons**: Coarse tessellation (`8–12` segments across a `46°` arc) produces visible polygonal facets along straight horizontal subtitle/letterbox edges, whereas custom fragment shaders cannot be injected into Jetpack XR SceneCore's DRM/hardware-protected `SurfaceEntity` video pipeline.
*   **Reason for Rejection**: Rejected by author: A 48-segment cylindrical `SurfaceEntity.Shape.CustomMesh` (`98` vertices, `< 4 KB` of direct NIO buffers allocated only on geometry changes) eliminates visible faceting while remaining compatible with SceneCore's native `SurfaceEntity` compositor and `stereoMode` pipeline.

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Performance & Memory Overhead**:
    *   `CurvedSurfaceMeshBuilder` allocates direct NIO buffers (`98` vertices × `3` floats = `1,176` bytes for positions, `784` bytes for UVs, `1,152` bytes for indices—under `4 KB` total) only when video dimensions, projection, wall-pin state, or the curved toggle change, never on a per-frame loop.
    *   `PcmAudioDelayProcessor` operates as a zero-allocation pass-through when `audioOffsetMs <= 0L` and caps its circular PCM buffer allocation at `+5_000 ms` (`~1.92 MB` for 48 kHz stereo 16-bit PCM, allocated lazily only when positive delay is requested).
    *   `PlaybackStatsCollector` captures one-shot decoder initialization strings via `AnalyticsListener` and polls `ExoPlayer` counters at `1 Hz` (`1000 ms` coroutine delay) only while `showStatsOverlay == true`, incurring zero polling or recomposition overhead when the overlay is hidden.
*   **Rollout & Backward Compatibility**:
    *   `xrPlayerPassthroughOpacity` defaults to `0.0f` (matching the existing Full Space blackout default during playback) and `xrPlayerCurvedScreen` defaults to `false` (preserving flat `Quad` geometry until toggled).
    *   `subtitleOffsetMs` and `audioOffsetMs` are session/item-ephemeral (`0L` on every new item launch or `onMediaItemTransition`) so a timing fix applied to one file never skews subsequent playback sessions.

## Risks & Mitigations

1.  **Live Audio Delay Reduction / Reset and Media3 Clock Alignment**:
    *   *Risk*: If a user sets `audioOffsetMs = +1000 ms` and later reduces it to `+200 ms` or `0 ms` (`Reset`), leaving the old buffered samples inside `PcmAudioDelayProcessor`'s FIFO ring buffer would trap stale audio delay until the next seek. Conversely, if `AudioSyncOffsetSink.getCurrentPositionUs` also shifted the clock when `audioOffsetMs > 0L`, it would conflict with `PcmAudioDelayProcessor`'s 1-to-1 leading-silence frame emission.
    *   *Mitigation*: `PcmAudioDelayProcessor` compares `bufferedFrames` against `targetDelayFrames` on every `queueInput` call and immediately advances the read pointer past excess frames (`bufferedFrames - targetDelayFrames`) whenever the target delay decreases, and clears the ring buffer on `onFlush()`. `AudioSyncOffsetSink.getCurrentPositionUs` shifts `basePosUs` strictly when `audioOffsetMs < 0L` and returns `basePosUs` unchanged when `audioOffsetMs >= 0L`. Furthermore, `audioOffsetMs` is bounded to `[-5_000L, +5_000L]` (matching `MAX_POSITIVE_PCM_DELAY_MS = 5_000L`) so there is no dead UI range and negative clock holds do not starve `MediaCodecVideoRenderer` output buffers.
2.  **HDMI Bitstream Passthrough & Split-A/V Coexistence**:
    *   *Risk*: During compressed HDMI bitstream passthrough (TrueHD, DTS-HD, E-AC-3 raw frames), Media3 bypasses PCM `AudioProcessor` instances; during Split-A/V playback, `PlayerSplitAvAdapter` already manages audio offload synchronization.
    *   *Mitigation*: Negative audio offsets (`audioOffsetMs < 0L`) operate on `AudioSyncOffsetSink.getCurrentPositionUs` and work during HDMI bitstream passthrough. When `audioOffsetMs > 0L` and bitstream passthrough is active (`!isCurrentFormatPcm`), `AudioSyncOffsetSink.isPositiveDelayBypassedByPassthrough()` returns `true` and `SyncOffsetDialogContent` displays an inline notice.
3.  **Negative Subtitle Offsets (`subtitleOffsetMs < 0L`) on Non-`libass` Streams**:
    *   *Risk*: When `useLibass == false` (3D/VR modes, PGS bitmap subtitles, or TTML), advancing subtitles earlier than video (`subtitleOffsetMs < 0L`) via `FallbackTextRenderer.render(positionUs - offsetUs, ...)` can only render cues that have already been demuxed into Media3's `SampleQueue`.
    *   *Mitigation*: Sideloaded external subtitle files are buffered in full, and muxed subtitle tracks are buffered ahead by `DefaultLoadControl`'s forward buffer (`30–50 s`), which covers typical synchronization adjustments within `[-10_000L, 0L]`.
4.  **SplitEngine Firmware Compatibility & Stereoscopic UV Mapping on `SurfaceEntity.Shape.CustomMesh`**:
    *   *Risk*: Older Samsung Galaxy XR firmware builds (prior to `androidx.xr:1.0.0-rc01` SplitEngine mesh support; see [`GEMINI.md` line 415](file:///home/ignacio/SpatialFin/GEMINI.md#L415)) could throw or fail when constructing or assigning `SurfaceEntity.Shape.CustomMesh`, which would abort `SurfaceEntity` setup if uncaught. Additionally, pre-splitting UVs to `[0, 0.5]` in `CurvedSurfaceMeshBuilder` while `SurfaceEntity.stereoMode` is `SIDE_BY_SIDE` or `TOP_BOTTOM` would double-crop 3D video.
    *   *Mitigation*: `CurvedSurfaceMeshBuilder` uses normalized `[0.0, 1.0]` UVs for both `leftEye` and `rightEye` meshes and delegates eye-texture subdivision to `SurfaceEntity.stereoMode`. Both `SurfaceEntity.create` (which always initializes with `SurfaceEntity.Shape.Quad` first) and subsequent `entity.shape = mapProjectionShape(...)` assignments wrap `CustomMesh` application in `runCatching`, falling back to `SurfaceEntity.Shape.Quad(quad)` if the runtime rejects `CustomMesh`.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

The implementation is partitioned into four incremental phases. Each phase leaves the repository in a compiling, unit-tested state.

### Phase 1: Preferences & Core Audio/Subtitle Sync Primitives (`:settings`, `:player:core`)

#### 1. `settings/src/main/java/dev/jdtech/jellyfin/settings/domain/AppPreferences.kt` (Modify)

*   **Type / Scope**: Settings / Preferences DataStore & SharedPreferences Facade (`:settings`)
*   **Proposed Changes**: Add two persisted XR player preferences:
    *   `xrPlayerPassthroughOpacity = Preference("pref_xr_player_passthrough_opacity", 0.0f)`
    *   `xrPlayerCurvedScreen = Preference("pref_xr_player_curved_screen", false)`
*   **Rationale**: Persists the user's chosen Full Space passthrough dimming opacity (`0.0f..1.0f`) and curved cinema screen state across playback sessions.

#### 2. `player/core/src/main/java/dev/jdtech/jellyfin/player/core/sync/AudioSubtitleSyncController.kt` (Create)

*   **Type / Scope**: Pure Synchronization State & Policy Controller (`:player:core`)
*   **Proposed Changes**: Define `AudioSubtitleSyncController` owning thread-safe `@Volatile` backing fields and `StateFlow<Long>` properties for:
    *   `subtitleOffsetMs: StateFlow<Long>` bounded to `[-10_000L, +10_000L]` (`MIN_SUBTITLE_OFFSET_MS = -10_000L`, `MAX_SUBTITLE_OFFSET_MS = 10_000L`)
    *   `audioOffsetMs: StateFlow<Long>` bounded to `[-5_000L, +5_000L]` (`MIN_AUDIO_OFFSET_MS = -5_000L`, `MAX_AUDIO_OFFSET_MS = 5_000L`, `MAX_POSITIVE_PCM_DELAY_MS = 5_000L`)
    *   Methods: `setSubtitleOffsetMs(offsetMs: Long): Long`, `adjustSubtitleOffsetMs(deltaMs: Long): Long`, `setAudioOffsetMs(offsetMs: Long): Long`, `adjustAudioOffsetMs(deltaMs: Long): Long`, `currentSubtitleOffsetMs(): Long`, `currentAudioOffsetMs(): Long`, `currentPositivePcmDelayMs(): Long` (returns `audioOffsetMs.coerceIn(0L, MAX_POSITIVE_PCM_DELAY_MS)`), `reset()`, and companion helper `effectiveLibassPositionMs(rawPositionMs: Long, subtitleOffsetMs: Long): Long = (rawPositionMs - subtitleOffsetMs).coerceAtLeast(0L)`.
*   **Rationale**: Centralizes clamping, sign-convention math, and lock-free atomic reads across the UI thread and Media3 rendering threads.

#### 3. `player/core/src/main/java/dev/jdtech/jellyfin/player/core/audio/PcmAudioDelayProcessor.kt` (Create)

*   **Type / Scope**: Media3 PCM Audio Processor (`:player:core`)
*   **Proposed Changes**: Subclass `androidx.media3.common.audio.BaseAudioProcessor` accepting `delayMsProvider: () -> Long`.
    *   In `onConfigure(inputAudioFormat)`, accept `C.ENCODING_PCM_16BIT` and `C.ENCODING_PCM_FLOAT` (returning `AudioFormat.NOT_SET` for other encodings).
    *   When `targetDelayMs <= 0L` and `bufferedBytes == 0`, copy `inputBuffer` directly to `replaceOutputBuffer(remaining)` with zero ring-buffer allocation.
    *   When `targetDelayMs > 0L` (clamped to `MAX_POSITIVE_PCM_DELAY_MS = 5_000L`), compute `targetDelayBytes = ((targetDelayMs * sampleRate) / 1000L).toInt() * bytesPerFrame`, lazily allocate/grow a circular `ByteArray` ring buffer, and if `bufferedBytes > targetDelayBytes` (because the user reduced or reset `audioOffsetMs` mid-playback), immediately advance `readIndex` and reduce `bufferedBytes` to `targetDelayBytes` (aligned to `bytesPerFrame`). Write incoming PCM frames into the ring buffer while emitting zero-valued PCM silence bytes until `bufferedBytes` reaches `targetDelayBytes`, then drain delayed PCM frames in FIFO order. Clear `bufferedBytes = 0` on `onFlush()` and `onReset()`.
*   **Rationale**: Delays decoded PCM audio relative to video when `audioOffsetMs > 0L` and responds immediately when the user reduces or resets the delay.

#### 4. `player/core/src/main/java/dev/jdtech/jellyfin/player/core/audio/AudioSyncOffsetSink.kt` (Create)

*   **Type / Scope**: Media3 Forwarding Audio Sink (`:player:core`)
*   **Proposed Changes**: Subclass `androidx.media3.exoplayer.audio.ForwardingAudioSink(delegate)` accepting `private val syncController: AudioSubtitleSyncController`.
    *   Override `configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?)` to record `@Volatile var isCurrentFormatPcm: Boolean = Util.isEncodingLinearPcm(inputFormat.pcmEncoding)`.
    *   Override `getCurrentPositionUs(sourceEnded: Boolean): Long`:
        *   Read `val basePosUs = super.getCurrentPositionUs(sourceEnded)`; if `basePosUs == AudioSink.CURRENT_POSITION_NOT_SET`, return `AudioSink.CURRENT_POSITION_NOT_SET`.
        *   Read `val offsetMs = syncController.currentAudioOffsetMs()`.
        *   When `offsetMs < 0L`, return `(basePosUs + offsetMs * 1_000L).coerceAtLeast(0L)` so `MediaCodecVideoRenderer` holds video frames by `abs(offsetMs)` ms to match lagging Bluetooth or AVR audio.
        *   When `offsetMs >= 0L`, return `basePosUs` unchanged (since `PcmAudioDelayProcessor` delays the PCM sample stream directly while preserving 1-to-1 frame count).
    *   Expose `fun isPositiveDelayBypassedByPassthrough(): Boolean = syncController.currentAudioOffsetMs() > 0L && !isCurrentFormatPcm`.
*   **Rationale**: Implements negative audio delay (`audioOffsetMs < 0L`) across both linear PCM and compressed HDMI bitstream passthrough without conflicting with `PcmAudioDelayProcessor` when `audioOffsetMs >= 0L`.

#### 5. `player/core/src/main/java/dev/jdtech/jellyfin/player/core/audio/AudioPassthroughSinks.kt` (Modify)

*   **Type / Scope**: Audio Sink Factory (`:player:core`)
*   **Proposed Changes**:
    *   Add parameter `syncController: AudioSubtitleSyncController? = null` to `buildOverride(...)`.
    *   Update the early-return guard at [line 62](file:///home/ignacio/SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/audio/AudioPassthroughSinks.kt#L62) from `if (capabilities == null && audioTrackProvider == null) return null` to `if (capabilities == null && audioTrackProvider == null && syncController == null) return null`.
    *   When `syncController != null`, attach `setAudioProcessors(arrayOf(PcmAudioDelayProcessor(syncController::currentPositivePcmDelayMs)))` on `DefaultAudioSink.Builder(context)` and wrap the built `AudioSink` in `AudioSyncOffsetSink(sink, syncController)`.
*   **Rationale**: Ensures all five player activities (`XrPlayerActivity`, `XrFCastInboundPlayerActivity`, `MultitaskPlayerActivity`, `BeamPlayerActivity`, `TvPlayerActivity`) install `PcmAudioDelayProcessor` and `AudioSyncOffsetSink` even when `AudioPassthroughMode.AUTO` (`capabilities == null`) is active.

#### 6. `player/core/src/main/java/dev/jdtech/jellyfin/player/core/FallbackTextRenderer.kt` (Modify)

*   **Type / Scope**: Media3 Fallback Text Renderer Wrapper (`:player:core`)
*   **Proposed Changes**:
    *   Add constructor parameter `private val subtitleOffsetMsProvider: () -> Long = { 0L }` to `FallbackTextRenderer`.
    *   Override `override fun render(positionUs: Long, elapsedRealtimeUs: Long)` to compute `val shiftedPositionUs = (positionUs - subtitleOffsetMsProvider() * 1_000L).coerceAtLeast(0L)` and pass `shiftedPositionUs` to `super.render(shiftedPositionUs, elapsedRealtimeUs)`.
*   **Rationale**: Shifts Media3 fallback subtitle cues (`useLibass == false`, including 3D/VR modes, PGS, TTML, and `PlayerView.subtitleView` on Beam, TV, and Multitask) for both positive (`+ms`) and negative (`-ms`) offsets without UI-layer coroutine timers.

#### 7. `player/core/src/main/java/dev/jdtech/jellyfin/player/core/video/ColorMetadataFormatter.kt` (Create)

*   **Type / Scope**: Shared HDR & Color Metadata Formatter (`:player:core`)
*   **Proposed Changes**: Extract/share CTA-861.3 `extractMaxContentLightLevel(hdrStaticInfo: ByteArray?): Int` (matching [`XrSurfaceColorMetadata.kt`](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrSurfaceColorMetadata.kt#L60-L79)) and `formatColorMetadataSummary(format: Format?): String` (e.g., `"BT.2020 / PQ (HDR10) / MaxCLL 1000 nits"`, `"BT.2020 / HLG"`, `"Dolby Vision"`, or `"BT.709 / SDR"`).
*   **Rationale**: Allows `PlaybackStatsCollector` in `:player:local` and `XrSurfaceColorMetadata` in `:player:xr` to share CTA-861.3 `MaxCLL` and `ColorInfo` parsing without duplicating byte-level logic.

#### 8. Phase 1 Unit Tests (Create)

*   `player/core/src/test/java/dev/jdtech/jellyfin/player/core/sync/AudioSubtitleSyncControllerTest.kt`: Verify clamping (`subtitleOffsetMs` in `[-10_000L, 10_000L]`, `audioOffsetMs` in `[-5_000L, 5_000L]`), step adjustments, `currentPositivePcmDelayMs()`, `reset()`, and `effectiveLibassPositionMs`.
*   `player/core/src/test/java/dev/jdtech/jellyfin/player/core/audio/PcmAudioDelayProcessorTest.kt`: Verify zero-delay pass-through, positive PCM ring-buffer silence insertion and FIFO sample ordering for 16-bit and float PCM, immediate ring-buffer trimming when `delayMs` decreases or resets to `0L`, `onFlush()` clearing, and `+5_000 ms` cap.
*   `player/core/src/test/java/dev/jdtech/jellyfin/player/core/audio/AudioSyncOffsetSinkTest.kt`: Verify `getCurrentPositionUs` shifts strictly when `audioOffsetMs < 0L`, leaves `basePosUs` unchanged when `audioOffsetMs >= 0L`, preserves `AudioSink.CURRENT_POSITION_NOT_SET`, and flags `isPositiveDelayBypassedByPassthrough()` when `audioOffsetMs > 0L` on non-PCM formats.
*   **Phase 1 Checkpoint**: `./gradlew :settings:compileDebugKotlin :player:core:testDebugUnitTest`

---

### Phase 2: ViewModel State & Diagnostics Collector (`:player:local`)

#### 9. `player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlaybackStatsCollector.kt` (Create)

*   **Type / Scope**: Non-Compose Player Telemetry Collector & Snapshot Model (`:player:local`)
*   **Proposed Changes**:
    *   Define pure Kotlin `data class PlaybackStatsSnapshot(val deliveryMethod: String, val transcodeReasons: String?, val containerAndProtocol: String, val videoSummary: String, val videoDecoderName: String, val colorMetadataSummary: String, val audioSummary: String, val audioDecoderAndOutput: String, val frameDropSummary: String, val bufferHealthSummary: String, val syncOffsetSummary: String, val splitAvSummary: String?)`.
    *   Define `class PlaybackStatsCollector : AnalyticsListener` that records `@Volatile var videoDecoderName: String = "—"` in `onVideoDecoderInitialized` (reset in `onVideoDisabled`) and `@Volatile var audioDecoderName: String = "—"` in `onAudioDecoderInitialized` (reset in `onAudioDisabled`).
    *   Implement `fun buildSnapshot(player: Player, uiState: PlayerViewModel.UiState?, syncController: AudioSubtitleSyncController?, audioOutputModeLabel: String? = null, splitAvAdapter: PlayerSplitAvAdapter? = null): PlaybackStatsSnapshot` that reuses `uiState?.currentPlaybackInfoLabel` and `uiState?.currentTranscodeReason`, calls `ColorMetadataFormatter.formatColorMetadataSummary(player.videoFormat)`, reads `(player as? ExoPlayer)?.videoDecoderCounters`, formats `player.totalBufferedDuration`, formats `syncController` offsets, and inspects `splitAvAdapter?.splitAvActive?.value` and `splitAvAdapter?.renderedVideoTiming()`.
*   **Rationale**: Keeps `:player:local` free of any Compose compiler or `:core:ui` dependency while capturing one-shot decoder init telemetry with zero idle polling overhead.

#### 10. `player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt` (Modify)

*   **Type / Scope**: Shared Player ViewModel (`:player:local`)
*   **Proposed Changes**:
    *   Instantiate `val syncController = AudioSubtitleSyncController()` and `val statsCollector = PlaybackStatsCollector()`.
    *   Expose `val subtitleOffsetMs: StateFlow<Long> = syncController.subtitleOffsetMs` and `val audioOffsetMs: StateFlow<Long> = syncController.audioOffsetMs`, plus mutators `setSubtitleOffsetMs(Long)`, `adjustSubtitleOffsetMs(Long)`, `setAudioOffsetMs(Long)`, `adjustAudioOffsetMs(Long)`, and `resetSyncOffsets()`.
    *   In `replacePlayer(newPlayer: Player)` ([line 469](file:///home/ignacio/SpatialFin/player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt#L469)), remove `statsCollector` from the old `ExoPlayer` and register `(newPlayer as? ExoPlayer)?.addAnalyticsListener(statsCollector)` alongside `EventLogger`.
    *   Call `syncController.reset()` inside `initializePlayer`, `initializeLocalPlayer`, `initializeNetworkPlayer`, `initializeUriPlayer`, `initializePlayerForUniversal`, and `onMediaItemTransition(mediaItem, reason)` when `mediaItem?.mediaId` changes.
*   **Rationale**: Exposes reactive subtitle/audio offset state and `statsCollector` to all form factors and resets offsets on every new media item.

#### 11. Phase 2 Unit Tests (Create)

*   `player/local/src/test/java/dev/jdtech/jellyfin/player/local/presentation/PlaybackStatsCollectorTest.kt`: Verify `AnalyticsListener` decoder name capture, reuse of `UiState.currentPlaybackInfoLabel` / `currentTranscodeReason`, resolution/fps/color formatting, dropped-frame and buffer formatting, and sync offset formatting.
*   **Phase 2 Checkpoint**: `./gradlew :player:local:testDebugUnitTest`

---

### Phase 3: XR Passthrough Dimmer, Curved Mesh, Shared Diagnostics Overlay, Voice Actions, & XR Screens (`:player:session`, `:player:xr`)

#### 12. `player/session/src/main/java/dev/jdtech/jellyfin/player/session/voice/XrPlayerAction.kt` (Modify)

*   **Type / Scope**: Voice & Session Action Vocabulary (`:player:session`)
*   **Proposed Changes**: Add sealed interface variants:
    *   `data class SetPassthroughOpacity(val opacityFraction: Float) : XrPlayerAction`
    *   `data class SetCurvedScreen(val enabled: Boolean) : XrPlayerAction`
    *   `data object ToggleCurvedScreen : XrPlayerAction`
    *   `data class AdjustSubtitleOffset(val deltaMs: Long? = null, val offsetMs: Long? = null, val reset: Boolean = false) : XrPlayerAction`
    *   `data class AdjustAudioOffset(val deltaMs: Long? = null, val offsetMs: Long? = null, val reset: Boolean = false) : XrPlayerAction`
    *   `data class SetStatsForNerds(val enabled: Boolean) : XrPlayerAction`
    *   `data object ToggleStatsForNerds : XrPlayerAction`
*   **Rationale**: Extends typed voice and session dispatch to control passthrough dimming, curved screen geometry, A/V & subtitle sync offsets, and the diagnostics HUD.

#### 13. `player/session/src/main/java/dev/jdtech/jellyfin/player/session/voice/PlayerSessionController.kt` (Modify)

*   **Type / Scope**: Session Action Dispatcher (`:player:session`)
*   **Proposed Changes**: Add optional constructor callbacks `setPassthroughOpacity: ((Float) -> Unit)? = null`, `getPassthroughOpacity: (() -> Float)? = null`, `setCurvedScreenEnabled: ((Boolean) -> Unit)? = null`, `getCurvedScreenEnabled: (() -> Boolean)? = null`, `setStatsForNerdsEnabled: ((Boolean) -> Unit)? = null`, and `getStatsForNerdsEnabled: (() -> Boolean)? = null`, and handle all seven new `XrPlayerAction` variants inside `suspend fun dispatch(action: XrPlayerAction): String` (calling `viewModel.adjustSubtitleOffsetMs` / `viewModel.setSubtitleOffsetMs` / `viewModel.adjustAudioOffsetMs` / `viewModel.setAudioOffsetMs` directly on `viewModel`).
*   **Rationale**: Bridges parsed voice commands to the active player screen state and `PlayerViewModel`.

#### 14. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialCommandCoordinator.kt` and `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/GemmaCommandParser.kt` (Modify)

*   **Type / Scope**: Voice Command Grammar & On-Device LLM Tool Parser (`:player:xr`)
*   **Proposed Changes**:
    *   In `SpatialCommandCoordinator.kt`: Extend `parseDeterministicCommand(...)` (`extractPassthroughAction`) to parse percentage/dimming phrases (e.g., *"dim passthrough to 40 percent"*, *"passthrough 50%"*) into `XrPlayerAction.SetPassthroughOpacity`, add deterministic extractors for curved screen (*"curve the screen"*, *"flat screen"*), subtitle/audio offset (*"delay subtitles by 500 ms"*, *"advance audio by 200 ms"*, *"reset subtitle sync"*), and stats HUD (*"show stats for nerds"*, *"hide playback stats"*), and update `buildModelPrompt` and `mapModelAction`.
    *   In `GemmaCommandParser.kt`: Add the new action enum names and `delta_ms` / `offset_ms` / `opacity` properties to `INTERPRET_COMMAND_TOOL`, `mapToolCall(...)`, and `mapModelAction(...)`.
*   **Rationale**: Enables hands-free voice control of all four new capabilities.

#### 15. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrPassthroughController.kt` (Create)

*   **Type / Scope**: XR Passthrough Opacity Controller (`:player:xr`)
*   **Proposed Changes**: Implement pure helpers:
    *   `clampPassthroughOpacity(opacity: Float): Float` (clamping `[0.0f, 1.0f]`, mapping `NaN` to `0.0f`)
    *   `resolveEffectiveOpacity(preferredOpacity: Float, immersiveProjection: Boolean, wallPinActive: Boolean): Float` (returning `0.0f` when `immersiveProjection == true`, `1.0f` when `wallPinActive == true && preferredOpacity <= 0.0f` or `preferredOpacity.coerceAtLeast(0.85f)` when `wallPinActive == true`, and `clampPassthroughOpacity(preferredOpacity)` otherwise)
    *   `nextPassthroughStep(currentOpacity: Float): Float` (stepping `0.0f -> 0.35f -> 0.70f -> 1.0f -> 0.0f`)
    *   `formatPassthroughPercent(opacity: Float): String`
    *   SceneCore applicators `applyPassthroughOpacity(session: Session, opacity: Float)` and `clearPassthroughOpacity(session: Session)`.
*   **Rationale**: Encapsulates `SpatialEnvironment.preferredPassthroughOpacity`, precedence rules, and cleanup in a unit-testable helper.

#### 16. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/CurvedSurfaceMeshBuilder.kt` (Create)

*   **Type / Scope**: XR Cylindrical Surface Mesh Generator (`:player:xr`)
*   **Proposed Changes**: Implement:
    *   `shouldUseCurvedScreen(curvedEnabled: Boolean, projection: String, wallPinActive: Boolean): Boolean = curvedEnabled && !PlayerTrackUtils.isImmersiveProjection(projection) && !wallPinActive`
    *   `computeCylinderVertex(columnIndex: Int, segments: Int, widthMeters: Float, heightMeters: Float, arcRadians: Float): FloatArray` where for column $i \in [0, N]$ ($N = 48$, $\theta_{\text{arc}} = 46^\circ = 0.802851\text{ rad}$, $R = W / \theta_{\text{arc}}$), $t_i = i / N$, $\alpha_i = (t_i - 0.5)\theta_{\text{arc}}$, $x_i = R\sin(\alpha_i)$, $y_{\text{top}} = +H/2$, $y_{\text{bottom}} = -H/2$, $z_i = R(1 - \cos(\alpha_i))$, and normalized UVs $u_i = t_i \in [0.0, 1.0], v_{\text{top}} = 0.0, v_{\text{bottom}} = 1.0$.
    *   `buildCurvedCinemaMesh(widthMeters: Float, heightMeters: Float, segments: Int = 48, arcDegrees: Float = 46f): SurfaceEntity.Shape.CustomMesh` allocating direct native-order `ByteBuffer` `FloatBuffer` (`positions`, `texCoords`) and `IntBuffer` (`indices`, `288` indices for `96` triangles) for `SurfaceEntity.Shape.TriangleMesh` and returning `SurfaceEntity.Shape.CustomMesh(leftEye = mesh, rightEye = mesh, drawMode = SurfaceEntity.DrawMode.TRIANGLES)`.
*   **Rationale**: Generates the 48-segment cylindrical `CustomMesh` while delegating stereo eye-texture subdivision (`mono`, `sbs`, `top_bottom`, `multiview`) to `SurfaceEntity.stereoMode`.

#### 17. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerTrackUtils.kt` (Modify)

*   **Type / Scope**: XR Track & Projection Shape Mapper (`:player:xr`)
*   **Proposed Changes**: Update `mapProjectionShape(projection: String, radiusMeters: Float, quad: FloatSize2d, curvedEnabled: Boolean = false, wallPinActive: Boolean = false): SurfaceEntity.Shape` to return `runCatching { CurvedSurfaceMeshBuilder.buildCurvedCinemaMesh(quad.width, quad.height) }.getOrElse { SurfaceEntity.Shape.Quad(quad) }` when `CurvedSurfaceMeshBuilder.shouldUseCurvedScreen(curvedEnabled, projection, wallPinActive)` is `true`. Also add helper `applySurfaceShapeWithFallback(entity: SurfaceEntity, desiredShape: SurfaceEntity.Shape, fallbackQuad: FloatSize2d)` that wraps `entity.shape = desiredShape` in `runCatching` and falls back to `entity.shape = SurfaceEntity.Shape.Quad(fallbackQuad)` if the runtime rejects `CustomMesh`.
*   **Rationale**: Integrates curved mesh selection with an automatic flat `Quad` safety fallback for older firmware runtimes.

#### 18. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlaybackStatsOverlay.kt` (Create)

*   **Type / Scope**: Shared Compose Diagnostics HUD (`:player:xr`, shared with `:player:beam` and `:player:tv`)
*   **Proposed Changes**:
    *   Define `@Composable fun rememberPlaybackStatsSnapshot(player: Player, statsCollector: PlaybackStatsCollector, uiState: PlayerViewModel.UiState?, syncController: AudioSubtitleSyncController?, enabled: Boolean, audioOutputModeLabel: String? = null, splitAvAdapter: PlayerSplitAvAdapter? = null): PlaybackStatsSnapshot?` that runs a `LaunchedEffect(enabled, player)` `1 Hz` (`delay(1000L)`) polling loop only when `enabled == true`.
    *   Define `@Composable fun PlaybackStatsOverlay(snapshot: PlaybackStatsSnapshot, onDismiss: () -> Unit, modifier: Modifier = Modifier)` rendering a compact semi-transparent (`Color.Black.copy(alpha = 0.78f)`) rounded card with `FontFamily.Monospace` metric rows and a close (`×`) button.
*   **Rationale**: Places the Compose HUD in `:player:xr` so `:player:xr`, `:player:beam`, and `:player:tv` share a single implementation without modifying any `build.gradle.kts` files.

#### 19. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/LibassRendererState.kt` (Modify)

*   **Type / Scope**: XR Subtitle Render Loop (`:player:xr`)
*   **Proposed Changes**: Add `subtitleOffsetMs: Long = 0L` parameter to `rememberLibassRenderer(...)`, capture `val currentSubtitleOffsetMs by rememberUpdatedState(subtitleOffsetMs)`, and inside the 60 fps `LaunchedEffect(state.useLibass)` `while (true)` loop compute `val effectivePos = AudioSubtitleSyncController.effectiveLibassPositionMs(player.currentPosition, currentSubtitleOffsetMs)` before calling `libassRenderer?.renderFrame(effectivePos)`.
*   **Rationale**: Shifts `libass` ASS/SSA subtitle rendering in real time without stale closure capture.

#### 20. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerSecondaryControls.kt` and `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerDialogs.kt` (Modify)

*   **Type / Scope**: XR Orbiter Controls & Glass Dialogs (`:player:xr`)
*   **Proposed Changes**:
    *   In `PlayerSecondaryControls.kt`: Update `StageControlsOrbiter` to display the current passthrough opacity percentage (`formatPassthroughPercent`), cycle passthrough opacity on tap (`XrPassthroughController.nextPassthroughStep`), and add a Curved Screen toggle button using `CoreR.drawable.ic_curved_screen` (with tooltip `"Curved screen"` / `"Flat screen"`, disabled when `wallPinActive || isImmersiveProjection`). Add a `"Sync Offset"` button (`Icons.Rounded.Tune` / tooltip `"A/V & subtitle sync"`) to `TrackOptionsOrbiter`.
    *   In `PlayerDialogs.kt`: Add `@Composable internal fun SyncOffsetDialogContent(...)` with `±250 ms`, `±50 ms`, and `Reset (0 ms)` controls for Subtitle Offset (`[-10_000L, +10_000L]`), Audio Delay (`[-5_000L, +5_000L]`, including the inline bitstream-passthrough notice when `isPositiveDelayBypassedByPassthrough == true`), and Passthrough Opacity (`0%`, `25%`, `50%`, `75%`, `100%` preset chips + continuous slider). Also add a `"Curved screen"` option in `ProjectionDialogContent` and a `"Stats for Nerds"` toggle row inside `QualityDialogContent`.
*   **Rationale**: Surfaces spatial controls for passthrough dimming, screen curvature, A/V & subtitle synchronization, and playback diagnostics in XR Full Space.

#### 21. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt` and `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt` (Modify)

*   **Type / Scope**: XR Full Space Player Composables (`:player:xr`)
*   **Proposed Changes**:
    *   In `SpatialPlayerScreen.kt`:
        1. Replace `passthroughOverrideEnabled` with `var passthroughOpacity by remember { mutableFloatStateOf(appPreferences.getValue(appPreferences.xrPlayerPassthroughOpacity)) }`, compute `val effectivePassthroughOpacity = XrPassthroughController.resolveEffectiveOpacity(passthroughOpacity, immersiveProjection, wallPinActive)` and `val passthroughEnabled = effectivePassthroughOpacity > 0.0f`, and apply via `XrPassthroughController.applyPassthroughOpacity(session, effectivePassthroughOpacity)` (clearing in `DisposableEffect` onDispose).
        2. Read `var curvedScreenEnabled by remember { mutableStateOf(appPreferences.getValue(appPreferences.xrPlayerCurvedScreen)) }`, add `curvedScreenEnabled` and `wallPinActive` to the geometry `LaunchedEffect` keys at [line 1979](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt#L1979), and replace the hardcoded `entity.shape = SurfaceEntity.Shape.Quad(FloatSize2d(videoWidth, videoHeight))` at [line 2041](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt#L2041) with `applySurfaceShapeWithFallback(entity, mapProjectionShape(currentProjection, 1.0f, FloatSize2d(videoWidth, videoHeight), curvedEnabled = curvedScreenEnabled, wallPinActive = wallPinActive), FloatSize2d(videoWidth, videoHeight))`.
        3. Collect `viewModel.subtitleOffsetMs` and `viewModel.audioOffsetMs`, pass `subtitleOffsetMs` to `rememberLibassRenderer`, wire `SyncOffsetDialogContent` (`activeDialog == "sync"`), `PlaybackStatsOverlay`, and the new `PlayerSessionController` voice callbacks.
    *   In `XrFCastInboundPlayerScreen.kt` (theater-only; no `PlayerViewModel` or `PlayerSessionController`):
        1. Replace the binary `LaunchedEffect(playing)` environment toggle ([lines 237–246](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt#L237-L246)) with `passthroughOpacity` initialized from `preferences.getValue(preferences.xrPlayerPassthroughOpacity)` and applied via `XrPassthroughController.applyPassthroughOpacity(session, effectivePassthroughOpacity)`.
        2. Read `curvedScreenEnabled` from `preferences.getValue(preferences.xrPlayerCurvedScreen)`, add `curvedScreenEnabled` to the geometry `LaunchedEffect` keys at [line 364](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt#L364) and [line 508](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerScreen.kt#L508), and replace the hardcoded `SurfaceEntity.Shape.Quad` assignments at lines 354, 405, and 518 with `applySurfaceShapeWithFallback(entity, mapProjectionShape(currentProjection, ...), ...)`.
*   **Rationale**: Connects passthrough dimming and curved screen geometry to both XR Full Space screens while respecting `XrFCastInboundPlayerScreen`'s theater-only boundary.

#### 22. `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrPlayerActivity.kt`, `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerActivity.kt`, and `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/MultitaskPlayerActivity.kt` (Modify)

*   **Type / Scope**: XR Player Activities (`:player:xr`)
*   **Proposed Changes**:
    *   In `XrPlayerActivity.kt`: Pass `syncController = viewModel.syncController` to `AudioPassthroughSinks.buildOverride(...)` and `subtitleOffsetMsProvider = viewModel.syncController::currentSubtitleOffsetMs` to `FallbackTextRenderer(...)`.
    *   In `XrFCastInboundPlayerActivity.kt`: Instantiate a local `private val syncController = AudioSubtitleSyncController()` (calling `syncController.reset()` in `applyRequest(...)`), pass `syncController = syncController` to `AudioPassthroughSinks.buildOverride(...)` and `FallbackTextRenderer(...)` in `buildPlayerWithLibass()`, and apply `AudioSubtitleSyncController.effectiveLibassPositionMs(exo.currentPosition, syncController.currentSubtitleOffsetMs())` inside `subtitleTicker` ([line 274](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrFCastInboundPlayerActivity.kt#L274)).
    *   In `MultitaskPlayerActivity.kt`: Remove the outer `if (passthroughMode != AudioPassthroughMode.AUTO)` guard in `buildAudioSink` ([line 112](file:///home/ignacio/SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/MultitaskPlayerActivity.kt#L112)) and always call `AudioPassthroughSinks.buildOverride(..., syncController = viewModel.syncController)`.
*   **Rationale**: Ensures all XR Full Space and Home Space activities use `AudioSyncOffsetSink`, `PcmAudioDelayProcessor`, and `FallbackTextRenderer` offset shifting.

#### 23. Phase 3 Unit Tests (Create)

*   `player/xr/src/test/java/dev/jdtech/jellyfin/player/xr/XrPassthroughControllerTest.kt`: Verify opacity clamping (`NaN`, `< 0f`, `> 1f`), `resolveEffectiveOpacity` precedence (`immersiveProjection` -> `0f`, `wallPinActive` -> visible wall), step cycling (`0.0f -> 0.35f -> 0.70f -> 1.0f -> 0.0f`), and percentage formatting.
*   `player/xr/src/test/java/dev/jdtech/jellyfin/player/xr/CurvedSurfaceMeshBuilderTest.kt`: Verify `shouldUseCurvedScreen` eligibility (disabled when wall-pinned or in `180°`/`360°`), cylinder vertex coordinates ($z = 0$ at center, positive sagitta at edges), index winding count (`288` indices for `48` segments), and normalized `[0.0, 1.0]` UV coordinates.
*   **Phase 3 Checkpoint**: `./gradlew :player:session:testDebugUnitTest :player:xr:testLibreDebugUnitTest`

---

### Phase 4: Beam & TV Integration & Documentation (`:player:beam`, `:player:tv`, Docs)

#### 24. `player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/BeamPlayerActivity.kt` and `player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/voice/BeamCommandCoordinator.kt` (Modify)

*   **Type / Scope**: Phone / Tablet / Foldable Player Activity, Screen, & Voice Coordinator (`:player:beam`)
*   **Proposed Changes**:
    *   In `BeamPlayerActivity.kt`: Pass `syncController = viewModel.syncController` to `AudioPassthroughSinks.buildOverride(...)` and `subtitleOffsetMsProvider = viewModel.syncController::currentSubtitleOffsetMs` to `FallbackTextRenderer(...)` inside `replacePlayerForBeamSubtitles`. Capture `val currentSubtitleOffsetMs by rememberUpdatedState(subtitleOffsetMs)` in the Beam 60 fps `libass` render loop ([line 1434](file:///home/ignacio/SpatialFin/player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/BeamPlayerActivity.kt#L1434)) and pass `AudioSubtitleSyncController.effectiveLibassPositionMs(player.currentPosition, currentSubtitleOffsetMs)` to `libassRenderer.renderFrame(...)`. Add `"Sync Offset (A/V & Subtitles)"` and `"Stats for Nerds"` actions to the Beam player settings sheet, add the Beam `SyncOffsetDialog`, and render `PlaybackStatsOverlay` in the top-start corner of `BeamPlayerScreen` when enabled.
    *   In `BeamCommandCoordinator.kt`: Add deterministic voice parsing for subtitle/audio offset adjustments and `"show/hide stats for nerds"`.
*   **Rationale**: Delivers live subtitle/audio synchronization, voice sync control, and real-time playback diagnostics on phones, tablets, and foldables.

#### 25. `player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt` (Modify)

*   **Type / Scope**: Android TV Leanback Player Activity & Screen (`:player:tv`)
*   **Proposed Changes**: Remove the outer `if (passthroughMode != AudioPassthroughMode.AUTO)` guard in `buildAudioSink` ([line 285](file:///home/ignacio/SpatialFin/player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt#L285)) and always pass `syncController = viewModel.syncController` to `AudioPassthroughSinks.buildOverride(...)`, and pass `subtitleOffsetMsProvider = viewModel.syncController::currentSubtitleOffsetMs` to `FallbackTextRenderer(...)` in `replacePlayerForTvSubtitles`. Capture `val currentSubtitleOffsetMs by rememberUpdatedState(subtitleOffsetMs)` in the TV 60 fps `libass` render loop ([line 1043](file:///home/ignacio/SpatialFin/player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt#L1043)) and pass `AudioSubtitleSyncController.effectiveLibassPositionMs(player.currentPosition, currentSubtitleOffsetMs)` to `libassRenderer.renderFrame(...)`. Add D-pad focusable `"Sync Offset"` and `"Stats for Nerds"` options to the TV player settings dialog, add the D-pad navigable TV `SyncOffsetDialog`, and render `PlaybackStatsOverlay` in `TvPlayerScreen` when enabled.
*   **Rationale**: Delivers live subtitle/audio synchronization and real-time playback diagnostics on Android TV with full D-pad remote navigation.

#### 26. `GEMINI.md`, `README.md`, and `docs/index.html` (Modify)

*   **Type / Scope**: Project Technical Reference & User-Facing Documentation
*   **Proposed Changes**:
    *   Update [`GEMINI.md` line 415](file:///home/ignacio/SpatialFin/GEMINI.md#L415) in place with an `<!-- updated 2026-11-10: ... -->` annotation reflecting `androidx.xr:1.0.0-rc01` `SurfaceEntity.Shape.CustomMesh` support with the `Quad` `runCatching` fallback guard, and document `XrPassthroughController`, `CurvedSurfaceMeshBuilder`, `AudioSubtitleSyncController` / `PcmAudioDelayProcessor` / `AudioSyncOffsetSink`, and `PlaybackStatsCollector` / `PlaybackStatsOverlay` under `Important Entry Points` and `Subsystem Cheat Sheet`.
    *   Update `README.md` (`Features`) and `docs/index.html` with graduated XR passthrough dimming, curved XR cinema screen, live A/V & subtitle synchronization, and the "Stats for Nerds" diagnostics HUD.
*   **Rationale**: Required by the `GEMINI.md` Self-Update Mandate whenever user-facing playback capabilities are added or prior assumptions are updated.

### Verification Plan

*   **Phase 1 Checkpoint**: `./gradlew :settings:compileDebugKotlin :player:core:testDebugUnitTest`
*   **Phase 2 Checkpoint**: `./gradlew :player:local:testDebugUnitTest`
*   **Phase 3 Checkpoint**: `./gradlew :player:session:testDebugUnitTest :player:xr:testLibreDebugUnitTest`
*   **Phase 4 & Full Build Checkpoint**: `./gradlew :player:beam:compileLibreDebugKotlin :player:tv:compileLibreDebugKotlin :app:unified:assembleLibreDebug`
*   **Deprecation Baseline Check**: `./.github/scripts/check-deprecations.sh`

--------------------------------------------------------------------------------

*Drafted with EGM*
