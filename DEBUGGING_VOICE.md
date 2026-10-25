# Debugging Voice & AI in SpatialFin

This document details device debugging workflows, wireless ADB connection steps, common log tags, triage order, and known failure patterns for the voice and on-device AI subsystems in SpatialFin.

---

## Wireless ADB Workflow For XR Headsets

1. On the headset: `Developer options` → `Wireless debugging` → `Pair device with pairing code`.
2. From the workstation: `adb pair <headset-ip>:<pairing-port>` and enter the pairing code.
3. Find the actual debug endpoint via the wireless debugging screen or `adb mdns services`.
4. `adb connect <headset-ip>:<debug-port>` and verify with `adb devices -l`.

Notes:
- Pairing port ≠ debug port. Don't confuse them.
- XR headsets may appear twice (`<ip>:<port>` and an mDNS alias). Always use the explicit `<ip>:<debug-port>` with `adb -s ...`.

## Install / Logcat Commands

```bash
./gradlew :app:unified:assembleLibreDebug
adb -s <ip>:<debug-port> install -r app/unified/build/outputs/apk/libre/debug/spatialfin-libre-arm64-v8a-debug.apk

adb -s <ip>:<debug-port> shell pidof dev.spatialfin.debug
adb -s <ip>:<debug-port> shell dumpsys package dev.spatialfin.debug

adb -s <ip>:<debug-port> logcat -c
adb -s <ip>:<debug-port> logcat -b all -v threadtime | tee xr-crash.log

# PID-filtered
adb -s <ip>:<debug-port> logcat -d -v threadtime --pid $(adb -s <ip>:<debug-port> shell pidof dev.spatialfin.debug)
```

High-signal log sources: `AndroidRuntime`, `libc`, `SurfaceFlinger`, `BufferQueue`, OpenXR vendor logs, and the app's own `dev.spatialfin.debug` lines (parser, voice, player, subtitle).

## Fast Verification

```bash
./gradlew :player:xr:testDebugUnitTest                            # cheapest regression check
./gradlew :player:xr:compileDebugKotlin
./gradlew :app:unified:compileLibreDebugKotlin
```

## Where To Look In Logs

User logs land under `Downloads/SpatialFin/`. Tags worth grepping: `SpatialVoiceService`, `SpatialCommandCoordinator`, `GemmaCommandParser`, `LlmChatModelHelper`, `VoiceTelemetryStore`, `SUB:` (libass).

## Failure Triage Order

1. **Speech layer** — did recognition start? soft errors `5`/`7`? did retry happen?
2. **Parser layer** — was transcript normalized? keyword/replay match? Did the LiteRT tool-call return `interpret_command` with a known `action`? Did the AICore JSON fallback truncate?
3. **Skill layer** — which `selectedSkill`? `validatedInput` sensible? reply was `DIRECT`/`MODEL`/`FALLBACK`?
4. **Execution / UI layer** — was `ChatQuery` actually handled on the current screen? did `recommendedItems` populate? did the overlay show feedback?

## Common Real Failure Patterns

- **`TF_LITE_AUX not found in the model`** — NPU unsupported for that model/device. GPU fallback expected. Cache the failing backend.
- **`GemmaCommandParser: tool-call path failed — falling back to JSON parse`** — the LiteRT tool-call returned null or threw. Expected on AICore (Gemini Nano has no schema support in `mlkit-genai-prompt:1.0.0-beta2`); investigate on LiteRT if the model's been behaving. The JSON-in-text retry still runs behind this, so it's not a user-visible failure on its own.
- **`GemmaCommandParser: raw response: {"`** — truncated JSON on the fallback path. Rare now that typed tool calling is primary on LiteRT; more likely on AICore. Parser retries once with a stricter instruction before falling through to `Unrecognized`.
- **Speech soft errors `5` / `7`** — transient recognizer issues. One automatic retry expected.
- **"Thinking…" never resolves** — usually not a parser problem. Check that `ChatQuery` is handled on the current screen and that feedback / results UI is rendered.
- **App silently disappears on XR space-mode switch (multitask ↔ immersive) or any Activity lifecycle transition** — **not a code crash**. Logcat fingerprint is `ActivityManager: Killing <pid>:dev.spatialfin.debug ... stop dev.spatialfin.debug due to set debug app` followed by `Zygote: Process <pid> exited due to signal 9 (Killed)`. No Java stack, no tombstone. Cause: `am set-debug-app -w dev.spatialfin.debug` is armed on the device (usually left behind by Android Studio's "Attach debugger to Android process" or a prior `adb shell am set-debug-app -w`). With `-w`, Android waits for a debugger and SIGKILLs the process on lifecycle events when none is attached. Verify with `adb shell settings get global debug_app` (non-null value means armed). Fix: `adb shell am clear-debug-app`. Does not persist across reboots, but Android Studio can re-arm it.
- **Transport voice commands ("fast forward", "rewind", "seek") crashing on Galaxy XR:** Playback commands must resolve in `exactKeywordMatch()` *before* LiteRT/Gemma inference. On Galaxy XR (Adreno GPU / Snapdragon XR2+ Gen 2), running on-device LLM inference while video decoding and spatial composition run concurrently causes GPU contention, driver crashes, or tool-call failures. In addition: (1) regexes must cover common ASR typos (e.g. `fast[ -]?for?ward`); (2) `PlayerSessionController` must clamp target positions against `duration` and `0L`, and dynamically resolve `activePlayer` via `viewModel.player` to avoid stale/released instances; (3) parsing and dispatch in `PlayerVoiceCapture` must be wrapped in `runCatching` so uncaught exceptions never crash the Android Main looper. <!-- added 2026-10-17: keyword bypass for playback controls & defensive seek clamping -->

## Telemetry Expectations

`VoiceTelemetryStore` records: `transcript`, `normalizedTranscript`, `action`, `strategy`, `selectedSkill`, `validatedInput`, `resultDisposition` (`DIRECT` / `MODEL` / `FALLBACK`), `success`, `details`. This split is critical for "did the parser fail, the skill fail, or the model fail?"
