# SpatialFin Audit - 2026-06-27

This note captures existing bugs and pragmatic improvement ideas found during a read-only audit of the SpatialFin repository. It now also tracks fix progress as issues are addressed.

The original audit did not change source files. Later fix work is tracked in the status notes below.

## Verification

| Command | Result | Notes |
| --- | --- | --- |
| `./gradlew :app:unified:assembleLibreDebug` | Passed | Debug app build succeeds. |
| `./gradlew :app:unified:bundleLibreRelease` | Passed | Release bundle task succeeds. |
| `./gradlew :app:unified:lintLibreDebug` | Passed with warnings | Lint still has baseline debt and active warnings. |
| `./gradlew test` | Passed after fix | Cross-module `internal` test compile failures were fixed by moving tests into owning modules. |

## Existing Bugs

### 1. Unit Tests And CI Are Broken

Status: Fixed on 2026-06-27.

Fix summary:

- Moved setup network-share tests from `app/unified` into `setup`.
- Moved Beam network-screen tests from `app/unified` into `shell/beam`.
- Moved Split A/V policy and calibration tests from `app/unified` into `fcast/session-ui`.
- Added minimal `testImplementation(libs.junit4)` dependencies to the modules now owning those tests.
- Verified with `./gradlew test`.

`./gradlew test` fails in `:app:unified:compileLibreDebugUnitTestKotlin`.

The failing tests live in `app/unified`, but they compile against `internal` declarations owned by other modules. Kotlin `internal` visibility is module-scoped, so these tests cannot reliably compile from the app module.

Affected test files include:

- `app/unified/src/test/java/dev/jdtech/jellyfin/presentation/network/NetworkShareSectionsTest.kt`
- `app/unified/src/test/java/dev/spatialfin/beam/BeamNetworkScreensTest.kt`
- `app/unified/src/test/java/dev/spatialfin/fcast/session/SplitAvAudioRoutePolicyTest.kt`
- `app/unified/src/test/java/dev/spatialfin/fcast/session/SplitAvStreamUrlPolicyTest.kt`
- `app/unified/src/test/java/dev/spatialfin/fcast/session/CalibrationPureTest.kt`

Examples of referenced `internal` production code:

- `setup/src/main/java/dev/jdtech/jellyfin/presentation/network/NetworkShareViewModel.kt`
- `shell/beam/src/main/java/dev/spatialfin/beam/BeamNetworkScreens.kt`
- `fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/SplitAvAudioRoutePolicy.kt`
- `fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/SplitAvStreamUrlPolicy.kt`
- `fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/calibration/CalibrationServer.kt`

Recommended fix:

- Move the tests into the modules that own the `internal` code, or
- Extract public pure-policy APIs where cross-module reuse is intentional, or
- Add deliberate test fixtures/friend-path configuration instead of relying on app-module visibility.

### 2. Download Encryption Finalization Can Report False Success

Status: Fixed on 2026-06-27.

Fix summary:

- `ResumableDownloadWorker.finalizeSuccess` now returns a success/failure result.
- Worker `doWork` only returns `Result.success()` when finalization actually succeeds.
- Encryption failure now marks the download task failed instead of continuing to `STATUS_SUCCESSFUL`.
- Encrypted file replacement now uses `Files.move(..., REPLACE_EXISTING)` with an atomic move when supported, avoiding delete-before-rename behavior that could lose the final file.
- Verified with `./gradlew :core:compileLibreDebugKotlin :core:testLibreDebugUnitTest`.

`ResumableDownloadWorker.finalizeSuccess` can mark a download as successful even when encryption replacement fails.

Relevant file:

- `core/src/main/java/dev/jdtech/jellyfin/work/ResumableDownloadWorker.kt`

The risky flow is:

1. The temporary download file is renamed to the final path.
2. If encryption is enabled, `encryptFileInPlace` is called.
3. `encryptFileInPlace` deletes the final plaintext file before renaming the encrypted file into place.
4. If that rename fails, the method returns `false`.
5. `finalizeSuccess` logs the encryption failure but continues to update the task as `STATUS_SUCCESSFUL`.

That can leave the database pointing at a completed download whose file is missing or invalid. `DownloadIntegrityWorker` may eventually repair some of these cases, but users can still see a broken completed download before that repair runs.

Recommended fix:

- Use an atomic or replace-safe move where available.
- If final replacement fails, mark the task failed or retryable.
- Never mark a download successful unless the final readable file is actually present.
- If encryption is enabled but the key is unavailable, fail or pause with an explicit unlock-required state instead of silently accepting plaintext.

### 3. Companion URL And Token Handling Is Duplicated And Under-Validated

Status: Fixed on 2026-06-27.

Fix summary:

- Added shared `CompanionEndpoint` validation/normalization in `core`.
- Added tests covering local cleartext URLs, public HTTPS URLs, public cleartext rejection, credentials/query rejection, path building, auth headers, and WebSocket conversion.
- Setup and Beam onboarding now validate before fetching and persist URL/token only after fetch/apply succeeds.
- TV pairing now validates the envelope endpoint and persists URL/token only after config apply succeeds.
- Background sync, live WebSocket sync, unified log upload, Beam log upload, and XR companion search now build token-bearing requests through the shared endpoint.
- Companion HTTP is allowed only for local-network hosts; public hosts require HTTPS.
- Verified with `./gradlew :core:testLibreDebugUnitTest :setup:compileDebugKotlin :shell:beam:compileDebugKotlin :shell:tv:compileDebugKotlin :app:unified:compileLibreDebugKotlin :player:xr:compileDebugKotlin`.

Companion setup paths persist `companion_url` and `setup_token` before full endpoint validation and successful fetch.

Relevant files:

- `setup/src/main/java/dev/jdtech/jellyfin/presentation/setup/welcome/CompanionViewModel.kt`
- `shell/beam/src/main/java/dev/spatialfin/beam/BeamCompanionScreen.kt`
- `shell/tv/src/main/java/dev/spatialfin/tv/TvCompanionScreen.kt`
- `core/src/main/java/dev/jdtech/jellyfin/work/CompanionSyncWorker.kt`
- `app/unified/src/main/java/dev/spatialfin/CompanionLiveSyncClient.kt`
- `shell/beam/src/main/java/dev/spatialfin/beam/BeamCompanionLogUploader.kt`

Multiple clients build URLs and attach `X-Setup-Token` independently. This creates inconsistent behavior and makes it easier for a malformed or unsafe pairing URL to persist.

Recommended fix:

- Add a central `CompanionEndpoint` parser/validator.
- Normalize base URLs with `HttpUrl`.
- Allow HTTPS everywhere.
- Allow HTTP only for loopback, RFC1918/private LAN, or `.local` hosts.
- Save URL/token only after validation and a successful companion fetch.
- Reuse the same endpoint abstraction for setup fetch, background sync, live WebSocket sync, and log upload.

### 4. Main-Thread Room Queries Are Allowed And Used

Status: Partially fixed on 2026-06-27.

Fix summary:

- Moved `MainViewModel` startup/session DAO reads and current-address repair writes to `Dispatchers.IO`.
- Moved `MainViewModel.loadServerAndUser` DAO reads to `Dispatchers.IO`.
- Moved the Hilt `JellyfinApi` provider's startup session lookup onto `Dispatchers.IO`.
- Moved `ServerAddressesViewModel.loadAddresses` DAO reads to `Dispatchers.IO`.
- Verified with `./gradlew :core:compileLibreDebugKotlin :setup:compileDebugKotlin`.

Remaining work:

- `allowMainThreadQueries()` is still present in `DatabaseModule`.
- Several older repository/activity paths still expose synchronous DAO calls and should be migrated before removing the global allowance.

Room is configured with `allowMainThreadQueries()` in:

- `core/src/main/java/dev/jdtech/jellyfin/di/DatabaseModule.kt`

`MainViewModel` then performs synchronous DAO calls from main-dispatched startup/navigation code in:

- `core/src/main/java/dev/jdtech/jellyfin/viewmodels/MainViewModel.kt`

This is a startup jank and ANR risk, especially as offline libraries and cached metadata grow.

Recommended fix:

- Move synchronous DAO calls to `Dispatchers.IO`.
- Prefer `suspend` DAO methods or `Flow` for read paths.
- Remove `allowMainThreadQueries()` once call sites are migrated.

### 5. Internal Player And Download Surfaces Are Exported

Status: Fixed on 2026-06-27.

Fix summary:

- Set `BeamPlayerActivity` to `android:exported="false"`.
- Moved `DownloadReceiver` processing off `BroadcastReceiver.onReceive`'s main thread with `goAsync()` and `Dispatchers.IO`.
- Hardened `DownloadReceiver` so it ignores non-terminal `DownloadManager` statuses instead of letting spoofed early broadcasts mark active downloads failed.
- Set `DownloadReceiver` to `android:exported="false"`. Confirmed the app no
  longer enqueues into the system `DownloadManager` (no `DownloadManager.Request`
  / `enqueue` call sites remain; all downloads run through WorkManager /
  `ResumableDownloadWorker`, and the system manager is only used for `.remove()`
  cleanup of legacy ids). Because nothing creates system downloads anymore,
  `ACTION_DOWNLOAD_COMPLETE` has no legitimate delivery path, so making the
  receiver non-exported removes no real functionality and closes the
  spoofable-broadcast surface — no device test of system delivery is required.
- Verified with `./gradlew :core:compileLibreDebugKotlin :player:beam:processDebugManifest :app:unified:processLibreDebugManifest`.

Remaining (optional) follow-up:

- The legacy `DownloadReceiver` is now effectively dead for new installs and is a
  candidate for full removal once no upgraded install can still hold an in-flight
  pre-WorkManager system download. Left in place (non-exported) for that edge case.

`BeamPlayerActivity` is exported without an intent filter:

- `player/beam/src/main/AndroidManifest.xml`

`DownloadReceiver` was exported for `android.intent.action.DOWNLOAD_COMPLETE` and then mutated DB/files (now non-exported):

- `app/unified/src/main/AndroidManifest.xml`
- `core/src/main/java/dev/jdtech/jellyfin/utils/DownloadReceiver.kt`

`BeamPlayerActivity` does validate missing extras defensively, so this is not an obvious crash path, but it still exposes an internal playback surface to explicit intents from other apps. `DownloadReceiver` should be reviewed now that the app also has a WorkManager-based resumable downloader.

Recommended fix:

- Set `BeamPlayerActivity` to `android:exported="false"` unless an external caller is required.
- Confirm whether the legacy `DownloadReceiver` is still needed.
- If it must remain exported, validate ownership/state tightly before mutating task state or files.

## Improvement Ideas

### 1. Fix Test Layout, Then Expand Coverage

Status: In progress on 2026-06-27.

Progress:

- Companion endpoint parsing/validation — covered by `CompanionEndpointTest`
  (core).
- `ResumableDownloadWorker` encryption/finalization — extracted the pure
  file/crypto logic into `ResumableDownloadFileOps` (atomic `replaceFile`,
  AES-CTR `encryptFileInPlace`, `progressFor`) so it is testable without a
  WorkManager/Hilt/Room harness, and added `ResumableDownloadFileOpsTest`:
  progress clamping, atomic replace (overwrite / create / missing-source
  failure), and encrypt→decrypt round-trips (multi-buffer, empty, and
  missing-source failure). Verified with `./gradlew :core:testLibreDebugUnitTest`.
- Sendspin off-LAN remote access — added `sendspin` module unit tests (the
  module previously had none): `RemoteIdTest` (hyphen/whitespace
  normalization, length/charset validation, null handling) and
  `SignalingCodecTest` (decode of every inbound frame, the FlexibleStringList
  single-string-vs-array ICE-server edge case, malformed-JSON tolerance, and
  outbound encode shapes). Verified with `./gradlew :sendspin:testDebugUnitTest`.

Remaining high-value targets:

- `SendspinReceiverService` in-service command/state machine (the WebRTC
  signaling/`RemoteId` parsing it depends on is now covered).
- Beam/TV player intent parsing.
- Room migration and upgrade behavior.
- FCAST split AV policy and calibration logic (partial: policy tests already
  exist under `fcast:session-ui`).

Modules with little or no direct test coverage include:

- `shell:beam`
- `shell:tv`
- `setup`
- `modes:film`
- `modes:audio`
- `modes:music`
- `player:beam`
- `player:tv`
- `player:session`

### 2. Split The Largest Files By Extracting Pure Logic First

Several files are large enough that defects become harder to isolate and test.

Largest examples found:

| File | Approximate LOC |
| --- | ---: |
| `shell/beam/.../BeamJellyfinScreens.kt` | 3969 |
| `player/xr/.../SpatialPlayerScreen.kt` | 2701 |
| `player/beam/.../BeamPlayerActivity.kt` | 2595 |
| `sendspin/.../SendspinReceiverService.kt` | 2348 |
| `player/tv/.../TvPlayerActivity.kt` | 2328 |
| `shell/tv/.../TvNavigationRoot.kt` | 2243 |
| `player/local/.../PlayerViewModel.kt` | 2083 |
| `fcast/session-ui/.../CastSessionManager.kt` | 1998 |

Recommended approach:

- Extract pure policies and state machines before UI reshuffling.
- Add tests around extracted logic.
- Leave visual/component refactors for after behavior is locked down.

### 3. Prune Lint Baseline Debt

Status: Active warning fixed on 2026-06-27; baseline pruning still open.

Fix summary:

- Annotated `JellyfinAudioDetailType` with `@androidx.annotation.Keep` so its
  kotlinx.serialization constant names survive R8, clearing the active
  `MissingKeepAnnotation` warning.
- Removed the now-stale `JellyfinAudioDetailType` entry from
  `app/unified/lint-baseline.xml`.

Remaining work:

- Regenerate/prune the rest of the stale baseline (the ~90 no-longer-present
  entries and the second `MissingKeepAnnotation` on `CollectionType`).
- Consider making new lint warnings fatal once the baseline is smaller.

`./gradlew :app:unified:lintLibreDebug` passes, but lint reports:

- 54 errors and 98 warnings hidden by the baseline.
- 90 baseline entries that are no longer present.
- An active R8-sensitive enum warning for `JellyfinAudioDetailType`.

Relevant file for the active warning:

- `modes/audio/src/main/java/dev/spatialfin/unified/audio/JellyfinAudioScreens.kt`

Recommended fix:

- Regenerate or prune the stale baseline.
- Fix the current `MissingKeepAnnotation` warning.
- Consider making new lint warnings fatal once the baseline is smaller and cleaner.

### 4. Revisit Release Shrinking

Release minification and resource shrinking are disabled in:

- `app/unified/build.gradle.kts`

The comment indicates this is due to a SceneCore crash. That may be necessary today, but it leaves size and optimization benefits on the table.

Recommended path:

- Add the missing `@Keep`/keep-rule coverage lint already points to.
- Re-enable R8 surgically by flavor or surface if possible.
- Try a narrowed SceneCore keep rule or `-dontoptimize` for the XR path before disabling shrinking globally.

### 5. Formalize The Root Binary Artifact

Status: Resolved on 2026-06-27 — the opaque root jar was removed.

Fix summary:

- Deleted `.aicore-0.0.1-exp02-classes.jar` from the repository root.
- Confirmed no build script references it (`grep` over `*.gradle.kts` / `*.gradle`
  / `*.toml` / `settings.gradle.kts` finds no usage), so removal is build-safe.
- If this artifact is ever re-vendored, add it under a documented `third_party`
  path with provenance + checksum rather than at the repo root.

The repository previously tracked:

- `.aicore-0.0.1-exp02-classes.jar`

Recommended fix:

- Move this to a documented `third_party` or Maven/local artifact path.
- Add provenance and checksum documentation.
- Avoid keeping opaque binary dependencies at the repository root.

## Notes On Prior Proposal

Some findings in `docs/IMPROVEMENT_PROPOSAL_2026-06.md` appear to be stale now. In particular:

- Database downgrade destructive migration appears to have been corrected with downgrade-only destructive fallback.
- Direct SearXNG URL HTTPS enforcement appears to have been addressed.
- Playlist subtitle probing now appears bounded by semaphore/timeout behavior.
- CI now runs lint.
- Several regression tests mentioned as missing now exist, though the broader test suite currently does not compile.
