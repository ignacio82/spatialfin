# Relicensing Audit

Date: 2026-03-15

## Bottom Line

This repository should be treated as **GPL-3.0-bound today**.

The current codebase is not just inspired by Findroid; it was imported as a large, already-working application in the initial SpatialFin commit and then extended with XR-specific work. As a result, removing branding or attribution strings is **not enough** to safely relicense the repo under a non-GPL license.

This audit is a technical provenance review, not legal advice.

## Method

The classification below is based on:

- explicit references to Findroid and GPL in the current repo
- the initial repo commit: `d0322c8b6c3b1e56a1afaf77e248203ea001f6e7`
- files added or modified after that initial commit
- package naming and module structure
- whether code appears to be generic app foundation versus clearly XR-specific additions

Important limitation:

- The git history available here starts at `Initial commit of SpatialFin`.
- That means I cannot prove file-by-file which parts came from Findroid versus another local prehistory.
- Because of that, this audit uses a conservative rule:
  if a file existed in the initial commit and looks like general app/client infrastructure, it should be treated as **likely inherited or derivative**.

## High-Confidence Findings

### 1. The initial SpatialFin commit already contained a full app foundation

The first commit already included:

- full Jellyfin data/repository/database layers
- setup/onboarding flows
- browse/detail/settings screens
- player modules
- translations and asset packs
- GPL license text
- Findroid app-store metadata

That strongly indicates the repo began from an imported codebase rather than from a clean-room rewrite.

### 2. Explicit Findroid carryover still exists

Current direct references include:

- [README.md](README.md) says the project was forked from Findroid and says inherited code keeps the original license.
- [LICENSE](LICENSE) is GPL-3.0.
- [fastlane/metadata/android/en-US/title.txt](fastlane/metadata/android/en-US/title.txt) still says `Findroid`.
- [fastlane/metadata/android/en-US/full_description.txt](fastlane/metadata/android/en-US/full_description.txt) still describes the app as Findroid.
- [GEMINI.md](GEMINI.md) still refers to Findroid.
- [app/xr/src/main/java/dev/jdtech/jellyfin/presentation/settings/AboutScreen.kt](app/xr/src/main/java/dev/jdtech/jellyfin/presentation/settings/AboutScreen.kt) still links to the original Findroid repo and donation page.

### 3. Most source code is still in the inherited namespace

Current Kotlin source package counts:

- `app/xr/src/main/java`: `98` Kotlin files, `89` in `dev.jdtech.jellyfin`, `9` in `dev.spatialfin`
- `core/src/main/java`: `42` Kotlin files, all `dev.jdtech.jellyfin`
- `data/src/main/java`: `52` Kotlin files, all `dev.jdtech.jellyfin`
- `settings/src/main/java`: `20` Kotlin files, all `dev.jdtech.jellyfin`
- `setup/src/main/java`: `23` Kotlin files, all `dev.jdtech.jellyfin`
- `player/core/src/main/java`: `8` Kotlin files, all `dev.jdtech.jellyfin`
- `player/local/src/main/java`: `3` Kotlin files, all `dev.jdtech.jellyfin`
- `player/session/src/main/java`: `3` Kotlin files, all `dev.jdtech.jellyfin`
- `player/xr/src/main/java`: `10` Kotlin files, all `dev.jdtech.jellyfin`
- `modes/film/src/main/java`: `34` Kotlin files, all `dev.jdtech.jellyfin`

This does not prove origin by itself, but it is strong evidence that the codebase is still fundamentally the inherited Jellyfin/Findroid app with SpatialFin-specific additions layered on top.

## Classification

## A. Likely Inherited Or Derivative

These areas should be assumed GPL-bound unless you have separate provenance proof or rewritten replacements.

### App foundation

- `core/**`
- `data/**`
- `settings/**`
- `setup/**`
- `modes/film/**`
- `player/core/**`
- `player/local/**`

### Most XR presentation built on imported app flows

- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/**`
  except for clearly new files listed below

### Existing player shell and XR app glue present in the first commit

- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrPlayerActivity.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/StereoModeDetector.kt`
- `app/xr/src/main/java/dev/spatialfin/MainActivity.kt`
- `app/xr/src/main/java/dev/spatialfin/NavigationRoot.kt`
- `app/xr/src/main/java/dev/spatialfin/SpatialFinApplication.kt`

These files have since been modified heavily, but they existed in the initial import, so the safe assumption is still derivative.

### Assets, strings, and metadata imported with the base app

- `core/src/main/res/**`
- `settings/src/main/res/**`
- `setup/src/main/res/**`
- `modes/film/src/main/res/**`
- `fastlane/**`
- `images/**`

### Branding and legal files inherited or derivative

- `LICENSE`
- `README.md`
- `PRIVACY`
- `GEMINI.md`

## B. Likely New SpatialFin Work

These are the strongest candidates for code that is primarily yours in this repo history.

### New XR/player capabilities added after the initial import

- `player/session/**`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/LibassRenderer.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/LibassSubtitleHelper.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/LibassTextRenderer.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/voice/**`
- `player/xr/src/main/cpp/libass_jni.c`
- `player/xr/build_native.sh`

### Local-media additions

- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/local/**`
- `data/src/main/java/dev/jdtech/jellyfin/repository/LocalMediaRepository.kt`
- `data/src/main/java/dev/jdtech/jellyfin/repository/LocalMediaRepositoryImpl.kt`
- `data/src/main/java/dev/jdtech/jellyfin/models/LocalVideoItem.kt`
- `data/src/main/java/dev/jdtech/jellyfin/models/LocalMediaPlaybackStateDto.kt`
- `data/src/main/java/dev/jdtech/jellyfin/downloads/DownloadStorageManager.kt`
- `data/src/main/java/dev/jdtech/jellyfin/models/DownloadRequest.kt`

### Offline/smart repository additions

- `core/src/main/java/dev/jdtech/jellyfin/offline/ServerConnectionMonitor.kt`
- `core/src/main/java/dev/jdtech/jellyfin/repository/SmartJellyfinRepository.kt`

### New utility and logging additions

- `app/xr/src/main/java/dev/spatialfin/LogFileTree.kt`
- `settings/src/main/java/dev/jdtech/jellyfin/settings/voice/VoiceTelemetryStore.kt`

### New UI components added after the import

- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/components/XrConfirmDialog.kt`
- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/film/components/DownloadOptionsDialog.kt`
- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/settings/components/SettingsInfoCard.kt`

Important caution:

- Even if these files are new, some may still depend tightly on inherited APIs and data models.
- That makes them safer as authorship candidates, but not automatically safe for relicensing if they are derivative adaptations of GPL code.

## C. Needs Manual Review

These files were present in the initial import but have been significantly reworked. They are likely still derivative, but they are the first places to review if you want to separate "old baseline" from "new SpatialFin behavior."

### XR app shell

- `app/xr/src/main/java/dev/spatialfin/MainActivity.kt`
- `app/xr/src/main/java/dev/spatialfin/NavigationRoot.kt`
- `app/xr/src/main/java/dev/spatialfin/SpatialFinApplication.kt`
- `app/xr/src/main/java/dev/spatialfin/presentation/theme/Theme.kt`

### XR player and player integration

- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt`
- `player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/XrPlayerActivity.kt`
- `player/local/src/main/java/dev/jdtech/jellyfin/player/local/presentation/PlayerViewModel.kt`
- `player/local/src/main/java/dev/jdtech/jellyfin/player/local/domain/PlaylistManager.kt`
- `player/core/src/main/java/dev/jdtech/jellyfin/player/core/domain/models/PlayerItem.kt`

### Browse/detail/settings screens with heavy XR rework

- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/film/**`
- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/settings/**`
- `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/setup/**`

### Repository/model files extended for new features

- `data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepository.kt`
- `data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryImpl.kt`
- `data/src/main/java/dev/jdtech/jellyfin/repository/JellyfinRepositoryOfflineImpl.kt`
- `data/src/main/java/dev/jdtech/jellyfin/database/ServerDatabase.kt`
- `data/src/main/java/dev/jdtech/jellyfin/database/ServerDatabaseDao.kt`
- `data/src/main/java/dev/jdtech/jellyfin/models/SpatialFinMediaStream.kt`
- `data/src/main/java/dev/jdtech/jellyfin/models/SpatialFinMediaStreamDto.kt`

## D. Third-Party Or Separately Licensed Material

These are not Findroid, but they still matter for relicensing:

- `build_native_work/**`
  contains vendored/generated third-party native dependencies and their own licenses
- `player/xr/src/main/jniLibs/arm64-v8a/libass_jni.so`
  binary artifact
- `release/**`
  generated artifacts
- Gradle wrapper and standard tool files

These do not help with a non-GPL relicense. They introduce their own license-compliance obligations.

## What This Means For Relicensing

## Safe assumption today

You should assume:

- the current repo as a whole is GPL-3.0
- most of the app foundation is inherited or derivative
- only a subset of newer XR/local/offline code is plausibly original enough to reuse directly

## What would be required to move to another license

You have two realistic paths.

### Option 1. Stay on GPL and clean branding

This is the low-risk option.

Do this if you want:

- no Findroid mentions in product copy
- cleaner attribution handling
- a more distinct SpatialFin identity

But this does **not** remove GPL obligations.

### Option 2. Build a new clean-room codebase

This is the realistic path to a different license.

That means:

1. Keep only code you can confidently classify as original and non-derivative.
2. Rewrite all inherited/derivative foundation layers from scratch.
3. Replace imported assets, strings, fastlane metadata, and app-store copy.
4. Re-audit third-party dependencies and native components independently.

Most likely rewrite targets:

- `core`
- `data`
- `settings`
- `setup`
- `modes/film`
- most `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/**`
- large parts of `player/local` and `player/xr`

## Practical Recommendation

Treat this repo as having three buckets:

### Bucket 1. Keep only as reference, do not reuse directly in a non-GPL rewrite

- all imported generic app modules
- setup, browsing, settings, repository, and database layers
- original resources and metadata

### Bucket 2. Candidate source for a future non-GPL rewrite, but still review carefully

- XR voice concepts
- SyncPlay product behavior
- local media/offline product behavior
- libass integration architecture
- `player/session` ideas and interfaces

### Bucket 3. Immediate cleanup work regardless of license decision

- remove stale Findroid app-store metadata
- remove Findroid repo/donation links
- replace fork wording in README if you prefer
- separate generated and vendored native artifacts from the app repo

## Suggested Next Steps

1. Do not change the license yet.
2. Remove misleading branding only if you keep the GPL notice intact.
3. If you want a non-GPL future, start a new repo and use this one only as a behavioral reference.
4. If needed, perform a second-pass audit at the file level for:
   - `app/xr/src/main/java/dev/jdtech/jellyfin/presentation/**`
   - `player/xr/**`
   - `player/local/**`
   - `data/**`

## High-Signal Evidence

- Initial import commit: `d0322c8b6c3b1e56a1afaf77e248203ea001f6e7`
- Current license file: [LICENSE](LICENSE)
- Current fork notice: [README.md](README.md)
- Stale Findroid metadata: [fastlane/metadata/android/en-US/title.txt](fastlane/metadata/android/en-US/title.txt), [fastlane/metadata/android/en-US/full_description.txt](fastlane/metadata/android/en-US/full_description.txt)
- Stale Findroid links: [app/xr/src/main/java/dev/jdtech/jellyfin/presentation/settings/AboutScreen.kt](app/xr/src/main/java/dev/jdtech/jellyfin/presentation/settings/AboutScreen.kt)
