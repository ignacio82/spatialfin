# SpatialFin — Audit & Improvement Proposal

- **Audit date:** 2026-06-18
- **Version:** 2.7.21 (122)
- **Branch / ref:** `main` @ `37ec550a`
- **Scope:** complementary to `ROADMAP.md` — focuses on repo/process hygiene,
  test-coverage structure, re-verified open items, and forward-looking bets.
  Verified code-level items are folded into `ROADMAP.md` (see **Sprint E**).

## Headline

SpatialFin is in unusually good shape for its size: ~129k LOC of Kotlin across
18 modules, a 600-line living `GEMINI.md`, a disciplined severity-tagged
`ROADMAP.md`, CI that runs assemble + unit tests + Compose stability gates, and
a baseline-profile / macrobenchmark harness. The codebase has only **3**
TODO/FIXME markers total — exceptionally clean.

This proposal deliberately avoids re-listing the roadmap. It targets four gaps
the roadmap doesn't currently cover: **(A)** repo/process hygiene, **(B)**
test-coverage structure, **(C)** still-open items re-verified against current
source, and **(D)** forward-looking product bets.

---

## A. Repo & process hygiene (low effort, real payoff)

The repo root has accumulated clutter `.gitignore` doesn't fully catch.

| Issue | Evidence | Why it matters |
|---|---|---|
| Vendored jar committed at repo root | `.aicore-0.0.1-exp02-classes.jar` (272 KB, **tracked**) | A binary dep at root instead of `third_party/maven/` or a real coordinate; opaque, unversioned, hard to audit/update. |
| One-off scripts tracked / loose | `capture_screenshot.py`, `view.xml`, `code.html`, `spec.html`, `scratch/dump_ump.main.kts` tracked; 7 untracked `*.py` image scripts (`fix_nano.py`, `regen_store_icon.py`, …) loose at root | Noise in every `git status`; image-processing scripts belong in `tools/` or `fastlane/`. |
| Large un-ignored working-tree artifacts | `logcat.txt` (~15 MB), `design.tar.gz` (~10 MB), `design.zip` (untracked but **not** ignored) | One stray `git add -A` commits ~25 MB of junk. |
| `.gitignore` gap | `*.log` is ignored but the captured log is named `logcat.txt` | Add `logcat*.txt`, `/*.tar.gz`, `/scratch/`, root `*.html`. |

**A1 — cleanup PR (~30 min, no code risk):** move scripts to `tools/`, relocate
the root jar into `third_party/maven/` (already used for the NFS jars) or resolve
it as a dependency, tighten `.gitignore`.

**A2 — CI gaps (`.github/workflows/ci.yml`):** CI assembles debug, runs the
unit-test matrix, and runs `stabilityCheck`. Missing:
- **No Android Lint** despite `lint.xml` existing — lint regressions ship silently.
- **No bundle/release-path validation** — release is unminified, so a
  `:bundleLibreRelease` break is invisible until store-upload day.
- **No dependency-vulnerability / license scan** — `renovate.json` only *updates*
  deps weekly; nothing *scans* them.

---

## B. Test coverage — the structural blind spot

56 unit-test files, but coverage is wildly uneven.

| Module | Unit tests | Note |
|---|---|---|
| `fcast` | 20 | Excellent — split-A/V drift/clock-sync well covered |
| `app/unified` | 14 | Good (voice/pose policy) |
| `player/xr` | 9 | Voice/skill logic covered |
| `data` | 3 | Repositories, network clients, downloads largely untested |
| `core` | 2 | LLM helper, workers untested |
| `modes/film` | **0** | All browse/detail/search logic untested |
| `player/local` | 2 | `PlayerViewModel` (2083 LOC), `PlaylistManager` untested |
| `player/tv`, `player/beam`, `player/session`, `setup`, `sendspin` | **0** | Zero |

Highest-ROI pure-logic classes with **no test** (device-free):
- `player/local/.../domain/PlaylistManager.kt` — episode/season resolution,
  next-up, subtitle probing (multiple `return null` branches).
- `core/.../llm/LlmChatModelHelper.kt` — the mutex-deadlock P0 fixed here has
  **no regression test**.
- `data/.../downloads/DownloadStorageManager.kt` — the `reconcileItemSources`
  data-loss P0 fixed here is untested.
- `data/.../network/{Smb,Nfs}FileClient.kt` — EOF/leak fixes unverified.

**B1:** add a regression test for **every P0 the roadmap marked ✅** in
`core`/`data` — these are the bugs most likely to silently reappear, and they
were all fixed without tests. Start with `LlmChatModelHelper` (mutex released on
`createConversation` throw) and `DownloadStorageManager`
(no-delete-while-download-active).

**B2:** stand up at least a smoke unit-test target in the four zero-test modules
so the CI matrix lines don't pass against nothing.

---

## C. Still-open items re-verified against current source (2026-06-18)

1. **`fallbackToDestructiveMigration(dropAllTables = true)` is still live** —
   `core/.../di/DatabaseModule.kt:33` (DB version 18). A missing migration
   silently drops `downloadtasks` + all offline userdata in production.
   *(Roadmap Sprint D #26 — confirmed still open; the builder lives in `core`,
   not `data`.)*
2. **WebSearchClient accepts `http://`** — `WebSearchClient.kt:82` uses
   `toHttpUrlOrNull()` with no scheme enforcement. LAN MITM can read assistant
   queries. *(Sprint C #19 — still open.)*
3. **`PlaylistManager` unbounded parallel subtitle probe** — `probeSubtitleSize`
   (`:674`) fired via `async(Dispatchers.IO)` (`:468`) with no `withTimeout` /
   concurrency bound; slow server stalls player-open.
4. **`PlaylistManager` swallows conversion errors** — bare `catch (e: Exception)`
   → `return null` (`:169/:186/:243/:289/:329`); playlist nav silently skips.
   *(Cross-season continue is now partially handled via `getNextUp` at `:68`.)*
5. **`GlobalScope` in `PlayerViewModel`** (`:50`) — detached SyncPlay-leave still
   has no failure surfacing.
6. **Release R8 disabled** — `app/unified/build.gradle.kts:77-78`
   (`isMinifyEnabled = false`, `isShrinkResources = false`), still blocked by the
   SceneCore `AbstractMethodError`. Larger APK, no symbol stripping, no dead-code
   elimination. See **D2**.

---

## D. Forward-looking bets (ranked by impact × confidence)

**D1 — Decompose the 2000+ LOC god-files.** `BeamJellyfinScreens.kt` (3969),
`SpatialPlayerScreen.kt` (2561), `BeamPlayerActivity.kt` (2407),
`SendspinReceiverService.kt` (2339), `TvPlayerActivity.kt` (2310),
`TvNavigationRoot.kt` (2240), `PlayerViewModel.kt` (2083). `SpatialPlayerScreen`
already shows the playbook (sibling `Player*.kt` extraction). `BeamJellyfinScreens`
at ~4k lines with zero tests is the worst offender — split it the same way.

**D2 — Re-attempt R8 surgically.** The blanket "release unminified" is a growing
liability. The crash is one `com.android.extensions.xr.function.Consumer.accept`
`AbstractMethodError`. Try: (a) R8 with shrinking on but `-dontoptimize`, or
(b) ship a **minified `tv`/Beam bundle** (non-XR, no SceneCore path) while keeping
XR unminified — shrinks the most-downloaded bundle without touching the crashing
path.

**D3 — Voice barge-in.** `SpatialVoiceSynthesizer._isSpeaking` is still a single
boolean ignoring `utteranceId` (roadmap P2). Tracking the active utterance id is
the foundation for reliable interruptible TTS — the biggest perceived-quality win
for a voice-first XR app.

**D4 — Profile-aware home.** MA + plugin layers are now per-Jellyfin-user scoped;
the home/browse surface isn't fully. A "who's watching" switch on XR/TV that
re-scopes Continue Watching + recommendations lands naturally on existing infra.

**D5 — Field observability.** The roadmap repeatedly notes "no telemetry" for the
exact failure modes that bit users (first-frame backstop release, RECAP mutex
hold, calibration min-distance). A small structured-event sink (local JSON-line,
extending `VoiceTelemetryStore`) makes the next audit data-driven.

---

## Suggested sequencing

1. **This week (zero-risk):** A1 cleanup + A2 lint-in-CI + WebSearchClient HTTPS
   (C2).
2. **Next:** B1 regression tests for shipped P0s; C3/C4 PlaylistManager timeout +
   typed errors.
3. **Sprint:** D2 (TV/Beam minified bundle) + D1 (`BeamJellyfinScreens` split).
4. **Bigger bets:** D3 voice barge-in, D5 telemetry, D4 profiles.
