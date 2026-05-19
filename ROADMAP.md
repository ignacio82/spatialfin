# SpatialFin Roadmap

Living document. Order is by user impact × cost-to-fix. Update or remove
entries as they ship; do not let this file rot.

**Status markers:** ✅ shipped (commit hash) · ⏭️ deferred · 🚫 dropped (with
reason). Mark in the same commit that lands the change.

**Last full audit:** 2026-05-17 (covers through commit `5b4feb6`; version
2.7.2 / 103). Sprints 0–1 are fully shipped; the cast / split-A/V / AirPlay
subsystem (PRs 1–6) and the auth-livelock + first-frame-audio fixes landed
*after* the previous audit and surfaced a new P0/P1 tail — see **Sprint A**.

## Why this exists

Anchored in an audit triggered by a recurring user-reported bug: "sometimes
when I'm offline I just see the left navbar instead of the videos I have
available because I downloaded them." The empty-offline-home root cause is
now **fully closed** — `a65e620` populated the offline fallback in
`HomeViewModel.loadData()`'s error path and `44513e3` killed the related
cold-start auth-livelock by re-binding the persisted session in
`MainViewModel.check()` before Home mounts. This document now tracks the
long tail that audit surfaced plus everything found since.

---

## Audit summary

Severity scale: **P0** (crash / data loss / blocks user) · **P1** (broken
feature) · **P2** (paper cut) · **P3** (polish).

### Sprint A — newly surfaced, verified (audit 2026-05-17)

These were found and source-verified in the 2026-05-17 audit of the
post-roadmap code (cast subsystem, split-A/V, the first-frame audio gate,
the on-device AI mutex). They are the new top of the list.

- ✅ **P0** `core/.../llm/LlmChatModelHelper.kt:230` (and `:306`) —
  `inferenceMutex.lock()` is taken at `:212`, but `createConversation(...)`
  runs at `:230` **outside** the `try` at `:232` whose `finally`/callbacks
  are the only unlock path. If `createConversation` throws (GPU OOM, engine
  in a failed state, engine closed mid-call by `LlmModelManager`), the
  process-wide mutex is held forever — **every subsequent voice command and
  chat query deadlocks until app restart**, with no error surfaced.
  `runToolCall` has the identical shape. Fix: move `createConversation`
  inside the try (or unlock on any throw before `suspendCancellableCoroutine`).
- ✅ **P0** `data/.../downloads/DownloadStorageManager.kt:127` —
  `reconcileItemSources` does `if (!File(source.path).exists())
  deleteItemBlocking(...)` with no "is a download active for this item"
  guard and no DB transaction. When a PRIMARY download is mid-flight (or
  `DownloadIntegrityWorker.requeueBrokenTask` just flipped `sources.path`
  back to the not-yet-existing `.download` tempPath), any offline
  `getMediaSources`/`getItem`/`getEpisode` for that item **wipes the
  movie/episode/season/show + userdata from the DB** and orphans the
  still-queued download task. Fix: skip deletion when a non-`SUCCESSFUL`
  `downloadtasks` row exists for the source; wrap the cascade in one
  `@Transaction`.
- ✅ **P0** `fcast/.../receiver/FCastReceiverServer.kt:105,133` — `sessions`
  is only ever `.add()`-ed; individual `FCastReceiverSession`s are never
  removed on disconnect (only a bulk `.clear()` at server stop) (`5b80c8f`). Every
  sender connect/disconnect over the receiver service's whole lifetime
  (Wi-Fi flaps, repeated split-A/V sessions, discovery probes) leaks a
  `Socket` + streams + cancelled `Job`, and the ~10 Hz beacon broadcast
  iterates the unbounded list. Multi-hour viewing on an unstable link ends
  in OOM. Fix: session notifies the server on close (callback/WeakRef);
  prune in `broadcast*` or via a `readerJob` completion handler.
- ✅ **P1** `player/tv/.../TvPlayerActivity.kt:600` &
  `player/beam/.../BeamPlayerActivity.kt:941` — the first-frame audio-gate
  escape hatch `tracks.groups.none { it.type == VIDEO }` is **vacuously
  true for the empty `Tracks` ExoPlayer emits early in prepare**, so on a
  slow Jellyfin/transcode start the gate releases and audio plays over a
  black screen — re-introducing exactly the bug `5b4feb6` fixed. Fix:
  require non-empty groups **and** a positive supported audio group.
  (`78defd6`)
- ✅ **P1** `player/beam/.../BeamPlayerActivity.kt:471` &
  `player/tv/.../TvPlayerActivity.kt:362` — backgrounding the activity
  while the first-frame gate holds playback persists the gate-forced
  `playWhenReady=false`; on return nothing re-requests play, so the screen
  is frozen black with no spinner and no path back. Fix: persist the
  *intended* `playWhenReady`, or restore through the gate on `onResume`.
  (`5982921`)
- ✅ **P1** `app/unified/.../fcast/session/SplitAvController.kt:399-454` — the
  v4 synchronized-start `aligned` latch is set `true` on first `playing`
  regardless of whether θ converged, and the clock-sync ping burst is
  fire-and-forget (`f3f9920`). One dropped early ping ⇒ the whole movie
  silently runs on the inferior RTT/2 legacy mapping with an 8 s blind
  window. Fix: gate `aligned=true` on a successful path; retry/extend the
  burst until θ has an estimate or a short deadline.
- ✅ **P1** `SplitAvController.kt:498-516` + `FCastSenderClient.kt:216-293` —
  the 6-ping burst overwhelms `FCastSenderClient`'s single-outstanding-ping
  tracking; on a pre-v4 / non-SpatialFin receiver every Pong mis-pairs to
  the latest send time, feeding garbage RTT into the legacy
  `NetworkDelayEstimator` for the rest of the session. Fix: serialize the
  burst, or keep a small FIFO of outstanding send-times.
  (`f3f9920`)
- **P1** `fcast/.../cast/adapter/googlecast/GoogleCastAdapter.kt:220-253` —
  the post-LAUNCH RECEIVER_STATUS filter can match a *spontaneous/stale*
  status frame (Cast devices emit unsolicited ones) and bind `transportId`
  to a dead Default-Media-Receiver instance; every later LOAD/PLAY goes
  nowhere. Fix: correlate on the LAUNCH `requestId` or snapshot
  pre-LAUNCH sessions.
- **P1** `fcast/.../cast/adapter/airplay/AirPlayHttpClient.kt:106-124` —
  volume map `-30 + 30v` makes the bottom ~half of the slider a dead zone
  and the only mute is the exact-zero sentinel; every AirPlay cast has a
  broken volume curve. Fix: map across the receiver's real perceptual
  range; treat a small epsilon as mute.
- **P1** `app/unified/.../HomeVoiceController.kt:387-442` — `destroy()`
  tears down the LLM/TTS services but never cancels `activeVoiceJob` nor
  no-ops the recognizer callback, so a transcript arriving after destroy
  re-builds LLM services nothing will dispose (leak) and an in-flight
  `assistant.query` races half-torn-down services. Fix: cancel the job
  first; guard the recognizer callback on a destroyed flag.
- **P1** `data/.../work/ResumableDownloadWorker.kt:142` — `OkHttpClient()`
  is constructed fresh per `doWork()` (and per retry); each owns a
  dispatcher pool + connection pool that are never shut down. Bulk season
  downloads leak threads/sockets steadily. Fix: inject/share one client.
- **P1** `data/.../utils/DownloaderImpl.kt:236` /
  `ResumableDownloadWorker.kt:130` — `resumeDownloadById` enqueues the
  resumable worker directly for a task paused/failed *before* prep filled
  `requestUrl`; `Request.Builder().url("")` (outside the try) throws and
  the worker dies with no `errorMessage`, leaving a stuck, undiagnosable
  task. Fix: re-enqueue prep when `requestUrl.isBlank()`; guard at worker
  entry.
- **P1** `data/.../network/NetworkStreamProxy.kt:30,49` — the loopback
  proxy that serves SMB/NFS files with stored share credentials binds
  `0.0.0.0`, so any LAN device can exfiltrate share content; `stop()` also
  leaks its coroutine scope (accept loop + open SMB/NFS connections keep
  running). Fix: bind `InetAddress.getLoopbackAddress()`; cancel the scope
  on stop; optionally add a per-session token.
- **P1** `data/.../network/SmbFileClient.kt:62-103` — `openFile` has no
  try/finally; any failure after `connect()` (bad share, IPC$ cast,
  missing file, seek error) leaks the SMB connection/session/share/file.
  `NetworkStreamProxy` retries per Range request, so it leaks repeatedly.
  Fix: nested try/catch that closes the chain and rethrows.
- **P1** `data/.../network/NfsFileClient.kt:233-252` — `read()` returns
  `-1` on any zero-byte READ without checking the NFSv4 `eof` flag; a
  legitimate server short-read terminates playback mid-file. Fix: inspect
  `opread.resok4.eof`; only EOF on `eof==true || position>=fileSize`.
- **P1** `player/local/.../SyncPlayCoordinator.kt:94-97,555-611` — the
  1500 ms echo-suppression window is wall-clock; an async
  `setMediaItems().prepare()` / `seekTo` that completes after it expires
  makes the client report state the server re-broadcasts → a SyncPlay
  group seek/pause feedback loop. Fix: refresh the window when the
  triggered player op actually completes.

### Sprint B — surfaced during 2.7.4 split-A/V hardening (2026-05-18)

Found while shipping the capability-driven split-A/V audio + clock-offset
+ Stop-button + fold-back work (commits `3353ca0`…`3406887`, version
2.7.4 / 105). The split-A/V fixes themselves are **shipped & live-verified**
(Galaxy XR + Google TV Streamer + Pixel 10 Pro XL); these are the residual
tail.

- ✅ **P1** TV-mode D-pad focus broken on the hero carousel + search
  (`dev.spatialfin.unified.UnifiedMainActivity` TV content — hero card
  carousel and search composables; exact file:line not yet localized).
  Pressing right to the next hero item makes the card unusable: the click
  hits the whole card, neither Play nor Details is individually
  focusable; the search view can't receive text and clicks do nothing
  while the launcher behind it moves. Confirmed **not caused by the
  split-A/V work** (that change set touched zero TV-UI files). Likely a
  pre-existing HEAD `tv`-build focus-traversal regression or a
  test-setup artifact — repro against prod `2.7.2-tv` and a clean
  relaunch to isolate before fixing. Relates to the earlier
  `98e873c` "resolve TV settings focus traversal" work.
- **P3** Split-A/V fold-back re-inits the audio renderer unconditionally
  (`PlayerSplitAvAdapter.onFoldBackToLocal` → `XrPlayerActivity` /
  `BeamPlayerActivity`): re-enabling the disabled `TRACK_TYPE_AUDIO`
  makes ExoPlayer re-select and decode audio with a brief hiccup even
  when the source codec was locally decodable the whole time. Behavior
  is correct, just not maximally seamless. Polish: pre-check the source
  audio codec against this device's `ReceiverAudioCodecs.fromCapabilities`
  and skip the re-enable churn when the track was already decodable.
  Shipped fold-back in `8cd2b84`.

### Perf / build / infra (re-confirmed open)

- **P1** No perf foundation **and no CI to host one**: no
  baseline-profile module, no `androidx.profileinstaller` dependency, no
  Macrobenchmark module, no Compose-compiler reports wiring, no `.github/`
  workflow at all. Highest-leverage perf gap, especially for the
  2 GB / Mali-G31 TV target. (Roadmap items 14–16 — scoped below; note
  the missing CI pipeline they depend on.)
- **P1** Unstable Compose state holders carried through the UI:
  `player/session/.../voice/PlayerStateSnapshot.kt` (35 fields, ~12 bare
  `List`, a `List<Pair<…>>`) and `modes/film/.../home/HomeState.kt`
  (`error: Exception?` + bare `List`s). Cheap to fix independent of CI:
  `kotlinx.collections.immutable` + a typed error + `@Immutable`.
- **P2** `UnifiedApplication.onCreate` front-loads main-thread disk I/O
  (log-file tree, several synchronous `SharedPreferences` reads, LLM warm,
  FCast wiring) before first frame — compounds the missing baseline
  profile. Defer non-essential work off-main / post-first-frame.
- **P2** `UnifiedMainActivity.onResume` fires an unconditional
  `MainViewModel.check()` (Room queries + a fresh `MainState` emit that
  ripples the whole `setContent` tree) on *every* resume. Gate on an
  actual session-version change (`activeSessionBus` already exists).

### Carried over from the original audit (still open)

#### Downloads / WorkManager
- **P2** No metered-network guard on `DownloadPreparationWorker`; Beam
  users on cellular silently kick off large downloads.
- **P2** Bulk download (`DownloaderImpl.downloadItems`) resolves per-episode
  `getMediaSources()` in the caller's coroutine — navigating away mid-loop
  drops un-queued episodes.
- **P2** `DownloadPreparationWorker` retries re-run all prep side effects;
  `downloadExternalMediaStreams` mints a fresh `UUID` per subtitle per
  attempt, inserting **duplicate `mediastreams` rows + duplicate subtitle
  tasks**. Make subtitle ids deterministic; gate prep steps to resume.
- **P2** `SyncWorker` builds its own `JellyfinApi` (not the DI singleton)
  and returns `Result.success()` on failure with no backoff — a transient
  failure mid-batch silently abandons unsynced offline edits until the
  next reconnect event. Return `Result.retry()` on partial failure.
- **P2** `SmartJellyfinRepository.runWrite` skips the offline mirror when
  the online write succeeds, so a downloaded item's local `userdata` goes
  stale (offline Continue Watching wrong; `SyncWorker` may push the stale
  value back). Make user-data writes write-through.

#### Player / voice / AI
- **P1** `PlayerViewModel.onCleared` SyncPlay `leaveGroup` — *partially*
  addressed by item 11 (pending-leave pref); the detached-`GlobalScope`
  shape of the immediate leave still has no failure surfacing.
- **P2** `PlaylistManager.toPlayerItem` swallows conversion errors with
  bare `catch(e: Exception)` returning null — playlist nav silently skips.
- **P2** `PlaylistManager` loads the entire season eagerly and has no
  cross-season continue (finale auto-stops instead of S+1E1); large
  seasons pay a startup-latency cost. Resolve next/prev lazily across
  season boundaries via `getNextUp`.
- **P2** `PlaylistManager.probeSubtitleSize` runs unbounded parallel
  blocking HTTP (3 s+3 s each) on the player-open path; an anime
  signs-and-songs pack on a slow server stalls `toPlayerItem` for ~6 s.
  Wrap in `withTimeoutOrNull` + bound concurrency.
- **P2** `BeamPlayerActivity` hard-coded `delay(...)` for control fades
  without explicit cancellation tokens.
- **P2** `SpatialVoiceSynthesizer` `_isSpeaking` is a single shared boolean
  ignoring `utteranceId`; a late `onDone(N)` after `onStart(N+1)` (the
  `QUEUE_FLUSH` chunked-reply path uses) prematurely fires
  `onTtsFinished`, resuming the player while the assistant is still
  talking. Track the active utterance id.
- **P2** `AssistantSpeechStateMachine.kt:31-48` `LaunchedEffect` branches
  on `followUpDeadlineMs` without keying on it (stale capture) — can skip
  a legitimate follow-up arm or arm against a reset deadline. Add it to
  the key list.
- **P2** `BeamPlayerActivity.createIntentForSpatialItem` returns `null` for
  some container types — verify the item 10 `resolveAndCreate…` path
  covers every Beam call site (TV equivalent still a follow-up).
- **P3** `SmartChatEngine` RECAP early-return leaves the LiteRT mutex held
  up to the 40 s timeout while Gemma keeps generating; next voice command
  blocks silently. Add telemetry + a "still thinking" surface.
- **P3** No telemetry when the 15 s first-frame backstop (rather than
  `onRenderedFirstFrame`) releases the gate — this is exactly the signal
  needed to detect the two P1 gate regressions in the field.
- **P3** `SpatialVoiceService` `onResult` is not nulled in
  `destroy()`/`stopListening()`; a retry firing post-destroy flips
  `_state` to a phantom `LISTENING`.

#### Voice / AI (carried)
- **P1** `WebSearchClient` SearXNG fallback has no auth and accepts
  non-HTTPS URLs; enforce HTTPS + surface a "no auth configured" settings
  warning.
- **P1** `RecommendationPlanner` MOOD_SURPRISE empty-library path returns
  the generic "no strong match" string with no telemetry distinction.
- **P2** `SkillClassifierRegressionTest` `ASPIRATIONAL_IMPROVEMENTS` not
  promoted to asserted cases.
- **P2** No queue depth / timeout guard on consecutive voice requests.
- **P2** `VoiceTelemetryStore` CSV encoding fragile with redacted user
  text; move to JSON-line.

#### Cast / split-A/V (carried, lower severity)
- **P2** `CalibrationServer.pickLocalLanIpv4` returns the first non-LAN
  IPv4 — a VPN/tether/secondary interface produces a URL the receiver
  can't route to; calibration silently times out with no actionable
  signal. Prefer the default-route / Wi-Fi interface.
- **P2** `CastV2Channel` hostname verification is dead code and the TLS
  session is never pinned to the mDNS-discovered `id` the comment claims
  to "trust" — a LAN MITM on host:8009 is transparent. Acceptable for
  Cast V2 parity, but pin per-remembered-receiver or drop the overstated
  comment.
- **P2** `BinaryPlist.parse` reads 8-byte trailer fields as `Int`; a
  malformed/hostile AirPlay response can throw `ArrayIndexOutOfBounds`
  (escapes the documented `IllegalArgumentException` contract). Add
  bounds checks.
- **P3** No user-facing surfacing of split-A/V `State.Degraded` — the user
  experiences silent lipsync drift with no prompt to re-pick the receiver.
- **P3** `MultiProtocolDiscovery` runs three concurrent `JmDNS` instances
  on one interface (acknowledged TODO; some XR firmware drops responses).
- **P3** Calibration alignment failures discarded with no min-distance
  telemetry — field debugging is intractable.

#### XR
- **P1** Ambilight blocked by Jetpack XR not exposing the `XrSession`
  handle for Mixed Reality (`project_ambilight_feature.md`). Worth a
  degraded 2D fallback (item 25).
- **P2** `XrPlayerActivity` self-process-kill means `Session.destroy()`
  never runs; lifecycle observers leak. Periodic check whether the
  upstream Filament bug is fixed.

#### TV
- **P2** `WatchNextWorker` periodic + manual triggers race and write
  duplicate Watch Next rows; dedup at the `WatchNextSync` layer.
- **P2** `SpatialFinSearchProvider` authority comes from a `resValue` —
  silently breaks if `buildFeatures.resValues` is ever flipped off.

#### Build / dependencies / perf
- **P2** Room `fallbackToDestructiveMigration(dropAllTables = true)` plus
  the manual/auto migration chain means any migration gap silently
  **drops `downloadtasks` and all offline userdata** in production. Drop
  destructive fallback (or exclude the download tables) so a missing
  migration fails loudly in CI/QA instead of nuking user downloads.
- **P1** Release is `isMinifyEnabled = false` — known XR/R8 debt. Needs a
  dated revisit each Jetpack XR release (item 23).
- **P2** No Compose stability reports / `stabilityCheck` CI gate
  (item 15).

---

## Shipped log (Sprints 0–1, all verified 2026-05-17)

All items below were re-verified against source during the 2026-05-17
audit; the ✅ markers are trustworthy.

1. ✅ (`a65e620`) `HomeViewModel.loadData()` always ends with offline
   sections populated; outer catch calls `loadOfflineLibrarySections()`
   and marks the server inaccessible on network-shaped failures.
2. ✅ (`a65e620`) `ServerConnectionMonitor.isApparentConnectionFailure`
   walks the cause chain; `ServerConnectionFailureTest` locks the
   contract. Extended by `44513e3`: returns `false` for HTTP 401/403
   (server answered → reachable, only creds bad) to kill the cold-start
   auth livelock; `MainViewModel.check()` re-binds the persisted session
   before Home mounts.
3. 🚫 `JellyfinRepositoryOfflineImpl.getStreamUrl` — dead code via the
   `SmartJellyfinRepository` facade; re-open only if the offline impl is
   injected directly.
4. 🚫 Initial `loadData()` from `observeConnectionState` — **no longer
   applicable**: `44513e3`'s deterministic pre-Home session re-bind
   closed the cold-start race this item described.
5. ⏭️ Explicit "Showing downloads only" empty state — covered by
   `HomeStatusCard` once `markServerInaccessible()` fires.
6. ⏭️ HomeViewModel Robolectric behavioral test — still open; needs
   `Dispatchers.Default` injection. Test infra wired in `:modes:film`.
7. ✅ `DownloadIntegrityWorker` sweeps PRIMARY + SUBTITLE.
8. ✅ `ResumableDownloadWorker` pre-checks disk space (64 MB margin).
9. ✅ `requeueBrokenTask` flips the DB row before deleting the file.
10. ✅ `BeamPlayerActivity.resolveAndCreateIntentForSpatialItem`
    auto-resolves Show/Season/BoxSet via `getNextUp()`. TV equivalent
    still a follow-up.
11. ✅ `PlayerViewModel` arms a `pendingSyncPlayLeave` pref and drains it
    on next launch (the immediate leave's failure surfacing is still in
    the open list above).
12. ✅ libass reads moved inside the `SpatialPanel` content lambda so the
    SceneCoreEntity scope no longer recomposes per frame.
13. ✅ `libs.versions.toml` icons-extended uses `version.ref`.

Cast / split-A/V multi-protocol stack (PRs 1–6, commits `29e3135`…
`d65c077`, `bb51d37`, plus `554c667` BeaconFreshnessGate and `5b4feb6`
first-frame audio gate) shipped after Sprint 1. Its residual gaps are
tracked in **Sprint A** and the cast/split-A/V carried list above — they
were previously invisible in this roadmap.

---

## Sprint A — fix the verified P0/P1 tail (this week)

Order within the sprint is by blast radius:

1. P0 `LlmChatModelHelper` mutex-unlock-on-throw (whole AI pipeline dies).
2. P0 `reconcileItemSources` download-vs-reconcile data-loss guard.
3. P0 `FCastReceiverServer` session pruning.
4. ✅ first-frame audio gate: empty-`Tracks` + background/foreground
   regressions (both stem from `5b4feb6`). (`78defd6`)
5. P1 `NetworkStreamProxy` loopback bind + scope cancel (security + leak).
6. P1 `SmbFileClient.openFile` / `NfsFileClient.read` resource + EOF fixes.
7. P1 split-A/V v4 aligned-start latch + ping-burst estimator poisoning.
8. P1 `HomeVoiceController.destroy()` job cancel + recognizer guard.
9. P1 `ResumableDownloadWorker` shared OkHttp + blank-URL guard.
10. P1 SyncPlay echo-suppression window completion-gating.
11. P1 GoogleCast LAUNCH `requestId` correlation; AirPlay volume curve.

## Sprint B — perf foundations + the Compose-stability prerequisite

12. Stand up a minimal GitHub Actions workflow (assemble + unit tests) —
    prerequisite for any CI gate.
13. Add the `androidx.baseline-profile` generator module +
    `androidx.profileinstaller` dependency; commit `baseline-prof.txt`.
    ~30% startup / ~40% first-scroll on phone & TV.
14. Stabilize `PlayerStateSnapshot` / `HomeState`
    (`kotlinx.collections.immutable` + typed error + `@Immutable`) — cheap,
    unblockable now.
15. Wire Compose compiler reports + `skydoves/compose-stability-analyzer`
    `stabilityCheck` gate on the new CI.
16. `@TraceRecomposition` + Macrobenchmark on home / player / Beam shell;
    establish before/after numbers.
17. Defer `UnifiedApplication.onCreate` non-essential work off-main; gate
    `UnifiedMainActivity.onResume` `refresh()` on a session-version change.

## Sprint C — feature polish

18. Fix the `WatchNextWorker` dedup race.
19. WebSearchClient: enforce HTTPS for SearXNG; surface "no auth
    configured" warning in settings.
20. RecommendationPlanner: differentiate "empty library" from "no good
    match" in telemetry + UI.
21. `SmartJellyfinRepository.runWrite` write-through for user-data writes.
22. `PlaylistManager`: cross-season continue + bounded subtitle probe +
    typed conversion errors.
23. Promote 1–2 `ASPIRATIONAL_IMPROVEMENTS` into asserted classifier cases.
24. Voice request queue: drop or coalesce taps while busy; TTS
    `utteranceId` tracking; `AssistantSpeechStateMachine` key fix.
25. Audit nav rail / `NavigationRail` for raycast blocking on XR.

## Sprint D — debt with a clock on it

26. Remove `fallbackToDestructiveMigration(dropAllTables = true)` (or
    exclude the download tables) so a missing migration fails loudly
    instead of nuking user downloads.
27. Re-attempt `isMinifyEnabled = true` against the latest Jetpack XR
    release; fix or refresh keep rules.
28. ~~AGP 9 upgrade~~ ✅ **done by fact** — `libs.versions.toml`
    `android-plugin = "9.2.1"`. Re-scope this slot to AGP 9.x maintenance
    / AGP 10 watch.
29. Revisit the ambilight blocker — file/upgrade the upstream Jetpack XR
    issue; ship a degraded 2D "ambient panel glow" fallback meanwhile.
30. `DownloaderImpl.downloadItems` bulk per-episode source resolution →
    move into a worker. Metered-network guard on `DownloadPreparationWorker`.

## Stretch / parking lot

- Multi-select bulk download polish on XR.
- TV focus instrumentation (Macrobenchmark + `FrameTimingMetric`).
- LiteRT NPU bundle once Qualcomm dispatch lib ships
  (`project_npu_blocker.md`).
- Migrate `XrPlayerActivity` off the process-kill workaround when upstream
  Filament bug closes.
- AirPlay 2 SRP-6a pairing (PIN-required HomeKit devices) — currently
  surface a typed error and disconnect.
- Custom Google Cast receiver app shipping libass via WebAssembly. PRs
  3–6 ship against Google's Default Media Receiver (`CC1AD845`), which
  supports WebVTT/TTML but not ASS — styled anime subs hit the
  burn-in-transcode path instead of rendering natively on Chromecast.
  Lifting that needs a registered Cast app id, public receiver HTML/JS,
  and a maintained CAF receiver running libass.wasm. Substantial infra;
  only worth doing if real demand for high-fidelity Chromecast anime subs
  materialises.

---

> **Doc-drift note (not a roadmap item, but flagged by the audit):**
> GEMINI.md:545 claims `UnifiedMainActivity.kt` is "~750 lines"; it is
> ~1059. The `SpatialPlayerScreen`/`PlayerViewModel` line counts at
> GEMINI.md:544 are likely stale post-cast. Per the GEMINI self-update
> mandate these should be corrected in the next commit that touches those
> files.
