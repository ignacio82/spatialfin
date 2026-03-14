# GEMINI.md - SpatialFin Technical Reference

This document provides technical context and architectural guidelines for developing the spatialized interface for Findroid on Android XR devices (e.g., Samsung Galaxy XR). Use this as a system prompt to ensure consistency with Jetpack XR SDK patterns.

## Official Documentation Links
- **Jetpack XR SDK Hub:** https://developer.android.com/develop/xr/jetpack-xr-sdk
- **SceneCore (Entities):** https://developer.android.com/develop/xr/jetpack-xr-sdk/work-with-entities
- **Compose for XR:** https://developer.android.com/develop/xr/jetpack-xr-sdk/ui-compose
- **Spatial Video:** https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-video
- **Spatial Audio:** https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-audio

## Core Architectural Principles

### Scene Graph & ECS
- **Scene Graph Hierarchy:** Android XR uses a hierarchical scene graph to organize 3D entities. The `ActivitySpace` is the top-level entity, representing a 3D coordinate system (right-handed, units in meters) that aligns with the user's real world. Positioning should be relative to other entities.
- **Entity-Component System (ECS):** The SDK follows "composition over inheritance." Expand entity behavior by attaching components (e.g., `MovableComponent`) rather than subclassing.
- **Subspace Partitioning:** A `Subspace` is a dedicated partition of 3D space within an app. Content inside is rendered when spatialization is enabled; otherwise, it falls back to 2D.

### UI Best Practices & Ergonomics
- **Material 3 for XR:** Standard Material 3 components (NavigationRail, NavigationBar, TopAppBar) automatically adapt to **XR Orbiters** in spatial environments. They float in 3D space around the main panel rather than being pinned inside it.
- **Adaptive Layouts:** Multi-pane scaffolds (ListDetailPaneScaffold, SupportingPaneScaffold) map panes 1:1 to individual **XR spatial panels**, allowing for a decentralized, immersive layout.
- **IMAX Scale:** For a cinematic experience, use large `SpatialPanel` dimensions (e.g., 1400dp width). Large panels in Android XR natively to fill the user's field of view.
- **Depth and Focus:** Use `SpatialDialog` or `SpatialPopup` which automatically pushes the background panel back by **125dp**, creating visual hierarchy.
- **User Agency:** Provide visual cues for interactivity. Use `MovableComponent` and `ResizableComponent` to allow users to customize their workspace.

### Spatial Panel Placement (from Android XR Design Guide)
- **Spawn distance:** Place panel centers **1.75 m** from the user's line of sight. This is the system default and the ergonomic sweet spot.
- **Vertical offset:** Place the panel's vertical center **5° below eye level** — users naturally look slightly downward.
- **Field of view:** Keep interactive content within the **center 41°** of the user's FOV to avoid excessive head movement.
- **Depth limits:** Panels must stay between **0.75 m** (minimum) and **5 m** (maximum) from the user to avoid conflicts with system UI. The 5 m limit is also the comfortable maximum for large video surfaces.
- **Orbiter offset:** Use **20 dp** as the visual distance between an Orbiter and its parent panel (SDK default).
- **Orbiter count:** Use orbiters sparingly — too many competing spatial elements causes "content fatigue." Stick to one orbiter per panel unless there's a strong reason for more.

### SpatialPanel Gesture Blocking — Critical Pitfall
- **Empty panels still intercept raycasts.** A `SpatialPanel` with no visible content (e.g., hidden via `AnimatedVisibility`) still exists in the scene and will block pointer/grab events for any entity behind it. This means: if an empty panel is placed between the user and a `MovableComponent` entity, the user cannot grab and move that entity.
- **Fix:** Make the `SceneCoreEntity`/`SpatialPanel` conditional in the Subspace composition — only add it when it needs to be visible. `AnimatedVisibility` alone is not sufficient; the panel container itself must be removed from composition.
- **Scrollable content vs. MovableComponent:** If a panel with scrollable content lives inside an entity that has a `MovableComponent`, scroll gestures may be intercepted and interpreted as "move" commands. Workaround: use a separate `GroupEntity` (without `MovableComponent`) for the scrollable panel, positioned so it does not sit between the user and the movable entity.

### Spatial Hierarchy & Parenting
To ensure low-latency movement of complex UIs (e.g., a video player with controls and subtitles), follow the SceneCore Entity Hierarchy.
- **Parenting:** Attach all related components (Surfaces, Panels, Orbiters) to a single "Root" Entity (typically a `PanelEntity`, `GroupEntity`, or `ContentlessEntity`).
- **Movement:** Apply the `MovableComponent` only to the Root. This allows the user to move the entire player unit synchronously without jitter.
- **Relative Positioning:** Use `entity.setParent(parent)` or the Compose `SceneCoreEntity` wrapper to lock UI elements (like subtitles or controls) to fixed relative offsets from the video.

### Rendering & Media Strategy
- **Video Surfaces:** Use `SurfaceEntity` for standard and stereoscopic (3D) video playback. Integrate with Media3 (ExoPlayer) to map the output to the XR surface.
- **Spatial Video Shapes:** Use `Quad` for flat video, `Hemisphere` for 180° immersive video, and `Sphere` for 360° video. Supports Side-by-Side (SBS) and MV-HEVC.
- **DRM Protection:** For DRM-protected content, ensure `surfaceProtection = SurfaceProtection.Protected` to render encrypted content into secure graphics buffers.
- **2D Overlays:** Use `SpatialPanel` for high-interactivity 2D interfaces (Settings, Library). It's a 3D container that can be moved and resized.
- **Contextual UI:** Use `Orbiter` for toolbars or secondary controls that should float around a main panel.

### Spatial Audio
- **Positional Audio:** Sound emanates from a specific point in 3D space. Best for 3D models or sound effects.
- **Stereo/Surround:** Automatically spatialized relative to the app's main panel.
- **Ambisonics:** Use for "skybox" audio or background environments to create a full-spherical sound field.
- **Engines:** `ExoPlayer` is recommended for standard media and surround sound, while `SoundPool` is preferred for low-latency sound effects.

### Subtitle Handling
- **Text-Based (SRT/VTT):** Render in a separate `SpatialPanel` at a comfortable depth for readability.
- **Advanced Rendering (ASS/SSA):** Use the integrated `libass` JNI renderer for pixel-perfect anime subtitles. This renderer handles complex typesetting, drawings, and animations that standard Media3 `SubtitleView` cannot.
- **Raycast Passthrough (Crucial):** Always conditionally compose the subtitle `SpatialPanel`. If no subtitles are visible (`hasContent == false`), remove the panel from the `Subspace` entirely to allow users to interact with the video or controls behind it.
- **Accessibility:** Bridge app-level subtitle preferences to `CaptionStyleCompat` when using Media3's `SubtitleView` to ensure user styling is respected over system defaults. Proactively re-apply styles in the `update` block of `AndroidView` to prevent OS-level overrides when system captions are disabled.

## Stability & Performance Mandates

### JNI & Native Safety
- **Guarded Initialization:** Native methods MUST NOT be called before `System.loadLibrary` is confirmed. Implement an `isAvailable()` check and invoke it within the class `init()` to prevent `UnsatisfiedLinkError` during early activity lifecycles.
- **Thread Affinity:** libass is not thread-safe. All native calls (init, render, destroy) MUST be dispatched to a dedicated `HandlerThread` to prevent race conditions and UI stutters.

### High-Fidelity Subtitle Rendering
- **8K Target Resolution:** XR displays have extremely high angular density. Render subtitle bitmaps at up to 2.0x density (e.g., `coerceIn(1280, 7680)`) to prevent "mushed" text and scaling artifacts.
- **Aspect Ratio Alignment:** Always call `ass_set_storage_size` in the native renderer using the original video dimensions (`player.videoSize`). This is critical for aligning complex anime typesetting (signs, overlays) with the video frame.
- **Complex Shaping:** Force `ASS_SHAPING_COMPLEX` (HarfBuzz) and `ASS_HINTING_LIGHT` in the native renderer to ensure sub-pixel kerning and legible character spacing.
- **Compose Filtering:** Use `BitmapPainter(filterQuality = FilterQuality.High)` when rendering the native bitmap in a `SpatialPanel` to ensure smooth edges in 3D perspective.

### Spatial Dialogs & UI
- **No Nested Dialogs:** Do not place 2D `AlertDialog` or `Popup` components inside a `SpatialDialog`. This causes z-fighting and input capture failure. Use a high-fidelity `Surface` with `RoundedCornerShape(32.dp)` as the root content for any `SpatialDialog`.
- **Constraint Management:** `LazyColumn` or other scrollable lists inside a `SpatialDialog` will crash with "infinite height" errors unless the dialog container has a `Modifier.heightIn(max = ...)` constraint.
- **Experimental Overrides:** Avoid global `EnableXrComponentOverrides` if using `NavigationRail` or standard Material 3 items, as it can cause `NoSuchFieldError` due to binary incompatibilities. Prefer surgical application to specific containers.

### Mode Transitions
- **FSM (Full Space Mode) Priority:** SpatialFin is designed as an immersive XR experience. The app should proactively request Full Space Mode (`xrSession.scene.requestFullSpaceMode()`) upon launch to occupy the full 3D volume.
- **HSM (Home Space Mode):** Used only as a fallback when spatial capabilities are unavailable.
- **Logic:** Check `SpatialCapability.SPATIAL_3D_CONTENT` within the XR session before triggering FSM-only features.

## Component Cheat Sheet
| Component | Description |
| :--- | :--- |
| **Subspace** | A container enabling 3D layouts and spatialized components within Compose. |
| **SpatialPanel** | A 3D container for 2D content (video player, settings) in XR. |
| **Orbiter** | Floating UI element anchored to a panel/entity, used for navigation/toolbars. |
| **SurfaceEntity** | Low-level SceneCore entity for rendering video/images with advanced control. |
| **SpatialExternalSurface** | Compose wrapper for `SurfaceEntity` managing `Surface` for Media3. |
| **SceneCoreEntity** | Subspace composable bridging 3D models (GLTF) or SceneCore entities into Compose. |
| **MovableComponent** | Makes an entity draggable by the user in 3D space. |
| **ResizableComponent** | Adds handles/logic to allow users to resize spatial entities. |
| **SpatialDialog** | Spatialized dialog pushing parent panel back to create focus. |

## Prompting Guidelines for Gemini
When requesting new features or fixes, use these keywords to apply these patterns:
- **"Spatialize the player"** -> Focus on `Subspace`, `SpatialPanel`, and `Orbiter`.
- **"IMAX experience"** -> Use Full Space Mode and large (1400dp+), curved Spatial Panels.
- **"Hierarchy movement"** -> Use SceneCore parenting for the video player root.
- **"Surface-level subtitles"** -> Shift rendering logic from Compose panels to the playback surface.
- **"Bridge preferences"** -> Map `AppPreferences` to `CaptionStyleCompat` for XR views.
- **"Immersive layout"** -> Apply `825dp` curve radius and `125dp` depth offsets.
- **"Enable XR audio"** -> Incorporate spatial audio techniques (Positional, Stereo/Surround, or Ambisonics).
