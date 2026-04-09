# GEMINI.md - SpatialFin Technical Reference

This document provides technical context and architectural guidelines for developing SpatialFin for Android XR devices (e.g., Samsung Galaxy XR). Use this as a system prompt to ensure consistency with Jetpack XR SDK patterns.

## Repository Navigation

When working in this repo, read files in this order unless the task is narrowly scoped:

1. `README.md`
2. `GEMINI.md`
3. `app/xr/build.gradle.kts`
4. `player/xr/build.gradle.kts`
5. The relevant module under `app/`, `player/`, `core/`, `data/`, `modes/`, `settings/`, or `setup/`

Prefer source files and build files over generated artifacts or store assets.

### Usually Ignore For Reasoning

These paths are usually not useful for code understanding and should be skipped unless the task explicitly targets them:

- `.git/`
- `**/build/`
- `build_native_work/`
- `release/`
- `fastlane/metadata/android/en-US/images/`
- `androidx/`

### Generated vs Intentional Binary Files

- `build_native_work/` is reproducible scratch/build output for the native subtitle toolchain and should not be treated as source of truth.
- `player/xr/src/main/jniLibs/` contains intentionally checked-in prebuilt `libass_jni.so` files so a fresh clone can still build without requiring native setup.
- `glb/spatialfin.glb` and `player/xr/src/main/assets/models/spatialfin.glb` are product assets, not architecture-defining code.

### Important Entry Points

- `app/xr/` for app startup and XR app wiring
- `player/xr/` for the immersive player, spatial UI, subtitles, and voice UX
- `player/session/` for player actions, orchestration, and voice command execution
- `data/` for Jellyfin API access, repositories, and persistence
- `settings/` and `setup/` for configuration and onboarding flows

### Voice / AI Entry Points

When the task touches voice, on-device AI, recommendations, or assistant UX, start here before chasing other files:

- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialVoiceService.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialCommandCoordinator.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/GemmaCommandParser.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SmartChatEngine.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/MediaSkillRegistry.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/RecommendationPlanner.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt`
- `app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt`
- `player/session/src/main/java/dev/jdtech/jellyfin/player/session/voice/PlayerSessionController.kt`
- `settings/src/main/java/dev/jdtech/jellyfin/settings/voice/VoiceTelemetryStore.kt`

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
- **ASS Packet Normalization:** Embedded ASS/SSA subtitle samples from Media3/MKV may arrive as full `Dialogue:` lines with embedded `Start,End` timestamps. When using `ass_process_chunk`, strip the `Dialogue:` prefix and those timestamp fields before passing the payload to libass; the JNI call already receives timing separately via `startMs` and `durationMs`. If you send the full line through unchanged, libass may render only the first event or otherwise behave as if timing is broken.
- **Extraction Path:** Keep `experimentalParseSubtitlesDuringExtraction(false)` for libass playback. If subtitle extraction/transcoding is left enabled, Media3 converts ASS into `application/x-media3-cues`, which bypasses raw ASS delivery and makes libass unusable.
- **Panel-to-Video Geometry:** The subtitle panel must cover the same projected video frame as the playback surface. Do not independently clamp the subtitle panel width/height after projecting the video size to subtitle depth, or ASS signs / positioned subtitles will drift out of place even if the bitmap render itself is correct.

### On-Device AI (LiteRT Gemma)
- **Inference Engine:** Uses `LiteRT LM` (formerly MediaPipe LLM Inference) for low-latency, private, on-device chat.
- **Hardware Acceleration:** Uses a tiered initialization strategy: **NPU (Tensor)** > **GPU (Adreno/Mali)** > **CPU**. 
- **CPU Restriction:** CPU-only inference is extremely slow. Gemma is **disabled by default** on CPU-only devices. Users must explicitly enable it in settings and are warned about high latency.
- **Lifecycle Management:** The `LlmChatModelHelper` handles the `Engine` and `Conversation` lifecycle. You MUST call `close()` in the `destroy()` block of any engine/viewmodel using it to prevent memory leaks and hardware lockup.
- **Model Provisioning:** The app auto-downloads the Gemma 4 `.task` model from HuggingFace to `context.filesDir/models/gemma-4.task` during the first initialization of `SmartChatEngine`.
- **Multimodal Context:** The engine supports `visualContext: Bitmap`. During media playback, this allows the AI to "see" the current frame (or a low-res snapshot) to answer questions about specific visual elements in a scene.
- **Prompt Composition:** Prompts are dynamically built by `SmartChatEngine` using `PlayerStateSnapshot` (metadata), `SubtitleContext` (recent dialogue), and `storySoFarContext`.
- **Stateless Inference Per Request:** Command parsing and chat must not share a long-lived conversation. `LlmChatModelHelper.runInference(...)` now creates a fresh conversation per request and uses explicit profiles (`COMMAND` vs `CHAT`) to reduce partial JSON and conversational carry-over.
- **Backend Failure Caching:** If LiteRT NPU initialization fails with `TF_LITE_AUX not found in the model`, the helper caches that as unsupported for the device/model and prefers the last successful backend on later launches. Do not remove this unless you also replace it with an equivalent per-device backend selection strategy.

## Voice & AI Architecture

### Current Request Pipeline

The voice path is intentionally split into separate layers. Keep new work within one of these layers instead of stuffing more logic into prompts:

1. `SpatialVoiceService`
   - Speech recognition lifecycle
   - Partial transcript / listening / processing state
   - Soft-error retry for recognizer errors `5` and `7`
2. `SpatialCommandCoordinator`
   - Keyword and replay-library matches
   - Screen-aware command parsing (`HOME` vs `PLAYER`)
   - Gemini/Gemma JSON command parsing with strict retry
3. `XrPlayerAction`
   - Typed action boundary between parsing and execution
4. `PlayerSessionController`
   - Executes playback/search/selection actions
   - Owns pending-selection state for dialog follow-ups like “play the first one”
5. `SmartChatEngine`
   - Handles `ChatQuery`
   - Builds prompt context only after a skill has been selected
6. `MediaSkillRegistry`
   - Selects a built-in skill
   - Validates extracted input
   - Decides whether to answer directly, use model phrasing, or fall back

### Built-In Media Skills

The current built-in skills live in `MediaSkillRegistry` and should be extended there, not through ad hoc prompt branches:

- `PLAYBACK_CONTROL`
- `WATCH_RECOMMENDER`
- `LIBRARY_SEARCH`
- `RECAP`
- `DIALOGUE_EXPLAINER`
- `METADATA_QA`
- `CONTINUE_WATCHING`
- `MOOD_SURPRISE`
- `EXTERNAL_KNOWLEDGE`
- `GENERAL_CHAT`

When adding a new assistant capability:

- First ask whether it is a parser concern, a typed action concern, a skill concern, or a UI concern.
- If it can be answered from structured local data, prefer a direct skill answer over a model call.
- If it needs model phrasing, still select a skill first and pass explicit task instructions into `SmartChatEngine`.

### Recommendation Flow

Recommendation handling is no longer “generic chat with a long prompt.” The intended flow is:

1. `MediaSkillRegistry` selects `WATCH_RECOMMENDER` or `MOOD_SURPRISE`
2. `RecommendationPlanner` extracts filters and follow-up context
3. Candidate items are pulled from:
   - `repository.getSuggestions()`
   - `repository.getResumeItems()`
   - `repository.getFavoriteItems()`
   - prior `RecommendationContext`
4. The planner ranks candidates using:
   - media type filters
   - runtime / “under N minutes”
   - comedy / new / English audio
   - anime avoidance
   - comfort / late-night / surprise heuristics
   - overlap with current media genres / prior recommendation seed items
5. XR displays the results in the interactive recommendation panel

### Interactive Recommendation Panel

The XR recommendation/search dialog in `SpatialPlayerScreen` is the canonical UI for actionable AI results. It is not just a text dump.

Each result card can expose:

- `Play`
- `Trailer`
- `More Like This`
- `Save`

Guidelines:

- Reuse the existing dialog and `PlayerSessionController` pending-selection flow when possible.
- “More Like This” should create a new `RecommendationContext` seeded from the selected item rather than inventing a prompt-only variant.
- “Save” should go through `JellyfinRepository.markAsFavorite` / `unmarkAsFavorite` and update the in-memory result list so the dialog reflects the new state immediately.
- Prefer poster or show-primary imagery from `SpatialFinImages` for result cards.

### External Knowledge Skill

External lookup is currently implemented as a skill, not as a parser feature.

- Primary media data source: TMDB via `data/src/main/java/dev/jdtech/jellyfin/api/TmdbApi.kt`
- Secondary general summary source: Wikipedia via `WikipediaSummaryClient`
- Credentials must come from app preferences (`tmdbApiKey`), never from prompt text
- The skill should degrade gracefully when:
  - TMDB key is missing
  - network is unavailable
  - no confident title/person match is found

Use external knowledge for:

- title lookups
- actor / director summaries
- richer answers than local metadata alone can provide

Do not use it for:

- core playback control
- library search
- recommendation ranking

### Home vs Player Differences

- `SpatialPlayerScreen` owns the richest voice UI: interactive result dialog, recommendation cards, subtitles, and playback-aware context.
- `UnifiedMainActivity` home mode shares the parser and chat engine, but remains text-first today.
- `ChatQuery` must be supported in both places. Never assume player-only handling.
- `RecommendationContext` and short conversation history must be preserved across follow-up voice turns in both home and player paths.

## Debugging Voice / AI

### Fast Verification Commands

Use these first after any voice or AI change:

```bash
./gradlew :player:xr:testDebugUnitTest
./gradlew :player:xr:compileDebugKotlin
./gradlew :app:unified:compileLibreDebugKotlin -Pksp.incremental=false
```

Notes:

- The unified compile may need `-Pksp.incremental=false` when KSP incremental state is stale.
- `:player:xr:testDebugUnitTest` is the cheapest regression check for recommendation and parser behavior.

### Where To Look In Logs

User logs are typically written under `Downloads/SpatialFin/`.

High-signal log categories:

- `SpatialVoiceService`
  - recognizer availability
  - soft speech errors
  - retry behavior
- `SpatialCommandCoordinator`
  - parser strategy
  - keyword vs model fallback behavior
- `GemmaCommandParser`
  - raw JSON response
  - malformed / truncated output
- `LlmChatModelHelper`
  - backend choice
  - NPU/GPU initialization failures
  - profile (`COMMAND` vs `CHAT`)
- `VoiceTelemetryStore`
  - recent stored attempts and retry markers

### Failure Triage Checklist

When “nothing happens,” isolate the failure in this order:

1. **Speech layer**
   - Did recognition start?
   - Are there soft errors `5` or `7`?
   - Did retry happen?
2. **Parser layer**
   - Was transcript normalized?
   - Did keyword/replay library match?
   - Did Gemma/Gemini return malformed JSON?
3. **Skill layer**
   - Which `selectedSkill` was chosen?
   - Was `validatedInput` sensible?
   - Was the reply `DIRECT`, `MODEL`, or `FALLBACK`?
4. **Execution / UI layer**
   - Was `ChatQuery` actually handled on the current screen?
   - Did `recommendedItems` populate?
   - Did the dialog/overlay show feedback?

### Common Real Failure Patterns

- **`TF_LITE_AUX not found in the model`**
  - NPU backend unsupported for that model/device combination
  - GPU fallback is expected
  - This should be cached so future startups skip the failing backend
- **`GemmaCommandParser: raw response: {"`**
  - usually truncated JSON
  - command parsing should retry with stricter instructions before falling through
- **Speech soft errors `5` / `7`**
  - transient recognizer problems
  - one automatic retry is expected
- **User asks for recommendations and only sees “Thinking...”**
  - often not a parser problem
  - check that `ChatQuery` is actually handled on that screen and that feedback / results UI is rendered

### Telemetry Expectations

Assistant telemetry is no longer just transcript + parser strategy.

For assistant replies, record:

- `transcript`
- `normalizedTranscript`
- `action`
- `strategy`
- `selectedSkill`
- `validatedInput`
- `resultDisposition`
- `success`
- `details`

`resultDisposition` should clearly distinguish:

- `DIRECT`
- `MODEL`
- `FALLBACK`

This is critical for answering “did the parser fail, did the skill fail, or did the model fail?”

## Voice / AI Development Rules

### Prefer Structured Layers Over Prompt Hacks

Before changing prompts, check whether the request belongs in:

- `SpatialCommandCoordinator` for command parsing
- `XrPlayerAction` for a new typed action
- `MediaSkillRegistry` for a new assistant capability
- `PlayerSessionController` or `SpatialPlayerScreen` for an actionable UI flow

### Keep Recommendation Follow-Ups Stateful

Queries like:

- “shorter”
- “movie only”
- “more like the second one”
- “with English audio”

depend on prior `RecommendationContext`. If that state is dropped, the feature will appear flaky even if parsing succeeds.

### Prefer Direct Answers For Structured Facts

If the answer is already in `PlayerStateSnapshot`, repository data, or TMDB/Wikipedia structured output, return a direct skill answer and skip the model.

Good examples:

- directors / writers / ratings
- continue watching
- local library search
- external title summary

### Preserve UX Feedback

For any new voice feature, ensure all of these are still true:

- overlay shows listening / processing / answered / error states
- `Thinking...` is replaced by a result or failure message
- follow-up windows are not broken by TTS or gesture state
- home and player behavior are both considered explicitly

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

## Release & Compliance Mandates

### Versioning
- **Semantic Versioning:** Follow `major.minor.patch` for `APP_NAME`.
- **Play Store Requirements:** ALWAYS increment `APP_CODE` (integer) and `APP_NAME` in `buildSrc/src/main/kotlin/Versions.kt` before building a production bundle. The Play Store will reject bundles with duplicate version codes.

### Minification & R8 (CRITICAL)
- **XR Extensions ProGuard:** Android XR sidecar extensions (under `com.android.extensions.xr`) are provided by the system. R8 minification MUST be configured to `-keep` these classes and interfaces. Failure to do so results in `java.lang.AbstractMethodError` at runtime when the app attempts to invoke system-provided shim methods (e.g., `Consumer.accept`).
- **Rule Verification:** Ensure `app/xr/proguard-rules.pro` contains:
  ```proguard
  -keep class com.android.extensions.xr.** { *; }
  -keep interface com.android.extensions.xr.** { *; }
  ```

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
