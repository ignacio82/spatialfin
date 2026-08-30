# SpatialFin Wear OS Companion App — Implementation Plan

> **Status:** ✅ **Implemented** (30 August 2026). `:companion:protocol`, `:companion:host` and
> `:companion:wear` are in `settings.gradle.kts` and build. Code blocks below remain *sketches of
> intent* — read the modules for what actually shipped.
>
> **Two decisions in this document turned out to be unimplementable and were changed. Both are
> recorded in [§10 Implementation Deviations](#10-implementation-deviations):** the §4.5 voice
> transport (PCM → transcript) and the §4.4 Ongoing Activity host service.
>
> **Document Version:** 2.1.0 (30 August 2026) — v1.0.0 was fact-checked against the tree and
> corrected; see [§9 Changelog](#9-changelog).
> **Proposed Module:** `:companion:wear`
> **Target Platforms:** Wear OS 4.0+ (API 33+), paired with Android XR (Galaxy XR), Android TV
> (`:shell:tv`), and the Beam phone shell (`:shell:beam`)
> **Authoritative Context:** [GEMINI.md](../GEMINI.md), [DESIGN.md](../DESIGN.md), [README.md](../README.md)

> ⚠️ **Before writing any code, read [§2 Blocking Design Decisions](#2-blocking-design-decisions).**
> Five constraints (applicationId, `minSdk`, module weight, credential bootstrap, Play delivery)
> invalidate parts of this plan if guessed wrong, and two of them are cheap to settle now and
> expensive to reverse after Phase 3.
>
> ⚠️ **Then read [§3.2 Host-Side Work & Existing Seams](#32-host-side-work--existing-seams).**
> Roughly half this feature lives in the *host* app, not on the watch, and the repo already has a
> typed action/state layer that most of Subsystem 1 and 2 should be built on instead of the
> hand-rolled RPC vocabulary this document originally proposed.

> 📄 **Note on file location:** `docs/` is the published GitHub Pages site
> (`spatialfin.martinez.fyi`, see `docs/CNAME`). This file is served publicly as raw Markdown at
> `/wear.md`. That is acceptable — `docs/SPATIALFIN_AUDIT_2026-06-27.md` is already there — but
> write it as a public document, not as an internal scratchpad.

---

## 1. Executive Summary & Vision

The **SpatialFin Wear OS Companion App** (`:companion:wear`) is a lightweight smartwatch client
designed as an ergonomic, tactile extension of the SpatialFin ecosystem.

SpatialFin delivers cinematic playback across Android XR, Android TV, and handheld phones, but
interaction in spatial computing runs into a physical constraint: **"gorilla arm" fatigue** from
sustained mid-air pinch-and-reach gestures, which is worst in exactly the posture people watch
films in — reclined on a couch or in bed, arms down.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                SPATIALFIN ECOSYSTEM                                    │
├──────────────────────────┬─────────────────────────────┬───────────────────────────────┤
│    Android XR Headset    │         Android TV          │        Beam Phone Shell       │
│  (Immersive Full/Home)   │     (10-foot Leanback)      │       (Handheld Player)       │
└────────────┬─────────────┴──────────────┬──────────────┴───────────────┬───────────────┘
             │                            │                              │
             ▼                            ▼                              ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        SPATIALFIN WEAR OS COMPANION APP                                │
│  • Tactile rotary crown scrubbing & volume     • Wrist voice mic (relayed, not on-watch)│
│  • FCast sender (wrist fling to headset/TV)    • FCast receiver (Split-A/V audio sink)  │
│  • Subtitle & audio stream fast switcher       • Music Assistant & SendSpin zone hub    │
│  • Glanceable ProtoLayout tiles & complications• Headset vitals & battery HUD           │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

Bringing transport controls, rotary scrubbing, voice input, split-audio playback, and glanceable
complications to the wrist makes spatial media effortless, tactile, and discreet.

### 1.1 Non-Goals

Stating these up front keeps scope from creeping across five phases:

- **No library browsing beyond "Continue Watching".** A watch is a remote, not a catalogue. Deep
  Jellyfin browse stays on the phone, headset, and TV.
- **No video playback on the watch.** The watch renders *audio only*, and only as a Split-A/V sink.
- **No on-watch LLM inference.** The voice pipeline (`SpatialVoiceService`,
  `SpatialCommandCoordinator`) stays on the paired host. The watch is a microphone and a speaker.
- **No standalone Jellyfin login UI on the watch.** Credentials are provisioned from a paired host
  (see [§2.4](#24-credential-bootstrap-for-standalone-mode)).
- **No independent Play listing.** The Wear artifact ships alongside the existing bundles
  (see [§2.5](#25-play-delivery-and-version-codes)).

---

## 2. Blocking Design Decisions

These five items were the source of most factual errors in v1.0.0 of this document. Settle each
one — with a written answer in this section — before Phase 1 starts.

### 2.1 `applicationId` must match the host app

**This is the single highest-risk item in the plan.**

The Wearable Data Layer (`MessageClient`, `DataClient`, `CapabilityClient`) exchanges messages and
data items only between apps that share the **same package name and signing certificate** across
the paired nodes. A Wear app published as `dev.spatialfin.companion.wear` therefore cannot see or
message `dev.spatialfin` on the phone — Transport A ([§4.1](#subsystem-1-dual-transport-connectivity-engine))
would silently deliver nothing, and the failure looks like a pairing bug rather than a packaging bug.

**Decision:** the Wear module uses `applicationId = "dev.spatialfin"` (matching `:app:unified`) and
distinguishes itself by `namespace` only. It must also mirror `:app:unified`'s per-build-type
suffixes (`.debug`, `.staging`) or debug builds will fail to pair with a debug phone build while
release builds work — a maddening asymmetry to debug after the fact.

- [ ] **Verify on hardware in Phase 0**, not Phase 2: install a stub Wear APK and a stub phone APK
      sharing `dev.spatialfin`, and confirm a `MessageClient` round trip before any UI is written.

### 2.2 `minSdk` floor is 31, not 30

`spatialfin.android.library` pins every library module to `SpatialfinSdk.MIN_SDK = 31`, and
`:fcast` sets `minSdk = Versions.MIN_SDK` (also 31) directly. An app module at `minSdk 30` that
consumes them fails the manifest merge outright.

**Decision:** `minSdk = 33` (Wear OS 4). This clears the 31 floor, matches the stated target
platform, and covers Galaxy Watch 6/7/Ultra and Pixel Watch 2/3. Wear OS 3 (API 30) is out of scope.

### 2.3 Do not depend on `:core`

`core/build.gradle.kts` pulls in `:data`, `:player:core`, `:settings`, Room, WorkManager, Hilt, the
Jellyfin SDK, `litertlm-android`, `mlkit-genai-prompt`, `tflite-gpu`, ZXing, and Material. Dragging
that graph onto a watch contradicts "lean footprint" by an order of magnitude, and most of it
(Room, LiteRT, MLKit) is dead weight the watch will never execute.

`:fcast` is the better boundary — it depends only on `androidx.core`, Compose, coroutines,
serialization, jmdns, OkHttp, and Timber, with no edge to `:core` or `:data`. It does carry
*mobile* Compose Material3 for `fcast/ui/FCastReceiverPicker.kt` and `fcast/ui/FCastSenderHost.kt`,
which R8 will strip from the Wear build but which still bloat the debug APK.

**Decision:**

- Depend on `:fcast` for the protocol, sender, receiver, and mDNS discovery.
- **Do not** depend on `:core`, `:data`, `:settings`, or `:player:session`.
- Add a new **pure-Kotlin (`java-library`) `:companion:protocol`** module holding only the wire
  types the watch and host both need, serialized with `kotlinx.serialization`. Both
  `:app:unified` and `:companion:wear` depend on it. It is also the only place in this feature
  that is cheaply unit-testable on the JVM ([§7](#7-verification--test-matrix)), which is a second
  reason to keep it pure.
  - The **command and state vocabulary should come from the existing `XrPlayerAction` and
    `PlayerStateSnapshot`**, not from new hand-rolled DTOs — see
    [§3.2](#32-host-side-work--existing-seams). Whether to *mirror* or *move* those types is an
    open question ([§8](#8-open-questions)).
  - Anything else the watch needs — `SpatialFinMediaStream`, `SpatialFinChapter`
    (`data/src/main/java/dev/jdtech/jellyfin/models/`), `CompanionTvPairingPayload` /
    `CompanionConfig` (`core/.../models/companion/CompanionModels.kt`) — must be **mirrored by
    field, never re-exported**, or `:companion:protocol` inherits `:data`'s Jellyfin SDK edge and
    stops being pure Kotlin.
- **Stretch:** if the Compose UI in `:fcast` proves costly, split it into `:fcast:ui` the way
  `:fcast:session-ui` is already split out. Measure first.

### 2.4 Credential bootstrap for standalone mode

Transport B ([§4.1](#subsystem-1-dual-transport-connectivity-engine)) is defined as "phone
disconnected", but SpatialFin's network remote control is **not peer-to-peer**. As
`RemoteControlViewModel` documents, commands reach the target through the **Jellyfin WebSocket
relay** (`SyncPlayCoordinator`), which requires a base URL, an access token, and a device id from
`JellyfinRepository`. The watch has none of these unless a paired host hands them over — and the
Data Layer is precisely the channel Transport B assumes is unavailable.

**Decision:** provision credentials **eagerly, while tethered**, and cache them encrypted on the
watch:

1. On every successful Data Layer connection, the host pushes a `CompanionConfig`-shaped credential
   bundle to `/state/credentials` via `DataClient`.
2. The watch persists it with `EncryptedSharedPreferences` (or Jetpack Security's successor,
   whichever the app standardises on) and refreshes it whenever the host reconnects.
3. Standalone mode reads the cache. If it is empty or the token is rejected, the watch shows
   "Connect your phone once to finish setup" rather than a generic network error.

Direct FCast (`_fcast._tcp`) needs no Jellyfin credentials, so the **FCast half of Transport B works
standalone on day one**; only the Jellyfin-relay half depends on this bootstrap. Phase the work
accordingly.

### 2.5 Play delivery and version codes

Two bundles ship today — `libre` (phone/XR/Beam) and `tv` — and GEMINI.md's *Play Track Bundles*
section forbids unifying them. `tv` offsets its version code by `+1_000_000`
(`Versions.APP_CODE + 1_000_000`), and `.github/scripts/tag-release.sh` hardcodes that offset in
its tag message.

A Wear artifact under the same `applicationId` ([§2.1](#21-applicationid-must-match-the-host-app))
is a **third** bundle in the same listing and needs a disjoint version-code range.

**Decision:** `versionCode = Versions.APP_CODE + 2_000_000`, and update
`.github/scripts/tag-release.sh` in the same change so its tag message enumerates all three
(`libre ${CODE}, tv $((CODE + 1000000)), wear $((CODE + 2000000))`). Its collision guard reads
`APP_CODE` from `Versions.kt`, so the guard keeps working — but the tag message will lie until it
is updated.

---

## 3. Architecture & Module Topology

### 3.1 Module Placement

```text
SpatialFin/
├── companion/
│   ├── protocol/                            # NEW pure-Kotlin shared DTOs (:companion:protocol)
│   │   └── src/main/kotlin/dev/spatialfin/companion/protocol/
│   └── wear/                                # NEW Wear OS application module (:companion:wear)
│       ├── build.gradle.kts
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/dev/spatialfin/companion/wear/
│           │   ├── presentation/            # Wear Compose UI screens, sheets, dialogs
│           │   ├── rotary/                  # Rotary crown input & haptic accumulators
│           │   ├── transport/               # Dual-mode connectivity (DataLayer & direct LAN)
│           │   ├── fcast/                   # Wear FCast sender & Split-A/V receiver service
│           │   ├── tiles/                   # ProtoLayout Wear OS tiles
│           │   ├── complications/           # Watch face complication data sources
│           │   ├── voice/                   # Wrist voice capture & dispatcher
│           │   └── di/                      # Hilt dependency injection modules
│           └── res/                         # Wear drawables, strings, layouts
├── core/                                    # ⛔ NOT a dependency — see §2.3
├── fcast/                                   # FCast codec, protocol, sender, receiver, mDNS.
│                                            #   Android library (not pure Kotlin): carries
│                                            #   Compose UI, jmdns, OkHttp.
└── data/                                    # ⛔ NOT a dependency — Jellyfin SDK & Room
```

Both new modules must be added to `settings.gradle.kts` (`include(":companion:protocol")`,
`include(":companion:wear")`).

A third module, `:companion:host`, is proposed in [§3.2](#32-host-side-work--existing-seams).

### 3.2 Host-Side Work & Existing Seams

**Roughly half of this feature is host-side, and v2.0.0 of this document contained zero host
tasks.** Every subsystem needs a counterpart in the app: a `WearableListenerService`, a state
publisher, a cover-art downscaler, a vitals collector, a credential pusher, and voice-channel
wiring into `SpatialVoiceService`. Per the project's standing rule that player features must be
plumbed through **every** form factor in the same change, all of it has to work on XR, Beam, and
TV — not just whichever one gets tested first.

The good news is that most of it already exists.

#### 3.2.1 Reuse `XrPlayerAction` — do not invent an RPC vocabulary

`player/session/src/main/java/dev/jdtech/jellyfin/player/session/voice/XrPlayerAction.kt` is a
sealed interface with ~45 typed actions, and `PlayerSessionController.dispatch(action): String` is
a single suspend entry point that executes any of them and returns human-readable feedback (it
exists for voice TTS; on the watch it is the confirmation toast for free).

It already covers essentially everything Subsystems 2 and 3 ask for:

| Wear feature | Existing action |
|---|---|
| Transport controls | `Play`, `Pause`, `TogglePlayPause` |
| Rotary scrubbing | `SeekTo(positionSeconds)`, `SeekForward`, `SeekBackward` |
| Audio / subtitle switcher | `SelectAudioTrack`, `SelectSubtitleTrack`, `DisableSubtitles` |
| Volume | `AdjustVolume(percentage, delta)` |
| Skip intro / next episode | `SkipIntro`, `SkipOutro`, `NextEpisode`, `PreviousEpisode` |
| Spatial recenter | `ResetScreenPlacement`, `AdjustScale`, `AdjustDistance` |
| Fling to a receiver | `CastToFCastReceiver` |
| SyncPlay | `OpenSyncPlay`, `JoinSyncPlayGroup`, `LeaveSyncPlayGroup`, … |

And `PlayerStateSnapshot` (same package) is already `/state/now_playing`, the fast-switcher sheet,
and the chapter carousel in one type: `isPlaying`, `positionSeconds`, `durationSeconds`,
`currentItemTitle`, series/season/episode, `currentSegmentType`, `chapterNames`,
`audioTrackNames` / `subtitleTrackNames` **and** the current selections.

**Decision:** the Data Layer payload is a serialized `XrPlayerAction`; the host listener hands it
straight to `PlayerSessionController.dispatch()`. The string paths sketched in
[§4.1](#subsystem-1-dual-transport-connectivity-engine) (`/playback/play_pause`, `/playback/seek`)
become one path, `/command/action`, carrying the typed payload. This deletes most of the host-side
work and gets tri-form-factor parity by construction, because `PlayerSessionController` is already
wired in all three players.

Two caveats:

- `:player:session` depends on `:data`, `:player:local`, `:player:core`, and `:settings`, so the
  watch **cannot** depend on it. The types must be mirrored into `:companion:protocol` or moved
  down into it — an open question, see [§8](#8-open-questions).
- `PlayerStateSnapshot` uses Compose's `@Immutable` and `kotlinx.collections.immutable`, so it is
  not serializable as-is; a wire mirror is needed regardless of which way that question is settled.

#### 3.2.2 Prerequisite: there is no way to reach the live player session

`PlayerSessionController` is constructed **independently inside each of the three players** —
`player/xr/.../SpatialPlayerScreen.kt`, `player/beam/.../BeamPlayerActivity.kt`, and
`player/tv/.../TvPlayerVoice.kt` — with no registry. A `WearableListenerService` runs outside any
activity and has no handle on the live one.

**A process-wide "current player session" holder has to be built before any Wear command can be
executed.** The repo already has the idiom to copy: `FCastInboundSession.bindBroadcaster(...)`, as
used by `FCastReceiverService` in `:fcast`, is exactly this pattern — the activity binds itself
into a process-wide object, the service reaches it, and the binding is cleared on teardown.

This is a genuine prerequisite, not a nice-to-have, and it benefits the voice pipeline too (which
currently reaches the controller only because it lives inside the player composable). Budget it in
Phase 1.

#### 3.2.3 Where the host code lives

It cannot live in `:shell:tv` or `:shell:beam` without being triplicated, and `:app:unified` is
deliberately a thin host.

**Decision:** a new `:companion:host` Android library — the listener service, the state publisher,
the cover-art downscaler, the credential pusher — reached from `:app:unified` through a
`:core:ui` seam, matching the existing `LocalFCastReceiverController` /
`LocalRemoteControlMiniPlayerHost` pattern. It depends on `:companion:protocol` and
`:player:session`; the watch module depends only on `:companion:protocol`.

#### 3.2.4 Host-side task list

- [ ] Process-wide player-session holder (§3.2.2) — **blocks everything else**.
- [ ] `:companion:host` module + `:core:ui` seam.
- [ ] `WearDataLayerListenerService`: `/command/action` → `dispatch()`, returning the feedback string.
- [ ] State publisher: observe `PlayerStateSnapshot`, debounce, write `/state/now_playing`.
- [ ] Cover-art downscaler → `DataClient` `Asset` (a URI is unresolvable on the watch).
- [ ] Vitals collector (`BatteryManager`, `PowerManager.getCurrentThermalStatus()`).
- [ ] Credential pusher for [§2.4](#24-credential-bootstrap-for-standalone-mode).
- [ ] Voice `ChannelClient` receiver → `SpatialVoiceService`.
- [ ] Verify each of the above on XR **and** Beam **and** TV before the phase is called done.

### 3.3 Gradle Configuration (`companion/wear/build.gradle.kts`)

Two repo conventions govern this file, and v1.0.0 broke both: **SDK levels and version codes come
from `buildSrc` `Versions`**, and **every dependency coordinate goes through the
`gradle/libs.versions.toml` catalog** — the root project applies `version-catalog-update` and
Renovate watches the catalog, so hardcoded coordinates silently rot.

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)   // NOT libs.plugins.kotlin.compose
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)                      // NOT libs.plugins.hilt.android
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.stability.analyzer)
}

android {
    namespace = "dev.spatialfin.companion.wear"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        // Same applicationId as :app:unified — the Data Layer requires it (§2.1).
        applicationId = "dev.spatialfin"
        minSdk = 33                               // Wear OS 4; above the 31 library floor (§2.2)
        targetSdk = Versions.TARGET_SDK
        versionCode = Versions.APP_CODE + 2_000_000   // disjoint from libre/tv (§2.5)
        versionName = Versions.APP_NAME

        // :core and several library modules declare a "variant" flavor dimension with only a
        // `libre` flavor. Without this, dependency resolution fails with an unresolved-variant
        // error that names the dimension but not the fix.
        missingDimensionStrategy("variant", "libre")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Must mirror :app:unified or a debug watch cannot pair with a debug phone (§2.1).
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        register("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
        }
    }

    compileOptions {
        sourceCompatibility = Versions.JAVA
        targetCompatibility = Versions.JAVA
    }

    buildFeatures { compose = true }
}

composeCompiler {
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
    metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
}

dependencies {
    implementation(projects.companion.protocol)
    implementation(projects.fcast)

    // Wear Compose (add catalog entries; do not hardcode)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    // Wearable Data Layer (Play Services). GMS is already in the graph via
    // play-services-tflite-gpu, so this introduces no new distribution constraint.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    // Wear OS Tiles & ProtoLayout
    implementation(libs.androidx.protolayout)
    implementation(libs.androidx.protolayout.material3)
    implementation(libs.androidx.tiles)

    // Complications & Ongoing Activities
    implementation(libs.androidx.watchface.complications.data.source.ktx)
    implementation(libs.androidx.wear.ongoing)

    // Media3, for Split-A/V audio rendering on the watch
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    // Already in the catalog — reuse the aliases, never the coordinates
    implementation(libs.okhttp)                    // 5.5.0, not 4.12.0
    implementation(libs.kotlinx.serialization.json) // 1.11.0, not 1.7.3
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)
}
```

**Catalog entries to add** (`gradle/libs.versions.toml`) — resolve exact versions at implementation
time against the current AndroidX releases rather than trusting the numbers in an older revision of
this document:

| Alias | Module | Notes |
|---|---|---|
| `androidx-wear-compose-*` | `androidx.wear.compose:compose-{material3,foundation,navigation}` | `material3` was still pre-1.0 alpha when this was drafted; check before pinning. |
| `androidx-protolayout*` | `androidx.wear.protolayout:protolayout{,-material3}` | |
| `androidx-tiles` | `androidx.wear.tiles:tiles` | |
| `androidx-watchface-*` | `androidx.wear.watchface:watchface-complications-data-source-ktx` | |
| `androidx-wear-ongoing` | `androidx.wear:wear-ongoing` | |
| `play-services-wearable` | `com.google.android.gms:play-services-wearable` | |
| `kotlinx-coroutines-play-services` | `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | Pin to the existing `kotlinx-coroutines` ref (1.11.0). |

**Horologist is deliberately absent.** Its rotary and layout helpers were folded into Wear Compose
Foundation 1.4 (`Modifier.rotaryScrollable`, the responsive-column APIs), so a Horologist dependency
in 2026 mostly buys deprecated wrappers. Add it later only if a specific media-UI component earns
its weight.

### 3.3 Manifest Requirements

v1.0.0 omitted the manifest entirely; several entries below are load-bearing.

```xml
<uses-feature android:name="android.hardware.type.watch" />

<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" /> <!-- jmdns -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />                <!-- §4.5 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application …>
    <!-- The watch app is usable without the phone app installed (FCast half of Transport B). -->
    <meta-data
        android:name="com.google.android.wearable.standalone"
        android:value="true" />

    <!-- targetSdk 36: a media-playing service must declare its type or it is killed on start. -->
    <service
        android:name=".fcast.WearAudioReceiverService"
        android:exported="false"
        android:foregroundServiceType="mediaPlayback" />
</application>
```

---

## 4. Core Subsystems & Technical Specifications

```
                     ┌──────────────────────────────────────────────────────────┐
                     │                 WEAR COMPANION APP UI                    │
                     │      (Wear Compose M3 / rotary-scrollable layouts)       │
                     └─────────────┬──────────────────────────────┬─────────────┘
                                   │                              │
                    ┌──────────────▼──────────────┐ ┌─────────────▼─────────────┐
                    │   Rotary Scrub Controller   │ │   Voice Mic Dispatcher    │
                    │ (haptic ticks & delta accum)│ │ (ChannelClient PCM stream)│
                    └──────────────┬──────────────┘ └─────────────┬─────────────┘
                                   │                              │
┌──────────────────────────────────▼──────────────────────────────▼──────────────────────────────────┐
│                             DUAL-TRANSPORT CONNECTIVITY ENGINE                                     │
├─────────────────────────────────────────────┬──────────────────────────────────────────────────────┤
│  A. Wearable Data Layer (tethered / BT)     │  B. Direct LAN Engine (standalone Wi-Fi)             │
│     • MessageClient (low-latency RPC)       │     • FCastSenderClient (direct socket) — no creds   │
│     • DataClient (synced cached state)      │     • Jellyfin WebSocket **relay** — needs creds §2.4│
│     • ChannelClient (voice PCM streaming)   │     • mDNS (_fcast._tcp, _jellyfin._tcp)             │
│     • CapabilityClient (node discovery)     │                                                      │
└─────────────────────────────────────────────┴──────────────────────────────────────────────────────┘
```

---

### Subsystem 1: Dual-Transport Connectivity Engine

The companion must work whether tethered to a phone over Bluetooth or untethered on home Wi-Fi.

#### Transport A: Wearable Data Layer API (tethered / phone / GMS)

Requires the shared `applicationId` from [§2.1](#21-applicationid-must-match-the-host-app).

- **`MessageClient`** — instant, low-latency remote-control commands (`/playback/play_pause`,
  `/playback/seek`, `/playback/set_volume`, `/voice/query`).
  **Size limit: 100 KB per message.** Fine for commands; unusable for audio (see `ChannelClient`).
- **`DataClient`** — replicated persistent state between host and watch. DataItems are also capped
  at ~100 KB, so **cover art must travel as an `Asset`, not as a URI**: the watch cannot resolve a
  Jellyfin image URL without credentials, and cannot resolve it at all when the server is
  unreachable. The host downscales to a watch-sized bitmap and attaches it via
  `Asset.createFromBytes`.
  - `/state/now_playing` — title, series/episode, position, duration, stream codecs, cover art Asset.
  - `/state/vitals` — XR headset battery %, thermal state, Wi-Fi link speed.
  - `/state/credentials` — the [§2.4](#24-credential-bootstrap-for-standalone-mode) bundle.
- **`ChannelClient`** — the correct API for the voice PCM stream ([§4.5](#subsystem-5-spatial-wrist-voice-commander)).
- **`CapabilityClient`** — discovers active SpatialFin instances declaring capability
  `spatialfin_host`.

#### Transport B: Direct LAN Engine (standalone Wi-Fi / TV / headset direct)

When the watch is on home Wi-Fi with the phone disconnected or out of range:

1. Perform local mDNS discovery for `_fcast._tcp` and `_jellyfin._tcp`.
2. **FCast path (no credentials needed):** open a direct TCP socket with
   [`FCastSenderClient`](../fcast/src/main/java/dev/jdtech/jellyfin/fcast/sender/FCastSenderClient.kt).
   This genuinely bypasses Bluetooth and stays on the local subnet.
3. **Jellyfin path (credentials needed):** commands are relayed **through the Jellyfin server's
   WebSocket session feed** — watch → server → target — not peer-to-peer. See
   `app/unified/src/main/java/dev/spatialfin/unified/RemoteControlViewModel.kt`, whose
   `activeRemoteSessions` filters on `supportsRemoteControl` from the server's live session feed.
   The server must be reachable, and the watch must hold the cached credentials from
   [§2.4](#24-credential-bootstrap-for-standalone-mode).

```kotlin
sealed interface TransportState {
    data class ConnectedViaDataLayer(val nodeId: String, val deviceName: String) : TransportState
    /** Direct FCast socket. Works with no Jellyfin credentials. */
    data class ConnectedViaFCastLan(val host: String, val port: Int, val deviceName: String) : TransportState
    /** Jellyfin WebSocket relay. Requires a reachable server and cached credentials. */
    data class ConnectedViaJellyfinRelay(val serverUrl: String, val sessionId: String, val deviceName: String) : TransportState
    data object Disconnected : TransportState
}
```

---

### Subsystem 2: Wear Remote & Rotary Control Surface

#### Colour tokens — read this before copying hex values

[DESIGN.md](../DESIGN.md) defines `darkSurface` as **`#111318`**, not `#000000`. (`#000000` is the
`scrim` token.) Pure black on Wear is therefore a **deliberate, Wear-only deviation** from the
design system for OLED power, not an inherited token — document it as such in the Wear theme file
so the next person does not "fix" it back to `#111318`.

Tokens that *are* inherited verbatim: `darkPrimary = #A4C9FE`, `darkPrimaryContainer = #1F4876`.

```
          ┌───────────────────────────────────┐
          │             10:42 PM              │
          │         [ Dune: Part Two ]        │
          │                                   │
          │      ( ◄◄ )   [ || ]   ( ►► )     │
          │       -10s    Pause    +10s       │
          │                                   │
          │     ═════════●═════════════       │
          │       01:24:18 / 02:46:00         │
          │                                   │
          │     [ 🔕 Vol 75% ] [ 💬 Sub: Eng ] │
          └───────────────────────────────────┘
```

#### Key capabilities

1. **Rotary crown scrubbing with haptic feedback**
   - Scrubbing is *not* list scrolling, so `Modifier.rotaryScrollable` (Wear Compose Foundation
     1.4+) is the wrong primitive. Use the low-level `Modifier.onRotaryScrollEvent { }` and
     accumulate `RotaryScrollEvent.verticalScrollPixels` into a seek delta.
   - Fire a haptic tick per 5-second scrub step. Prefer
     `View.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)` (API 34+, purpose-
     built for exactly this) over Compose's `HapticFeedbackType.TextHandleMove`, with a graceful
     fallback on API 33.
   - Debounce dispatch at 100 ms to keep fast spins from flooding the socket or the Data Layer,
     while updating the local scrubber position at frame rate so the UI stays responsive.
2. **Audio & subtitle fast-switcher sheet** — quick-access sheet over the stream list mirrored from
   the host's `SpatialFinMediaStream` data (via `:companion:protocol`); one-tap audio language
   change and forced/regular subtitle toggle.
3. **Chapter navigation carousel** — scrollable chapter list with titles and start timestamps,
   mirrored from `SpatialFinChapter`.
4. **Spatial recenter & space toggle** — recenter the Android XR panel, or toggle Home Space ⇄ Full
   Space. Both map to existing host-side actions; no new XR API surface is needed on the watch.

---

### Subsystem 3: FCast Sender & Split-A/V Receiver on Wear OS

> ⚠️ The code in v1.0.0 of this document did not compile against `:fcast`. Method names, suspend
> modifiers, and constructor signatures were all invented. The snippets below match the real API as
> of `2.7.56 (157)` — re-check them against the module before implementing, since `:fcast` is under
> active development.

#### FCast **sender**

Real API, for reference:

- `FCastSenderClient(receiver: FCastReceiver, parentScope: CoroutineScope? = null, senderInfo: InitialSenderMessage = …, connectTimeoutMs: Int = 4_000, nowMs: () -> Long = …)`
- Transport verbs are `play(PlayMessage)`, `pause()`, `resume()`, `seek(seconds: Double)`,
  `stop()`, `setVolume(Double)`, `setSpeed(Double)`, `setTrack(type, trackId)` — **not**
  `sendPlay` / `sendSeek` / `sendResume` / `sendPause`.
- `InitialSenderMessage(displayName, appName, appVersion)` — `displayName` serializes as
  `friendlyName` on the wire.
- Observe state via the exposed flows: `state`, `playbackUpdates`, `volumeUpdates`, `tracksUpdates`,
  `errors`, `pongs`.
- **The client is not reusable after `close()`** — construct a new one per target.

```kotlin
class WearFCastSender(
    private val scope: CoroutineScope,
) {
    private var activeClient: FCastSenderClient? = null

    suspend fun castMediaToDevice(
        targetReceiver: FCastReceiver,
        streamUrl: String,
        container: String,
        title: String,
        initialPositionMs: Long,
    ) {
        activeClient?.close()

        val client = FCastSenderClient(
            receiver = targetReceiver,
            parentScope = scope,
            senderInfo = InitialSenderMessage(
                displayName = "SpatialFin Watch",
                appName = "SpatialFin",
            ),
        )
        client.connect()
        activeClient = client

        client.play(
            PlayMessageBuilder.build(
                url = streamUrl,
                container = container,
                positionSeconds = initialPositionMs / 1000.0,
                title = title,
            ),
        )
    }

    suspend fun seekBy(deltaSeconds: Long, currentPosSeconds: Double) {
        activeClient?.seek((currentPosSeconds + deltaSeconds).coerceAtLeast(0.0))
    }

    suspend fun togglePlayPause(isCurrentlyPaused: Boolean) {
        if (isCurrentlyPaused) activeClient?.resume() else activeClient?.pause()
    }
}
```

`PlayMessageBuilder.build` also accepts `volume`, `speed`, `headers`, `thumbnailUrl`, and the
SpatialFin metadata extensions (`sourceAudioCodec`, `audioTranscoded`, `preferredAudioLanguage`,
`subtitleTracks`) — worth wiring for parity with the phone sender once the basics work.

#### FCast **receiver** (Split-A/V audio sink)

A watch paired with Bluetooth earbuds can act as the **private audio sink** in SpatialFin's
Split-A/V architecture: headset or TV renders video, watch renders audio.

Corrections to the v1.0.0 sketch, all verified against the module:

| v1.0.0 claim | Reality |
|---|---|
| `FCastIngressRouter` has `suspend fun handlePlay/handlePause/…` | Methods are **non-suspend** and named `onPlay/onPause/onResume/onStop/onSeek/onSetVolume/onSetSpeed/onSetTrack`. Only `onResumeAt` has a default. |
| `handlePlay` returns `Unit` | `onPlay(request: PlayMessage): IngressResult` — returns `Accepted` or `Rejected(reason)`. |
| `server.start()` callable from `onCreate` | `FCastReceiverServer.start()` is **`suspend`**. `stop()` is not. |
| `FCastReceiverAdvertiser(context, port, name)` + `startAdvertising()` | Constructor takes **`Context` only**; the API is `suspend fun register(instanceName, port, properties)` / `suspend fun unregister()`. |
| Advertise on `FCAST_DEFAULT_PORT` | Advertise `server.boundPort` — the server falls back to an ephemeral port when 46899 is taken, and advertising the wrong port makes the sink undiscoverable. |
| Plain `Service` | Must be a **foreground service** with `mediaPlayback` type, or targetSdk 36 kills it. |
| `WIFI_MODE_FULL_HIGH_PERF` | Deprecated since API 29. Use `WIFI_MODE_FULL_LOW_LATENCY` — and on a watch the battery cost is the dominant concern, so hold it only while a stream is actually playing. |

`FCastReceiverService` in `:fcast` is the working reference implementation; read it before writing
the Wear variant.

```kotlin
@AndroidEntryPoint
class WearAudioReceiverService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: FCastReceiverServer? = null
    private var advertiser: FCastReceiverAdvertiser? = null
    private var player: ExoPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildOngoingNotification())
        scope.launch { startSink() }
        return START_STICKY
    }

    private suspend fun startSink() {
        // 1. Keep the Wi-Fi radio responsive while the screen is off. LOW_LATENCY, not
        //    HIGH_PERF (deprecated API 29), and released the moment playback stops.
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SpatialFin:WearReceiverLock")
            .apply { acquire() }

        // 2. Low-latency Media3 ExoPlayer rendering to the paired earbuds.
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .build()

        // 3. Ingress router. Non-suspend methods that must return promptly — hop to the player
        //    scope for anything that blocks.
        val router = object : FCastIngressRouter {
            override fun onPlay(request: PlayMessage): FCastIngressRouter.IngressResult {
                scope.launch {
                    player?.apply {
                        setMediaItem(MediaItem.fromUri(request.url))
                        request.time?.let { seekTo((it * 1000).toLong()) }
                        prepare()
                        play()
                    }
                }
                return FCastIngressRouter.IngressResult.Accepted
            }

            override fun onPause() { scope.launch { player?.pause() } }
            override fun onResume() { scope.launch { player?.play() } }
            override fun onStop() { scope.launch { player?.stop() } }
            override fun onSeek(seconds: Double) {
                scope.launch { player?.seekTo((seconds * 1000).toLong()) }
            }
            override fun onSetVolume(volume: Double) {
                scope.launch { player?.volume = volume.toFloat() }
            }
            override fun onSetSpeed(speed: Double) {
                scope.launch { player?.setPlaybackSpeed(speed.toFloat()) }
            }
            override fun onSetTrack(type: Int, trackId: String) = Unit  // audio-only sink
        }

        // 4. Start the receiver socket server, then advertise its *actual* bound port.
        val newServer = FCastReceiverServer(
            config = FCastReceiverServer.Config(displayName = "SpatialFin Watch Audio"),
            routerFactory = { router },
            parentScope = scope,
        )
        newServer.start()   // suspend
        server = newServer

        advertiser = FCastReceiverAdvertiser(applicationContext).apply {
            register(
                instanceName = "SpatialFin Watch Audio",
                port = newServer.boundPort,   // not FCAST_DEFAULT_PORT
                properties = mapOf("appName" to "SpatialFin"),
            )
        }
    }

    override fun onDestroy() {
        scope.launch { advertiser?.unregister() }
        server?.stop()
        player?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

#### Security note

A receiver that advertises itself over mDNS is an **unauthenticated media sink on the LAN**: any
host on the subnet can push a URL to it. The phone/TV receiver accepts this trade-off behind a
user-facing toggle. The watch must do at least as well — default the sink **off**, require an
explicit opt-in per session, and surface the connected sender's `friendlyName` in the ongoing
notification so an unexpected sender is visible rather than silent.

---

### Subsystem 4: Wear OS ProtoLayout Tiles & Complications

#### 1. "Now Playing" tile (`NowPlayingTileService`)

- Media control from the watch face carousel without launching the app.
- Built on `androidx.wear.protolayout` for fast, low-jank rendering.
- Circular progress ring, title and show/artist subtitle, play/pause and ±10 s buttons.
- **Interaction model:** ProtoLayout `Clickable` supports only `LaunchAction` (start an activity)
  and `LoadAction` (re-request the tile with updated state). It cannot invoke arbitrary code. Route
  play/pause through a `LoadAction` and perform the actual command inside `onTileRequest`, or launch
  a transparent trampoline activity — pick one and be consistent across tile and complication.
- **Update latency:** tiles refresh on the platform's schedule. `setFreshnessIntervalMillis` is
  coarse (tens of minutes); for prompt updates the Data Layer listener must call
  `TileService.getUpdater(context).requestUpdate()` on state change. Even then the platform
  throttles — budget seconds, not milliseconds ([§7](#7-verification--test-matrix)).

#### 2. "Continue Watching" tile (`UpNextTileService`)

- Surfaces the next unwatched episode or paused movie from Jellyfin's `NextUp` feed, mirrored from
  the host — the watch does not query Jellyfin directly.
- Single tap starts playback on the primary XR headset or TV.

#### 3. Watch face complications (`SpatialFinComplicationProviderService`)

- **Now Playing (short text)** — title plus a playback icon.
- **Headset battery (ranged value)** — XR headset battery 0–100 %, with a low-battery colour token.
- **App launcher icon** — shortcut to the wrist remote.
- Complication updates are rate-limited by the platform on the same principle as tiles. Push
  updates from the Data Layer listener; never poll.

#### 4. Ongoing Activity (`androidx.wear:wear-ongoing`)

- While media plays on any paired SpatialFin target, an animated media chip appears on the watch
  face; tapping it returns to the remote.
- **Prerequisite:** an Ongoing Activity attaches to an active foreground-service notification. In
  remote-only mode the watch is not playing anything locally, so it still needs a lightweight
  foreground service to host the notification. Decide in Phase 4 whether the ongoing chip is worth
  that service's battery cost when the watch is a pure remote — it unambiguously *is* worth it when
  the watch is the Split-A/V sink, since that service already exists.

---

### Subsystem 5: Spatial Wrist Voice Commander

```
[ Tap wrist mic ] ──► [ AudioRecord: 16 kHz 16-bit mono PCM ]
                                     │
                                     ▼
                      [ ChannelClient stream  ·OR·  direct socket ]
                                     │
                                     ▼
                            [ SpatialVoiceService ]        (player/xr/.../voice/)
                                     │
                                     ▼
                         [ SpatialCommandCoordinator ]
                                     │
                                     ▼
                          [ Action executed on XR / TV ]
```

1. The user taps the mic icon or a hardware button shortcut.
2. The watch captures audio via `AudioRecord` (16 kHz, 16-bit, mono PCM).
3. **The buffer streams over `ChannelClient`, not `MessageClient`.** At 32 KB/s, a five-second
   utterance is ~160 KB — well past `MessageClient`'s 100 KB per-message cap. Open a channel with
   `ChannelClient.openChannel(nodeId, "/voice/stream")` and write to its `OutputStream`; in
   standalone mode, stream over the direct socket instead.
4. The host runs the query through `SpatialVoiceService` → `SpatialCommandCoordinator`, which
   already handles the XR and TV voice paths:
   - *"Skip this intro"*
   - *"Dim the virtual cinema lights"*
   - *"What's the name of this background song?"*
   - *"Switch audio to director's commentary"*
5. **No inference runs on the watch.** The LiteRT/MLKit stack lives in `:core`, which the watch does
   not depend on ([§2.3](#23-do-not-depend-on-core)).

**Privacy:** `RECORD_AUDIO` on a wrist-worn device deserves a visible indicator beyond the system
mic dot — show recording state in-app, stop on screen-off, and never open the channel until the tap
is registered.

---

### Subsystem 6: Companion Fast-Pairing & Security Broker

- **1-tap QR / TV pairing approval.** When a TV or XR headset shows a pairing QR or PIN
  (`CompanionTvPairingPayload`, `core/.../models/companion/CompanionModels.kt`; rendered by
  `TvCompanionScreen` / `BeamCompanionScreen`), a notification appears on the watch. Tapping
  **Approve** transmits the setup token (`CompanionConfig`) to pair the device.
- **Biometric app-lock approval.** When the encrypted profile app lock is active
  (`pref_app_lock_mode`, `settings/.../domain/AppPreferences.kt`), the watch offers a biometric or
  PIN confirmation so the user need not type on a virtual spatial keyboard.
- **Threat model to write down before building this.** A watch that can approve pairings and unlock
  a profile is a second factor *and* a second attack surface. At minimum: approvals expire (~60 s),
  the approval prompt names the requesting device, and an approval is never auto-granted from a
  locked or off-wrist watch.

---

## 5. Battery, Power & Performance

Watches run under hard thermal and battery limits. These rules are requirements, not suggestions.

| Constraint | Strategy |
|---|---|
| **OLED power** | Screen backgrounds are pure `#000000` so inactive OLED pixels are off. This is a deliberate Wear-only override of `darkSurface` (`#111318`) — see [§4.2](#subsystem-2-wear-remote--rotary-control-surface). |
| **Ambient / always-on** | Implement `AmbientLifecycleObserver`. In ambient mode: no smooth animations, scrubber updates drop to 1 Hz, no anti-aliased gradients. |
| **Wi-Fi duty cycling** | Data Layer over Bluetooth is the primary transport; standalone Wi-Fi activates only when Bluetooth is unavailable, and the Wi-Fi lock is `WIFI_MODE_FULL_LOW_LATENCY`, held only while a stream is playing. |
| **Rotary throttling** | Accumulate crown deltas in memory; dispatch seek/volume RPCs on a 100 ms debounce. Local UI still updates per frame. |
| **Socket keep-alives** | TCP keep-alive probes on the FCast port (`FCAST_DEFAULT_PORT` = 46899, or the negotiated `boundPort`) at a 15 s interval, to detect stale sockets without excessive radio wakeups. |
| **Tile / complication updates** | Push-driven (`requestUpdate()` from the Data Layer listener). Never poll, and never set a short freshness interval — the platform throttles it anyway and the wakeups are charged to you. |
| **Foreground services** | Exactly one at a time, and only while it has work. The audio sink and the ongoing-activity host must not both run. |

---

## 6. Phased Implementation Roadmap

```
  Phase 0: De-risk         Phase 1: Foundation      Phase 2: Transport       Phase 3: FCast           Phase 4: Polish
  ┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
  │• applicationId   │ ──► │• Module creation │ ──► │• DataLayer RPC   │ ──► │• FCast sender    │ ──► │• Tiles & compl.  │
  │  round trip      │     │• :companion:     │     │• Direct LAN mDNS │     │• Split-A/V audio │     │• Voice relay     │
  │• minSdk / graph  │     │  protocol DTOs   │     │• Credential      │     │  sink service    │     │• Fast pairing    │
  │• Play delivery   │     │• Rotary scrubber │     │  bootstrap       │     │• Drift validation│     │• Ongoing chip    │
  └──────────────────┘     └──────────────────┘     └──────────────────┘     └──────────────────┘     └──────────────────┘
```

### Phase 0: De-risk the blocking decisions

Cheap now, expensive after Phase 3. Do not skip.

- [ ] Stub Wear APK + stub phone APK sharing `applicationId = "dev.spatialfin"`; confirm a
      `MessageClient` round trip on real hardware ([§2.1](#21-applicationid-must-match-the-host-app)).
- [ ] Confirm the same round trip for `.debug`-suffixed builds.
- [ ] Confirm a `minSdk 33` app module resolves `:fcast` with `missingDimensionStrategy("variant", "libre")`
      ([§2.2](#22-minsdk-floor-is-31-not-30), [§3.2](#32-gradle-configuration-companionwearbuildgradlekts)).
- [ ] Measure the `:fcast`-only APK size on the watch; decide whether `:fcast:ui` needs splitting
      ([§2.3](#23-do-not-depend-on-core)).
- [ ] Confirm Play accepts a third bundle under the existing listing at `APP_CODE + 2_000_000`, and
      update `.github/scripts/tag-release.sh` ([§2.5](#25-play-delivery-and-version-codes)).

### Phase 1: Module scaffolding & Wear Compose UI

- [ ] Add `:companion:protocol` and `:companion:wear` to `settings.gradle.kts`.
- [ ] Add the Wear catalog entries from [§3.2](#32-gradle-configuration-companionwearbuildgradlekts).
- [ ] Define the shared DTOs in `:companion:protocol` (pure Kotlin, `kotlinx.serialization`).
- [ ] Implement `WearRemoteControlScreen` with the Wear theme ([§4.2](#subsystem-2-wear-remote--rotary-control-surface)).
- [ ] Implement the rotary scrubber on `Modifier.onRotaryScrollEvent` with `SEGMENT_FREQUENT_TICK`
      haptics.

### Phase 2: Dual-transport connectivity

- [ ] `WearDataLayerListenerService` — receive `/state/*`, send `/command/*`.
- [ ] Cover-art `Asset` pipeline (host-side downscale + watch-side decode).
- [ ] `WearDirectLanClient` — mDNS discovery + direct FCast socket.
- [ ] Credential bootstrap and encrypted cache ([§2.4](#24-credential-bootstrap-for-standalone-mode)).
- [ ] Connection state machine with automatic failover between Data Layer, FCast LAN, and the
      Jellyfin relay.

### Phase 3: FCast sender & Split-A/V receiver

- [ ] Wire `FCastSenderClient` to fling media from watch views to headsets/TVs.
- [ ] Build `WearAudioReceiverService` as a `mediaPlayback` foreground service with
      `FCastReceiverServer`, ExoPlayer, and a `LOW_LATENCY` Wi-Fi lock.
- [ ] Default the sink off; add the per-session opt-in and sender-name disclosure
      ([§4.3 security note](#security-note)).
- [ ] Validate drift against `SplitAvController` / `SplitAvPolicy`, including chirp calibration
      ([§7](#7-verification--test-matrix)).

### Phase 4: Tiles, complications & ongoing activities

- [ ] `NowPlayingTileService` — ProtoLayout progress ring and controls, `LoadAction` interaction.
- [ ] `UpNextTileService`.
- [ ] `HeadsetBatteryComplicationService` and `NowPlayingComplicationService`.
- [ ] Ongoing Activity chip during playback (after deciding the foreground-service question).

### Phase 5: Voice relay, pairing broker & end-to-end testing

- [ ] Wrist mic capture and `ChannelClient` streaming to `SpatialVoiceService`.
- [ ] 1-tap TV/XR pairing approval for `CompanionTvPairingPayload`, with expiry and device naming.
- [ ] Validate on hardware: Galaxy Watch 6/7/Ultra (Wear OS 4/5) and Pixel Watch 2/3.
- [x] **Update `GEMINI.md`** — its self-update mandate requires the Module Map, and the Play Track
      Bundles section, to be corrected in the same commit that adds the modules. Update
      `README.md` and `docs/index.html` too, per the same mandate's feature clause.

---

## 7. Verification & Test Matrix

Criteria are grounded in the repo's own constants where they exist. Targets marked **(budget)** are
goals to be measured, not claims — v1.0.0 stated several as if already measured.

| Test | Target devices | Criteria |
|---|---|---|
| **Data Layer round trip** | Any watch + phone, debug and release | A `MessageClient` message sent from the watch is received by `dev.spatialfin` on the phone in both build types. **Blocking gate for Phase 1.** |
| **Rotary scrub latency** | Galaxy Watch 7 (crown), Pixel Watch 3 | Crown rotation seeks on Galaxy XR within ≤120 ms **(budget)**; haptic tick per 5 s increment; no dropped frames on the local scrubber during a fast spin. |
| **Split-A/V audio sink** | Watch + Galaxy Buds2 Pro + XR headset | After chirp calibration (`SplitAvCalibrationDialog` / `CalibrationOrchestrator`), residual drift stays inside `SplitAvPolicy`'s perceptual hold band — `HOLD_VIDEO_LEADS_MS = 30` / `HOLD_AUDIO_LEADS_MS = 15` — and never trips `HARD_SEEK_THRESHOLD_MS = 500` during a 2-hour session. **Note:** Bluetooth A2DP alone adds 100–250 ms of codec latency, so this is unachievable *without* calibration; an uncalibrated "<25 ms" target, as claimed in v1.0.0, is not physically reachable. |
| **Standalone FCast fallback** | Phone disconnected, standalone watch | Watch discovers TV and headset via mDNS and executes play/pause/seek over a direct TCP socket, with **no** Jellyfin credentials present. |
| **Standalone Jellyfin relay** | Phone disconnected, credentials pre-cached | Relay commands reach the target via the server WebSocket. With the cache cleared, the watch shows the "connect your phone once" state, not a network error. |
| **Ambient power** | Galaxy Watch 6, 2-hour session | Always-on ambient monitoring draws ≤4.5 percentage points/hour **above** the idle AOD baseline measured on the same watch and watch face **(budget)**. Report the baseline alongside the number. |
| **Tile & complication updates** | Wear OS watch faces | State reaches the tile within a few seconds of the `DataClient` item arriving, subject to platform tile-update throttling. A sub-second guarantee is not available on Wear OS. |
| **Foreground service survival** | targetSdk 36 watch, screen off | The audio sink survives 30 minutes of screen-off playback without being killed, and releases the Wi-Fi lock within seconds of stopping. |

---

## 8. Open Questions

1. **Is a Wear companion the right investment versus improving the Beam phone remote?** The phone
   already has `RemoteControlUi` and a full FCast sender. The watch's unique value is the crown and
   the always-on wrist glance — if those two do not carry the feature, the rest is duplication.
2. **Music Assistant / SendSpin zone control on the wrist** is listed in the vision diagram but has
   no subsystem section. Scope it or drop it from the diagram.
3. **Which host owns the watch when several are on the LAN?** `CapabilityClient` may report an XR
   headset and a TV simultaneously. Last-used, nearest-RSSI, or explicit pick?
4. **Baseline profile for the Wear module** — `:baselineprofile` currently targets `:app:unified`
   only. Worth extending once the Wear UI stabilises.
5. **`:fcast` Compose split** — deferred pending the Phase 0 APK measurement.

---

## 9. Changelog

### 2.0.0 — 29 August 2026

Fact-checked v1.0.0 against the tree at `2.7.56 (157)`. Corrections:

- **Added [§2 Blocking Design Decisions](#2-blocking-design-decisions)** — the `applicationId` /
  Data Layer constraint, the `minSdk` floor, the `:core` dependency weight, the standalone
  credential bootstrap, and Play delivery. Two of these invalidated the v1.0.0 architecture.
- **Gradle:** `minSdk 30` → `33` (library floor is 31); hardcoded `compileSdk`/`targetSdk`/versions →
  `Versions.*`; `versionCode 1000157` (which collides with the `tv` flavor's `APP_CODE + 1_000_000`)
  → `APP_CODE + 2_000_000`; wrong plugin aliases (`kotlin.compose`, `hilt.android`) → the real ones
  (`kotlin.compose.compiler`, `hilt`); hardcoded coordinates → catalog aliases; added the required
  `missingDimensionStrategy("variant", "libre")` and the `staging` build type.
- **Stale versions corrected:** media3 1.5.0 → 1.11.0, OkHttp 4.12.0 → 5.5.0, serialization
  1.7.3 → 1.11.0, coroutines 1.9.0 → 1.11.0.
- **FCast code rewritten** — every method name, suspend modifier, and constructor signature in the
  v1.0.0 sender and receiver snippets was wrong. See the correction table in
  [§4.3](#subsystem-3-fcast-sender--split-av-receiver-on-wear-os).
- **DESIGN.md tokens:** `darkSurface` is `#111318`, not `#000000`; pure black is now documented as a
  deliberate Wear-only override.
- **Transport B corrected** — the Jellyfin path is a *server relay*, not peer-to-peer, and needs
  credentials the watch cannot obtain while standalone.
- **Voice:** `MessageClient`'s 100 KB cap makes it unusable for PCM; switched to `ChannelClient`.
- **Cover art:** switched from a URI (unresolvable on the watch) to a `DataClient` `Asset`.
- **Added:** manifest requirements, foreground-service typing, Horologist rationale, non-goals,
  security notes for the LAN sink and the pairing broker, Phase 0, and open questions.
- **Test matrix:** replaced the unreachable "<25 ms drift" criterion with the repo's real
  `SplitAvPolicy` band; relabelled unmeasured targets as budgets; added Data Layer, standalone, and
  foreground-service tests.

---

## 10. Implementation Deviations

Two things this plan specified could not be built as written. Both were changed deliberately;
neither is a shortcut.

### 10.1 Voice is a transcript, not a PCM stream (§4.5)

**The plan:** capture 16 kHz PCM on the watch, stream it over `ChannelClient` to
`SpatialVoiceService` on the host.

**Why it cannot work:** `SpatialVoiceService`
(`player/xr/.../voice/SpatialVoiceService.kt`) is a wrapper around Android's
`SpeechRecognizer`. That class exposes no API for injecting an external audio buffer — it owns
the microphone itself. A PCM stream arriving at the host had no destination; the plan's
`ChannelClient` pipe ended in a `SharedFlow` nothing could consume.

**What was built:** recognition runs on the watch (Wear OS ships an on-device recognizer) and
only the resulting text crosses the link, on `/voice/query`. Consequences:

- §4.5's non-goal — *"No on-watch LLM inference"* — still holds. ASR is not the LLM; every
  parsing and generation step (`SpatialCommandCoordinator`, Gemini/Gemma) still runs on the host.
- The `MessageClient` 100 KB cap that forced `ChannelClient` in the first place is moot: a
  transcript is a few hundred bytes, not ~160 KB.
- The host reaches its own coordinator through
  `ActivePlayerSessionHolder.ActiveSession.onVoiceCommand`, supplied by each of the three
  players. `:companion:host` therefore needs no `:player:xr` dependency, and XR, Beam and TV all
  answer wrist commands through their own pipelines.

### 10.2 The Ongoing Activity chip rides the audio sink (§4.4)

**The plan:** left this open — *"Decide in Phase 4 whether the ongoing chip is worth that
service's battery cost when the watch is a pure remote."*

**The decision: no separate service.** An Ongoing Activity must attach to a foreground-service
notification, and the only type that fits is `mediaPlayback`, which on modern Android is for
apps actually rendering media. A watch acting as a pure remote renders nothing, so it can
neither honestly declare that type nor justify the wakeups. The chip is attached to
`WearAudioReceiverService` instead — the case the plan itself called unambiguous — which also
satisfies §5's *"exactly one foreground service at a time"* rule for free.

### 10.3 Notes on §4.6 pairing

The watch half (offer → prompt naming the device → approve/reject, with expiry) is built, and
`CompanionPairingOffers` in `:core` carries offers and decisions between `:shell:tv` and
`:companion:host` without an edge between them. A **rejection closes the TV's pairing window**;
silence is never treated as consent, and with no watch paired TV pairing behaves exactly as
before.

What is *not* built is "tapping Approve transmits the setup token": the envelope that completes
pairing comes from the external SpatialFin-Companion service, not from anything in this repo, so
there is no in-repo sender to trigger. The watch is a veto, not yet a credential courier.
