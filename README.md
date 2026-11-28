# SpatialFin

![SpatialFin](docs/images/SpatialFin-banner.png)

SpatialFin is an open-source media client built XR-first for **Android XR** (Samsung Galaxy XR and compatible headsets), shipping natively across **Android Phone & Beam Pro**, **Android TV**, **SpatialFin for Wear OS**, and the browser-based **SpatialFin WebXR** Progressive Web App (`/web/`).

- **Website & User Guides:** [spatialfin.martinez.fyi](https://spatialfin.martinez.fyi/)
- **WebXR App:** [spatialfin.martinez.fyi/web](https://spatialfin.martinez.fyi/web/)
- **Google Play (`dev.spatialfin`):** [Install for XR, Phone, TV & Wear OS](https://play.google.com/store/apps/details?id=dev.spatialfin)
- **Community:** [SpatialFin Discord](https://discord.gg/9MHD52r9)

> **Forked from [Findroid](https://github.com/jarnedemeulemeester/findroid)** — SpatialFin began as a fork of Findroid, an open-source Jellyfin client for Android. We thank the Findroid contributors for their work.

---

## Core Capabilities

- **No Jellyfin Server Required:** Stream from Jellyfin 10.10+ servers, browse local device storage and Android **Open with** intents, mount **SMB/NFS** shares with optional TMDB metadata enrichment, or run sandboxed [QuickJS / Grayjay plugins](https://spatialfin.martinez.fyi/plugins/).
- **Android XR Cinema & Spatial Presence:** Home Space multitasking (persisting `xrAppPanelScale`), Full Space curved cinema (`Flat`, `Subtle 9.0m`, `Cinema 6.5m`, `Deep 5.2m`) spawning centered with persistent zoom scale and depth, graduated `0%–100%` passthrough dimming, optional flat-cinema ARCore wall anchoring, stereoscopic SBS/TAB/MV-HEVC + VR180/360° projection, and positional `SpatialAudioTrack` audio.
- **On-Device Voice AI & Cast Card:** Open-palm XR activation and closed-fist interrupt powered by local `gemma-4-E2B-it.litertlm` / AICore Gemini Nano (optional `gemini-3.1-flash-lite-preview` cloud fallback), a **20-minute** rolling subtitle buffer with disk fallback, and interactive `"Who is this?"` spatial cast cards.
- **Native `libass` Subtitles, Sync & Intro Skip:** JNI `libass` ASS/SSA rendering, live subtitle (`±10 s`) and audio (`±5 s`) sync, 4-tier intro/outro skipping (Jellyfin Media Segments → Intro Skipper → embedded chapter titles → AniSkip), Smart Language track rules, and Stats for Nerds.
- **Casting, Theater Audio & Music Assistant:** Multi-protocol casting (FCast v4, Google Cast, and unencrypted AirPlay v1 video-only), Split-A/V private earbud audio over FCast/Wear OS/WebXR, standalone multi-room **Theater Audio (`spatialfin-avr`)** across **Galaxy XR, Beam/Phone, Android TV, and WebXR** with persisted room state, upgraded embedded Web UI (`/` — inline SVG QR, interactive 2D floorplan, live controls, paired-client & Music Assistant admin), opt-in dual-mode TLS (QR SPKI pinning & 8-char TV SAS), in-player multi-room quick switching, TrueHD Atmos OAMD 3D VBAP + live object counts + 4 upmix modes, acoustic lip-sync auto-calibration, LAN SendSpin speakers, direct PC HDMI/ALSA (`2.0`–`7.1`) and unified **ESP32-C5/S3** binary/FLAC Noise TCP outputs, Beam USB-C OTG `UMIK-1` room calibration, and Galaxy XR 6DoF `LCR Screen Lock` + dark-room `Center Snap`, plus SyncPlay rooms, LAN remote control, Music Assistant queues, and SendSpin multi-room audio.
- **Encrypted Offline Downloads (`Download/SpatialFin`):** Resumable `WorkManager` downloads stored under `Download/SpatialFin`, integrity verification, optional Biometric or PBKDF2 PIN App Lock with AES-256-CTR encrypted video storage, and on-device Media3 `Transformer` H.264/AAC airplane transcoding.
- **SpatialFin Companion Hub (Optional Docker Server):** Optional self-hosted [SpatialFin-Companion](https://github.com/ignacio82/SpatialFin-Companion) Docker service on port `1982` (`docker compose up -d --build`) for QR Quick Connect brokering (`qc_broker_v1` / `X-Setup-Token`), bundled SearXNG web search, and remote diagnostic logs — separate from [SpatialFin for Wear OS](https://spatialfin.martinez.fyi/guides/wear-os/).

---

## Documentation Index (`spatialfin.martinez.fyi`)

User-facing guides live in `site/` (compiled to `_site/` by `site/build.mjs` and deployed via `.github/workflows/pages.yml`):

| Surface / Guide | Location | URL |
|---|---|---|
| Overview, Capability Matrix & Downloads | `site/pages/index.html` | https://spatialfin.martinez.fyi/ |
| User Guides Hub | `site/pages/guides/index.html` | https://spatialfin.martinez.fyi/guides/ |
| Getting Started (Quick Connect, LAN `:41230`, Local/SMB) | `site/pages/guides/getting-started.html` | https://spatialfin.martinez.fyi/guides/getting-started/ |
| Android XR Cinema & Voice AI | `site/pages/guides/xr-cinema-voice.html` | https://spatialfin.martinez.fyi/guides/xr-cinema-voice/ |
| Smart Playback, `Download/SpatialFin`, App Lock & Security | `site/pages/guides/playback-offline.html` | https://spatialfin.martinez.fyi/guides/playback-offline/ |
| Casting, Split-A/V & Music Assistant | `site/pages/guides/casting-music.html` | https://spatialfin.martinez.fyi/guides/casting-music/ |
| SpatialFin for Wear OS | `site/pages/guides/wear-os.html` | https://spatialfin.martinez.fyi/guides/wear-os/ |
| SpatialFin WebXR (`/web/`, CORS & Tailscale Serve) | `site/pages/guides/webxr.html` | https://spatialfin.martinez.fyi/guides/webxr/ |
| SpatialFin Companion Hub (Docker) | `site/pages/companion/index.html` | https://spatialfin.martinez.fyi/companion/ |
| QuickJS & Grayjay Source Plugins | `site/pages/plugins/index.html` | https://spatialfin.martinez.fyi/plugins/ |

---

## Architecture

SpatialFin is a multi-module Android project. `:app:unified` ships a unified APK branching at runtime on `DeviceClass` (`XR` / `PHONE` / `TV`), paired with `:companion:wear` for Wear OS and `spatialfin-webxr/` for WebXR. See [GEMINI.md](GEMINI.md) for the exhaustive module map.

| Module / Directory | Description |
|---|---|
| `:app:unified` | Application host (`dev.spatialfin`): `UnifiedMainActivity`, `DeviceClass` switch, XR shell, DI glue (`libre` + `tv` flavors). |
| `:shell:tv`, `:shell:beam` | TV (Leanback) and Phone/Beam Compose navigation shells. |
| `:player:xr`, `:player:beam`, `:player:tv` | Form-factor players (`:player:xr` holds SceneCore cinema, `libass_jni`, and XR voice). |
| `:player:core`, `:player:local`, `:player:session` | Media3/ExoPlayer core, Smart Language/SyncPlay coordinators, `PcmAudioDelayProcessor`, `OnDeviceTranscoder`, `ChapterSegmentDetector`. |
| `:modes:film`, `:modes:music`, `:modes:audio` | Feature screens and ViewModels for films/shows/photos, Music Assistant, and Jellyfin audio. |
| `:data`, `:core`, `:core:ui` | Jellyfin/TMDB/Seerr/AniSkip repositories, Room DB, SMB/NFS (`jcifs-ng`/`nfs4j`), WorkManager downloads, encrypted storage, shared theme/components. |
| `:fcast`, `:fcast:session-ui` | Protocol-agnostic FCast v4, Google Cast, and AirPlay v1 video casting + Split-A/V UI. |
| `:plugins`, `:sendspin` | Sandboxed `quickjs-kt` plugin runtime and native SendSpin synchronized audio endpoint. |
| `:companion:protocol`, `:companion:host`, `:companion:wear` | Wear OS protocol, Data Layer host bridge, and standalone **SpatialFin for Wear OS** watch APK/AAB (`minSdk 33`). |
| `:settings`, `:setup` | DataStore preferences, App Lock/AES-CTR key material, onboarding, and LAN `/setup` server (port `41230`). |
| `spatialfin-webxr/`, `docs/web/` | TypeScript Three.js WebXR PWA source and prebuilt `/web/` bundle copied read-only during website builds. |

---

## Building & Releasing

```bash
# Debug APK (XR / Phone / Beam Pro)
./gradlew :app:unified:assembleLibreDebug

# Build & verify the static website locally (output -> gitignored _site/)
node site/build.mjs && node site/verify.mjs
```

### Play Store Tracks & Release Script

One Google Play listing (`dev.spatialfin`) distributes **three** Play App Bundles built from this repository with matching release signing certificates (via shared [`buildSrc/src/main/kotlin/SpatialfinSigning.kt`](buildSrc/src/main/kotlin/SpatialfinSigning.kt)):

| Bundle | Gradle Task | `minSdk` | `versionCode` |
|---|---|---|---|
| `libre` (XR / Phone / Beam Pro) | `:app:unified:bundleLibreRelease` | 31 | `APP_CODE` |
| `tv` (Android TV / Google TV) | `:app:unified:bundleTvRelease` | 31 | `APP_CODE + 1_000_000` |
| `wear` (SpatialFin for Wear OS) | `:companion:wear:bundleRelease` | 33 | `APP_CODE + 2_000_000` |

Run all release preflight checks, website verification, serialized R8 bundle builds, and `keytool` certificate-parity verification with:

```bash
bash .github/scripts/release-bundles.sh
```

Provide release signing credentials (`SPATIALFIN_KEYSTORE`, `SPATIALFIN_KEYSTORE_PASSWORD`, `SPATIALFIN_KEY_ALIAS`, `SPATIALFIN_KEY_PASSWORD`) via Gradle properties, root `local.properties`, or environment variables. [`Versions.kt`](buildSrc/src/main/kotlin/Versions.kt) is the single source of truth for `APP_NAME` and `APP_CODE`.

### Native Subtitle Library (`libass_jni.so`)

Prebuilt `libass_jni.so` binaries are committed under `player/xr/src/main/jniLibs/` so fresh clones build without an NDK. To rebuild 16 KB page-size binaries from source:

```bash
./player/xr/build_native.sh /path/to/android-ndk
```

---

## AI Usage & Development Context

See **[`GEMINI.md`](GEMINI.md)** (and [`CLAUDE.md`](CLAUDE.md) for Claude Code entry) for full architectural invariants, XR/JNI/R8 rules, and test conventions. Usually ignore `.git/`, `.kotlin/`, `**/build/`, `build_native_work/`, `release/`, `fastlane/metadata/android/en-US/images/`, `_site/`, and `androidx/` unless explicitly targeting them.

---

## License

SpatialFin is open-source software licensed under the terms in [LICENSE](LICENSE). Original Findroid license applies to inherited code.
