# GEMINI.md — SpatialFin Technical Reference

This document is the canonical context for any AI assistant working on SpatialFin. It captures the architecture, hard-won XR/voice lessons, build conventions, and known pitfalls.

> **SpatialFin** is a multi-module Kotlin/Android project — a Jellyfin client targeted primarily at Android XR (Samsung Galaxy XR and similar), with secondary phone (`Beam`) and TV form factors built from the same APK.
>
> Current version (always re-read `buildSrc/src/main/kotlin/Versions.kt` if in doubt): **2.7.13 (114)**, `compileSdk 37`, `targetSdk 35`, `minSdk 31`, JDK 21. The `tv` flavor uses `APP_CODE + 1_000_000` (currently `1000114`) — see [Play Track Bundles](#play-track-bundles).

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
3. `DESIGN.md` — visual identity, design tokens, and form-factor (XR/Beam/TV) design constraints. Follows the open-sourced [Google Labs `DESIGN.md` standard](https://github.com/google-labs-code/design.md) (YAML frontmatter with `colors` / `typography` / `rounded` / `spacing` / `components` tokens, then a canonical markdown body: Overview → Colors → Typography → Layout → Elevation & Depth → Shapes → Components → Do's and Don'ts). Treat the frontmatter as machine-readable and keep it in sync with any new component that picks up a distinct token surface.
4. `buildSrc/src/main/kotlin/Versions.kt` — current version + SDK targets
5. `settings.gradle.kts` — authoritative module list
6. `app/unified/build.gradle.kts` — the only application build script (XR/Beam/TV staging happens here)
7. The relevant module under `app/`, `player/`, `core/`, `data/`, `modes/`, `settings/`, `setup/`

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

- `app/unified/src/main/java/dev/spatialfin/unified/UnifiedApplication.kt` — Hilt application bootstrap. Installs crash logging and FCast ingress synchronously, then defers diagnostics, companion sync, optional LLM warmup, download integrity, and stale-receiver cleanup until the first rendered activity frame (or a two-second fallback for service/worker-only starts).
- `app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt` — the only UI startup Activity. Branches on `DeviceClass` (XR/PHONE/TV), orchestrates the XR session, panel placement, and gesture wiring, and signals deferred application work after its first Compose frame. Session refreshes are driven by `ActiveSessionBus`, not each `onResume`.
- `app/unified/src/main/java/dev/spatialfin/unified/HomeVoiceController.kt` — owns Home-Space voice services (`SpatialVoiceService`, TTS, Gemini Nano/Cloud, command coordinator, chat engine), the request/interrupt state machine, telemetry, and the Compose effects that drive feedback timeouts, ERROR auto-reset, TTS bookkeeping, and follow-up auto-listen. Delegates TTS transition logic to `AssistantSpeechTransitionEffect`. Pass `LlmModelManager` as a provider/lambda, not an eager instance, or XR Home Space composition will warm LiteRT/GPU at launch. <!-- updated 2026-05-22: lazy LLM provider avoids Galaxy XR startup contention -->
- `app/unified/src/main/java/dev/spatialfin/unified/HomeVoicePolicy.kt` — pure decision helpers (`isVoiceTurnBusy`, `decideRequest`, `shouldResumeFollowUpAfterInterrupt`, `feedbackTimeoutMs`) that the controller delegates to. Tested by `HomeVoicePolicyTest`.
- `app/unified/src/main/java/dev/spatialfin/unified/PanelPoseController.kt` — legacy pose persistence helper for the old SceneCore-wrapped XR app panel. The active Galaxy XR browsing panel is now a direct Compose XR `SpatialPanel` with `transformingMovable()` in `UnifiedMainActivity`; do not reintroduce `PanelPoseController` into that path unless a real device proves the DP4 blank-panel regression is gone. Pure policy logic remains covered by `PanelPosePolicyTest`. <!-- updated 2026-05-22: immersive app panel moved off custom SceneCore GroupEntity after Galaxy XR blank Full Space regression -->
- `app/unified/src/main/java/dev/spatialfin/unified/XrSpaceController.kt` — single source of truth for `HOME` ↔ `FULL` space transitions.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrPlayerActivity.kt` — Full Space immersive player (separate Activity).
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/MultitaskPlayerActivity.kt` — Home Space side-by-side player.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt` — main player Composable (large; decomposed into sibling `Player*.kt` files — see the comment block near the bottom of the file for the index of what was lifted out).
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerVoiceCapture.kt` — `startVoiceCapture` + helpers. Builds the `PlayerStateSnapshot`, decides character-ID vs general visual context for chat queries, pauses/resumes playback around GPU-bound on-device inference, dispatches non-chat actions through `PlayerSessionController` (including `play_media` tool results), and records telemetry.
- `data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryImpl.kt` — primary Jellyfin/DB gateway. `SmartJellyfinRepository` provides provenance-aware `fetch*` methods (returning `Fetched<T>`) to track server vs cache origins. <!-- updated 2026-04-22: added provenance tracking -->
- `player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/SyncPlayCoordinator.kt` — owns SyncPlay / Jellyfin remote-control state + message handlers. `PlayerViewModel` delegates the four public entry points (`refresh/create/join/leave`) and exposes `syncPlay.isActive()` / `shouldSuppressEvents()` for its `Player.Listener` callbacks.
- `player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerTrackSelector.kt` — owns smart-language audio/subtitle auto-selection, manual `switchToTrack`, and series-override persistence. `PlayerViewModel` delegates `switchToTrack`, and calls `trackSelector.applySmart()` on media-item transition / tracks-changed.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerSubtitleContext.kt` — `rememberPlayerSubtitleContext(viewModel)` holder for the AI-facing subtitle bundle (rolling cue buffer, disk-cache fallback, derived assistant lines).
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/LibassRendererState.kt` — `rememberLibassRenderer(...)` holder that owns libass render state + Media3 fallback cues + the Player.Listener + 4 self-driven effects (render loop, useLibass gate, MCP bridge mirror, post-move diagnostic). Screen consumes `libass.useLibass`, `libass.bitmap`, `libass.hasSubtitleContent`, etc., and calls `libass.bumpOverlayAttachment()` / `libass.reset()` on entity attachment / screen exit.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerVoiceServices.kt` — `rememberPlayerVoiceServices(viewModel)` holder for the six voice / on-device AI singletons (`SpatialVoiceService`, Gemini Nano / Cloud, `SpatialVoiceSynthesizer`, LLM entry point) plus lazy `requireCommandCoordinator` / `requireChatEngine`. Call `voiceServices.destroy()` from the screen's `DisposableEffect(context)` onDispose.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerVoiceCoordinator.kt` — `rememberPlayerVoiceCoordinator(...)` holder for the 14 voice-overlay state vars + `armFollowUpWindow` / `speakAssistantReply` / `interruptVoiceCommand` / `isVoiceTurnBusy` primitives + 4 self-contained effects (audio ducking, feedback auto-dismiss, ERROR auto-reset, TTS transition state machine). The screen-inline `requestVoiceCommand` and the follow-up auto-start / pinch-detector effects stay in `SpatialPlayerScreen` because they depend on `startVoiceCapture`'s remaining ~22-param surface.
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerSnapshotBuilder.kt` — `buildPlayerStateSnapshot(...)` is the single source of truth for building a `PlayerStateSnapshot` from player + UiState + voice-search + recommendation context. Both `SpatialPlayerScreen.currentRecommendationSnapshot()` and `startVoiceCapture` now call it instead of duplicating the 45-line constructor (which had drifted on `audioTrackNames` / `subtitleTrackNames` / `chapterNames`).

### Voice / AI Entry Points

When the task touches voice, on-device AI, recommendations, or assistant UX, start here before chasing other files:

- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialVoiceService.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialCommandCoordinator.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/GemmaCommandParser.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SmartChatEngine.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/MediaSkillRegistry.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/ChatToolRegistry.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/RecommendationPlanner.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SecondaryHandPinchDetector.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/SpatialVoiceSynthesizer.kt`
- `player/session/src/main/java/dev/jdtech/jellyfin/player/session/voice/PlayerSessionController.kt`
- `settings/src/main/java/dev/jdtech/jellyfin/settings/voice/VoiceTelemetryStore.kt`
- `app/unified/src/main/java/dev/spatialfin/unified/HomeVoiceController.kt` (voice state machine + lazy LLM/TTS service creation; used by both XR Home Space and the Beam phone shell)
- `app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt` (XR gesture wiring + navigation surface for the controller)
- `app/unified/src/main/java/dev/spatialfin/beam/BeamNavigationRoot.kt` (Beam-phone mic FAB, voice feedback overlay, recommendation sheet — delegates to `HomeVoiceController` for the same AI pipeline as XR)

---

## Module Map

`settings.gradle.kts` is authoritative. Current modules:

| Module | Purpose |
|---|---|
| `:app:unified` | The **only** application module (`applicationId = dev.spatialfin`). Single APK that branches at runtime on `DeviceClass`. XR, TV, and Beam code all live in this module's `src/main/java` under their own packages (`dev.jdtech.jellyfin.*`, `dev.spatialfin.tv.*`, `dev.spatialfin.beam.*`). |
| `:baselineprofile` | Instrumentation-only Baseline Profile producer and Macrobenchmark harness for `:app:unified` startup, first shell scroll, and the exported TV player launch; run its flavored benchmark tests on an API 33+ phone/XR or TV device when regenerating profiles or capturing performance numbers. |
| `:player:xr` | Immersive XR player, libass JNI, voice subsystem, spatial UI. |
| `:player:local` | ExoPlayer/Media3 wrapper for local & Jellyfin playback (`PlayerViewModel`). |
| `:player:session` | Player session/action layer; voice-typed actions (`XrPlayerAction`) and `PlayerSessionController`. |
| `:player:core` | Player abstractions and domain models. |
| `:player:beam` | Phone-form-factor player (re-exports `:player:xr` for jniLibs / dex-merge reasons — see [Build Quirks](#build-quirks)). |
| `:player:tv` | TV-form-factor player (Leanback). |
| `:modes:film` | Browse/detail screens for movies/shows/episodes/collections, search. |
| `:data` | Jellyfin/TMDB/Seerr/OMDB API clients, Room DB, downloads, repositories, network shares (SMB/NFS), mDNS discovery. |
| `:fcast` | FCast protocol sender + receiver, plus the protocol-agnostic `cast/` abstraction layer that all sender protocols (FCast today; Google Cast / AirPlay in follow-up PRs) implement. Pure-Kotlin codec (`FCastFrame` / `FCastMessage`), `FCastSenderClient`, `FCastCastingController`, mDNS discovery + advertiser, `FCastReceiverServer`, foreground `FCastReceiverService`, `FCastIngressRouter` + `ExternalStreamPlayer` extension points, and the new `cast/` package: `CastProtocol`, `CastReceiver`, `CastCapability`, `CastMedia`, `CastSessionEvent`, `ProtocolAdapter`, `CastAdapterFactory`, `FCastAdapter`. No Jellyfin/Compose/Hilt deps so it stays JVM-testable. |
| `:core` | Shared UI components, LLM model manager, download workers, sync workers. |
| `:settings` | DataStore-based preferences (`AppPreferences`), voice telemetry, smart-language settings. |
| `:setup` | Server onboarding, login, address selection. |

**All app source lives under `app/unified/src/main/java`.** The former `app/xr/`, `app/tv/`, `app/beam/` trees have been consolidated into `:app:unified` (single source set, no staging task). Package layout inside `src/main/java`:

- `dev.jdtech.jellyfin.*` — the XR code (film screens, player XR helpers, voice composables).
- `dev.spatialfin.presentation.theme.*` — shared XR theme / spacings.
- `dev.spatialfin.tv.*` — TV home, detail, companion, users screens.
- `dev.spatialfin.beam.*` — Beam Pro phone screens and view-models.
- `dev.spatialfin.*` (top-level) — `UnifiedMainActivity`, `UnifiedApplication`, `DeviceClass`, companion log plumbing, nav root.

Because everything is in one module now, TV/Beam files that reference `R` or `BuildConfig` resolve them via an explicit `import dev.spatialfin.R` / `import dev.spatialfin.BuildConfig` — no build-time source rewriting. If you touch a file and the IDE can't find `R`, add that import by hand.

### Player Module Cross-Reference

`:player:beam` exposes `:player:xr` via `api(project(":player:xr"))` to keep `libass_jni.so` and `LibassRenderer` flowing through a single dex-merge path. Do not also `implementation(project(":player:xr"))` from `:app:unified` — it causes duplicate-class errors. The current `:app:unified` build pulls XR transitively through `:player:beam`.

**First-frame audio gate (TV + Beam `PlayerView` players).** `TvPlayerActivity` and `BeamPlayerActivity` host ExoPlayer in a Compose `AndroidView`-wrapped `PlayerView`/`SurfaceView` whose surface attaches asynchronously, while `PlayerViewModel.initializePlayer` calls `player.prepare()` then `player.play()` immediately. ExoPlayer's video renderer reports `STATE_READY` even with no surface, so audio used to start over a black screen with the overlay still showing the Play icon. Both screens now install a one-shot `Player.Listener` that forces `playWhenReady=false` until `onRenderedFirstFrame` (or `onTracksChanged` with no video track → audio-only), then resumes; a 15 s backstop releases the gate so playback can never hang behind it, and the controls / pause overlay are gated on `firstFrameRendered` with a spinner shown until then. `isPlaying` is now driven by `onIsPlayingChanged` (was a stale 500 ms poll). Do not re-add a `player.play()` in the `PlayerView` factory — it races ahead of the surface. XR (`SpatialPlayerScreen`) uses `SurfaceEntity`, a different attach path, and is not gated this way. <!-- added 2026-05-17: fix audio-before-video on TV/Beam -->

---

## Build Quirks

- **Single application module** — `:app:unified` only. XR / TV / Beam packages all live inside its single `src/main/java` tree; there are no longer any `app/xr`, `app/tv`, or `app/beam` source directories.
- **Product flavors** — `libre` (universal: phone / Galaxy XR / Beam Pro) and `tv` (Google Play Android TV track only). Build types: `debug`, `staging` (release-derived, `.staging` suffix), `release`. See [Play Track Bundles](#play-track-bundles) for which bundle goes to which track.
- **Performance gates** — `:baselineprofile` owns the `BaselineProfileRule` and Macrobenchmark journeys for startup, first shell scroll, and the exported TV player launch; `app/unified/src/main/baseline-prof.txt` is the checked-in seed profile until refreshed by a device run. Compose-bearing modules emit compiler reports and their debug stability references are enforced in `.github/workflows/ci.yml`.
- **Upstream modules only declare `libre`** — the `tv` flavor uses `matchingFallbacks += "libre"` so library dependencies resolve cleanly. If you ever add a new `:foo` module, remember to keep its single flavor named `libre` (or add `matchingFallbacks` / a `tv` flavor there too).
- **Release is `isMinifyEnabled = false`** — SceneCore `1.0.0-alpha03` release notes report minified-client support, but optimized builds using the current `1.0.0-alpha15` still crash at startup on Galaxy XR (`SM_I610`) with `AbstractMethodError` in `com.android.extensions.xr.function.Consumer.accept(Object)` (verified 2026-05-25). Targeted callback-member keep retries did not resolve it. Keep release unminified until a newer XR / R8 combination passes the on-device spatial startup test.
- **ABI splits** — `arm64-v8a` and `armeabi-v7a` are split for APK builds, but **disabled when building bundles** to work around AGP 8.9.0 issue [#402800800](https://issuetracker.google.com/issues/402800800).
- **`XrPlayerActivity` runs in a `:xrplayer` process and self-kills on finish** — works around [#503521336](https://issuetracker.google.com/issues/503521336): teardown after using `SurfaceEntity` leaks per-eye Filament `MaterialInstance`s, so `Session.destroy()` at ON_DESTROY SIGABRTs inside `FMaterial::terminate`. Under the current SceneCore API, `SpatialPlayerScreen` detaches its direct entities with `parent = null`; the Activity remains process-isolated (manifest `android:process=":xrplayer"`) and `Process.killProcess()` runs in `onPause`/`onStop` whenever `isFinishing` is true, so the lifecycle observer's `Session.destroy()` never fires. `MultitaskPlayerActivity` does not use `SurfaceEntity` and is not isolated. If the bug is fixed upstream, drop both the `:xrplayer` process tag and the `killProcessIfFinishing` helper. <!-- updated 2026-05-25: adopt SceneCore detach teardown API -->
- **KSP incremental** — Reliable on the current Kotlin 2.3.20 / KSP 2.3.6 pairing; builds run incrementally by default. `-Pksp.incremental=false` is kept as a last-resort escape hatch for Hilt-generated-class staleness, but it is not needed under normal development.
- **AndroidX XR release train** — XR artifact numbers are not lockstep. The official May 19, 2026 releases currently pair `runtime` / `compose` / `arcore` `1.0.0-alpha14` with `scenecore` `1.0.0-alpha15` and Compose Material3 `1.0.0-alpha17`, matching `libs.versions.toml`. Preserve a published compatible release train; do not force `scenecore` onto the shared `androidx-xr` version simply to make numbers identical. <!-- updated 2026-05-25: corrected non-lockstep DP4 release train -->

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
const val APP_CODE = 114        // monotonically increasing integer
const val APP_NAME = "2.7.13"   // semver
```

Always increment **both** `APP_CODE` and `APP_NAME` before producing a Play Store bundle. Duplicate version codes are a hard reject. `APP_CODE` is the `libre` bundle's versionCode; the `tv` flavor automatically derives `APP_CODE + 1_000_000` (see [Play Track Bundles](#play-track-bundles)), so bumping `APP_CODE` bumps both bundles.

**Pre-Bundle Requirement:** Before building a bundle for the Play Store, you MUST run `./gradlew versionCatalogUpdate` to check for library updates. If any updates are found, notify the user, commit the changes to `gradle/libs.versions.toml`, and advise the user to perform regression testing before final bundle generation.

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
| Chat tool-calling pre-pass | `player/xr/.../voice/ChatToolRegistry.kt` | registry |
| Player action execution | `player/session/.../voice/PlayerSessionController.kt` | controller |
| TTS + ducking | `player/xr/.../voice/SpatialVoiceSynthesizer.kt` | engine |
| Hand gesture activation | `player/xr/.../voice/SecondaryHandPinchDetector.kt` | detector |
| Jellyfin gateway | `data/.../repository/JellyfinRepositoryImpl.kt` | repository |
| Online-first / provenance gateway | `core/.../repository/SmartJellyfinRepository.kt` | repository (uses `Fetched<T>`) |
| SyncPlay / remote control | `player/local/.../presentation/SyncPlayCoordinator.kt` | collaborator (Host callback pattern) |
| Smart language / track selection | `player/local/.../presentation/PlayerTrackSelector.kt` | collaborator (Host callback pattern) |
| Assistant subtitle context | `player/xr/.../PlayerSubtitleContext.kt` | Compose holder (`@Stable`) |
| Libass render state + cue listener | `player/xr/.../LibassRendererState.kt` | Compose holder (`@Stable`) — owns its effects |
| Voice service bundle | `player/xr/.../PlayerVoiceServices.kt` | Compose holder (`@Stable`) |
| Voice overlay state + TTS machine | `player/xr/.../PlayerVoiceCoordinator.kt` | Compose holder (`@Stable`) — delegates to `AssistantSpeechTransitionEffect` |
| Offline gateway | `data/.../repository/JellyfinRepositoryOfflineImpl.kt` | repository (partial — see [Known Gaps](#known-gaps)) |
| Continue Watching filter | `data/.../repository/ResumeFilter.kt` | pure object (`ResumeFilterTest`) |
| Track signature ids | `player/local/.../presentation/PlayerTrackSignatures.kt` | pure object (`PlayerTrackSignaturesTest`) |
| SMB/NFS bridge | `data/.../network/{Smb,Nfs}FileClient.kt`, `NetworkStreamProxy.kt` | clients + local HTTP proxy |
| mDNS discovery (Jellyfin/SMB/NFS) | `data/.../network/NetworkDiscovery.kt` | discovery |
| Multi-protocol cast abstraction | `fcast/.../cast/CastProtocol.kt`, `CastReceiver.kt`, `CastCapability.kt`, `CastMedia.kt`, `CastSessionEvent.kt` (includes `SubtitleFidelityChanged` + `SubtitleFidelity` enum), `ProtocolAdapter.kt` (exposes `currentCapabilities: StateFlow<Set<CastCapability>>` for post-handshake widening), `CastAdapterFactory.kt`, `adapter/FCastAdapter.kt` (observes `FCastSenderClient.initialReceiver` and adds `NativeAss` + `EmbeddedFonts` when peer's `appName` starts with `SpatialFin`), `FCastReceiverMapping.kt`, `subtitle/AssStyleDetector.kt` (regex over override tags — `\pos`, `\an`, `\move`, `\frx/y/z`, `\k`/`\kf`/`\ko`, `\clip`, `\fad`, `\t(`, etc. — capped at first 50 Dialogue lines), `subtitle/SubtitlePolicy.kt` (pure decision tree: receiver capabilities × track styling × user preference → `Native` / `BurnIn` / `Degraded` / `NoSubtitles`), `subtitle/SubtitleWarningTracker.kt` (once-per-device-per-session toast dedup), `discovery/MultiProtocolDiscovery.kt` (fan-out FCast + Google Cast mDNS browse, returns a unified `Result(fcast, googleCast)`) | protocol-agnostic sender API. `CastCapability.SplitAv` is FCast-only and gates the split-A/V calibration / drift pipeline at every entry point (see Split-A/V row). `CastCapability.NativeAss` + `CastCapability.EmbeddedFonts` are also FCast-only and only added at session start (post-Initial) when the peer identifies as SpatialFin — they drive the sender-side ASS burn-in policy. PR 4 adds the AirPlay adapter behind the same interface; it inherits the sender-side subtitle policy without modification. <!-- updated 2026-05-13: PR 3 added MultiProtocolDiscovery + Google Cast adapter --> |
| Google Cast adapter | `fcast/.../cast/adapter/googlecast/CastMessage.kt` + `CastMessageCodec` (hand-rolled protobuf encoder/decoder — Chromium-public `cast_channel.CastMessage` shape), `CastV2Channel.kt` (TLS to host:8009 with permissive trust manager, u32-big-endian length-prefixed framing), `CastNamespaces.kt` + `CastMessages.kt` (the four V2 namespaces + the `LAUNCH` / `CONNECT` / `MEDIA_STATUS` / `SET_VOLUME` message constants; Default Media Receiver appId = `CC1AD845`), `CastJsonPayloads.kt` (kotlinx.serialization models for the JSON payloads carried inside `CastMessage.payload_utf8`), `GoogleCastAdapter.kt` (`ProtocolAdapter` implementation: TLS connect → CONNECT → LAUNCH → second virtual connection on returned `transportId` → LOAD on media namespace → control via `mediaSessionId` captured from first `MEDIA_STATUS`; 5 s PING heartbeat, capability set populated from RECEIVER_STATUS), `GoogleCastDiscovery.kt` (`_googlecast._tcp.local.` mDNS browser; capability inference from TXT `ca` bitmask). **Trust model:** the cast V2 ecosystem has no usable PKI (every Chromecast presents a self-signed cert whose CN rarely matches the LAN IP), so the channel accepts any cert and skips hostname verification. We're trusting the mDNS-discovered `id` instead, which is the same trade-off every Chromium-derived client makes. **No NativeAss path:** the Default Media Receiver supports WebVTT/TTML only — styled ASS reaches Chromecast via the sender-side burn-in transcode policy from PR 2. <!-- added 2026-05-13: PR 3 --> |
| AirPlay adapter (v1) | `fcast/.../cast/adapter/airplay/AirPlayHttpClient.kt` (OkHttp wrapper around the 6 AirPlay v1 verbs: POST /play `text/parameters`, POST /rate?value=, POST /scrub?position=, GET /playback-info, POST /stop, POST /volume?volume= with linear→dB conversion `-30 + 30v` and `-144` for absolute mute. Common headers User-Agent: MediaControl/1.0, X-Apple-Session-ID per-connection UUID, monotonic CSeq. Returns typed `AirPlayException.PinRequired` on HTTP 470), `BinaryPlist.kt` (hand-rolled `bplist00` parser; supports bool / int / real / ASCII + UTF-16BE strings / arrays / dicts; everything else logs+skips), `AirPlayAdapter.kt` (`ProtocolAdapter` implementation: stateless HTTP control surface with 1Hz `/playback-info` polling for state push; `setSpeed` returns `UnsupportedOperationException` — AirPlay v1 has no playback-rate primitive), `AirPlayDiscovery.kt` (`_airplay._tcp.local.` + `_raop._tcp.local.` browsers merged by host:port; capability inference from TXT `features` hex bitmask, bit 9 = v1 video, bit 26 = AirPlay 2 audio. AirPlay 2-only devices surface as disabled rows in the picker until pairing ships in PR 6). **No NativeAss path:** styled ASS reaches AirPlay via the same burn-in transcode policy from PR 2. **Scope:** AirPlay v1 video only; PIN-required HomeKit devices surface a typed error and disconnect — SRP-6a pairing handshake is PR 6 work. <!-- added 2026-05-13: PR 4 --> |
| FCast wire codec | `fcast/.../protocol/FCastFrame.kt`, `FCastMessage.kt`, `FCastPayloads.kt` | pure framing + serializers |
| FCast sender (cast out) | `fcast/.../sender/FCastSenderClient.kt`, `FCastCastingController.kt`, `PlayMessageBuilder.kt` | coroutine TCP client + lifecycle wrapper |
| FCast discovery + advertise | `fcast/.../discovery/FCastDiscovery.kt`, `FCastReceiverAdvertiser.kt` | mDNS browse / register on `_fcast._tcp` |
| FCast receiver (cast in) | `fcast/.../receiver/FCastReceiverServer.kt`, `FCastReceiverSession.kt`, `FCastReceiverService.kt` | foreground service + per-sender sessions |
| FCast→player adapters | `fcast/.../receiver/FCastIngressRouter.kt`, `ExternalStream.kt` | router interface + `ExternalStreamPlayer` extension point for arbitrary-URL playback |
| FCast inbound player Activity | `app/unified/.../fcast/FCastInboundPlayerActivity.kt` | 2D ExoPlayer Activity that plays whatever URL inbound FCast hands it. `@AndroidEntryPoint` so it can `@Inject` `JellyfinRepository` for embedded-font fetching. Wires `LibassTextRenderer` + `LibassRenderer` (from `:player:xr`) with `DefaultMediaSourceFactory.experimentalParseSubtitlesDuringExtraction(false)` so embedded ASS/SSA reaches libass with full styling. Rendered subs land on a stacked `ImageView` over the `PlayerView` and update at ~60 Hz. `libassRenderer.clearCache()` runs on every player seek (both `Player.Listener.onPositionDiscontinuity` and the inbound-FCast `seek` control-message path) to prevent ghost subtitles. **PR 6:** when the cast URL matches `/Videos/<uuid>/` the Activity fires `preloadLibassFontsAsync` off the main thread to fetch the item's font attachments via `repository.getMediaSources` + `getMediaAttachment`; the deferred result feeds into `LibassTextRenderer`'s `fontLoader` so anime with custom typefaces renders with the author's intended fonts. Non-Jellyfin URLs silently complete the deferred with an empty list — libass falls back to system font matching. <!-- updated 2026-05-13: PR 6 added embedded-font fetch --> |
| FCast app wiring + autopromote | `app/unified/.../fcast/FCastReceiverWiring.kt` | builds the `ExternalStreamPlayer`, calls `XrSpaceController.enterFullSpace()` on inbound Play when `fcastReceiverAutopromote` is on, registers the router with `FCastReceiverService` from `UnifiedApplication.onCreate`. |
| FCast picker UI | `fcast/.../ui/FCastReceiverPickerSheet.kt`, `FCastSenderHost.kt` | drop-in Compose receiver picker (mDNS browse + manual host:port) + sender-host that wraps a caller-supplied `FCastCastingController`. The in-player Beam dialog still uses `FCastSenderHost` directly; XR / global surfaces use the session-scoped picker host below. **Debug/release coexistence:** the receiver binds `FCAST_DEFAULT_PORT` (46899) on release but `FCAST_DEFAULT_PORT + 1` (46900) on the debug build, and `FCastReceiverWiring.resolveDisplayName` prefixes `"Dev "` on debug — because `.debug` installs alongside the release app and two receivers on one device cannot share the fixed port (loser hits EADDRINUSE → `stopSelf()` → never advertises). Senders honour the mDNS-advertised SRV port so discovery is port-agnostic. <!-- added 2026-05-16: prod/dev receiver coexistence --> |
| Split-A/V (XR video + remote audio) | `fcast/.../protocol/SplitAvMetadata.kt` (wire schema, embedded under `metadata.custom.splitAv`) + `app/unified/.../fcast/session/SplitAvController.kt` + `SplitAvPolicy.kt` (pure drift-correction policy + `isAvailable(receiver)` capability gate, `SplitAvPolicyTest`) + `SplitAvUnsupportedException.kt` + `SplitAvSessionRegistry.kt` (process-wide read-side handle to the active SplitAv receiver; SplitAvBridgeService consults it as defense in depth) + `player/core/.../splitav/SplitAvVideoMaster.kt` (master interface) + `SplitAvBridgeIpc.kt` + `SplitAvBridgeService.kt` (cross-process Messenger bridge: `XrPlayerActivity` runs in `:xrplayer`, controller in `:main`). **Protocol gating:** split-A/V is FCast-only by design — Google Cast / AirPlay don't expose commanded sub-frame start, a calibration side-channel, or sub-frame clock telemetry. Only `FCastAdapter` populates `CastCapability.SplitAv`; every entry point (`SplitAvController.start`, `CalibrationOrchestrator.calibrate`, `SplitAvBridgeService` REGISTER, `CastSessionManager.castSpatialItemSplitAv`) checks `SplitAvPolicy.isAvailable(...)` and refuses non-FCast receivers. XR is the **video master**; the picked receiver renders **audio** with a one-time calibration chirp (matched-filter onset detection `ChirpDetector.detectMatched`, RMS `detect` is the auto-fallback) via `RememberedReceiversStore.audioLatencyMs`, plus a per-codec delta (`AudioLatencyProfile`, keyed off the beacon's probed `audioFormat`). Drift pipeline (all pure, `SplitAvDrift.kt`, replay-tested): raw beacon drift → `BeaconFreshnessGate` (drop queued/stale beacons by their **θ-disciplined monotonic age** `senderNow − (monotonicSampleMs − θ)` vs a *sliding-window* min; null age when θ unconverged ⇒ no-op. Deliberately **not** a wall-clock `generationTime` delta against an all-time min — that mixed undisciplined heterogeneous clocks and dropped every beacon after ~1–2 h of crystal drift or permanently after one receiver NTP step) → `DriftEstimator` (EWMA + median outlier reject + drift-*rate* slope) → **`ClockOffsetEstimator`** (FCast v4 NTP four-timestamp Ping/Pong, min-δ clock filter → offset θ — **θ and `BeaconState.clockOffsetMs` are `Long`, never `Int`**: θ is the gap between the two devices' `elapsedRealtime` epochs and routinely exceeds 2^31 ms when uptimes differ by weeks; truncating wraps it and the v4 mapping emits multi-gigasecond drift → hard-seek-every-beacon → degrade → master pause; when θ + the beacon's `monotonicSampleMs` are present `expectedXrPositionMs` maps the beacon precisely instead of the `RTT/2` symmetry guess, falling back to the legacy path otherwise) → `SplitAvPolicy.decide` (asymmetric perceptual hold band — video-leads 30 ms / audio-leads 15 ms; hard-seek ≥ 500 ms) → `NudgeController` (PI + feed-forward speed trim applied **continuously**, not the old transient ±1 % bang-bang — the integral nulls steady clock skew that pure proportional could not). All local-interval math uses a monotonic clock (`SystemClock.elapsedRealtime`), never wall-clock; Ping/Pong RTT loops are children of the session coroutine (not the singleton scope) so repeated sessions don't leak; per-beacon `SplitAvTrace` rows feed the offline `SplitAvReplayTest` harness for gain tuning without hardware. Gated by an 8 s warmup grace from first `playWhenReady=true`. Mirror cascades only on *transitions* (initial paused state is seeded, never sent — stops the boot-time deadlock). Receiver loads with `playWhenReady=false` until the master signals ready, then a Seek frame pre-aligns the receiver to the master's actual `currentPosition` (fixes resume-from-saved-position). **FCast v4 synchronized start:** when θ has converged by first-play the sender seeks the receiver to where the free-running video *will* be at `T0 = now + ALIGNED_START_LEAD_MS` and sends `Resume(atReceiverMonotonicMs = T0 + θ)`; the receiver schedules `exo.play()` for that monotonic instant (clamped — past/too-far ⇒ play now) so both render the same media position at the same wall instant and the warmup transient disappears. Pre-v4 peer or θ-not-yet-converged ⇒ the plain resume-now path above (warmup-grace backstop retained). Protocol v4 extensions (`SPLIT_AV_SYNC_V4.md`) are optional bodies on the otherwise body-less Ping/Pong/Resume opcodes — a null payload is byte-identical to v3, emitted only when the negotiated version ≥ 4, so non-SpatialFin interop is unaffected. `pauseFromMaster` is edge-triggered on TV→Paused transitions only. Receiver-initiated stop calls `endByReceiver()`: unmutes the master via `setAudioMuted(false)`, cancels the drift loop, and `PlayerSplitAvAdapter.splitAvActive` flips false so `PlayerVoiceCoordinator` stops re-forcing volume=0. Master-initiated stop (the XR in-player cast sheet's "Stop split-A/V" button) goes `:xrplayer` → `SplitAvBridgeIpcClient.requestEndFromMaster()` → IPC `MSG_END_FROM_MASTER` → `SplitAvBridgeService` (`@AndroidEntryPoint`, injects the `@Singleton SplitAvController`) → `endFromMaster()` (stop the receiver cast + `endByReceiver()`); the button must use this IPC path because the `:xrplayer`-local `FCastCastingController` has no session (it lives in `:main`) — calling `stopCast()` there was a silent no-op. **Seamless fold-back and local capability pre-check:** fold-back is now fully seamless. At launch, the split-A/V master disables `TRACK_TYPE_AUDIO` defensively to prevent ExoPlayer from failing on unsupported codecs (Atmos, TrueHD, DTS-HD). A player listener tracks when `onTracksChanged` fires, pre-checks the source audio codec against the local device's `ReceiverAudioCodecs.fromCapabilities`, and if it is locally decodable, pre-enables the audio track type silently during preparation. During fold-back, `onFoldBackToLocal` only changes track selection parameters if the track was actually kept disabled, avoiding a brief audio hiccup for locally decodable tracks. <!-- updated 2026-05-25: implemented seamless fold-back and local capability pre-check to eliminate audio hiccups --> **Audio-codec compatibility (capability-driven, never a hardcoded table):** the receiver advertises its *authoritative* passthrough-capable codecs (`ReceiverAudioCodecs.fromCapabilities` over `media3 AudioCapabilities`) on the `PlaybackUpdateMessage.supportedAudioCodecs` SpatialFin beacon extension; `castSpatialItemSplitAv` direct-streams (lossless/Atmos preserved) iff `ReceiverAudioCodecs.canRenderDirect(sourceCodec, caps)` (universal-software set ∪ reported passthrough set; E-AC-3-JOC degrades to E-AC-3), else server-transcodes via `JellyfinRepository.getAudioTranscodeStreamUrl` — a constrained-profile HLS stream (`master.m3u8`+TS, audio→best of E-AC-3/AC-3/AAC the chain supports, video copied, **unbounded bitrate so the only transcode reason is the codec, never bandwidth**) — *not* the removed `static=false&audioCodec=` raw-`/stream` rewrite (that produced an unreadable live-transcode container ⇒ receiver `groups=0`). First cast to an unseen receiver uses the conservative software-only default and **self-corrects**: the first authoritative beacon triggers a one-shot transparent re-cast (`recast=true`, `recastDone`-guarded) from the current position with the transcode decision — fixes the TrueHD-on-DD+-soundbar silent failure even though its audio track is dropped (`groups=0` ⇒ no `audioFormat` signal to react to). `castAudioFallback` pref still hard-overrides (`passthrough`=always direct, `transcode_aac`=always transcode); user sees `pendingAudioTranscodeNotice` instead of silence. The legacy `withCastCompatibleCodecs`/`audioCodecDecision` table now serves only the non-split `castSpatialItem` path. **`app/unified` must depend on `media3-exoplayer-hls`** — the FCast inbound receiver plays the transcode as an HLS `master.m3u8`; without the module `DefaultMediaSourceFactory` falls back to `ProgressiveMediaSource`, the playlist hits `UnrecognizedInputFormatException` and the receiver shows `groups=0` (this was the real reason *any* transcode silently failed there). <!-- updated 2026-05-17: split-A/V audio decision now receiver-capability-driven (supportedAudioCodecs beacon + ReceiverAudioCodecs + constrained-HLS getAudioTranscodeStreamUrl + self-correcting re-cast); fixed TrueHD/DTS-on-non-passthrough-receiver silent failure --><!-- updated 2026-05-16: sync overhaul — PI continuous trim + DriftEstimator + freshness gate + matched-filter chirp + monotonic clock + ping-loop leak fix; FCast v4 NTP clock-sync + synchronized start (SPLIT_AV_SYNC_V4.md); freshness gate re-based on θ-disciplined windowed age (was an undisciplined wall-vs-monotonic all-time-min that disabled drift correction over long playback) -->
| Cast session manager (global) | `app/unified/.../fcast/session/CastSessionManager.kt` (+ `FCastSessionUi.kt`, `PlaybackLauncher.kt`, `di/FCastModule.kt`) | **PR 5 update:** the manager now exposes protocol-agnostic active-session flows — `activeMediaState`, `activePositionMs`, `activeDurationMs`, `activeVolume` — bridged from FCast's `controller.remoteState` *or* from the active `ProtocolAdapter`'s event stream (`bridgeAdapterEvents`) depending on which protocol is currently driving the cast. The control methods (`pause`/`resume`/`seek`/`setVolume`/`setSpeed`) branch on `_pickedTarget.value.protocol` so the mini-controller drives Cast and AirPlay sessions through the same call sites that used to be FCast-only. Hilt `@Singleton` that wraps `FCastCastingController` so a cast survives Activity boundaries. Class was renamed from `FCastSessionManager` in PR 2 — the package stays `dev.spatialfin.fcast.session` until the Google Cast / AirPlay adapters land in PR 3/4 (a package move now would force a 14-file import-only diff in this PR with nothing to show for it). Owns the global picker visibility (`pickerVisible`), the picked-receiver state (`pickedReceiver`), `JellyfinRepository`-backed URL resolution for `castSpatialItem(item)`, the discovery cache (1.5 s opportunistic scan, 30 s freshness window), and the `lastUsedFCastReceiverHostPort` pre-select hint. Also owns the **sender-side subtitle policy**: a per-host:port `peerCapabilityCache` populated from `controller.peerInitial` (forwarded from `FCastSenderClient.initialReceiver`), an `ensurePeerCapabilityObserver()` job that updates the cache the first time each receiver sends its Initial frame, and `castSpatialItem` applies `SubtitlePolicy.decide(...)` before building the URL — `withSubtitleBurnIn(decision)` appends `static=false&SubtitleStreamIndex=N&SubtitleMethod=Encode` for `BurnIn` decisions, leaves the URL alone otherwise. Emits `subtitleFidelity: StateFlow<SubtitleFidelity>` (`Native` / `Transcoding` / `Degraded` / `None`) so the UI can render the "Transcoding for subtitle compatibility" chip, and `pendingSubtitleDegradationWarning: StateFlow<String?>` for the one-time toast when a styled track was about to be sent to a receiver lacking libass under "Faster start". Optimistic capability assumption on first cast: assume SpatialFin peer (NativeAss + EmbeddedFonts) so SpatialFin → SpatialFin casts hit the native path immediately; the cache corrects on the next cast if the peer turned out to be third-party. `FCastSessionUi.kt` exposes the `LocalFCastSession` composition-local (installed by `NavigationRoot` for XR and `BeamNavigationRoot` for Beam) so hero/detail surfaces can render a cast affordance without threading the manager through every screen signature. `FCastGlobalPickerHost` / `FCastMiniController` are the two composables form-factor roots still mount; the picker host owns the once-per-session degraded-fidelity toast, the mini-controller renders the transcoding chip. The cast button itself lives inline: an XR `NavigationRailItem` above the user avatar, a Beam tab-cell next to the Network tab in both the sidebar and bottom-nav, plus an inline `BeamCastIconButton` / `XrIconActionButton` immediately left of the 3-dots overflow on every playable hero card. All surfaces use `core/ic_cast.xml` (custom FCast logo). `launchPlayback(...)` is the play-tap interceptor: routes to FCast when `hasCastIntent()` is true, else falls through to the local-player intent. <!-- updated 2026-05-13: added sender-side subtitle policy (PR 2 Half B) --> |
| Downloads | `core/.../work/` workers + `data/.../downloads/DownloadStorageManager.kt` | WorkManager (prep → resumable → integrity sweep) |
| Google TV Watch Next sync | `core/.../watchnext/WatchNextSync.kt` + `WatchNextScheduler.kt` + `core/.../work/WatchNextWorker.kt` | WorkManager + tvprovider |
| Play deep link (`spatialfin://play`) | `core/.../deeplink/PlayDeepLink.kt` | shared URI build/parse |
| Google TV global search | `core/.../search/SpatialFinSearchProvider.kt` + `app/unified/src/tv/res/xml/tv_searchable.xml` | ContentProvider + searchable XML |
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
- **Cinema scale:** Use large `SpatialPanel` dimensions (≥1400dp) for cinematic feel. The unified main browsing panel is `1400dp × 824dp` (`UnifiedMainActivity` constants), matching the current Compose XR examples and staying inside the Galaxy XR Home Space window. <!-- updated 2026-05-22: reduced from 1792×1008 during DP4/Galaxy XR immersive debug -->
- **Depth & focus:** `SpatialDialog` / `SpatialPopup` push the parent panel back **125dp**.
- **User agency:** For Compose XR panels, prefer `SubspaceModifier.transformingMovable()` / `movable()` / `resizable()` before dropping to SceneCore components. Reserve `MovableComponent` / `ResizableComponent` for direct SceneCore entity trees such as the player video surface hierarchy. <!-- updated 2026-05-22: direct Compose movement fixed Galaxy XR immersive app panel rendering -->

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
- **Galaxy XR DP4 blank immersive app panel:** The app launched fine in Home Space, but switching to immersive showed only passthrough. Logs showed `FullSpaceContent` was reached and the task entered `full-space-managed`; the bug was the browsing UI's `SpatialPanel` nested under a custom `GroupEntity` via `SceneCoreEntity`, not the panel being too large or behind the user. Fix was to render `SpatialPanel` directly inside `Subspace` with `SubspaceModifier.width(1400.dp).height(824.dp).transformingMovable()`. Keep direct SceneCore parenting for the video player path (`SurfaceEntity` + controls) where it is needed, but do not wrap the unified browsing panel in `GroupEntity` again without Galaxy XR validation. <!-- added 2026-05-22: real-device Galaxy XR immersive regression -->

### Spatial Hierarchy & Parenting
For low-latency movement of complex **player/video** UIs:
- Use independent UI and subtitle roots for controls, orbiters, subtitles, and prompt hit targets when direct SceneCore control is required for pose, scale, stereo mode, frame rate, or teardown.
- Keep `SurfaceEntity` placement explicit. On Galaxy XR DP4, the stable path is `SurfaceEntity.create(..., pose = launchPose, parent = activitySpace)`; attaching its `MovableComponent` does not alter that hierarchy. Mirror the surface pose/scale onto UI and subtitle roots. Creating the surface at `Pose.Identity` and parenting it under another root reproduced the "controls + audio but no video" failure. <!-- updated 2026-05-25: native surface affordance without reparenting -->
- Apply `MovableComponent` to the directly attached video `SurfaceEntity` so Android XR draws the native move affordance on the visible frame; mirror its translation-only pose to the logical UI/subtitle roots without parenting the surface beneath them. <!-- updated 2026-05-25: surface-bound native move affordance -->
- Lock Compose children to fixed offsets via the Compose `SceneCoreEntity` wrapper. For raw SceneCore entities, prefer explicit `parent` at creation when the API supports it.
- Do **not** generalize this pattern to the unified browsing app panel. On Galaxy XR DP4, a custom `GroupEntity` parent around a Compose `SpatialPanel` produced a blank Full Space panel; the stable browsing path is direct `Subspace { SpatialPanel(...) }`. <!-- updated 2026-05-22: separate player SceneCore hierarchy from browsing Compose panel -->

### Rendering & Media Strategy
- `SurfaceEntity` for video, with Media3 (ExoPlayer) feeding the surface.
- **Galaxy XR DP4 video debugging:** If playback has audio/controls but no picture, first check `XR_VIDEO` logs. A `video size changed` plus `first rendered frame` means Media3 is decoding into the surface; the remaining problem is SceneCore placement/visibility. During the DP4 fix, reading entity poses in `Space.ACTIVITY` folded in the system full-space transform and inflated the saved/mirrored video depth to ~13.7 m. Pose persistence and mirror loops must use `Space.PARENT`, with `lastReportedMovePose` preferred over activity-space samples. Validate with headset screenshots/logcat, not compilation alone. <!-- added 2026-05-22: fixed Galaxy XR audio-without-video regression -->
- Spatial video shapes: `Quad` (flat), `Hemisphere` (180°), `Sphere` (360°). SBS and MV-HEVC supported.
- DRM: `surfaceProtection = SurfaceProtection.Protected`.
- 2D overlays: `SpatialPanel`. Toolbars / secondary controls: `Orbiter`.
- 3D models: use Compose XR `SpatialGltfModel` for static Compose-owned models. The paused mascot in `SpatialPlayerScreen` is now declarative (`rememberSpatialGltfModelState` + `SpatialGltfModel`) with pose, scale, delayed visibility, and `SpatialCapability.SPATIAL_3D_CONTENT` gating in Compose; retain direct SceneCore entities only for the movable video/surface hierarchy. <!-- updated 2026-05-25: migrated paused GLTF mascot to native Compose XR API -->

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
2. **`SpatialCommandCoordinator`** — keyword/replay-library matches, screen-aware parsing (`HOME` vs `PLAYER`), and model-backed parsing through `GemmaCommandParser`. The parser's primary path is LiteRT-LM typed tool calling (`ConversationConfig.tools` + a single `interpret_command` OpenAPI tool with a constrained `action` enum → `ParsedToolCall` with `Map<String, Any?>` arguments — no JSON string extraction, no retry). AICore falls back to JSON-in-text with one strict retry because `mlkit-genai-prompt:1.0.0-beta2` doesn't expose schemas yet.
3. **`XrPlayerAction`** — typed action boundary between parsing and execution.
4. **`PlayerSessionController`** — executes playback/search/selection actions; owns pending-selection state for follow-ups like "play the first one."
5. **`SmartChatEngine`** — handles `ChatQuery`; builds prompt context **only after** a skill is selected.
6. **`MediaSkillRegistry`** — selects the built-in skill, validates input, decides reply mode (`DIRECT` / `MODEL` / `FALLBACK`).
7. **`ChatToolRegistry`** — optional pre-pass that runs before the main chat inference when the skill lands on `GENERAL_CHAT`. Calls `VoiceAiEngine.runToolCall` against a single `research_media` tool with an `action` enum (`lookup_title`, `lookup_person`, `describe_current_item`, `play_media`, and `web_search` when `WebSearchClient.isConfigured()`) — mirrors the `interpret_command` pattern in `GemmaCommandParser`. Gated on LiteRT with a non-CPU backend; AICore has no tool schema support and cloud doesn't need this wedge. Returns structured notes that render as a "Research notes" block in `PromptContext.researchNotes`, or a `play_media` request that short-circuits the chat round-trip. <!-- updated 2026-04-22: added play_media tool -->
8. **`WebSearchClient`** — fronts web search for the `web_search` tool action. Prefers the paired companion's `GET /api/v1/search?q=...` (auth via existing `X-Setup-Token`), falls back to a user-pasted SearXNG URL (`voiceAssistantSearxngUrl` preference). Graceful no-op when neither is set. The companion is expected to run a SearXNG sidecar bound to 127.0.0.1 — do not hit SearXNG directly from the headset even on a LAN, it has no auth of its own.

### Built-In Skills (`MediaSkillRegistry`)

`PLAYBACK_CONTROL`, `WATCH_RECOMMENDER`, `LIBRARY_SEARCH`, `RECAP`, `DIALOGUE_EXPLAINER`, `METADATA_QA`, `CONTINUE_WATCHING`, `MOOD_SURPRISE`, `EXTERNAL_KNOWLEDGE`, `GENERAL_CHAT`.

Extend skills here, not via prompt branches.

### Recommendation Flow

1. `MediaSkillRegistry` → `WATCH_RECOMMENDER` or `MOOD_SURPRISE`.
2. `RecommendationPlanner` extracts filters / follow-up context.
3. Candidates from `repository.{getSuggestions, getResumeItems, getFavoriteItems}` and prior `RecommendationContext`.
4. Planner ranks by media type, runtime ("under N minutes"), comedy/new/English/anime-avoidance, comfort, current-media genre overlap, prior seed items.
   - After scoring, an MMR pass (`RecommendationPlanner.applyMmrDiversity`, lambda=0.5) reranks the top-15 shortlist to the final 6 so the picks span genre / decade / franchise instead of clustering on one strand. The MMR constants live at the top of the planner; tune lambda up if picks feel too scattered, down if they feel franchise-heavy.
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

### Home vs Player vs Beam

- `SpatialPlayerScreen` owns the richest voice UI (interactive results, recommendation cards, subtitle context).
- `UnifiedMainActivity` Home Space shares the parser and chat engine but is text-first today.
- `BeamNavigationRoot` (phone) reuses `HomeVoiceController` end-to-end. Mic FAB cancels any busy state (listening / processing / TTS). Feedback chip is anchored top-center so it never sits under the FAB. Recommendations render in a bottom sheet that routes taps through `BeamPlayerActivity.createIntentForSpatialItem`. **Do not fall back to transcript→Search** — that was the old behavior that bypassed the AI layers entirely.
- `ChatQuery` must be supported in **all three** surfaces. `RecommendationContext` and short conversation history must persist across follow-up turns in each path.
- **Recommendation replies are title-only** (`RecommendationPlanner.buildRecommendationReply` + the `WATCH_RECOMMENDER` / `MOOD_SURPRISE` task instructions in `MediaSkillRegistry`). Adding per-item rationale was rejected — the model's reasons were weak enough to undermine the picks. If you reintroduce reasons, talk to the user first.

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
./gradlew :app:unified:compileLibreDebugKotlin
```

### Where To Look In Logs

User logs land under `Downloads/SpatialFin/`. Tags worth grepping: `SpatialVoiceService`, `SpatialCommandCoordinator`, `GemmaCommandParser`, `LlmChatModelHelper`, `VoiceTelemetryStore`, `SUB:` (libass).

### Failure Triage Order

1. **Speech layer** — did recognition start? soft errors `5`/`7`? did retry happen?
2. **Parser layer** — was transcript normalized? keyword/replay match? Did the LiteRT tool-call return `interpret_command` with a known `action`? Did the AICore JSON fallback truncate?
3. **Skill layer** — which `selectedSkill`? `validatedInput` sensible? reply was `DIRECT`/`MODEL`/`FALLBACK`?
4. **Execution / UI layer** — was `ChatQuery` actually handled on the current screen? did `recommendedItems` populate? did the overlay show feedback?

### Common Real Failure Patterns

- **`TF_LITE_AUX not found in the model`** — NPU unsupported for that model/device. GPU fallback expected. Cache the failing backend.
- **`GemmaCommandParser: tool-call path failed — falling back to JSON parse`** — the LiteRT tool-call returned null or threw. Expected on AICore (Gemini Nano has no schema support in `mlkit-genai-prompt:1.0.0-beta2`); investigate on LiteRT if the model's been behaving. The JSON-in-text retry still runs behind this, so it's not a user-visible failure on its own.
- **`GemmaCommandParser: raw response: {"`** — truncated JSON on the fallback path. Rare now that typed tool calling is primary on LiteRT; more likely on AICore. Parser retries once with a stricter instruction before falling through to `Unrecognized`.
- **Speech soft errors `5` / `7`** — transient recognizer issues. One automatic retry expected.
- **"Thinking…" never resolves** — usually not a parser problem. Check that `ChatQuery` is handled on the current screen and that feedback / results UI is rendered.
- **App silently disappears on XR space-mode switch (multitask ↔ immersive) or any Activity lifecycle transition** — **not a code crash**. Logcat fingerprint is `ActivityManager: Killing <pid>:dev.spatialfin.debug ... stop dev.spatialfin.debug due to set debug app` followed by `Zygote: Process <pid> exited due to signal 9 (Killed)`. No Java stack, no tombstone. Cause: `am set-debug-app -w dev.spatialfin.debug` is armed on the device (usually left behind by Android Studio's "Attach debugger to Android process" or a prior `adb shell am set-debug-app -w`). With `-w`, Android waits for a debugger and SIGKILLs the process on lifecycle events when none is attached. Verify with `adb shell settings get global debug_app` (non-null value means armed). Fix: `adb shell am clear-debug-app`. Does not persist across reboots, but Android Studio can re-arm it.

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

### TV Form-Factor Perf (Chromecast with Google TV / low-end Leanback)
TV runs on weak Amlogic GPUs (Mali-G31) and 2 GB RAM. Compose features that are cheap on XR / phone become visibly choppy on TV.
- **No Compose `.blur()` on TV backgrounds.** `TvAmbientBackground` (`app/unified/src/main/java/dev/spatialfin/tv/TvNavigationRoot.kt`) uses scrim + radial gradients instead. Blur radii above ~24dp stall focus navigation on these SoCs.
- **Coil crossfade is disabled on TV.** The `SingletonImageLoader.Factory` in `UnifiedApplication.newImageLoader` branches on `DeviceClass`; TV loads images with crossfade off to avoid stacking fades during fast D-pad navigation.
- **Ambient backdrop decodes at 960×540.** `TvAmbientBackground` wraps the URL in an `ImageRequest.Builder(...).size(960, 540)` — a full-screen 1920×1080 decode is 8 MB peak per frame on a 2 GB device.
- **LLM eager init is skipped on TV.** `UnifiedApplication.eagerInitializeLlmIfNeeded` returns immediately for `DeviceClass.TV`. TV settings now expose voice-assistant preferences (picker, cloud key, Gemma/AICore management) to match Beam/XR, but TV has no active voice *listener* — eager init would warm an engine nothing on TV invokes. If you wire up a TV voice entry point later, flip this gate.
- **Lazy list items must be keyed.** TV grids in `TvNavigationRoot.kt` chunk items into rows; every `items(rows, ...)` call passes `key = { it.firstOrNull()?.id ?: it.hashCode() }` so focus state and animations are preserved across scroll recycles.
- **Focus-scale animations are tween, not spring.** 10 sites in `TvNavigationRoot.kt` use `animateFloatAsState(animationSpec = tween(120), ...)` — the default spring's ~350ms animation forces graphicsLayer re-rasterization too long on weak GPUs.
- **Nested lazy prefetch bumped to 4.** `TvContentShelf` / `TvLibraryShelf` construct their `LazyListState` through `rememberTvShelfListState()` with `LazyListPrefetchStrategy(4)`. Default nested prefetch is 2, which leaves visible pop-in as the user D-pads down the home screen's outer `LazyColumn` and a new shelf slides in. Only tunes **nested** prefetch — direct horizontal D-pad within a row is still fixed at 1-ahead in the default strategy. Custom strategies would need a full `LazyListPrefetchStrategy` implementation.
- **Home's `loadViews()` runs after first paint.** `HomeViewModel.loadData` emits `isLoading = false` *after* suggestions/resume/next-up arrive, then calls `loadViews()` (N+1 API calls, one per library for latest media) in the same coroutine so the home screen paints immediately.
- **Google TV Watch Next sync.** `WatchNextScheduler` publishes resume + next-up into the system Watch Next row on TV only (gated by `FEATURE_LEANBACK`). `UnifiedApplication.startDeferredInitialization()` enqueues a 30-min `PeriodicWorkRequest` after first frame (or its non-UI fallback); `HomeViewModel.loadData` fires a one-shot after each successful online refresh. Tap targets go straight to `TvPlayerActivity` via the `spatialfin://play?id=<uuid>&kind=<Movie|Episode>&startMs=<long>` deep link — see `PlayDeepLink` for the canonical builder/parser and `app/unified/src/tv/AndroidManifest.xml` for the `VIEW` intent-filter (plus `exported=true`) on the TV flavor. Fully-played items (`played = true`) are dropped and their rows deleted, per Google's UX guidance.
- **Play deep link.** `spatialfin://play?id=<uuid>&kind=<Movie|Episode>&startMs=<long>`. Built and parsed through `dev.jdtech.jellyfin.deeplink.PlayDeepLink` (shared between WatchNextSync, TvPlayerActivity, and the global-search ContentProvider). Only the TV flavor's manifest declares the intent-filter today; future consumers (AppFunctions, share-sheet hand-offs) should reuse this URI shape rather than inventing a parallel one.
- **Google TV global search.** `SpatialFinSearchProvider` surfaces Room-cached movies / shows / episodes into the system search bar and Google Assistant via `SearchManager.SUGGEST_URI_PATH_QUERY`. The provider authority is `${applicationId}.search` (templated through `resValue("string", "search_authority", ...)` in `app/unified/build.gradle.kts` so debug/staging/release installs don't collide). Scope is deliberately **local cache only** — a LAN round-trip to Jellyfin on every keystroke would ANR. Suggestion taps fire `ACTION_VIEW` + `spatialfin://play?...` which lands in `TvPlayerActivity`. `ACTION_SEARCH` (user hits Enter, Assistant voice hand-off) lands on `UnifiedMainActivity`, which extracts `SearchManager.QUERY` and pre-populates `TvSearchViewModel` before navigating to `TvRoute.Search`. When `buildFeatures.resValues = true` gets disabled, the authority resource vanishes and the provider installs with a broken authority — don't flip it off.

---

## Release & Compliance Mandates

### Dependency Management
- `./gradlew versionCatalogUpdate` to bump `gradle/libs.versions.toml`. Always review the diff before committing.
- **Avoid hardcoding versions:** Never hardcode version strings directly in the `[libraries]` section of `gradle/libs.versions.toml`. Always define a version in the `[versions]` block and use `version.ref` to maintain a single source of truth and ensure compatibility with automated update tools.

### Versioning
- Bump both `APP_CODE` and `APP_NAME` in `buildSrc/src/main/kotlin/Versions.kt` for any Play Store bundle. Duplicate version codes are a hard reject.

### R8 / ProGuard
- Release is currently unminified (see [Build Quirks](#build-quirks)). The keep rules for `com.android.extensions.xr.*` in `app/unified/proguard-rules.pro` are the floor — do not remove them, even though minification is off today, because an optimized alpha15 build still faults across this boundary on Galaxy XR.

  ```proguard
  -keep class com.android.extensions.xr.** { *; }
  -keep interface com.android.extensions.xr.** { *; }
  ```

### Play Track Bundles

Google Play splits delivery by form factor. We ship **two** AABs from the same source tree:

| Flavor | Task | Leanback | versionCode | Play track(s) |
|---|---|---|---|---|
| `libre` | `./gradlew :app:unified:bundleLibreRelease` | `required="false"` | `APP_CODE` | phone / Galaxy XR / Beam Pro |
| `tv` | `./gradlew :app:unified:bundleTvRelease` | `required="true"` | `APP_CODE + 1_000_000` | Android TV (Google TV Streamer, etc.) |

Bundle outputs:
- `app/unified/build/outputs/bundle/libreRelease/spatialfin-libre-release.aab`
- `app/unified/build/outputs/bundle/tvRelease/spatialfin-tv-release.aab`

**Hard rule — do not flip `android.software.leanback` to `required="true"` in the main `AndroidManifest.xml`.** It is a `${leanbackRequired}` manifest placeholder. `libre` leaves it `"false"` (installs on every form factor); `tv` overrides it to `"true"` via `manifestPlaceholders` in its flavor block. Making it unconditionally required would make the `libre` bundle install only on TV devices, silently breaking XR and Beam Pro. Play's TV track is the one place that *demands* it be required.

Same rule for the `xrSpatialFeatureRequired` placeholder — default stays `"false"` unless you have a concrete reason to gate on XR capability.

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
- **`core/.../utils/DownloaderImpl.kt`** — synchronous prep (media-source fetch, segment fetch, trickplay download, item/source/user-data DB writes, subtitle worker enqueueing) was lifted into `core/.../work/DownloadPreparationWorker.kt`, which then chains `ResumableDownloadWorker`. The impl now just inserts a placeholder PENDING row and enqueues the prep worker, so navigating away no longer interrupts the download pipeline. <!-- updated 2026-05-02: prep moved to WorkManager -->
- **`core/.../utils/DownloaderImpl.kt#downloadItems` (bulk)** — queues `BulkDownloadResolutionWorker`, which resolves per-episode media sources after the UI caller returns and hands each item to `DownloadPreparationWorker`. Item-id payloads are chunked at 128 UUIDs per request to remain below WorkManager's 10 KB `Data` limit; preparation/resumable requests require `UNMETERED` unless the mobile-data preference explicitly permits metered downloads. <!-- updated 2026-05-25: bulk resolution is worker-owned and constraint guarded -->
- **`core/.../work/DownloadIntegrityWorker.kt`** — sweeps every PRIMARY *and* SUBTITLE task with status `STATUS_SUCCESSFUL`, validates `File(finalPath).exists() && length() == totalBytes` (AES-CTR keeps ciphertext at the same length as plaintext, so the size check is valid even with content encryption on; subtitles aren't encrypted so the check is even more straightforward). On mismatch the order is **DB-first then file-delete**: flip `sources.path` (PRIMARY) or `mediastreams.path` (SUBTITLE) back to the `.download` tempPath so `isDownloaded()` reflects reality, mark the task PENDING, *then* delete any leftover bytes, then re-enqueue `ResumableDownloadWorker` with `ExistingWorkPolicy.KEEP`. The reverse order would leave a `STATUS_SUCCESSFUL` row pointing at a deleted file if we crashed mid-requeue. Triggered from `UnifiedApplication.startDeferredInitialization()` after first frame (with a non-UI fallback) and from `DownloadsViewModel.init` via `Downloader.verifyDownloads()`. <!-- updated 2026-05-24: application trigger deferred after first frame -->
- **Multi-select / "Download all unwatched"** — `EpisodeCard` accepts `selectionMode` / `selected` / `onLongClick`; `SeasonScreen` tracks selected episode IDs in remember-state, shows a selection top bar with "Download N" when active, and an overflow menu with "Download all unwatched (N)" / "Download season (N)" otherwise. `SeasonAction.DownloadEpisodes(episodes)` routes through `SeasonViewModel.downloadEpisodes` → `Downloader.downloadItems(episodes, BulkDownloadSettings())`. `SeasonViewModel` now takes `Downloader` in its constructor — if you add another season-screen surface, mirror this DI shape rather than reaching for `DownloaderViewModel` (which is for single-item flows). Beam phone already had its own bulk-download dialog (`BeamBulkDownloadDialog`); these XR additions don't touch it. <!-- added 2026-05-03 -->
- **`SpatialPlayerScreen.kt`, `PlayerViewModel.kt`** — screen down to ~2.07k lines (from 2.40k); VM down to ~1.8k (from 2.77k). Phase 1+2 lifted SyncPlay / track selection out of the VM and pulled voice services + AI subtitle context out of the screen. Phase 3 extracted the libass renderer (`LibassRendererState`) and the voice state / TTS state machine (`PlayerVoiceCoordinator`) — both own their own effects so the screen no longer juggles ~15 libass/voice `LaunchedEffect`s. What still lives inline is `requestVoiceCommand` (27-param call into `startVoiceCapture`), the follow-up auto-start effect, and the pinch-detector gesture-collection effect — all three need `requestVoiceCommand` in-scope, so lifting them requires either flattening `startVoiceCapture`'s surface or splitting the function.
- **XR `SurfaceEntity` hierarchy rule (alpha15/DP4)** — parenting the video surface below a movable/scaled root caused black output on Galaxy XR. The working path keeps `SurfaceEntity` attached directly to `activitySpace`, attaches its native `MovableComponent` affordance directly to that visible surface, and mirrors its pose/scale to independent generic UI/subtitle roots created via `Entity.create`. <!-- updated 2026-05-25: surface-bound affordance preserves sibling-surface layout -->
- **XR player move/input arbitration** — controls and video use separate projected roots. The native `MovableComponent` affordance surrounds the video frame with a `0.40 m` margin on each edge for a larger selectable glowing border, and movement accepts translation only (`Quaternion.Identity`) so the screen cannot tilt during a reposition. During an active pointer gesture inside `ControlPanelUI`, temporarily detach movement so a button press or drag cannot be interpreted as a video grab, then reattach on pointer release; each accepted video pose synchronously updates the subtitle root to retain alignment. <!-- updated 2026-05-25: enlarged native frame affordance and translation-only movement -->
- **`UnifiedMainActivity.kt`** — voice was extracted into `HomeVoiceController` and pose persistence into `PanelPoseController`; the Activity now owns device-class branching, the FullSpace Subspace tree, and first-frame notification for deferred application startup.
- **Skill-classifier drift sentinel** — `player/xr/.../voice/SkillClassifierRegressionTest.kt` runs `MediaSkillRegistry.selectSkill` over ~25 seeded queries and reports every divergence in a single assertion with the full diff. When a classifier heuristic edit intentionally reclassifies a query, update the expected `MediaSkillId` in the same commit so the git diff documents the behavior change. The file also carries an `ASPIRATIONAL_IMPROVEMENTS` comment listing queries that fall through to `GENERAL_CHAT` today but should be routed elsewhere — promote a case into the asserted `CASES` list when the classifier gains a matching heuristic.
- **Test coverage** — JUnit 4 + MockK + Robolectric + Turbine + `kotlinx-coroutines-test` are wired up in `:app:unified`, `:core`, `:data`, and `:modes:film` (pin `@Config(sdk = [35])` on any Robolectric test — the shipped SDK jars end at API 35). Existing pure-policy tests: `RecommendationPlannerTest`, `VoiceReplayCommandLibraryTest`, `StereoModeDetectorTest`, `SmbPathNormalizerTest`, `HomeVoicePolicyTest`, `PanelPosePolicyTest`, `ResumeFilterTest`, `PlayerTrackSignaturesTest`, `ServerConnectionFailureTest`. Smoke tests for the new infra: `DeviceClassCapabilitiesTest` (pure) and `UserImageUriTest` (Robolectric, exercises `android.net.Uri`). The player UI, repositories, and most of `JellyfinRepositoryOfflineImpl` are still essentially untested — MockK means you no longer have to extract every Android-typed helper into a pure object just to test it; use Robolectric when you need a real `Uri` / Context shadow, and MockK when you need to stub `JellyfinRepository` / `ServerConnectionMonitor` / view-model collaborators.
- **Auth failure ≠ server offline (livelock fix)** — `ServerConnectionMonitor.isApparentConnectionFailure` deliberately returns `false` for an `org.jellyfin.sdk.api.client.exception.InvalidStatusException` with HTTP 401/403: the server *answered*, so it is reachable; only the credentials are bad. Treating a 401 as a connection failure caused a startup livelock — `SmartJellyfinRepository` marked the server inaccessible while the unauthenticated `/System/Info/Public` probe (`probeServer`) kept reporting it reachable, so the server flip-flopped accessible↔inaccessible and `HomeViewModel.observeConnectionState` re-`loadData()`'d on every flip, blinking "Loading your media…" forever (selecting a user fixed it because `SetupRepositoryImpl.setCurrentUser` re-bound the access token). Non-401/403 statuses (500/404) still count as failures so offline fallback is preserved. Contract locked by `ServerConnectionFailureTest`. The trigger was a cold-start race: `ApiModule.provideJellyfinApi` is a `@Singleton` built exactly once, sometimes before SharedPreferences/Room are warm, leaving the shared `JellyfinApi` tokenless and never re-binding. `MainViewModel.check()` now idempotently re-applies the persisted server/user (`api.update(baseUrl, accessToken)` + `userId`) before emitting the non-loading `MainState`, so the API client reflects the stored session before navigation mounts Home. <!-- added 2026-05-17: auth-vs-reachability livelock + cold-start tokenless-singleton repair -->
- **`isMinifyEnabled = false` for release** — AndroidX documents a SceneCore minification fix in `1.0.0-alpha03`, but an optimized `libreStaging` build with `scenecore` `1.0.0-alpha15` still reproduced the callback `AbstractMethodError` on Galaxy XR (`SM_I610`) on 2026-05-25, including after focused `Consumer.accept` keep-rule retries. Revisit after an XR / R8 update and require an optimized device smoke test before enabling release minification.

---

## Prompting Guidelines

If you're asking the model for new features or fixes, use these as shorthand — they map to the established patterns above:

- **"Spatialize the player"** → `Subspace` + `SpatialPanel` + `Orbiter`.
- **"Cinematic experience"** → Full Space Mode + ≥1400dp panels.
- **"Hierarchy movement"** → For the player, keep `SurfaceEntity` directly under `activitySpace`, attach its native `MovableComponent` affordance to the visible surface, and mirror pose to UI/subtitle roots.
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
