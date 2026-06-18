# SpatialFin Audit And Improvement Proposal

Date: 2026-06-18

## Scope

This audit reviewed the repository structure, project docs, Gradle configuration, manifests, Room setup, static-risk patterns, test coverage, quality gates, and high-risk source areas for SpatialFin. It did not include real-device XR, Beam Pro, or Android TV smoke testing.

Primary inputs:

- `README.md`, `GEMINI.md`, `ROADMAP.md`, `AI_CONTEXT.md`
- `settings.gradle.kts`, root Gradle files, module build files, `gradle/libs.versions.toml`
- `app/unified`, `core`, `data`, `fcast`, `player/*`, `settings`, `setup`, `plugins`, `sendspin`
- CI workflow in `.github/workflows/ci.yml`
- Local Gradle assemble, test, stability, and lint runs

## Current Posture

SpatialFin is a large, ambitious Android/Kotlin app with a real differentiator: one app codebase serving Android XR, Beam/phone, and TV while also handling Jellyfin, local media, SMB/NFS, FCast, Google Cast, AirPlay, Split-A/V, Music Assistant, SendSpin, app lock, encrypted downloads, and local/on-device AI.

The biggest strength is that the repo already captures hard-earned product knowledge in `GEMINI.md` and `ROADMAP.md`. The biggest risk is that several quality gates and safety controls have drifted behind the feature velocity.

Key facts from the audit:

- Current version: `2.7.21 (122)` in `buildSrc/src/main/kotlin/Versions.kt`.
- Modules: 16 Gradle modules, with the only app module at `:app:unified`.
- Source size: about 134k Kotlin/Java lines across 1,134 source/build/XML files.
- Tests: 57 tests/benchmarks, about 7k test lines.
- Large-file hotspots: `BeamJellyfinScreens.kt` 3,942 lines, `SpatialPlayerScreen.kt` 2,561, `BeamPlayerActivity.kt` 2,402, `TvPlayerActivity.kt` 2,310, `TvNavigationRoot.kt` 2,170, `PlayerViewModel.kt` 2,083, `SendspinReceiverService.kt` 2,074, `CastSessionManager.kt` 1,998.

## Verification Results

Passed:

```bash
./gradlew --no-daemon :app:unified:assembleLibreDebug :app:unified:assembleTvDebug :app:unified:testLibreDebugUnitTest :app:unified:testTvDebugUnitTest :core:testLibreDebugUnitTest :data:testDebugUnitTest :fcast:testDebugUnitTest :modes:film:testDebugUnitTest :player:beam:testDebugUnitTest :player:core:testDebugUnitTest :player:local:testDebugUnitTest :player:session:testDebugUnitTest :player:tv:testDebugUnitTest :player:xr:testDebugUnitTest :settings:testDebugUnitTest :setup:testDebugUnitTest :plugins:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL in 1m 10s`. Some modules report `NO-SOURCE` for unit tests.

Failed:

```bash
./gradlew --no-daemon :app:unified:libreDebugStabilityCheck :app:unified:tvDebugStabilityCheck :core:libreDebugStabilityCheck :fcast:debugStabilityCheck :modes:film:debugStabilityCheck :player:beam:debugStabilityCheck :player:session:debugStabilityCheck :player:tv:debugStabilityCheck :player:xr:debugStabilityCheck
```

Result: `:player:xr:debugStabilityCheck` failed. The baseline is stale for new/removed XR composables (`SessionOrbiter`, `StageControlsOrbiter`, `TrackOptionsOrbiter`, `XrGlassSheet`, `XrOptionRow`, removed `SecondaryControlsOrbiter`) and a changed `ControlPanelUI` parameter count.

Failed:

```bash
./gradlew --no-daemon :app:unified:lintLibreDebug :app:unified:lintTvDebug
```

Result:

- `tvDebug`: 90 errors, 145 warnings, 8 hints.
- `libreDebug`: 89 errors, 151 warnings, 8 hints.
- Error clusters: 62 `UnsafeOptInUsageError`, 24 `LocalContextGetResourceValueCall`, 3 `RestrictedApi`, 1 `IntentFilterExportedReceiver`.
- First blocker: `app/unified/src/main/java/dev/spatialfin/fcast/FCastInboundPlayerActivity.kt:312` overrides/calls `ComponentActivity.dispatchKeyEvent`, flagged as `RestrictedApi`.

## Highest-Priority Findings

### 1. Quality Gates Are Not Actually Green

The CI workflow claims stability checks are part of the quality bar, but the same gate fails locally for `:player:xr`. App lint also fails with many errors and appears not to be wired into CI.

Proposal:

- Update the XR stability baseline if the composable changes are intentional, or revert/reshape the unstable API changes if not.
- Add app lint to CI only after either fixing the current errors or checking in a deliberate baseline.
- Treat new lint errors as failing once the baseline exists.
- Add a short `docs/quality-gates.md` explaining what must pass before release.

Why this matters: the current state lets broken quality signals accumulate until they are too noisy to use.

### 2. Room Can Still Drop User Offline Data On Migration Gaps

`core/src/main/java/dev/jdtech/jellyfin/di/DatabaseModule.kt` uses:

```kotlin
.fallbackToDestructiveMigration(dropAllTables = true)
.allowMainThreadQueries()
```

The roadmap already calls out destructive migration as a production data-loss risk. The app stores downloads, offline user data, local/network playback state, servers, and users in this database. A missing migration should fail loudly in QA, not silently remove user state.

Proposal:

- Remove `fallbackToDestructiveMigration(dropAllTables = true)` for production builds.
- Add migration tests that open historical schemas from `data/schemas/.../2.json` through `18.json`.
- If destructive fallback is still needed for debug, gate it by build type and document it.
- Remove `allowMainThreadQueries()` or confine it to a temporary debug-only provider.

Why this matters: SpatialFin's offline/download story is a core product promise; database loss undercuts that promise directly.

### 3. Exported Surface Area Needs Tightening

The manifest has valid exported entry points for launchers, media sessions, and TV search/deep links. A few surfaces look broader than needed:

- `player/beam/src/main/AndroidManifest.xml`: `BeamPlayerActivity` is exported with no intent filter.
- `app/unified/src/main/AndroidManifest.xml`: `SplitAvDebugLaunchActivity` is exported in all build types and relies on `BuildConfig.DEBUG` at runtime.
- `core/src/main/java/dev/jdtech/jellyfin/deeplink/PlayDeepLink.kt`: parses any nonblank `kind`; it should accept only `Movie` or `Episode` for external deep links.
- `app/unified/src/tv/AndroidManifest.xml`: lint flags the SEARCH overlay as missing explicit `android:exported` despite the base activity declaring it.

Proposal:

- Set `BeamPlayerActivity` to `exported=false` unless an external explicit-component launch is required.
- Move `SplitAvDebugLaunchActivity` into a debug-only manifest/source set, or protect it with a signature permission in addition to the runtime guard.
- Constrain `PlayDeepLink.kind` to the existing constants.
- Fix the TV overlay lint finding with an explicit merged `android:exported` declaration or manifest structure that lint understands.

Why this matters: explicit-component launches are easy to overlook, and playback activities accept powerful media/source extras.

### 4. App Lint Is Dominated By Fixable Rule Drift

Most lint errors are not deep bugs. They are rule/config drift:

- Media3 `UnstableApi` opt-in checks are already handled in `data/lint.xml`, `player/local/lint.xml`, and `player/core/lint.xml`, but not in `app/unified`.
- Compose resource lookup errors point to `context.getString(...)` inside composition where `stringResource(...)` or derived state should be used.
- The `RestrictedApi` dispatch-key override should be replaced, suppressed with a narrow justification, or moved to a supported API path.

Proposal:

- Decide whether Media3 unstable APIs are an accepted project-wide dependency. If yes, centralize the lint option in root `lint.xml`; if no, annotate only the intentional files.
- Fix Compose resource lookups in batches by screen.
- Use lint baselines sparingly: baseline existing warnings, not errors that are trivial to correct.

Why this matters: lint can become a useful pre-release guard only after the current noise floor is lowered.

### 5. Megafiles Are Slowing Review And Increasing Regression Risk

Several core workflows are still concentrated in 2k-4k line files. This makes changes harder to review and hides lifecycle coupling.

Proposal:

- Split by ownership, not by arbitrary UI fragments.
- First targets:
  - `BeamJellyfinScreens.kt`: separate browsing, detail, downloads, network, and action dispatch.
  - `SpatialPlayerScreen.kt`: continue extracting voice/follow-up orchestration and control-surface composition.
  - `BeamPlayerActivity.kt` and `TvPlayerActivity.kt`: extract common first-frame gate, track dialogs, subtitle setup, and player launch parsing.
  - `PlayerViewModel.kt`: move playback finalization and detached reporting into collaborators.
  - `SendspinReceiverService.kt`: extract topology, stall watchdog, notification state, and client lifecycle.
  - `CastSessionManager.kt`: split discovery, subtitle policy, receiver picking, URL resolution, and active-session controls.

Why this matters: SpatialFin is already feature-rich; the next gains will come from reducing the cost of safe changes.

## Strategic Improvement Plan

### Sprint 0: Re-Green The Engineering Surface

Goal: make the existing quality gates trustworthy.

Work:

- Update or correct `player/xr/stability/xr-debug.stability`.
- Fix the app lint error clusters or add a deliberate baseline plus CI enforcement.
- Move hardcoded dependencies in `app/unified/build.gradle.kts` (`zxing`, `constraintlayout`) into `libs.versions.toml`.
- Fix recurring Kotlin warnings that point to stale nullability and deprecated API assumptions.
- Investigate Gradle configuration-time dependency resolution warnings in `:app:unified`.
- Trial Gradle configuration cache and document blockers.

Success criteria:

- CI assemble/test/stability passes from a clean checkout.
- App lint can be run locally without generating an unbounded report.
- New lint errors are visible in PRs.

### Sprint 1: Protect User Data And Entry Points

Goal: remove risks that can cause data loss, unsafe launch paths, or hard-to-debug background behavior.

Work:

- Remove production destructive Room fallback and add migration tests.
- Remove or constrain `allowMainThreadQueries()`.
- Harden exported activity/deep-link surfaces.
- Audit `DownloadReceiver`, boot startup, media-session services, and TV search provider for expected caller and input validation.
- Standardize background work status/error reporting for downloads, SyncPlay leave, FCast receiver, SendSpin, and companion sync.

Success criteria:

- Missing migration fails in tests instead of wiping data.
- External deep links reject invalid kinds and malformed payloads.
- Debug-only entry points are not exported in release.

### Sprint 2: Test The Product Promises

Goal: cover the flows that make SpatialFin different.

High-value test additions:

- Room migration chain from schemas 2 through 18.
- Download reconciliation with active tasks, encrypted downloads, missing final files, and subtitle tasks.
- Offline home fallback and stale download cleanup behavior.
- Deep-link parser and exported-entry validation.
- Cast subtitle policy, receiver capability changes, and split-A/V recast/fold-back state.
- SendSpin stall watchdog and topology selection as pure collaborators.
- TV focus navigation smoke tests for hero/search/settings.
- Player first-frame gate behavior for TV/Beam, including background/resume.

Success criteria:

- New tests target previously reported or roadmap-listed regressions.
- Complex networking/media logic is covered through pure policy tests where possible, with Robolectric only where Android APIs are needed.

### Sprint 3: Architecture And Performance Paydown

Goal: reduce feature cost and improve device reliability.

Work:

- Extract megafile collaborators listed above.
- Unify `OkHttpClient` ownership where possible. Current ad hoc clients exist in APIs, voice, companion, workers, and UI helpers; some are valid, but the worker path should not create fresh pools per run.
- Replace detached `CoroutineScope(SupervisorJob() + Dispatchers.IO)` patterns with explicit app-level services, WorkManager, or lifecycle-owned coordinators where completion matters.
- Add telemetry for first-frame timeout fallback, calibration confidence, split-A/V drift degradation, LLM backend choice/failure, and SendSpin stall recovery.
- Re-run baseline profile and macrobenchmarks after major navigation/player refactors.

Success criteria:

- Player/cast/SendSpin lifecycle failures produce actionable logs or local diagnostics.
- Most new changes avoid editing 2k+ line files.
- Startup and TV focus performance have repeatable measurements.

## Product Ideas Worth Considering

These are not fixes, but they align with SpatialFin's differentiators.

1. Device Readiness Check
   - A first-run and settings screen that shows XR capability, hand tracking, microphone, local video permission, receiver service state, SendSpin state, and LLM model status.

2. Offline Confidence Dashboard
   - Per-download readiness: media file, subtitles, posters, trickplay, encryption state, last integrity check, and whether offline playback has been verified.

3. Cast Lab
   - A diagnostics panel for receiver codec capabilities, subtitle fidelity decision, split-A/V calibration confidence, measured latency, drift state, and last degradation reason.

4. TV Focus QA Mode
   - A hidden dev overlay or instrumentation journey that displays current focused element, focus path, and failed focus requests for TV/Leanback debugging.

5. Assistant Reliability Mode
   - A voice settings page that separates speech recognition, deterministic commands, local Gemma, cloud Gemini, and external knowledge health so users understand which layer failed.

6. Network Share Setup Wizard
   - A NAS-oriented flow that tests SMB/NFS credentials, range-read seek behavior, TMDB matching confidence, and local proxy readiness before saving a share.

7. Release Readiness Checklist
   - A generated checklist from Gradle tasks: version bumped, `versionCatalogUpdate` reviewed, stability gate green, lint baseline clean, release bundles built, TV banner/icon present, no debug-only exported release entries.

## Documentation Cleanup

`GEMINI.md` is current and valuable. `AI_CONTEXT.md` is stale: it still points readers to `app/xr/build.gradle.kts` even though the app was consolidated into `app/unified`. This should be corrected or removed to avoid sending future contributors down old paths.

Proposal:

- Make `GEMINI.md` the only canonical AI guide.
- Update `AI_CONTEXT.md` to mirror the current module map, or replace it with a short pointer to `GEMINI.md`.
- Add a release/process doc for quality gates rather than burying those expectations in roadmap prose.

## Suggested Ordering

1. Re-green stability and lint.
2. Remove destructive Room fallback and add migration tests.
3. Harden exported/deep-link surfaces.
4. Add tests for downloads/offline/player/cast policies.
5. Refactor the largest files along existing ownership boundaries.
6. Invest in product diagnostics for XR, cast, offline, voice, and SendSpin.

## Residual Risks

- No physical XR/TV/Beam validation was performed in this audit.
- Lint dependency-update warnings are current to this local lint run, but dependency upgrades should still follow the repository rule: run `./gradlew versionCatalogUpdate`, review the diff, then do device regression testing.
- Release minification remains intentionally disabled because of the documented SceneCore/R8 startup crash. Re-enabling it should wait for a newer XR/R8 combination and a real Galaxy XR optimized-build smoke test.
