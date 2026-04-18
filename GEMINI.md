# GEMINI.md — SpatialFin Technical Reference

This document is the canonical context for any AI assistant working on SpatialFin. It captures the architecture, hard-won XR/voice lessons, build conventions, and known pitfalls.

> **SpatialFin** is a multi-module Kotlin/Android project — a Jellyfin client targeted primarily at Android XR (Samsung Galaxy XR and similar), with secondary phone (`Beam`) and TV form factors built from the same APK.
>
> Current version (always re-read `buildSrc/src/main/kotlin/Versions.kt` if in doubt): **2.5.0 (87)**, `compileSdk 36`, `targetSdk 35`, `minSdk 31`, JDK 21.

---

## ⚙️ Self-Update Mandate (Read This First)

**You MUST keep this file fresh.** GEMINI.md is the long-term memory for AI work on SpatialFin. Whenever any of the following happens, update this document in the same change:

- A module is added, renamed, removed, or moved.
- A repeated bug or footgun is discovered and worked around (especially XR/JNI/Compose interactions).
- A core entry point (Activity, ViewModel, repository, or coordinator) is restructured.
- A build, signing, flavor, NDK, R8/ProGuard, or version setting changes.
- A new dependency is introduced that affects downstream code (e.g. swapping LLM SDK, adding a media engine).
- An assumption documented here turns out to be wrong — fix the wrong claim, do not just add a contradicting note.
- A multi-step debugging session yields a non-obvious fix that future-you would lose 30+ minutes rediscovering.

**How to update:**

1. Edit the relevant section in place; do not append "errata" sections.
2. If you add a new module / subsystem, list it in [Module Map](#module-map) AND give it a one-line entry under [Subsystem Cheat Sheet](#subsystem-cheat-sheet).
3. When you fix or invalidate a claim, mark it `<!-- updated YYYY-MM-DD: short reason -->` so the next reader trusts the line.
4. Keep the file under ~700 lines. If it grows past that, split a section into a sibling `*.md` and link it.

This rule applies to every AI assistant that touches the repo, not just the one that wrote a particular section.

---

## Repository Navigation

When working in this repo, read files in this order unless the task is narrowly scoped:

1. `README.md` — user-facing feature surface
2. `GEMINI.md` — this file (technical context, gotchas)
3. `buildSrc/src/main/kotlin/Versions.kt` — current version + SDK targets
4. `settings.gradle.kts` — authoritative module list
5. `app/unified/build.gradle.kts` — the only application build script (XR/Beam/TV staging happens here)
6. The relevant module under `app/`, `player/`, `core/`, `data/`, `modes/`, `settings/`, `setup/`

Prefer source files and build files over generated artifacts or store assets.

### Usually Ignore For Reasoning

These paths are usually not useful for code understanding and should be skipped unless the task explicitly targets them:

- `.git/`, `.kotlin/`
- `**/build/`
- `build_native_work/`
- `release/`
- `fastlane/metadata/android/en-US/images/`
- `androidx/` (vendored XR API surface, not your code)

### Generated vs Intentional Binary Files

- `build_native_work/` — reproducible scratch/build output for the native subtitle toolchain. **Not** source of truth.
- `player/xr/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libass_jni.so` — **intentionally checked in** so a fresh clone builds without an NDK.
- `glb/spatialfin.glb` and `player/xr/src/main/assets/models/spatialfin.glb` — product assets, not architecture-defining code.

### Important Entry Points

- `app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt` — the only `Application`/`Activity` startup path. Branches on `DeviceClass` (XR/PHONE/TV) and orchestrates the XR session, panel placement, and gesture wiring. Delegates voice to `HomeVoiceController`.
- `app/unified/src/main/java/dev/spatialfin/unified/HomeVoiceController.kt` — owns Home-Space voice services (`SpatialVoiceService`, TTS, Gemini Nano/Cloud, command coordinator, chat engine), the request/interrupt state machine, telemetry, and the Compose effects that drive feedback timeouts, ERROR auto-reset, TTS bookkeeping, and follow-up auto-listen.
- `app/unified/src/main/java/dev/spatialfin/unified/HomeVoicePolicy.kt` — pure decision helpers (`isVoiceTurnBusy`, `decideRequest`, `shouldResumeFollowUpAfterInterrupt`, `feedbackTimeoutMs`) that the controller delegates to. Tested by `HomeVoicePolicyTest`.
- `app/unified/src/main/java/dev/spatialfin/unified/PanelPoseController.kt` — persists the user-placed XR app panel pose, performs legacy-default migration, and runs the 1 Hz pose-tracking loop. Pure logic lives in the sibling `PanelPosePolicy` object and is covered by `PanelPosePolicyTest`.
- `app/unified/src/main/java/dev/spatialfin/unified/XrSpaceController.kt` — single source of truth for `HOME` ↔ `FULL` space transitions.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrPlayerActivity.kt` — Full Space immersive player (separate Activity).
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/MultitaskPlayerActivity.kt` — Home Space side-by-side player.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt` — main player Composable (large; decomposed into sibling `Player*.kt` files — see the comment block near the bottom of the file for the index of what was lifted out).
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerVoiceCapture.kt` — `startVoiceCapture` + helpers. Builds the `PlayerStateSnapshot`, decides character-ID vs general visual context for chat queries, pauses/resumes playback around GPU-bound on-device inference, dispatches non-chat actions through `PlayerSessionController`, and records telemetry.
- `data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryImpl.kt` — primary Jellyfin/DB gateway.

### Voice / AI Entry Points

When the task touches voice, on-device AI, recommendations, or assistant UX, start here before chasing other files:

- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialVoiceService.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialCommandCoordinator.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/GemmaCommandParser.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SmartChatEngine.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/MediaSkillRegistry.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/RecommendationPlanner.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SecondaryHandPinchDetector.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialVoiceSynthesizer.kt`
- `player/session/src/main/java/dev/jdtech/jellyfin/player/session/voice/PlayerSessionController.kt`
- `settings/src/main/java/dev/jdtech/jellyfin/settings/voice/VoiceTelemetryStore.kt`
- `app/unified/src/main/java/dev/spatialfin/unified/HomeVoiceController.kt` (Home-Space voice state machine + lazy LLM/TTS service creation)
- `app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt` (gesture wiring + navigation surface for the controller)

---

## Module Map

`settings.gradle.kts` is authoritative. Current modules:

| Module | Purpose |
|---|---|
| `:app:unified` | The **only** application module (`applicationId = dev.spatialfin`). Single APK that branches at runtime on `DeviceClass`. Stages XR / TV / Beam sources into the build (see [Source Staging](#source-staging-quirk)). |
| `:player:xr` | Immersive XR player, libass JNI, voice subsystem, spatial UI. |
| `:player:local` | ExoPlayer/Media3 wrapper for local & Jellyfin playback (`PlayerViewModel`). |
| `:player:session` | Player session/action layer; voice-typed actions (`XrPlayerAction`) and `PlayerSessionController`. |
| `:player:core` | Player abstractions and domain models. |
| `:player:beam` | Phone-form-factor player (re-exports `:player:xr` for jniLibs / dex-merge reasons — see [Build Quirks](#build-quirks)). |
| `:player:tv` | TV-form-factor player (Leanback). |
| `:modes:film` | Browse/detail screens for movies/shows/episodes/collections, search. |
| `:data` | Jellyfin/TMDB/Seerr/OMDB API clients, Room DB, downloads, repositories, network shares (SMB/NFS), mDNS discovery. |
| `:core` | Shared UI components, LLM model manager, download workers, sync workers. |
| `:settings` | DataStore-based preferences (`AppPreferences`), voice telemetry, smart-language settings. |
| `:setup` | Server onboarding, login, address selection. |

**Source dirs that exist on disk but are NOT registered Gradle modules:**

- `app/xr/src/main/java`
- `app/tv/src/main/java`
- `app/beam/src/main/java`

These are **staged into `:app:unified`** at build time by `StageSourcesTask` in `app/unified/build.gradle.kts`. Editing files under `app/xr/...`, `app/tv/...`, or `app/beam/...` *does* affect the unified APK — Gradle copies (and patches `R`/`BuildConfig` imports for TV/Beam) before kotlinc runs. Do not delete these source trees without unwinding the staging task and migrating sources into `app/unified/src/main/java`.

### Source Staging Quirk

`app/unified/build.gradle.kts` defines `StageSourcesTask` (~line 26) which:

1. Copies `app/{xr,tv,beam}/src/main/java` → `build/filteredSources/{xr,tv,beam}`.
2. For TV/Beam, injects `import dev.spatialfin.R` and `import dev.spatialfin.BuildConfig` into every package declaration so the legacy code resolves the unified `R` class.
3. Registers each filtered dir via `variant.sources.java?.addGeneratedSourceDirectory(...)` so AGP/Hilt/KSP see them as generated sources.

If you add a new file under `app/xr/...`, `app/tv/...`, `app/beam/...`, no Gradle change is needed — the next build will pick it up. **But** Android Studio source navigation may lag; do an `./gradlew :app:unified:assembleLibreDebug` to refresh staged outputs if the IDE complains.

### Player Module Cross-Reference

`:player:beam` exposes `:player:xr` via `api(project(":player:xr"))` to keep `libass_jni.so` and `LibassRenderer` flowing through a single dex-merge path. Do not also `implementation(project(":player:xr"))` from `:app:unified` — it causes duplicate-class errors. The current `:app:unified` build pulls XR transitively through `:player:beam`.

---

## Build Quirks

- **Single application module** — `:app:unified` only. There is no `:app:xr` Gradle module; the directory exists for source staging only.
- **Single flavor** — `libre`. Build types: `debug`, `staging` (release-derived, `.staging` suffix), `release`.
- **Release is `isMinifyEnabled = false`** — XR system-extension callbacks under `com.android.extensions.xr.*` were crashing with `AbstractMethodError` in optimized builds. Until the androidx.xr / R8 interaction is fixed, do not flip this on without testing the entire spatial UI. If you must minify, the keep rules in `app/unified/proguard-rules.pro` are the starting point.
- **ABI splits** — `arm64-v8a` and `armeabi-v7a` are split for APK builds, but **disabled when building bundles** to work around AGP 8.9.0 issue [#402800800](https://issuetracker.google.com/issues/402800800).
- **No proprietary flavor** — Everything ships under `libre`.
- **KSP incremental** — Sometimes goes stale during big refactors. If a clean build complains about generated Hilt classes, retry with `-Pksp.incremental=false`.

### Signing

Release signing reads, in order: Gradle `-P`, `local.properties`, environment variable.

- `SPATIALFIN_KEYSTORE`
- `SPATIALFIN_KEYSTORE_PASSWORD`
- `SPATIALFIN_KEY_ALIAS`
- `SPATIALFIN_KEY_PASSWORD`

### Native Subtitle Library

Prebuilt `libass_jni.so` lives in `player/xr/src/main/jniLibs`. To rebuild from source:

```bash
./player/xr/build_native.sh /path/to/android-ndk
```

`build_native_work/` is build scratch — reproducible, do not edit by hand.

---

## Versioning & Release

`buildSrc/src/main/kotlin/Versions.kt` holds:

```kotlin
const val APP_CODE = 87        // monotonically increasing integer
const val APP_NAME = "2.5.0"   // semver
```

Always increment **both** `APP_CODE` and `APP_NAME` before producing a Play Store bundle. Duplicate version codes are a hard reject.

`./gradlew versionCatalogUpdate` updates `gradle/libs.versions.toml`. Always review `git diff gradle/libs.versions.toml` before committing — XR / Compose / Media3 patch bumps occasionally break the build.

---

## Subsystem Cheat Sheet

| Subsystem | Where it lives | Owner type |
|---|---|---|
| App startup, device branching | `app/unified/.../UnifiedMainActivity.kt` | `Activity` + Compose |
| Home-Space voice state machine | `app/unified/.../HomeVoiceController.kt` (+ `HomeVoicePolicy.kt`) | Compose-aware controller + pure policy |
| XR app panel pose persistence | `app/unified/.../PanelPoseController.kt` (+ `PanelPosePolicy.kt`) | controller + pure policy |
| XR space transitions (Home ↔ Full) | `app/unified/.../XrSpaceController.kt` | runtime controller |
| XR immersive player Activity | `player/xr/.../XrPlayerActivity.kt` | `Activity` |
| Home Space player | `player/xr/.../MultitaskPlayerActivity.kt` | `Activity` |
| Player Composable | `player/xr/.../SpatialPlayerScreen.kt` (+ sibling `Player*.kt`) | `@Composable` |
| Voice service / mic lifecycle | `player/xr/.../voice/SpatialVoiceService.kt` | service-style holder |
| Voice command routing | `player/xr/.../voice/SpatialCommandCoordinator.kt` | coordinator |
| AI chat / multi-backend routing | `player/xr/.../voice/SmartChatEngine.kt` | engine |
| Built-in skills | `player/xr/.../voice/MediaSkillRegistry.kt` | registry |
| Player action execution | `player/session/.../voice/PlayerSessionController.kt` | controller |
| TTS + ducking | `player/xr/.../voice/SpatialVoiceSynthesizer.kt` | engine |
| Hand gesture activation | `player/xr/.../voice/SecondaryHandPinchDetector.kt` | detector |
| Jellyfin gateway | `data/.../repository/JellyfinRepositoryImpl.kt` | repository |
| Offline gateway | `data/.../repository/JellyfinRepositoryOfflineImpl.kt` | repository (partial — see [Known Gaps](#known-gaps)) |
| Continue Watching filter | `data/.../repository/ResumeFilter.kt` | pure object (`ResumeFilterTest`) |
| Track signature ids | `player/local/.../presentation/PlayerTrackSignatures.kt` | pure object (`PlayerTrackSignaturesTest`) |
| SMB/NFS bridge | `data/.../network/{Smb,Nfs}FileClient.kt`, `NetworkStreamProxy.kt` | clients + local HTTP proxy |
| mDNS discovery | `data/.../network/NetworkDiscovery.kt` | discovery |
| Downloads | `core/.../work/` workers + `data/.../downloads/DownloadStorageManager.kt` | WorkManager |
| Preferences | `settings/.../domain/AppPreferences.kt` | DataStore facade |
| Voice telemetry | `settings/.../voice/VoiceTelemetryStore.kt` | local telemetry |
| Companion (QR import) | `app/unified/.../UnifiedMainActivity.kt` (sync glue) | one-shot importer |
| Smart language ranking | `settings/.../voice/SmartLanguageSettings.kt` + per-series overrides | preference + cache |

---

## Core Architectural Principles (XR)

### Scene Graph & ECS
- Android XR uses a hierarchical scene graph. `ActivitySpace` is the top-level entity, right-handed coordinates in meters.
- ECS: composition over inheritance. Add `MovableComponent` / `ResizableComponent` instead of subclassing.
- A `Subspace` is a dedicated 3D partition. Content inside renders spatially when XR is on; falls back to 2D otherwise.

### UI Best Practices & Ergonomics
- **Material 3 for XR:** `NavigationRail`, `NavigationBar`, `TopAppBar` adapt to **XR Orbiters** automatically.
- **Adaptive layouts:** `ListDetailPaneScaffold` / `SupportingPaneScaffold` map panes 1:1 to spatial panels.
- **Cinema scale:** Use large `SpatialPanel` dimensions (≥1400dp) for cinematic feel. The unified main panel is `1792dp × 1008dp` (`UnifiedMainActivity` constants).
- **Depth & focus:** `SpatialDialog` / `SpatialPopup` push the parent panel back **125dp**.
- **User agency:** `MovableComponent` + `ResizableComponent` for workspace customization.

### Spatial Panel Placement (Android XR Design Guide)
- **Spawn distance:** Place panel centers ~**1.75 m** from the user.
- **Vertical offset:** Center ~**5° below eye level**.
- **FOV:** Keep interactive content within the **center 41°**.
- **Depth limits:** Panels between **0.75 m** and **5 m** from the user.
- **Orbiter offset:** **20 dp** between an Orbiter and its parent panel.
- **Orbiter count:** One per panel unless absolutely needed — content fatigue is real.

### SpatialPanel Gesture Blocking — Critical Pitfall
- **Empty panels still intercept raycasts.** A `SpatialPanel` with no visible content (e.g. hidden via `AnimatedVisibility`) still blocks pointer/grab events for entities behind it.
- **Fix:** Make the `SceneCoreEntity` / `SpatialPanel` *conditional* in the `Subspace` composition. `AnimatedVisibility` alone is not sufficient — the panel container itself must be removed from composition.
- **Scrollable content vs. `MovableComponent`:** Scroll gestures inside a `MovableComponent` entity may be hijacked as drag/move. Use a separate `GroupEntity` (no `MovableComponent`) for scrollable panels, positioned so it doesn't sit between the user and the movable entity.

### Spatial Hierarchy & Parenting
For low-latency movement of complex player UIs:
- Attach Surfaces, Panels, and Orbiters to a single **Root** Entity (typically `PanelEntity` / `GroupEntity` / `ContentlessEntity`).
- Apply `MovableComponent` only to the Root.
- Lock children to fixed offsets via `entity.setParent(parent)` or the Compose `SceneCoreEntity` wrapper.

### Rendering & Media Strategy
- `SurfaceEntity` for video, with Media3 (ExoPlayer) feeding the surface.
- Spatial video shapes: `Quad` (flat), `Hemisphere` (180°), `Sphere` (360°). SBS and MV-HEVC supported.
- DRM: `surfaceProtection = SurfaceProtection.Protected`.
- 2D overlays: `SpatialPanel`. Toolbars / secondary controls: `Orbiter`.

### Spatial Audio
- Positional, stereo/surround (auto-spatialized vs main panel), or ambisonic ("skybox").
- ExoPlayer for media; `SoundPool` for low-latency SFX.

### Subtitle Handling
- **Text-based (SRT/VTT):** Render in a separate `SpatialPanel` at a comfortable depth.
- **Advanced (ASS/SSA):** Use the integrated libass JNI renderer (`LibassRenderer`, `LibassTextRenderer`).
- **Raycast passthrough (CRUCIAL):** Always conditionally compose the subtitle `SpatialPanel`. If `hasContent == false`, remove it from the `Subspace` entirely or it blocks interactions with the video / controls behind it.
- **Accessibility:** Bridge app-level subtitle preferences to `CaptionStyleCompat` for Media3's `SubtitleView`. Re-apply styles in the `update` block of `AndroidView` to defeat OS-level overrides when system captions are off.
- **ASS packet normalization:** Embedded ASS samples from Media3/MKV arrive as full `Dialogue:` lines with embedded `Start,End` timestamps. When using `ass_process_chunk`, **strip the `Dialogue:` prefix and timestamp fields** before passing the payload to libass — timing already comes via `startMs` / `durationMs`. Sending the full line will make libass render only the first event.
- **Extraction path:** Keep `experimentalParseSubtitlesDuringExtraction(false)` for libass playback. Otherwise Media3 converts ASS into `application/x-media3-cues` and bypasses raw delivery.
- **Panel-to-video geometry:** The subtitle panel must cover the same projected video frame as the playback surface. Do not independently clamp the subtitle panel size after projecting the video size to subtitle depth — positioned ASS signs will drift.
- **Synthetic header for chunked streams:** When ASS arrives in chunks with empty `initData`, a synthetic `[Script Info]` header is injected (see `f578de2`/`a51b536`). Don't remove this without re-checking chunked subtitle playback.

### On-Device AI (LiteRT Gemma)
- **Engine:** LiteRT LM (formerly MediaPipe LLM Inference).
- **Backends, in order:** **NPU (Tensor)** → **GPU (Adreno/Mali)** → **CPU**. CPU is ~unusable; **disabled by default** unless the user opts in (with a latency warning).
- **Lifecycle:** `LlmChatModelHelper` owns `Engine` + `Conversation`. Always call `close()` on Activity/ViewModel destroy or you will leak GPU memory and lock the device's accelerator.
- **Model provisioning:** `SmartChatEngine` triggers an auto-download of a Gemma `.task` model into `context.filesDir/models/`.
- **Multimodal:** Supports `visualContext: Bitmap` so the assistant can "see" the current video frame.
- **Prompt composition:** Built dynamically from `PlayerStateSnapshot` + `SubtitleContext` + `storySoFarContext`.
- **Stateless inference per request:** `LlmChatModelHelper.runInference(...)` creates a fresh conversation per call and uses explicit profiles (`COMMAND` vs `CHAT`). Do not rebuild a long-lived shared conversation — it leaks state into command parsing.
- **Backend failure caching:** If LiteRT NPU init fails with `TF_LITE_AUX not found in the model`, the helper caches that as unsupported for the device/model and prefers the last successful backend on later launches. Don't strip this without an equivalent strategy.

---

## Voice & AI Architecture

### Request Pipeline (do not mash these layers together)

1. **`SpatialVoiceService`** — speech recognition lifecycle, partial transcript, listening/processing state, soft-error retry for recognizer errors `5` and `7`.
2. **`SpatialCommandCoordinator`** — keyword/replay-library matches, screen-aware parsing (`HOME` vs `PLAYER`), Gemini/Gemma JSON command parsing with strict retry.
3. **`XrPlayerAction`** — typed action boundary between parsing and execution.
4. **`PlayerSessionController`** — executes playback/search/selection actions; owns pending-selection state for follow-ups like "play the first one."
5. **`SmartChatEngine`** — handles `ChatQuery`; builds prompt context **only after** a skill is selected.
6. **`MediaSkillRegistry`** — selects the built-in skill, validates input, decides reply mode (`DIRECT` / `MODEL` / `FALLBACK`).

### Built-In Skills (`MediaSkillRegistry`)

`PLAYBACK_CONTROL`, `WATCH_RECOMMENDER`, `LIBRARY_SEARCH`, `RECAP`, `DIALOGUE_EXPLAINER`, `METADATA_QA`, `CONTINUE_WATCHING`, `MOOD_SURPRISE`, `EXTERNAL_KNOWLEDGE`, `GENERAL_CHAT`.

Extend skills here, not via prompt branches.

### Recommendation Flow

1. `MediaSkillRegistry` → `WATCH_RECOMMENDER` or `MOOD_SURPRISE`.
2. `RecommendationPlanner` extracts filters / follow-up context.
3. Candidates from `repository.{getSuggestions, getResumeItems, getFavoriteItems}` and prior `RecommendationContext`.
4. Planner ranks by media type, runtime ("under N minutes"), comedy/new/English/anime-avoidance, comfort, current-media genre overlap, prior seed items.
5. XR shows results in the interactive recommendation panel.

### Interactive Recommendation Panel (`SpatialPlayerScreen`)

Each result card exposes `Play`, `Trailer`, `More Like This`, `Save`. Reuse the existing dialog and `PlayerSessionController` pending-selection flow. "More Like This" creates a fresh `RecommendationContext` seeded from the selected item. "Save" goes through `JellyfinRepository.markAsFavorite` / `unmarkAsFavorite` and updates the in-memory list immediately.

### External Knowledge (skill, not parser feature)

- TMDB via `data/src/main/java/dev/jdtech/jellyfin/api/TmdbApi.kt`.
- Wikipedia via `WikipediaSummaryClient`.
- Credentials always come from `appPreferences.tmdbApiKey`, never from prompt text.
- Degrade gracefully when the key is missing, the network is down, or no confident match exists.
- Use for: title lookups, actor/director summaries, richer answers than local metadata.
- Do **not** use for: playback control, library search, recommendation ranking.

### Home vs Player

- `SpatialPlayerScreen` owns the richest voice UI (interactive results, recommendation cards, subtitle context).
- `UnifiedMainActivity` Home Space shares the parser and chat engine but is text-first today.
- `ChatQuery` must be supported in **both** screens. `RecommendationContext` and short conversation history must persist across follow-up turns in both paths.

---

## Debugging Voice / AI

### Wireless ADB Workflow For XR Headsets

1. On the headset: `Developer options` → `Wireless debugging` → `Pair device with pairing code`.
2. From the workstation: `adb pair <headset-ip>:<pairing-port>` and enter the pairing code.
3. Find the actual debug endpoint via the wireless debugging screen or `adb mdns services`.
4. `adb connect <headset-ip>:<debug-port>` and verify with `adb devices -l`.

Notes:
- Pairing port ≠ debug port. Don't confuse them.
- XR headsets may appear twice (`<ip>:<port>` and an mDNS alias). Always use the explicit `<ip>:<debug-port>` with `adb -s ...`.

### Install / Logcat Commands

```bash
./gradlew :app:unified:assembleLibreDebug
adb -s <ip>:<debug-port> install -r app/unified/build/outputs/apk/libre/debug/spatialfin-libre-arm64-v8a-debug.apk

adb -s <ip>:<debug-port> shell pidof dev.spatialfin.debug
adb -s <ip>:<debug-port> shell dumpsys package dev.spatialfin.debug

adb -s <ip>:<debug-port> logcat -c
adb -s <ip>:<debug-port> logcat -b all -v threadtime | tee xr-crash.log
```

PID-filtered:

```bash
adb -s <ip>:<debug-port> logcat -d -v threadtime --pid $(adb -s <ip>:<debug-port> shell pidof dev.spatialfin.debug)
```

High-signal log sources: `AndroidRuntime`, `libc`, `SurfaceFlinger`, `BufferQueue`, OpenXR vendor logs, and the app's own `dev.spatialfin.debug` lines (parser, voice, player, subtitle).

### Fast Verification

```bash
./gradlew :player:xr:testDebugUnitTest                            # cheapest regression check
./gradlew :player:xr:compileDebugKotlin
./gradlew :app:unified:compileLibreDebugKotlin -Pksp.incremental=false
```

### Where To Look In Logs

User logs land under `Downloads/SpatialFin/`. Tags worth grepping: `SpatialVoiceService`, `SpatialCommandCoordinator`, `GemmaCommandParser`, `LlmChatModelHelper`, `VoiceTelemetryStore`, `SUB:` (libass).

### Failure Triage Order

1. **Speech layer** — did recognition start? soft errors `5`/`7`? did retry happen?
2. **Parser layer** — was transcript normalized? keyword/replay match? Gemma JSON malformed?
3. **Skill layer** — which `selectedSkill`? `validatedInput` sensible? reply was `DIRECT`/`MODEL`/`FALLBACK`?
4. **Execution / UI layer** — was `ChatQuery` actually handled on the current screen? did `recommendedItems` populate? did the overlay show feedback?

### Common Real Failure Patterns

- **`TF_LITE_AUX not found in the model`** — NPU unsupported for that model/device. GPU fallback expected. Cache the failing backend.
- **`GemmaCommandParser: raw response: {"`** — truncated JSON. Parser should retry with stricter instructions before falling through.
- **Speech soft errors `5` / `7`** — transient recognizer issues. One automatic retry expected.
- **"Thinking…" never resolves** — usually not a parser problem. Check that `ChatQuery` is handled on the current screen and that feedback / results UI is rendered.

### Telemetry Expectations

`VoiceTelemetryStore` records: `transcript`, `normalizedTranscript`, `action`, `strategy`, `selectedSkill`, `validatedInput`, `resultDisposition` (`DIRECT` / `MODEL` / `FALLBACK`), `success`, `details`. This split is critical for "did the parser fail, the skill fail, or the model fail?"

---

## Voice / AI Development Rules

- **Prefer structured layers over prompt hacks.** Decide whether a request belongs in the coordinator (parsing), `XrPlayerAction` (typed action), `MediaSkillRegistry` (skill), or `PlayerSessionController` / `SpatialPlayerScreen` (UI flow) — *before* editing prompts.
- **Keep recommendation follow-ups stateful.** "shorter," "movie only," "more like the second one," "with English audio" all depend on prior `RecommendationContext`. Drop that state and the feature appears flaky even when parsing is fine.
- **Prefer direct answers for structured facts.** Director / writer / rating / continue-watching / local search / external title summary should be `DIRECT` skill answers, not model calls.
- **Preserve UX feedback.** Overlay must always show listening / processing / answered / error state; "Thinking…" must always be replaced; follow-up windows must not be broken by TTS or gesture state; both Home and Player paths must be considered explicitly.

---

## Stability & Performance Mandates

### JNI & Native Safety
- Native methods must not be called before `System.loadLibrary` succeeds. Use `LibassRenderer.isAvailable()` guards inside `init()`.
- libass is **not thread-safe**. Dispatch all native calls (init, render, destroy) to a dedicated `HandlerThread`.

### High-Fidelity Subtitle Rendering
- **Render at high resolution.** XR has very high angular density. Render bitmaps up to 2.0x density (`coerceIn(1280, 7680)`) to avoid mushy text.
- **Aspect ratio:** Always call `ass_set_storage_size` with the original video dimensions (`player.videoSize`) so positioned ASS signs / overlays line up.
- **Shaping:** Force `ASS_SHAPING_COMPLEX` (HarfBuzz) and `ASS_HINTING_LIGHT`.
- **Compose filtering:** Use `BitmapPainter(filterQuality = FilterQuality.High)`.

### Spatial Dialogs & UI
- **No nested dialogs.** Don't put a 2D `AlertDialog` / `Popup` inside a `SpatialDialog` — z-fighting + input capture failure. Use a `Surface(shape = RoundedCornerShape(32.dp))` as the root content.
- **Constrain scrollables.** `LazyColumn` inside `SpatialDialog` crashes with "infinite height" unless the dialog has `Modifier.heightIn(max = …)`.
- **Avoid global `EnableXrComponentOverrides`** if you also use `NavigationRail` or stock M3 items — `NoSuchFieldError` from binary incompatibility. Apply surgically.

### Mode Transitions
- SpatialFin launches in **Home Space** by default (multitask). Users (or auto-rules) opt into **Full Space** for immersive playback.
- All HOME ↔ FULL transitions must go through `XrSpaceController`. Do not call `xrSession.scene.requestFullSpaceMode()` ad-hoc from screens — the controller owns the state machine and the auto-return-on-stop policy.
- Always check `SpatialCapability.SPATIAL_3D_CONTENT` before entering FSM-only flows.

### Pose Persistence
- Saving the app-panel pose: write to `SharedPreferences` only when the pose **actually changes** (`UnifiedMainActivity.poseApproximatelyEqual`). A 1 Hz unconditional write wears flash and races the `MovableComponent`'s final pose with stale ticks.
- Migrate legacy default poses (`migrateLegacyCenteredAppPose`) — old default depths (-5m / -6m / -9m / -11m) get rewritten to the current default; do not break this when you add a new default.

---

## Release & Compliance Mandates

### Dependency Management
- `./gradlew versionCatalogUpdate` to bump `gradle/libs.versions.toml`. Always review the diff before committing.

### Versioning
- Bump both `APP_CODE` and `APP_NAME` in `buildSrc/src/main/kotlin/Versions.kt` for any Play Store bundle. Duplicate version codes are a hard reject.

### R8 / ProGuard
- Release is currently unminified (see [Build Quirks](#build-quirks)). The keep rules for `com.android.extensions.xr.*` in `app/unified/proguard-rules.pro` are the floor — do not remove them, even though minification is off today, because the moment R8 flips back on those classes will fault.

  ```proguard
  -keep class com.android.extensions.xr.** { *; }
  -keep interface com.android.extensions.xr.** { *; }
  ```

---

## Component Cheat Sheet

| Component | What it is |
|---|---|
| `Subspace` | Container enabling 3D layouts and spatialized components within Compose. |
| `SpatialPanel` | 3D container for 2D content (player, settings) in XR. |
| `Orbiter` | Floating UI element anchored to a panel/entity (toolbar / nav). |
| `SurfaceEntity` | Low-level SceneCore entity for video/image rendering. |
| `SpatialExternalSurface` | Compose wrapper that hands a `Surface` to Media3. |
| `SceneCoreEntity` | Subspace composable bridging SceneCore entities (or GLTF) into Compose. |
| `MovableComponent` | Makes an entity user-draggable in 3D space. |
| `ResizableComponent` | Adds resize handles to an entity. |
| `SpatialDialog` | Spatialized dialog that pushes parent panel back to create focus. |

---

## Known Gaps (TODOs Worth Tracking)

These are the gaps a future contributor (human or AI) should know about. If you fix one, update or remove the line.

- **`JellyfinRepositoryOfflineImpl.kt`** — `getItemsPaging`, `getPerson`, `getPersonItems` now return safe defaults (empty paging, placeholder person, empty list) instead of crashing. `getStreamUrl` raises a loud `error(...)` since downloaded items play from local file URIs and should never reach this path. `getPublicSystemInfo` still throws — onboarding/setup is online-only by design.
- **`core/.../utils/DownloaderImpl.kt`** — has a TODO noting that some download steps may abort if the user navigates away mid-flight; the long-term fix is to push everything onto WorkManager.
- **`SpatialPlayerScreen.kt.orig`** — leftover backup file. Safe to delete; verify no script references it first.
- **`SpatialPlayerScreen.kt`, `PlayerViewModel.kt`** — both are very large (>2000 lines). Split before adding major new player features; otherwise AI context windows and human reviewers both struggle.
- **`UnifiedMainActivity.kt`** — voice was extracted into `HomeVoiceController` and pose persistence into `PanelPoseController`; the Activity now reads as pure orchestration (`~750` lines, mostly device-class branching and the FullSpace Subspace tree). Next size pressure is `SpatialPlayerScreen.kt` and `PlayerViewModel.kt` — both still >2k lines.
- **Test coverage** — `RecommendationPlannerTest`, `VoiceReplayCommandLibraryTest`, `StereoModeDetectorTest`, `SmbPathNormalizerTest`, `HomeVoicePolicyTest`, `PanelPosePolicyTest`, `ResumeFilterTest`, `PlayerTrackSignaturesTest` exist. The player UI, repositories, and most of `JellyfinRepositoryOfflineImpl` are still essentially untested — add tests when changing those areas. Note: only JUnit 4 is wired in (no Mockito/MockK/Robolectric), so prefer extracting pure helpers/policies (taking primitive args, not framework types) for testability rather than mocking framework calls.
- **`isMinifyEnabled = false` for release** — see [Build Quirks](#build-quirks). Long-term debt; revisit when androidx.xr fixes the R8 interaction.

---

## Prompting Guidelines

If you're asking the model for new features or fixes, use these as shorthand — they map to the established patterns above:

- **"Spatialize the player"** → `Subspace` + `SpatialPanel` + `Orbiter`.
- **"Cinematic experience"** → Full Space Mode + ≥1400dp panels.
- **"Hierarchy movement"** → SceneCore parenting for the player root, `MovableComponent` only on the root.
- **"Surface-level subtitles"** → Move rendering from Compose panels to the playback surface.
- **"Bridge preferences"** → Map `AppPreferences` to `CaptionStyleCompat` for Media3 views.
- **"Immersive layout"** → 825dp curve radius + 125dp depth offsets.
- **"Enable XR audio"** → Choose between Positional, Stereo/Surround, or Ambisonics consciously.

---

## Official Documentation Links

- **Jetpack XR SDK Hub:** https://developer.android.com/develop/xr/jetpack-xr-sdk
- **SceneCore (Entities):** https://developer.android.com/develop/xr/jetpack-xr-sdk/work-with-entities
- **Compose for XR:** https://developer.android.com/develop/xr/jetpack-xr-sdk/ui-compose
- **Spatial Video:** https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-video
- **Spatial Audio:** https://developer.android.com/develop/xr/jetpack-xr-sdk/add-spatial-audio
