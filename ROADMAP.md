# SpatialFin Roadmap

Living document. Order is by user impact × cost-to-fix. Update or remove
entries as they ship; do not let this file rot.

## Why this exists

Anchored in an audit triggered by a recurring user-reported bug: "sometimes
when I'm offline I just see the left navbar instead of the videos I have
available because I downloaded them." That investigation surfaced a residual
gap in `HomeViewModel.loadData()`'s error path that commit `6e70a37` did not
close, plus a long tail of adjacent issues across the player, downloads,
voice/AI, XR, TV, and build subsystems.

---

## Root cause: offline home screen is sometimes empty

`modes/film/src/main/java/dev/jdtech/jellyfin/film/presentation/home/HomeViewModel.kt:116-119`
— the outer `try/catch` in `loadData()` swallows any exception by setting
`error = e, isLoading = false` **without ever calling
`loadOfflineLibrarySections()`**. Commit `6e70a37` only fixed eager clearing
on the success path; the error path is still un-fallback'd.

Failure path:

1. App is online, or in the 15-second `isDegradedMode` grace window
   (`ServerConnectionMonitor.kt`) — `shouldUseOfflineRepository()` returns
   `false` so the online branch runs.
2. `SmartJellyfinRepository.fetchWithFallback`
   (`core/src/main/java/dev/jdtech/jellyfin/repository/SmartJellyfinRepository.kt:458`)
   only treats `IOException` + `ApiClientException` as "connection failure"
   worth falling back to offline. Anything else propagates up.
3. The exception lands in `HomeViewModel.kt:116`. State becomes:
   `error = <something>`, `isLoading = false`, **all sections empty** because
   the offline-load fallback never ran.
4. `HomeScreen.kt:159-243` renders an empty `LazyColumn`; the error icon is
   hidden in `HomeHeader` and only opens a dialog on click. The nav rail /
   header stays visible. Result: **navbar only**.

There is also a cold-start sliver where `serverAccessible = true` is the
default until the first probe completes; if the probe gets stuck, the load
either hangs in `loadResumeItems()` or times out into the same buggy outer
catch.

---

## Audit summary

Severity scale: **P0** (crash / data loss / blocks user) · **P1** (broken
feature) · **P2** (paper cut) · **P3** (polish).

### Offline / home / repository

- **P0** `HomeViewModel.kt:116-119` outer catch never falls back to offline
  sections — root cause of the empty-screen bug.
- **P0** `JellyfinRepositoryOfflineImpl.getStreamUrl`
  (`data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryOfflineImpl.kt:293`)
  throws via `error(...)`. Should return the cached path or a typed
  exception.
- **P1** `ServerConnectionMonitor.isConnectionFailure` only matches
  `IOException | ApiClientException`. Reality has more shapes (some Jellyfin
  SDK errors wrap in `RuntimeException`; SSL handshake exceptions in
  captive-portal scenarios).
- **P1** `HomeViewModel.observeConnectionState` captures `previousState`
  before the first emission and only reloads on changes; initial
  probe-completion does not retrigger `loadData()`.
- **P2** `HomeViewModel` calls `loadResumeItems` / `loadNextUpItems` *before*
  the branch; if either silently fell back to offline, those sections show
  offline data even on the online branch — blurs the online vs offline UX
  promise.

### Downloads / WorkManager

- **P1** `DownloadIntegrityWorker` only sweeps PRIMARY tasks. Subtitle tasks
  can be truncated and the app keeps treating them as healthy.
- **P1** `ResumableDownloadWorker` doesn't pre-check free disk space;
  storage-full triggers MAX_AUTO_RETRIES of `IOException` retries with no
  UI surface.
- **P2** `DownloadIntegrityWorker:83-95` deletes leftover bytes *before*
  flipping the DB row to PENDING — crash in between leaves a "downloaded"
  row with no file.
- **P2** No metered-network guard on `DownloadPreparationWorker`; Beam
  users on cellular silently kick off large downloads.
- **P2** Bulk download (`DownloaderImpl.downloadItems`) resolves per-episode
  `getMediaSources()` in the caller's coroutine (already in GEMINI.md
  known-gaps).

### Player

- **P1** `PlayerViewModel.onCleared` launches the SyncPlay `leaveGroup` in a
  detached `GlobalScope` with `runCatching` only — failure is invisible,
  server-side group can be orphaned.
- **P2** `PlaylistManager.toPlayerItem` swallows conversion errors with bare
  `catch(e: Exception)` returning null — playlist navigation appears to
  "skip" silently.
- **P2** `BeamPlayerActivity` uses several hard-coded `delay(...)` for
  feedback / control fades (4000ms, 500ms, 5000ms) without explicit
  cancellation tokens.
- **P2** `LibassRendererState` declares `@Stable` but exposes
  `mutableStateOf<Bitmap?>()`; `Bitmap` is not Compose-stable, every
  render-loop swap risks invalidating callers.
- **P2** `BeamPlayerActivity.createIntentForSpatialItem` returns `null` for
  `Show` / `Season` / `BoxSet` — voice "play <show>" silently does nothing
  instead of routing to detail or auto-resolving the next episode.

### Voice / AI

- **P1** `WebSearchClient` SearXNG fallback has no auth and accepts non-HTTPS
  URLs; mismatch with the GEMINI.md guidance that "SearXNG must be sidecared
  behind the companion."
- **P1** `RecommendationPlanner` MOOD_SURPRISE empty-library path returns the
  generic "I couldn't find a strong match" string with no telemetry
  distinction — looks broken to the user.
- **P2** `SkillClassifierRegressionTest`'s `ASPIRATIONAL_IMPROVEMENTS` list
  documents drift but is not promoted to issues.
- **P2** No queue depth / timeout guard on consecutive voice requests.
- **P2** `VoiceTelemetryStore` CSV encoding (`|`, `;`) is fragile with
  redacted-but-still-user-text inputs; JSON-line would prevent corrupted
  entries.

### XR

- **P1** Ambilight blocked by Jetpack XR not exposing the `XrSession` handle
  for Mixed Reality (tracked in `project_ambilight_feature.md`); upstream
  issue, but worth a degraded fallback path.
- **P2** `XrPlayerActivity`'s self-process-kill workaround means
  `Session.destroy()` never runs; any registered lifecycle observers leak.
  GEMINI.md acknowledges this; needs a periodic check whether the Filament
  bug is fixed.

### TV

- **P2** `WatchNextWorker` periodic + manual triggers can race and write
  duplicate Watch Next rows; need dedup at the `WatchNextSync` layer.
- **P2** `SpatialFinSearchProvider` authority comes from a `resValue` —
  silently breaks if `buildFeatures.resValues = false` is ever flipped.

### Build / dependencies / perf

- **P1** `gradle/libs.versions.toml:65` hardcodes
  `androidx-compose-material-icons-extended = "...:1.7.8"` (violates
  GEMINI.md mandate to use `version.ref`).
- **P1** No baseline-profile module / no committed `baseline-prof.txt` —
  Compose-heavy app on XR + TV + phone, this is the highest-leverage perf
  win available.
- **P1** Release is `isMinifyEnabled = false` — known debt from XR R8 fault.
  Needs a tracking issue with a dated revisit (every Jetpack XR release).
- **P2** No Compose Compiler stability reports / `stabilityCheck` CI gate —
  `PlayerStateSnapshot` (30+ fields) and other carriers slip into "runtime
  stable" silently.
- **P2** No AGP 9 upgrade path tracked.

---

## Sprint 0 — fix the offline empty screen (this week)

1. Make `HomeViewModel.loadData()` always end with offline sections
   populated. In the outer catch, also call `loadOfflineLibrarySections()`
   before returning, and ensure the success path always populates them when
   `serverAccessible` is false.
2. Widen `ServerConnectionMonitor.isConnectionFailure` to also treat any
   `Throwable` whose cause chain contains an `IOException`, plus a small
   allowlist of common SDK wrappers. Add a unit test feeding it the real
   exception types we've seen in the wild.
3. Make `JellyfinRepositoryOfflineImpl.getStreamUrl` return a typed
   `OfflineNotSupportedException` (or null) instead of `error(...)`. Audit
   callers.
4. Trigger an initial `loadData()` from `observeConnectionState` the first
   time a state flows in (or call `loadData()` once unconditionally from
   `init {}`), so cold-start probes that complete late don't strand the UI.
5. Render an explicit "Showing downloads only" empty state when
   `isOfflineMode = true` and there are zero downloaded items, instead of a
   blank panel.
6. Add a Robolectric test for `HomeViewModel` that simulates: cold-start
   offline, mid-load throw, repeated retries.

## Sprint 1 — bug fixes adjacent to the user's pain (next 1–2 weeks)

7. Sweep subtitle tasks in `DownloadIntegrityWorker`.
8. Pre-check disk space in `ResumableDownloadWorker`; surface "low storage"
   notification.
9. Reorder `DownloadIntegrityWorker:83-95` so DB flips PENDING **before**
   file deletion (crash-safe).
10. Fix `BeamPlayerActivity.createIntentForSpatialItem` to route Show /
    Season / BoxSet to detail or auto-resolve next-episode.
11. Surface SyncPlay leave-group failure in `PlayerViewModel.onCleared`
    (Timber.e + retry once on next session join).
12. Move libass `cachedBitmap` off `mutableStateOf<Bitmap?>` into a stable
    holder.
13. Switch `libs.versions.toml:65` to `version.ref`.

## Sprint 2 — perf foundations (2–3 weeks)

14. Add a baseline-profile module. Pays back ~30% startup, ~40% first-scroll
    on phone & TV.
15. Wire Compose Compiler reports + `skydoves/compose-stability-analyzer` CI
    gate so `PlayerStateSnapshot` and `HomeState` don't silently become
    unstable.
16. `@TraceRecomposition` + Macrobenchmark on the home screen, the player,
    and the Beam shell. Establish before/after numbers.

## Sprint 3 — feature polish (3–6 weeks)

17. Fix the `WatchNextWorker` dedup race.
18. WebSearchClient: enforce HTTPS for SearXNG; surface "no auth configured"
    warning in settings.
19. RecommendationPlanner: differentiate "empty library" from "no good match"
    in telemetry + UI.
20. Promote 1–2 `ASPIRATIONAL_IMPROVEMENTS` from `SkillClassifierRegressionTest`
    into asserted cases.
21. Voice request queue: drop or coalesce taps while busy.
22. Audit nav rail / `NavigationRail` for raycast blocking on XR (per
    GEMINI.md pitfall).

## Sprint 4 — debt with a clock on it

23. Re-attempt `isMinifyEnabled = true` against the latest Jetpack XR
    release; fix or refresh keep rules.
24. Track and prepare for AGP 9 upgrade.
25. Revisit ambilight blocker — file / upgrade upstream issue, ship a
    degraded "ambient panel glow" 2D fallback in the meantime.

## Stretch / parking lot

- Multi-select bulk download polish on XR.
- TV focus instrumentation (Macrobenchmark + `FrameTimingMetric`).
- LiteRT NPU bundle once Qualcomm dispatch lib ships
  (`project_npu_blocker.md`).
- Migrate `XrPlayerActivity` off the process-kill workaround when upstream
  Filament bug closes.
