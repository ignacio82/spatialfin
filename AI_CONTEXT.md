# AI_CONTEXT.md

This file is a compact repository guide for LLM-assisted work. It is intended for manual use, external tooling, or repo-to-context export scripts.

## Read First

Read files in this order unless the task is narrowly scoped:

1. `README.md`
2. `GEMINI.md`
3. `app/xr/build.gradle.kts`
4. `player/xr/build.gradle.kts`
5. Relevant source under `app/`, `player/`, `core/`, `data/`, `modes/`, `settings/`, or `setup/`

## High-Value Modules

- `app/xr/`: app startup and XR application wiring
- `player/xr/`: immersive player, subtitles, spatial UI, voice UX
- `player/session/`: player actions, orchestration, and voice command execution
- `player/local/`: local playback engine
- `data/`: Jellyfin API, persistence, repositories
- `core/`: shared UI and utilities
- `settings/`: preferences
- `setup/`: onboarding and server setup

## Usually Ignore

Skip these paths unless the task explicitly targets them:

- `.git/`
- `**/build/`
- `build_native_work/`
- `release/`
- `fastlane/metadata/android/en-US/images/`
- `androidx/`

## Binary And Generated File Notes

- `build_native_work/` is reproducible native build scratch space, not source of truth.
- `player/xr/src/main/jniLibs/` contains intentionally checked-in prebuilt `libass_jni.so` files so a fresh clone can build without native toolchain setup.
- `glb/` and large media/store asset folders are useful for product packaging, but usually not for code reasoning.

## Native Rebuild Note

If the prebuilt subtitle JNI library must be regenerated, use:

```bash
./player/xr/build_native.sh /path/to/android-ndk
```
