# SpatialFin Theater Audio Roadmap v2: Multi-Room Persistence, Web UI, Lip-Sync, Expanded Clients, TLS, and ESP32 Sink

*   **Author:** Ignacio Martinez <ignacio@martinez.fyi>
*   **Status:** Proposed
*   **Date:** 2026-11-28

--------------------------------------------------------------------------------

## Overview

`spatialfin-avr` and SpatialFin currently support multi-room audio routing, mixed output types (LAN Sendspin WebSocket speakers, direct host PC HDMI/ALSA playback, and Noise `KKpsk2` TCP sinks), an embedded Web UI, TrueHD Atmos OAMD 3D VBAP decoding, live upmix modes, and Music Assistant handoff across Galaxy XR and Android Phone/Beam clients.

This document defines a phased plan to close nine roadmap gaps across `spatialfin-avr` and `SpatialFin`:

1.  **Per-Room Runtime Persistence:** Save each room's volume, mute, EQ bypass, upmix mode, and crossover settings across server restarts.
2.  **Web Administration UI Upgrade:** Add optional inline SVG QR codes for pairing, live playback/DSP controls, a 2D top-down speaker floorplan editor, paired client revocation, and browser-based Music Assistant configuration.
3.  **Live Atmos/OAMD & HDMI Capability Telemetry:** Surface real-time TrueHD object rendering status (`truehd_vbap` vs `bed` failover and active OAMD object count) and startup-cached ALSA/HDMI channel and format capabilities in both the Android HUD and Web UI.
4.  **In-Player Multi-Room Quick Switcher:** Move active Split-A/V playback between configured rooms (and fold back to local device audio) directly from the XR and Beam player HUD without closing the player Activity, and cycle all four upmix modes.
5.  **Acoustic Lip-Sync Auto-Calibration UI:** Wire the existing WebSocket `clock.ping` and `calibration.lip_sync_chirp` RPCs into a guided microphone capture workflow on Pixel/Beam and Galaxy XR to measure pure room audio hardware delay (`fixed_hw_delay_us`) automatically.
6.  **Optional Built-In Dual-Mode TLS Termination:** Allow `spatialfin-avr` to serve HTTPS/WSS directly on the LAN using either CA-backed PEM certificates or auto-generated ECDSA P-256 certificates with SPKI pinning and TV/manual SAS verification.
7.  **Android TV & WebXR Theater Audio Clients:** Extend Theater Audio discovery, pairing, room selection, and synchronized Split-A/V playback controls to Android TV (`:shell:tv`, `:player:tv`) and WebXR (`spatialfin-webxr/`).
8.  **Unified ESP32-C5/S3 Sink Firmware & Hardware Soak Gates:** Provide a deployable ESP-IDF C firmware project supporting 2-channel I2S stereo and 8-channel I2S TDM topologies with binary/FLAC Noise `:8937` framing and continuous fractional clock-skew actuation, plus real soak assertions in the Phase 0 hardware test suite.
9.  **CI Coverage Expansion:** Extend the existing `spatialfin-avr/.github/workflows/ci.yml` with `--features alsa-native` multi-room tests, embedded Web UI script validation, and a host-side container HTTP/CSP smoke check.

### Context, Terminology & Workspace Layout

*   **Repositories:** Work spans two side-by-side repositories referenced in verification commands as `$SPATIALFIN_DIR` (`SpatialFin`: Android multi-module app, `spatialfin-webxr/` TypeScript WebXR client, and `site/` static documentation site built via `node site/build.mjs`) and `$SPATIALFIN_AVR_DIR` (`spatialfin-avr`: Rust multi-room AVR daemon, Linux sink binary, Docker packaging, and `firmware/esp32-sink/`).
*   **Spec 0 (`v=1`):** The canonical HTTP/WebSocket control protocol defined in [theater-audio-protocol-v1.md](theater-audio-protocol-v1.md) and its JSON schemas/fixtures under `schemas/theater-audio/v1/` (mirrored in both repositories).
*   **Split-A/V & `1.0000×` Cadence Lock:** Playback mode where the client plays video locally at exact `1.0000×` playback rate while `spatialfin-avr` decodes and renders synchronized audio over the LAN, tracking video presentation via `2 Hz` `playback.video_timing` beacons.
*   **Form Factors & Modules:** `Beam` is SpatialFin's Android phone/tablet UI mode (`:shell:beam`, `:player:beam`); `XR` is Android XR (`:player:xr`, separate `:xrplayer` process bridged via `SplitAvTheaterBridge`); `TV` is Android TV (`:shell:tv`, `:player:tv`). Theater Audio discovery and session management live inside `:fcast` (`SendspinAvr` adapter) and `:fcast:session-ui` (`CastSessionManager`, `SplitAvController`), shared with FCast casting.
*   **Sendspin Naming Distinction:** Three distinct layers share the Sendspin name in the existing codebase: (1) LAN Sendspin WebSocket speakers (external speakers speaking Sendspin WS), (2) `sendspin_wire` (the encrypted Noise `KKpsk2` TCP `:8937` protocol used by `spatialfin-sink` and ESP32 sinks), and (3) `SendspinAvr` (the Android `:fcast` adapter talking Spec 0 to `spatialfin-avr`).
*   **Room-to-Receiver Projection:** `spatialfin-avr` addresses rooms by `room_id` (`X-Avr-Room-Id` on REST, `?room_id=` on WS). On Android, `CastSessionManager` projects each room returned by `GET /api/v1/rooms` into an individual `CastReceiver` entry sharing one per-server Bearer token (`StoredAvrToken`).
*   **Audio / DSP Terms:** `OAMD` (Object Audio Metadata inside Dolby TrueHD Atmos bitstreams), `VBAP` (Vector Base Amplitude Panning onto physical 3D speaker coordinates), `Lr4Filter` (Linkwitz-Riley 4th-order crossover filter), `MLP` (main listening position at room origin `(0, 0, 0)`), and `LCR anchors` (XR spatial screen alignment to physical Left/Center/Right speakers).
*   **Music Assistant Handoff & `auto_resume`:** Integration that pauses active Music Assistant audio on a room's speakers when cinema playback starts and optionally resumes it (`auto_resume`) when cinema playback ends.
*   **`SsrfPolicy`:** Outbound URL policy on `spatialfin-avr` that permits private LAN/VPN `http(s)` targets (required for local Jellyfin and Music Assistant servers) while rejecting link-local metadata ranges (`169.254.0.0/16`, `fe80::/10`), non-`http(s)` schemes, credentials-in-URLs, and public WAN hosts unless explicitly allowlisted.

## The Problem

While the core multi-room engine and initial Android XR/Beam integration work today, nine operational gaps limit daily reliability and prevent covering all target rooms and devices:

*   **Volatile room state:** Every `spatialfin-avr` restart resets every room to `master_volume = 0.75`, `muted = false`, `eq_bypass = false`, and `upmix_mode = "native"`, forcing manual re-adjustment after container updates or host reboots.
*   **Incomplete browser administration:** The embedded Web UI at `/` can create rooms and pair outputs, but users must still type 6-digit PINs manually (no scannable QR image), enter raw `(x, y, z)` coordinates by hand, and edit `docker-compose.yaml` for Music Assistant settings. The UI also lacks live room telemetry, volume/upmix controls, and paired-device revocation.
*   **Opaque runtime diagnostics:** During playback, neither the Android HUD nor the Web UI reports whether TrueHD Atmos is rendering live OAMD 3D objects or fell back to the FFmpeg bed decoder, nor whether an HDMI port's ALSA/EDID sink accepts 5.1/7.1 LPCM versus 2.0 ARC stereo.
*   **Friction when changing rooms:** Moving playback between rooms (for example, from a stereo bedroom to a 5.1 living room) requires exiting playback or opening the full Cast picker, stopping the current session, picking the new room, and re-launching.
*   **Manual lip-sync delay guesswork:** Setting `fixed_hw_delay_us` on an HDMI soundbar or wireless speaker relies on trial and error even though the server already implements `calibration.lip_sync_chirp`.
*   **Proxy dependency for HTTPS/WSS:** Enabling `SPATIALFIN_AVR_USE_TLS=true` advertises `tls=1` in mDNS and pairing QR codes, but the daemon only binds plain TCP/HTTP, requiring users to configure Caddy or Tailscale Serve for encrypted LAN transport or browser WebXR access.
*   **Missing client coverage on TV and WebXR:** Android TV (`:shell:tv`, `:player:tv`) and `spatialfin-webxr/` cannot discover or cast audio to `spatialfin-avr` rooms, leaving those form factors disconnected from the shared multi-room AVR setup.
*   **No deployable ESP32 7.1 sink firmware & stubbed hardware soak gates:** The AVR server provisions Noise `KKpsk2` PSKs for `:8937` ESP32 arrays, and four hardware-gated Phase 0 soak tests exist as `#[ignore]` stubs, but there is no ESP32 firmware in the repository to flash onto a 7.1 room array and no real soak assertions verifying clock drift or flush behavior.
*   **Incomplete `spatialfin-avr` CI coverage:** Existing CI in `spatialfin-avr/.github/workflows/ci.yml` does not run `--features alsa-native` multi-room tests, validate embedded Web UI JavaScript syntax, or verify the built container serves `GET /` with `Content-Security-Policy`.

## Goals

*   **G1 — Persistent Room Preferences:** Every room in `avr_state.json` persists `master_volume`, `muted`, `eq_bypass`, `upmix_mode`, and per-speaker `crossover_hz` atomically across daemon restarts (including `SIGTERM` shutdown flush), restoring the persisted DSP parameters on boot without breaking existing `avr_state.json` files.
*   **G2 — Self-Contained Web Administration UI:** From `http(s)://<avr-host>:<port>/` with zero external CDN requests, an authenticated user can:
    *   Scan an inline base64 SVG QR code to pair a SpatialFin client.
    *   View live room playback state, active codec/renderer mode, drift ms, and adjust volume, mute, EQ bypass, and upmix mode.
    *   Drag speaker icons on a 2D top-down room canvas around the main listening position (MLP) with automatic `room_geom_delay_us` preview and `If-Match` save.
    *   List and revoke individual paired client tokens (`GET /api/v1/clients`).
    *   Configure Music Assistant URL, write-only Bearer token, and auto-resume flag without editing `docker-compose.yaml`.
*   **G3 — Live Atmos/OAMD & HDMI Capability Visibility:** `spatialfin-dsp-rt` publishes live `renderer_mode`, `fallback_active`, `fallback_reason`, and `active_oamd_objects` lock-free into `telemetry.session` and `GET /api/v1/status`; `GET /api/v1/outputs/local` reports startup-cached PCM formats (`S32LE`, `S24_3LE`, `S16LE`), supported channel counts (`2`, `4`, `6`, `8`), and a 2-channel ARC warning when an HDMI device confirms a 2-channel ceiling.
*   **G4 — Two-Tap In-Player Room Switching & Full Upmix Cycle:** Galaxy XR and Phone/Beam players display a compact room switcher pill when Theater Audio is active or paired rooms exist, transferring playback between rooms (or folding back to local device audio) at the settled media timestamp (`pts_us`) targeting `<= 3 s` without closing the player Activity, and cycling all four upmix modes (`native`, `all_channel_stereo`, `dts_height_upmix`, `neural_height`).
*   **G5 — Automated Acoustic Lip-Sync Measurement:** A user on Pixel/Beam or Galaxy XR can run a guided microphone calibration over existing WebSocket `clock.ping` and `calibration.lip_sync_chirp` RPCs that measures pure room audio hardware delay (subtracting stored MLP-to-speaker acoustic Time-of-Flight, targeting `±5 ms` median repeatability across 5 chirps) and saves `fixed_hw_delay_us` to the room profile on user confirmation.
*   **G6 — Optional Built-In Dual-Mode TLS:** Setting PEM certificate and key file paths (or enabling auto-generated LAN certificate mode with SPKI QR pinning and TV/manual SAS verification) binds HTTPS/WSS inside `spatialfin-avr` using `rustls` (optionally on a dedicated `SPATIALFIN_AVR_TLS_PORT` so LAN HTTP and local healthchecks coexist), keeping plain HTTP as the default when unset.
*   **G7 — Android TV & WebXR Theater Audio Support:** Android TV (`:shell:tv` / `:player:tv`) discovers, pairs, selects, and plays to `spatialfin-avr` rooms using D-pad navigation and hardware remote volume keys; `spatialfin-webxr/` pairs via PIN/token over `https://`/`wss://` using ephemeral single-use WebSocket tickets, selects rooms, and drives Split-A/V playback controls.
*   **G8 — Deployable ESP32-C5/S3 Sink Firmware & Hardware Soak Gates:** Add a buildable ESP-IDF C firmware project (`firmware/esp32-sink/`) consuming a compact binary audio chunk extension to the Noise `KKpsk2` `:8937` wire protocol (supporting 2-channel I2S stereo and 8-channel I2S TDM with optional stateful FLAC decoding and continuous fixed-point fractional ASRC clock steering), and replace the Phase 0 soak stubs with real soak/probe assertions executed via `scripts/phase0/run_gates.sh`.
*   **G9 — Expanded `spatialfin-avr` CI:** `spatialfin-avr/.github/workflows/ci.yml` runs on every push/PR and verifies existing workspace/schema/Docker checks plus `cargo test -p spatialfin-avr --features alsa-native --test multi_room`, embedded Web UI JavaScript syntax validation, and a host-side container `GET /` + `Content-Security-Policy` smoke check.

## Non-Goals

*   **Breaking Spec 0 `v=1` Compatibility:** Existing paired Android clients using Spec 0 `v=1` HTTP/WS messages must continue operating without mandatory re-pairing or breaking field renames. All protocol additions are additive and optional.
*   **WAN / Cloud Relay Streaming:** `spatialfin-avr` remains a LAN/VPN service governed by `SsrfPolicy`; public internet hosting, STUN/TURN audio relays, and cloud accounts are out of scope.
*   **Licensed Proprietary Bitstream Decoders or Encoders:** Commercial Dolby/DTS SDKs and outgoing Dolby Digital/Atmos bitstream encoding over HDMI are out of scope; local HDMI playback remains decoded multi-channel LPCM through the Rust DSP engine.
*   **Custom PCB Fabrication or Enclosure CAD:** The ESP32-C5/S3 milestone covers firmware, pinout configuration, and bring-up documentation for off-the-shelf dev boards and I2S/TDM DAC breakouts, not electrical schematic or PCB manufacturing files.
*   **Multi-AVR Synchronized Grouping Across Different Hosts:** Simultaneous independent rooms on one `spatialfin-avr` server are supported, but synchronizing playback across two physically separate `spatialfin-avr` server instances is out of scope.

## Resolved Decisions

All four initial design questions are resolved and incorporated into the Technical Plan below:

*   **Q1 (TLS Trust Model):** Dual mode — load user-provided PEM cert/key (`SPATIALFIN_AVR_TLS_CERT` / `SPATIALFIN_AVR_TLS_KEY`, with hot-reload) when configured; fall back to an auto-generated 10-year ECDSA P-256 self-signed LAN cert reusing a persistent private key (`<data_dir>/tls/key.pem`, `0600`) with base64url (`URL_SAFE_NO_PAD`) SHA-256 SPKI fingerprint pinning embedded in `spatialfin-avr://pair?...&spki=<b64url>` and an 8-character SAS displayed beside the 6-digit PIN for TV/manual pairing. Support optional `SPATIALFIN_AVR_TLS_PORT` so HTTPS/WSS coexists with LAN HTTP unless `SPATIALFIN_AVR_DISABLE_HTTP=true`.
*   **Q2 (ESP32 7.1 Topology):** Unified ESP-IDF C firmware (`spatialfin-esp32-sink`) sharing one Noise `KKpsk2`, 2D Kalman + fixed-point Hermite fractional ASRC, and optional stateful `dr_flac` decode core with selectable `i2s_std_2ch` (multi-node stereo, fits internal SRAM) or `i2s_tdm_8ch` (single 8-channel TDM DAC, requires `>= 2 MB` PSRAM) output driver via NVS/build config.
*   **Q3 (Music Assistant Credentials Storage):** Persisted in a dedicated `0600` file `<data_dir>/ma_config.json` (matching `sendspin_keys.json`) with an explicit `disabled: bool` tombstone so file state always takes precedence over env vars. `GET /api/v1/settings/music-assistant` returns `has_token: bool` and never echoes the stored secret; changing the normalized `(scheme, host, port)` origin requires supplying a new `token` or `clear_token: true`.
*   **Q4 (WebXR Mixed-Content, CORS, PNA & Browser WS Auth):** Browsers running `https://spatialfin.martinez.fyi/web/` require CA-backed HTTPS/WSS for `spatialfin-avr`, explicit CORS allowlisting (`SPATIALFIN_AVR_CORS_ORIGINS`, disabled by default when unset, fail-closed on `*`), Chromium Private Network Access preflight headers (`Access-Control-Allow-Private-Network: true`), `Origin` validation on `/ws/v1/control`, and single-use opaque WebSocket tickets (`POST /api/v1/ws/ticket` presented via `Sec-WebSocket-Protocol`).

## Critical User Journeys (CUJs)

### CUJ 1: Persisted Room Preferences Across Container Restart (G1)

*   **Given:** A `spatialfin-avr` server with an example room (`Big Bedroom`) set to `master_volume = 0.55`, `muted = true`, `upmix_mode = "all_channel_stereo"`, `eq_bypass = true`, and front-speaker `crossover_hz = 80`.
*   **When:** The `spatialfin-avr` Docker container restarts (`docker compose restart spatialfin-avr`, sending `SIGTERM`).
*   **Then:** `GET /api/v1/status`, `GET /api/v1/rooms`, and `GET /api/v1/room/profile` for `Big Bedroom` report `master_volume = 0.55`, `muted = true`, `upmix_mode = "all_channel_stereo"`, `eq_bypass = true`, and `crossover_hz = 80`, and the next playback session launches with those persisted DSP parameters.

### CUJ 2: Browser Administration with QR Pairing, 2D Floorplan & Live Controls (G2)

*   **Given:** An authenticated desktop browser open to `http://192.168.1.90:1986/` while `Living Room` is idle or playing.
*   **When:** The user opens the **Pair SpatialFin Device** modal, scans the inline base64 SVG QR code with Galaxy XR, drags `FL`/`FR` icons on the 2D floorplan, configures Music Assistant URL/token in Settings, revokes an old phone in Paired Devices, and adjusts volume/upmix on a playing room.
*   **Then:** Galaxy XR pairs without manual PIN entry, `PUT /api/v1/room/profile` persists updated coordinates and geometric delays, Music Assistant handoff activates without restarting the daemon, the revoked client receives `401 auth_invalid` on its next call, and the server applies volume and upmix updates to the active DSP pipeline on the next 10 ms render frame.

### CUJ 3: Two-Tap Room Handoff During Active Movie Playback (G4)

*   **Given:** Galaxy XR is playing a 5.1 movie routed to `Big Bedroom (2.0)` at `00:42:15`.
*   **When:** The user taps the Theater Audio room pill in the player HUD and selects `Living Room (5.1)`.
*   **Then:** Playback pauses video, waits for pause settlement, stops the `Big Bedroom` AVR session (releasing its Sendspin WS speakers), starts and synchronizes the `Living Room` HDMI ALSA session at the settled timestamp, and resumes video locked at `1.0000×` within 3 seconds without closing `XrPlayerActivity` (or folds back to local headset audio with an error toast if `Living Room` fails to start).

### CUJ 4: Acoustic Lip-Sync Auto-Calibration for a New HDMI Soundbar (G5)

*   **Given:** A newly plugged-in HDMI soundbar assigned to `Living Room` whose internal DSP latency is unknown, with front-speaker distance stored in the room profile.
*   **When:** The user opens Room Calibration on Pixel or Galaxy XR, holds the device at the MLP, starts **Auto-Calibrate Lip-Sync** (triggering WS `clock.ping` sync and a single 5-chirp `calibration.lip_sync_chirp` burst with `>= 1.5 s` HDMI wake-up lead-in), verifies convergence, optionally checks the post-calibration flash+chirp preview, and taps **Save Delay**.
*   **Then:** The client computes the median pure audio hardware delay (subtracting stored MLP-to-speaker acoustic Time-of-Flight, targeting `±5 ms` repeatability with `IQR <= 10 ms` and `SNR >= 12 dB`), displays per-pulse spread, and writes `fixed_hw_delay_us` to `Living Room`'s room profile.

### CUJ 5: Live TrueHD Atmos Verification & HDMI Capability Check (G3)

*   **Given:** A TrueHD Atmos movie playing on a `5.1.2` or `7.1.4` room, and an HDMI soundbar scanned on `GET /api/v1/outputs/local` (including while playback is active).
*   **When:** The user inspects the HDMI port before or during playback and views the active playback HUD / Web UI during the movie.
*   **Then:** Local output discovery displays startup-cached PCM formats and channel limits (flagging a 2-channel ARC warning only when a clean probe confirmed `max_channels <= 2`, never on `EBUSY`), and live telemetry shows `renderer_mode = "truehd_vbap"` with `active_oamd_objects` (or `fallback_active = true` with `fallback_reason` if bed fallback engaged).

### CUJ 6: Android TV & WebXR Playback to Shared AVR Rooms (G7)

*   **Given:** An Android TV running SpatialFin TV and a browser running SpatialFin WebXR over HTTPS, both on the LAN with `spatialfin-avr`.
*   **When:** The user pairs each client once via 6-digit PIN (or token on WebXR), selects a shared room from the room picker, and starts a Jellyfin movie.
*   **Then:** Both TV and WebXR inherit all server-defined rooms automatically, stream video locally while `spatialfin-avr` plays synchronized direct-stream audio to the room's speakers, expose play/pause/seek/volume/upmix controls, and intercept TV remote hardware Volume +/-/Mute keys while Theater Audio is active.

### CUJ 7: Family Room 7.1 ESP32-C5/S3 Sink Bring-Up & Hardware Soak (G8)

*   **Given:** A PSRAM-equipped ESP32-C5/S3 node (or 2-ch stereo array) flashed with `spatialfin-esp32-sink` and provisioned with the `SinkPskFileConfig` JSON copied from SpatialFin or the Web UI.
*   **When:** The node boots on 5 GHz Wi-Fi (`WIFI_PS_NONE`), connects to `spatialfin-avr:8937`, completes the Noise `KKpsk2` handshake, and runs the Phase 0 hardware soak suite.
*   **Then:** The node locks its Kalman + fractional ASRC clock, appears online in the target room, passes 30-minute playback without underrun bursts or `> 500 µs` clock drift, and flushes with a 5 ms ramp on `StreamClear`.

### CUJ 8: Opt-In Built-In TLS Deployment (G6)

*   **Given:** An operator enables `SPATIALFIN_AVR_USE_TLS=true` either with CA-backed PEM paths (`SPATIALFIN_AVR_TLS_CERT`/`KEY`) for WebXR/desktop browsers or with no PEM paths (auto-generated self-signed ECDSA P-256 cert) on an isolated LAN.
*   **When:** The daemon starts (optionally binding `SPATIALFIN_AVR_TLS_PORT` alongside LAN HTTP), Docker runs `spatialfin-avr healthcheck`, Galaxy XR scans the pairing QR (`&spki=<b64url>` in self-signed mode), and Android TV pairs via 6-digit PIN + 8-character SAS confirmation.
*   **Then:** Docker healthchecks remain healthy, already-paired TLS Android clients refuse plaintext `tls=0` mDNS downgrades, self-signed IP changes preserve the SPKI pin by reusing `key.pem`, and CA-backed PEM certificates reload on `SIGHUP`/mtime without breaking WebXR or desktop browser connections.

--------------------------------------------------------------------------------

## Technical Plan (Architecture)

All nine workstreams are organized into four dependency-ordered phases so every server protocol contract lands before dependent UI, client, and firmware layers. All Spec 0 `v=1` wire extensions are additive and optional (`#[serde(default)]` in Rust, default parameter values in Kotlin/TypeScript) so existing clients continue operating without version bumps or re-pairing.

```text
                       +------------------------------------------------------------+
                       |                    Clients (LAN / HTTPS)                   |
                       | Galaxy XR | Phone/Beam | Android TV | WebXR | Web UI (/)   |
                       +-------------+----------------------------------------------+
                                     | HTTP REST + WS /ws/v1/control (X-Avr-Room-Id)
                                     | Optional Dual-Mode TLS (PEM / SPKI + SAS)
                                     | WebXR: POST /api/v1/ws/ticket + CORS/PNA
                                     v
  +--------------------------------------------------------------------------------------+
  |                          spatialfin-avr (Multi-Room Daemon)                          |
  |                                                                                      |
  |  [avr-protocol]                                                                      |
  |   - ControlPlaneState: AuthManager (paired_client_summaries, optional qr_svg,        |
  |     single-use WS ticket map) + CORS/PNA + WS Origin validation                      |
  |   - Embedded Web UI (web_ui.html): base64 QR SVG, 2 s polled HUD + controls,         |
  |     2D SVG floorplan (All/Floor/Height), paired clients, write-only MA settings      |
  |                                                                                      |
  |  [MultiRoomAvrBackend -> RoomBackend per room_id]                                    |
  |   - Serialized coalescing avr_state.json writer + SIGTERM shutdown flush             |
  |   - Lock-free Atomic LiveRendererSnapshot (renderer_mode, oamd_objects, fallback)    |
  |   - RuntimeMusicAssistantState (/data/ma_config.json, 0600, origin-change guard)     |
  |   - Startup-cached LocalAlsaOutputManager (formats, channels, EBUSY-safe ARC warn)   |
  |   - spatialfin-dsp-rt: FFmpeg -> TrueHdAtmosRenderer -> TheaterDspEngine -> Hub      |
  +----------+--------------------------------+---------------------------------+--------+
             | Sendspin WS (LAN)              | Direct ALSA (/dev/snd)          | Noise KKpsk2 :8937
             v                                v                                 | (JSON ctrl + binary audio)
   [Sendspin WS Speakers]           [Host PC HDMI Soundbar]                     v
   (e.g. Bedroom 2.0)               (e.g. Living Room 2.0-7.1)       [spatialfin-esp32-sink (ESP-IDF)]
                                                                     (2-ch I2S OR 8-ch TDM + ASRC)
```

### Phase A: AVR Persistence, Live Telemetry, Runtime Settings & Web UI

#### 1. Per-Room Runtime Persistence & Crossover Propagation (G1 / CUJ 1)

*   Extend `PersistedRoomRecord` with backward-compatible `#[serde(default)]` fields (`master_volume = 0.75`, `muted = false`, `eq_bypass = false`, `upmix_mode = "native"`). On boot, `RoomBackend::from_record` hydrates `LiveRuntimeControl`, `TheaterDspEngine` (including per-speaker crossovers from `room_profile.speakers`), and `RoomInner`, clamping `master_volume` to finite `[0.0, 1.0]` and validating `upmix_mode` against known variants.
*   Route all `avr_state.json` mutations through one serialized coalescing persistence writer owned by `MultiRoomAvrBackend` (`temp + fsync + rename`): discrete toggles (`muted`, `eq_bypass`, `upmix_mode`, profile/room edits) persist immediately, while rapid `master_volume` slider drags coalesce at `500 ms`. Register a Unix `SIGTERM`/`SIGINT` shutdown barrier in `main.rs` that flushes pending dirty state via `MultiRoomAvrBackend::shutdown_all()` before exit.
*   When `PUT /api/v1/room/profile` updates speaker `crossover_hz`, rebuild `TheaterDspEngine`'s 12-channel logical bus crossover filters (`main_hp`, `main_lp`, `lfe_apf`) and push updated coefficients to any active `spatialfin-dsp-rt` thread via `LiveRuntimeControl` with a 10–20 ms gain ramp to avoid audible clicks.

#### 2. Lock-Free Atmos/OAMD Telemetry & Startup-Cached ALSA Probing (G3 / CUJ 5)

*   **Atmos/OAMD live telemetry:** Extend `RendererStatus` (`spatial.rs`) with `active_oamd_objects: Option<u16>` populated by `TrueHdAtmosRenderer::status()` (counting dynamic objects above `-inf` gain). Publish live metrics from `spatialfin-dsp-rt` into a lock-free `LiveRendererSnapshot` composed solely of `AtomicU8`/`AtomicU16`/`AtomicU64` enums and counters (no heap `String` allocations or `Mutex` locking on the 10 ms RT audio loop). Static session metadata (`codec_active`, `active_decode_chain`) is written once at session setup, and Tokio HTTP/WS readers format JSON lazily. Keep existing non-optional `TelemetrySessionPayload.fallback_active: bool` intact; add `active_oamd_objects: Option<u16>` to `TelemetrySessionPayload` and `renderer_mode`, `active_oamd_objects`, `fallback_active`, `fallback_reason` to `AvrStatusResponse`.
*   **HDMI / ALSA capability probing:** Probe host ALSA devices (`S32LE`, `S24_3LE`, `S16LE` and channel counts `2, 4, 6, 8`) at daemon startup and during idle rescans, caching capabilities per device ID inside `LocalAlsaOutputManager`. When an output is actively playing (or returns `EBUSY`), `GET /api/v1/outputs/local` returns the cached capabilities with `in_use: true` and emits `capability_warning` only when a clean probe confirmed `max_channels <= 2` on an HDMI device.

#### 3. Admin & Live Control HTTP Routes (G2 / CUJ 2)

Add authenticated (`Authorization: Bearer`) REST endpoints on `ControlPlaneBackend` (delegated through `MultiRoomAvrBackend`):

*   `GET /api/v1/clients`: returns `{ clients: [{ client_id, name, created_s, is_current }] }` via `AuthManager::paired_client_summaries(...)` (preserving existing `AuthManager::paired_clients()`). Existing `DELETE /api/v1/clients/{client_id}` rejects self-revocation (`409 cannot_revoke_self`) unless `?force=true` is passed and rejects revoking the static env admin (`403 cannot_revoke_static_admin`).
*   `POST /api/v1/room/controls`: room-scoped (`X-Avr-Room-Id`) mutation accepting optional `{ master_volume, muted, eq_bypass, upmix_mode }`, delegating to `RoomBackend`, returning `AvrStatusResponse`, and broadcasting `room.state_changed` to subscribed WS sessions (`200` on empty `{}` no-op, `400 invalid_volume` / `400 invalid_upmix_mode`, `404 room_not_found`).
*   `GET /api/v1/settings/music-assistant` & `PUT /api/v1/settings/music-assistant`:
    *   `GET` returns `{ configured, base_url, has_token, auto_resume, source }` (`"file" | "env" | "none"`), backed by `RuntimeMusicAssistantState { config, source }`. Never returns the plaintext token.
    *   `PUT` rejects conflicting `token` + `clear_token: true` (`400 conflicting_token_flags`), rejects any normalized `(scheme, host, port)` origin change unless a new `token` or `clear_token: true` is supplied (`400 token_required_on_origin_change`, preventing token exfiltration to another LAN host), validates `base_url` against `SsrfPolicy` (`400 ssrf_blocked`), writes `<data_dir>/ma_config.json` atomically (`0600`) with an explicit `disabled: bool` tombstone when cleared, and hot-swaps `SharedServerState.music_assistant` without restarting the daemon. `ma_bridge.rs` enforces `SsrfPolicy` at TCP connect time on resolved IPs and disables HTTP redirects (`Policy::none()`).
*   `POST /api/v1/pair/pin`: accepts optional `?include_qr_svg=true` to return base64-encoded SVG QR data (`qr_svg`) rendered server-side using `qrcode` (`default-features = false, features = ["svg"]`) without bloating CLI or Android responses.

#### 4. Embedded Web Administration UI Upgrade (G2 / CUJ 2)

Enhance the zero-CDN single-file `web_ui.html` while keeping strict CSP (`default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline'; script-src 'sha256-...'` / existing inline policy) and XSS-safe DOM rendering:

*   **Scannable QR Code in Pair Device Modal:** Request `?include_qr_svg=true` and render `<img src="data:image/svg+xml;base64,...">` above the 6-digit PIN, countdown timer, and (in self-signed TLS mode) 8-character SAS fingerprint.
*   **Live Room Telemetry & Control Strip:** Poll `GET /api/v1/status` every `2 s` strictly while `document.visibilityState === "visible"` (plus immediate refresh after control mutations) to display active codec, `truehd_vbap` + object count vs `bed` fallback badge, drift ms, and controls for volume slider, mute, EQ bypass, and 4-mode upmix calling `POST /api/v1/room/controls`.
*   **2D Top-Down Room Floorplan Editor:** Render an SVG canvas built via `createElementNS("http://www.w3.org/2000/svg", ...)` and `textContent` (`+X` right, `-Z` toward screen/top, default `±4.0 m` auto-expanding if speakers sit farther out, MLP icon at `(0, 0)`). Include an `All / Floor / Height` layer toggle so co-located Atmos height speakers (`TFL`/`TFR`/`TRL`/`TRR`) and floor speakers (`FL`/`FR`) remain independently draggable, preserve each speaker's `y` height with an inline numeric `y` input, preview Euclidean `room_geom_delay_us`, and save via `PUT /api/v1/room/profile` with `If-Match` (prompting reload on `412 Precondition Failed`).
*   **HDMI Capability Badges & Server Settings:** Show cached `supported_formats`, `supported_channel_counts`, `in_use`, and any `capability_warning` inline on local output cards. Add a Server Settings tab for Paired Clients revocation (`GET /api/v1/clients`, `DELETE /api/v1/clients/{id}`) and write-only Music Assistant configuration (displaying a visible warning banner when saving tokens over non-localhost plaintext HTTP).

--------------------------------------------------------------------------------

### Phase B: Acoustic Lip-Sync Auto-Calibration & XR/Beam HUD Room Switcher

#### 5. Acoustic Lip-Sync Auto-Calibration UI (G5 / CUJ 4)

*   **Reuse Existing WS Protocol:** Run calibration over a transient `SendspinAvrTransport` WebSocket connection (`clock.ping` via `SendspinAvrClockSync` for 5-RTT median offset `delta_clock_us = server_us - local_us`, followed by the existing `calibration.lip_sync_chirp` RPC — no duplicate HTTP chirp or clock-ping endpoints). Extend `CalibrationLipSyncChirpPayload` with optional `bypass_hw_delay: bool` (default `true` for auto-calibration so the result is absolute and idempotent) and schedule all 5 pulses in a single burst preceded by `>= 1.5 s` of silence lead-in (exceeding `CALIBRATION_LEAD_US = 650_000` and waking cold HDMI/ARC soundbars before pulse #1; return `409 session_active` / `409 calibration_busy` if the room is busy).
*   **Microsecond Capture & Phase-Invariant Onset Detection:** Upgrade `CalibrationCapture.Result` to expose `captureStartBoottimeUs: Long` (`AudioTimestamp.nanoTime / 1_000`) and fail/warn if `usedAudioRecordTimestamp == false` (never accepting coarse `elapsedRealtime()` fallback). Capture all 5 chirps in one pass while suspending XR `SpatialVoiceService` (reusing `Umik1CaptureEngine`'s mic-exclusivity guard). Match the server's existing 40 ms log chirp (`generate_lipsync_chirp_signal`) using a phase-invariant rectified/RMS envelope correlator (`ChirpDetector.detectEnvelopeMatched(...)`) with consistent dB SNR (`20 * log10(peak / noiseFloor) >= 12 dB`), eliminating the `lagStrideMs = 1` carrier-aliasing defect.
*   **Pure Room Delay Math & Confirmation Preview:** For each detected pulse `i`, compute `measured_hw_delay_us = (captureStartBoottimeUs + onset_us) - (scheduled_avr_us[i] - delta_clock_us) - acoustic_tof_us`, where `acoustic_tof_us = round((front_speaker_distance_m / 343.0) * 1_000_000)` from the room profile's stored speaker-to-MLP coordinates. Do not subtract phone/headset display latency when saving to the shared room profile (`fixed_hw_delay_us` measures pure audio hardware delay, keeping display offsets client-side). Discard outliers beyond `±25 ms` from the median, require `>= 3` pulses with `IQR <= 10 ms`, clamp to `[0, 500_000]`, offer an optional post-calibration flash+chirp confirmation preview with `±5 ms` manual trim nudges, and save the confirmed `fixed_hw_delay_us` to the room's front/target output speakers via `PUT /api/v1/room/profile`.

#### 6. In-Player Multi-Room Quick Switcher & Full Upmix Cycle (G4 / CUJ 3)

*   Extend `TheaterAudioTelemetry` (`:player:core`) with `activeOamdObjects: Int? = null` (already carries `rendererMode`, `fallbackActive`, `fallbackReason`). Extend `TheaterSessionSnapshot` with `availableRooms: List<TheaterRoomSummaryContract>` (`receiverId`, `roomId`, `roomName`, `layout`, `isCurrent`) and add `TheaterClientEvent.SwitchRoom(val receiverId: String)` (`LOCAL_AUDIO_ID` sentinel or `FoldBackRequested` for local fallback).
*   **Acyclic Orchestration in `CastSessionManager`:** Because `CastSessionManager` injects `SplitAvController`, forward `TheaterClientEvent.SwitchRoom` from `SplitAvController` to `CastSessionManager` via a registered callback/SharedFlow. Inside `CastSessionManager`, serialize all transitions (`LOCAL -> ROOM`, `ROOM_A -> ROOM_B`, `ROOM -> LOCAL`) behind a `Mutex` and monotonic `switchGeneration` (ignoring same-room taps and aborting superseded coroutines). Pause ExoPlayer first and wait for pause settlement *before* capturing `posUs`; stop the active engine via `stop("room_switch")`; resolve the target `CastReceiver` + `SendspinAvrSessionConfig` at `initialTargetPtsUs = posUs` and launch `splitAvController.startSendspinAvr(...)`, resuming video at `1.0000×` (or preserving paused state if previously paused) without recreating `XrPlayerActivity` / `BeamPlayerActivity`. If starting the target room fails (`401`, `409`, timeout), show an error toast and fold back to local device audio so playback is never stranded.
*   **XR & Beam HUD:** Render a compact room switcher pill (`Room: <name> ▾`) opening an inline picker row (XR) or bottom sheet (Beam) with paired rooms plus `"This Device"`, show the Atmos/OAMD badge (`Atmos VBAP · N obj` or `Bed Fallback`) from `theaterSnapshot.telemetry` in both the Theater strip and `PlaybackStatsOverlay`, and cycle all four upmix modes (`native -> all_channel_stereo -> dts_height_upmix -> neural_height -> native`).

--------------------------------------------------------------------------------

### Phase C: Optional Dual-Mode TLS, CORS/PNA, Android TV & WebXR Theater Audio

#### 7. Optional Built-In Dual-Mode TLS, CORS/PNA & Browser WS Tickets (G6 / CUJ 8)

*   **Server TLS (`config.rs`, `tls.rs`, `main.rs`):**
    *   Add `SPATIALFIN_AVR_TLS_CERT`, `SPATIALFIN_AVR_TLS_KEY`, `SPATIALFIN_AVR_TLS_PORT`, `SPATIALFIN_AVR_DISABLE_HTTP`, and `SPATIALFIN_AVR_CORS_ORIGINS` to `AvrConfig`. Install `rustls::crypto::ring::default_provider()` once at startup. Fail fast if only one of `TLS_CERT`/`TLS_KEY` is provided.
    *   When `SPATIALFIN_AVR_USE_TLS=true`: load PEM cert/key via `axum_server::tls_rustls::RustlsConfig::from_pem_file` (with `SIGHUP`/mtime hot-reload via `reload_from_pem_file`), or if unset, generate/load a persistent 10-year ECDSA P-256 cert in `<data_dir>/tls/{cert.pem,key.pem}` (`0600`) with SANs (`advertised_host`, `localhost`, `127.0.0.1`), reusing the existing `key.pem` if `advertised_host` changes so `tls_spki_sha256` (`URL_SAFE_NO_PAD` base64url) stays stable.
    *   Bind HTTPS/WSS on `SPATIALFIN_AVR_TLS_PORT` alongside LAN HTTP (defaulting to single-port HTTPS only when `SPATIALFIN_AVR_TLS_PORT` is unset or `SPATIALFIN_AVR_DISABLE_HTTP=true`). Update `Commands::Healthcheck` and `Commands::PairPin` in `main.rs` to connect over HTTPS trusting the local PEM cert via `reqwest::Certificate::from_pem` whenever HTTP is disabled.
    *   Include `&spki=<b64url>` in `AuthManager::arm_pin()` `qr_uri` and an 8-character SAS fingerprint in `PairPinResponse` only in self-signed mode.
*   **CORS, Chrome PNA & Browser WS Auth (`http.rs`, `ws.rs`, `auth.rs`):**
    *   When `SPATIALFIN_AVR_CORS_ORIGINS` is non-empty, validate origins at startup (fail closed on `*` or malformed origins), attach a `tower-http` `CorsLayer`, and add middleware emitting `Access-Control-Allow-Private-Network: true` on `OPTIONS` preflights when `Access-Control-Request-Private-Network: true` is present for an allowed origin.
    *   Add authenticated `POST /api/v1/ws/ticket` minting a 15-second, single-use opaque ticket stored in a bounded in-memory TTL map. Extend `ws_upgrade_handler` (`ws.rs`) to validate the `Origin` header (same-origin or allowlisted CORS origin) and accept either `Authorization: Bearer` (native clients) or `Sec-WebSocket-Protocol: spatialfin-avr.v1, ticket.<opaque_id>` (browser clients — never Bearer tokens in query strings).
*   **Android SPKI Pinning & Anti-Downgrade (`:settings`, `:fcast`, `:fcast:session-ui`):**
    *   Persist `tlsSpkiSha256: String? = null` and sticky `requireTls: Boolean = false` in `StoredAvrToken` (`TheaterAvrTokenStore.kt`, stored in `SharedPreferences` encrypted via `AndroidKeyStore` AES-256-GCM + AAD); refuse plaintext `tls=0` mDNS downgrades once paired with TLS.
    *   Propagate `tlsSpkiSha256` through `TheaterPairingRequest` (`TheaterPairingDialog.kt`), `parseTheaterPairQrUri` (`CastSessionManager.kt`), `SendspinAvrHttpClient.Endpoint`, and `SendspinAvrSessionConfig` (`SplitAvSessionEngine.kt`). When `useTls && tlsSpkiSha256 != null`, `SendspinAvrTlsPinning` builds a cached `OkHttpClient` pairing a strict leaf SPKI `X509TrustManager` with a scoped SPKI-bound `HostnameVerifier` (rejecting any mismatch with `TheaterHudBanner(code = "tls_pin_mismatch", requiresRepair = true)` while preserving the stored token until the user re-pairs). For Android TV / manual PIN pairing without a camera, display the fetched leaf cert's 8-character SAS for user confirmation before saving the pin.

#### 8. Android TV Theater Audio (G7 / CUJ 6)

*   Add `projects.fcast.sessionUi` and `projects.fcast` **only** to `:shell:tv` (`shell/tv/build.gradle.kts`) — keeping `:player:tv` decoupled via `:player:core`'s `SplitAvVideoBridge` / `SplitAvTheaterBridge` and reusing Beam's Theater HUD composable through `:player:tv`'s existing `projects.player.beam` dependency (never pulling `androidx.xr.compose` into `:player:tv`).
*   Pass `fcastSession: CastSessionManager` from `UnifiedMainActivity.kt` into `TvNavigationRoot` (`LocalFCastSession`), surface a D-pad-focusable **Theater Audio** action in `TvTopBar` / `TvOverflowSheet` opening `TvTheaterAudioDialog.kt` (mDNS room list + manual `host:port` + 6-digit PIN + optional self-signed SAS confirmation), and route TV Play actions through `dev.spatialfin.fcast.session.launchPlayback(...)`.
*   Add `EXTRA_SPLIT_AV_VIDEO_ROLE` / `EXTRA_SPLIT_AV_HOLD_AT_START` extras to `TvPlayerActivity.kt`, intercept remote `KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN`, and `KEYCODE_VOLUME_MUTE` while Theater Audio is active to control AVR room volume/mute with an on-screen HUD indicator, wait for any pending HDMI `Surface.setFrameRate` mode switch to settle (`<= 2.5 s` cap) before signaling `playback.ready`, and render a D-pad-accessible Theater HUD row (room switcher, volume, mute, EQ bypass, upmix mode, Atmos badge, fold-back to TV audio).

#### 9. WebXR Theater Audio (G7 / CUJ 6)

*   Create `spatialfin-webxr/src/theater/TheaterAvrClient.ts` and `TheaterAvrUi.ts` implementing Spec 0 `v=1` HTTP + `/ws/v1/control?room_id=...` over `fetch` + `POST /api/v1/ws/ticket` + `Sec-WebSocket-Protocol`, with `localStorage` credential/room persistence and manual AVR `host:port` entry.
*   Require browser-trusted HTTPS/WSS when `location.protocol === 'https:'` (displaying setup instructions for `SPATIALFIN_AVR_USE_TLS=true` + PEM cert or Tailscale Serve + `SPATIALFIN_AVR_CORS_ORIGINS=https://spatialfin.martinez.fyi`).
*   Mount `TheaterAvrUi` through `main.ts` / `index.html` / `BrowserApp.ts` (Cast dialog & Settings), and attach Theater Audio playback sync to both `PlayerSpace.ts` (3D WebXR) and `BrowserApp.ts` (2D browser playback): mute local `<video>`, build a decoupled credential-free direct Jellyfin `stream_url` + `jellyfin_token` per Spec 0 (never forwarding a browser-only HLS transcode URL containing `?api_key=`), run `session.start -> playback.load -> playback.ready`, emit `2 Hz` `playback.video_timing` anchored to `video.requestVideoFrameCallback()` (`meta.mediaTime` + `meta.presentationTime`, `video.currentTime` fallback) with a Web Worker/frame-driven `2 s` `session.heartbeat`, forward transport/DSP controls, and call `session.stop` on exit.

--------------------------------------------------------------------------------

### Phase D: Unified ESP32-C5/S3 Sink Firmware, Hardware Soak Suite & CI

#### 10. Unified ESP-IDF C Firmware & Binary/FLAC Noise `:8937` Wire Extension (G8 / CUJ 7)

*   **Server Binary Audio Framing & Async Stateful FLAC (`sendspin_wire.rs`, `sendspin.rs`, `bins/spatialfin-sink/src/lib.rs`):**
    *   Preserve the existing Noise `KKpsk2` `:8937` handshake (`u16_be(len) || client_id_utf8` intro, `u16_be`-framed Noise handshake messages, `u32_be(chunk_count)` + `<= 56 KiB` Noise ciphertext chunks) and existing JSON control frames (`hello`, `clock_ping`, `clock_pong`, `stream_clear`, `heartbeat`).
    *   Extend `SendspinWireFrame::Hello` with `#[serde(default = "default_codec_pcm24")] pub codec: String` (`"pcm24"` default for wired `spatialfin-sink`, `"bin_pcm24"` or `"bin_flac"` for ESP32 sinks) and add a compact binary transport packet tag (`0x01` = JSON control frame; `0x02` = binary audio chunk header `[pts_us: i64_le][play_at_avr_us: i64_le][channels: u8][flags: u8][payload_bytes...]`) so raw PCM or FLAC bytes bypass `serde_json` integer-array expansion completely.
    *   Offload per-peer stateful `flacenc` encoding (`48 kHz`, 24-bit, `480`-frame 10 ms blocks, `max_lpc_order = 4`, emitting `STREAMINFO` once on stream init and resetting encoder state on `stream_clear`) to each peer's async Tokio writer task off `spatialfin-dsp-rt`. Multi-node `i2s_std_2ch` stereo nodes receive their assigned channel pair via server-side `client_id -> channel_pair` routing.
*   **Unified ESP-IDF v5.3+ C Firmware (`firmware/esp32-sink/`):**
    *   **Hardware Tiers:** `i2s_std_2ch` (stereo Philips 24-in-32-bit I2S for PCM5102A / MAX98357A / ES9038Q2M) runs within internal SRAM on both `esp32c5` and `esp32s3`; `i2s_tdm_8ch` (8-slot 32-bit TDM 48 kHz for CS42448 / PCM1681 / ADAU1452) requires `>= 2 MB` PSRAM and 5 GHz Wi-Fi (`5–7 Mbps` realistic FLAC bitrate budget), with CPU headroom benchmarked on single-core no-FPU `esp32c5` vs dual-core `esp32s3`.
    *   **Provisioning & Network (`config_store.c`, `partitions.csv`, `noise_client.c`):** Reads Wi-Fi credentials, `avr_host:8937`, `output_mode`, `channels`, and GPIO pin overrides from NVS namespace `sfin_sink` (`wifi_ssid`, `wifi_pass`, `avr_host`, `avr_port`, `output_mode`, `channels`, `gpio_mclk`, `gpio_bclk`, `gpio_ws`, `gpio_dout`) and `SinkPskFileConfig` JSON from LittleFS `/littlefs/sink_psk.json` (defined in `partitions.csv`). Disables Wi-Fi power-save (`esp_wifi_set_ps(WIFI_PS_NONE)`), enables `TCP_NODELAY`, and completes Noise `KKpsk2` via `noise-c` backed by `espressif/libsodium`.
    *   **Clock Steering, FLAC Decode & DMA (`kalman_jitter.c`, `flac_decoder.c`, `i2s_output.c`):** Uses fixed-point `int64_t` 2D Kalman offset/skew tracking against `esp_timer_get_time()` with RTT outlier rejection (`discard if rtt_us > 2 * min_rtt_us`), drives a continuous fixed-point Hermite fractional ASRC (`±150 ppm` slew, `±100 µs` deadband) so clock drift stays `<= 500 µs` over 30 minutes without an APLL, decodes persistent FLAC streams via vendored `dr_flac.h` (resetting decoder + ramping 5 ms to silence on `stream_clear`), and drives `i2s_std_2ch` / `i2s_tdm_8ch` DMA buffers with documented default GPIO pinouts per MCU.

#### 11. Phase 0 Hardware Soak Suite & `spatialfin-avr` CI Expansion (G8, G9)

*   **Four Phase 0 Hardware Gates (`phase0_gates.rs`, `sendspin_ws.rs`, `scripts/phase0/run_gates.sh`):**
    *   **Gate 0A (`TEST_ESP32_IP`):** Connects to ESP32 Noise `:8937` sink, streams 30 minutes of multi-channel audio, asserts zero underrun bursts, `|clock_skew_us| <= 500`, and `StreamClear` flush recovery.
    *   **Gate 0B-Noise (`TEST_SENDSPIN_TARGET`):** Same Noise `:8937` soak against a Linux `spatialfin-sink` target.
    *   **Gate 0B-WS (`TEST_SENDSPIN_WS_URL`):** Connects to a LAN Sendspin WebSocket speaker, verifies audio playback streaming and `stream/clear` flush.
    *   **Gate 0C (`TEST_JELLYFIN_URL`):** Runs direct-stream ingest and seek verification against a live Jellyfin item.
    *   Expand `scripts/phase0/run_gates.sh` to execute only gates whose env vars are set and output a structured pass/fail/skipped report.
*   **CI Additions (`spatialfin-avr/.github/workflows/ci.yml`):**
    *   Add `cargo test -p spatialfin-avr --features alsa-native --test multi_room` (reusing existing `libasound2-dev` + `pkg-config` apt step; tests must not require `/dev/snd`).
    *   Add embedded Web UI JavaScript syntax validation (`matchAll` + `new vm.Script(...)` across all `<script>` blocks in `web_ui.html`).
    *   Add a host-side Docker smoke step (`docker run -d -p 1986:1986 spatialfin-avr:ci`, wait for `spatialfin-avr healthcheck`, then `curl -sfI http://127.0.0.1:1986/` asserting HTTP `200` and `Content-Security-Policy`).

--------------------------------------------------------------------------------

## Alternatives Considered

### Alternative A: Single-Mode Built-In TLS (PEM-Only or Self-Signed-Only)

*   **Pros:** Fewer TLS code paths, no `rcgen` dependency (if PEM-only), or no external certificate setup (if self-signed-only).
*   **Cons:** PEM-only leaves users on isolated LANs without a domain/CA unable to enable `SPATIALFIN_AVR_USE_TLS=true` without running Caddy or Tailscale Serve. Self-signed-only causes TLS certificate errors in desktop Chrome, Safari, and `https://spatialfin.martinez.fyi/web/`.
*   **Reason for Rejection:** Rejected because dual mode (user PEM cert when configured; auto-generated ECDSA P-256 self-signed cert with QR SPKI pinning and TV SAS fallback when unset) supports both CA-free LAN Android deployments and CA-backed WebXR/browser deployments without disabling certificate validation.

### Alternative B: Single-Topology ESP32 Firmware (2-Ch Stereo-Only or 8-Ch TDM-Only)

*   **Pros:** Smaller firmware binary, simpler DMA/I2S configuration, and a narrower test matrix.
*   **Cons:** A stereo-only firmware cannot drive an 8-channel central TDM DAC (CS42448 / PCM1681 / ADAU1452) with one shared clock, while a TDM-only firmware prevents prototyping or deploying room arrays from inexpensive stereo PCM5102A/MAX98357A boards.
*   **Reason for Rejection:** Rejected because a unified ESP-IDF C firmware (`spatialfin-esp32-sink`) sharing one Noise `KKpsk2`, 2D Kalman + fractional ASRC, and optional FLAC decode core with selectable `i2s_std_2ch` and `i2s_tdm_8ch` output drivers covers both hardware builds from one codebase.

### Alternative C: Splitting TV/WebXR, Native TLS, and ESP32 Firmware Into Separate Specs

*   **Pros:** Smaller single-document review footprint focused on already-deployed devices.
*   **Cons:** Delays defining shared additive protocol fields (`spki`, `CORS/PNA`, `POST /api/v1/ws/ticket`, `GET /api/v1/clients`, `POST /api/v1/room/controls`, `active_oamd_objects`, and binary/FLAC Noise framing), increasing risk of follow-up contract churn across `spatialfin-avr` and `SpatialFin`.
*   **Reason for Rejection:** Rejected because sequencing all nine workstreams across Phases A–D in one specification establishes a single additive contract while keeping each phase independently shippable.

### Alternative D: External JS Framework / CDN Bundle for `spatialfin-avr` Web UI

*   **Pros:** Off-the-shelf component libraries for QR codes, drag-and-drop canvases, and state management.
*   **Cons:** Requires either a Node/npm toolchain inside the Rust Docker multi-stage build or outbound internet CDN fetches (which fail on offline LANs and relax `Content-Security-Policy`).
*   **Reason for Rejection:** Rejected because `spatialfin-avr` already depends on the `qrcode` Rust crate for terminal QR output; rendering base64 SVG QR server-side (`data:image/svg+xml;base64,...`) and using native HTML5 SVG pointer events keeps the Web UI zero-dependency, offline-capable, and under existing CSP rules.

### Alternative E: Restarting Player Activity or Dual-Engine Make-Before-Break to Switch Rooms

*   **Pros:** Restarting the Activity reuses cold-start playback launch; dual-engine make-before-break overlaps startup of Room B before stopping Room A.
*   **Cons:** Restarting the Activity destroys XR spatial panel pose, wall/LCR anchors, `libass` subtitle state, and video buffers. Dual-engine make-before-break doubles server FFmpeg/DSP memory and requires shared-output conflict detection for <1 s LAN savings.
*   **Reason for Rejection:** Rejected in favor of serialized break-before-make inside `CastSessionManager`: keeps ExoPlayer, subtitles, and XR panel pose intact, hits the `<= 3 s` budget on LAN, and falls back to local device audio on target room failure without dual-engine complexity.

### Alternative F: Storing Music Assistant Credentials Inside `avr_state.json` and Echoing Them on `GET`

*   **Pros:** One fewer config file inside `<data_dir>`.
*   **Cons:** Exposes the Music Assistant Bearer token in any API response or diagnostic dump of `avr_state.json` and allows browser scripts to read back the raw secret.
*   **Reason for Rejection:** Rejected in favor of a dedicated `0600` `<data_dir>/ma_config.json` file with write-only masking (`has_token: bool` on `GET`), origin-change token protection, and a `disabled` tombstone, matching the secret isolation used by `sendspin_keys.json`.

### Alternative G: Manual Lip-Sync Slider Only or Closed-Loop Camera Flash Capture

*   **Pros:** A manual slider requires zero DSP/mic code; camera capture could directly measure display-panel latency.
*   **Cons:** A manual slider leaves new soundbar setups on guesswork; camera capture requires `CAMERA` permission, varies wildly with frame exposure/rolling shutter, and cannot measure an external TV when calibrating from an XR headset.
*   **Reason for Rejection:** Rejected because reusing existing WS `clock.ping` and `calibration.lip_sync_chirp` with microphone envelope onset detection and stored acoustic ToF subtraction measures pure room audio hardware delay (`fixed_hw_delay_us`) accurately without camera permissions, while preserving a `±5 ms` manual trim control for perceptual display fine-tuning.

### Alternative H: Migrating `avr_state.json` to SQLite WAL

*   **Pros:** Built-in transactional row locking and query indexing.
*   **Cons:** Adds C/SQLite build dependencies and schema migration tooling for a tiny document (`< 50 KB`, typically 1–6 rooms) that is already updated infrequently.
*   **Reason for Rejection:** Rejected because a single serialized coalescing atomic-rename writer (`temp + fsync + rename`) with `SIGTERM` shutdown flush eliminates multi-room clobbering and write amplification with zero new dependencies.

--------------------------------------------------------------------------------

## Risks, Limits & Mitigations

| Risk / Limit | Failure Mode / Constraint | Mitigation |
| :--- | :--- | :--- |
| **Concurrent Room CPU/RAM Ceiling** | Each active room runs an FFmpeg decode + `spatialfin-dsp-rt` + optional TrueHD/VBAP pipeline (~0.3–1.0 CPU core, ~40–120 MB RAM). | Document practical host sizing (up to 4 concurrent active rooms on a 4-core x86/ARM host); offload per-peer FLAC encoding to async writer tasks off the 10 ms RT audio thread. |
| **Acoustic Lip-Sync Noise & Reverb** | High ambient noise, HVAC rumble, or cold HDMI unmute delays (`> 1.5 s`) degrade chirp SNR or corrupt arrival timing. | Enforce `>= 1.5 s` silence lead-in, require `>= 3` of 5 pulses with `SNR >= 12 dB` and `IQR <= 10 ms`, reject `CalibrationCapture` calls lacking `TIMEBASE_BOOTTIME`, and fall back to manual `fixed_hw_delay_us` entry on failure. |
| **Flat LAN Admin Privilege Model & Plaintext HTTP** | Every paired playback client holds a Bearer token accepted by admin endpoints (`DELETE /api/v1/clients`, `PUT /api/v1/settings/music-assistant`); default HTTP sends tokens in cleartext on the LAN. | Document the trusted-home-LAN security boundary; enforce origin-change token rejection + connect-time `SsrfPolicy` on Music Assistant settings; display a warning banner in `web_ui.html` when entering secrets over non-localhost HTTP; offer opt-in built-in TLS (`G6`). |
| **Self-Signed TLS Browser & DHCP Limits** | Desktop browsers and hosted WebXR reject self-signed certs; DHCP IP changes can mismatch initial cert SANs. | Document that WebXR requires CA-backed PEM certs (Let's Encrypt / Tailscale Serve); keep LAN HTTP optional via `SPATIALFIN_AVR_TLS_PORT`; reuse `key.pem` on SAN re-signing and use SPKI-bound `HostnameVerifier` on Android so SPKI pins survive DHCP changes. |
| **ALSA Exclusive-Device Contention (`EBUSY`)** | Probing `hw:`/`hdmi:` while a room is playing fails with `EBUSY`. | Cache startup/idle ALSA probes in `LocalAlsaOutputManager`; return cached capabilities with `in_use: true` on `EBUSY` and suppress false 2-ch ARC warnings. |
| **ESP32-C5 RAM, No-FPU & Wi-Fi Contention** | ESP32-C5 has 384 KB internal SRAM and no hardware FPU; 8-ch 24-bit audio exceeds internal SRAM and TCP retries delay `clock_ping`. | Gate `i2s_tdm_8ch` on `>= 2 MB` PSRAM (benchmarking C5 vs dual-core S3), use `int64_t` fixed-point Kalman/Hermite ASRC, mandate `WIFI_PS_NONE` + `TCP_NODELAY` + RTT outlier gating (`rtt_us > 2 * min_rtt_us`), and use binary/FLAC framing. |
| **Room Switch Partial Failure** | Target room may be offline, busy (`409`), or unreachable after stopping the outgoing room. | Serialize switches with `Mutex` + `switchGeneration`, pause ExoPlayer before reading `posUs`, and fold back automatically to local device audio with an error toast on failure. |

--------------------------------------------------------------------------------

## Non-Functional Requirements

*   **Security & Privacy:**
    *   All new HTTP endpoints (`/api/v1/clients`, `/api/v1/room/controls`, `/api/v1/settings/music-assistant`, `/api/v1/ws/ticket`) enforce constant-time `Authorization: Bearer` checks via `authorize_headers`.
    *   `<data_dir>/ma_config.json` and `<data_dir>/tls/key.pem` are written atomically with `0600` Unix permissions via `atomic_write_0600`; tokens are wrapped in `RedactedSecret` in memory, never logged or echoed in HTTP responses, and never forwarded across `base_url` origin changes.
    *   Self-signed LAN TLS on Android validates exact leaf SPKI SHA-256 equality (`tlsSpkiSha256`) with a scoped SPKI-bound `HostnameVerifier` and sticky anti-downgrade (`requireTls`); trust-all `X509TrustManager` implementations are forbidden.
    *   `SPATIALFIN_AVR_CORS_ORIGINS` is empty by default (failing closed on `*` or malformed origins), PNA headers are returned only for allowlisted origins, `/ws/v1/control` validates `Origin` on upgrade, and `ma_bridge.rs` enforces `SsrfPolicy` at TCP connect time with HTTP redirects disabled.
    *   Web UI renders server-generated QR SVGs via `<img src="data:image/svg+xml;base64,...">` (a non-scriptable image context) rather than `innerHTML` and constructs all SVG floorplan nodes via `createElementNS` + `textContent`, preventing script execution from SVG payloads and maintaining existing CSP rules.
*   **Performance & Latency:**
    *   Discrete room toggles (`muted`, `eq_bypass`, `upmix_mode`) persist immediately while `master_volume` coalesces at `500 ms` inside one serialized writer with a `SIGTERM` shutdown flush.
    *   `LiveRendererSnapshot` uses lock-free atomic enums and counters updated at 1 Hz or on mode transitions, avoiding heap allocations and mutex locks on the 10 ms `spatialfin-dsp-rt` audio loop.
    *   In-player room switching targets `<= 3 s` total handoff from tap to locked audio playback on the new room.
    *   ESP32 8-channel TDM mode uses binary-framed lossless FLAC (`flacenc` -> `dr_flac`) offloaded to per-peer async writers to keep 5 GHz Wi-Fi network bitrate within `5–7 Mbps` (compared to `9.2 Mbps` raw 24-bit/48 kHz 8-ch PCM).
*   **Rollout & Rollback:**
    *   Every new JSON field in `avr_state.json` and Spec 0 HTTP/WS DTOs uses `#[serde(default)]` / Kotlin default values so upgrading or downgrading `spatialfin-avr` never invalidates existing `/data/avr_state.json` files or paired tokens.
    *   Built-in TLS (`SPATIALFIN_AVR_USE_TLS=false`) and CORS (`SPATIALFIN_AVR_CORS_ORIGINS=""`) default to off; rolling back requires only unsetting the env vars and restarting the container.
    *   ESP32 firmware and Android TV/WebXR support are additive and do not alter existing Galaxy XR or Pixel/Beam playback paths.

--------------------------------------------------------------------------------

## Additional Resources

*   **Spec 0 — Wire Protocol:** [theater-audio-protocol-v1.md](theater-audio-protocol-v1.md) and canonical JSON schemas/fixtures under `schemas/theater-audio/v1/` (mirrored in `$SPATIALFIN_AVR_DIR/schemas/theater-audio/v1/`).
*   **Spec 1 — Android Client Architecture:** [spatialfin-theater-audio-android-client.md](spatialfin-theater-audio-android-client.md).
*   **Spec 2 — Rust AVR Service & Hardware Bring-Up:** [spatialfin-avr-rust-service-and-hardware.md](spatialfin-avr-rust-service-and-hardware.md) and `$SPATIALFIN_AVR_DIR/hardware/README.md`.
*   **Existing Phase 0 Soak Runner & CI:** `$SPATIALFIN_AVR_DIR/scripts/phase0/run_gates.sh` and `$SPATIALFIN_AVR_DIR/.github/workflows/ci.yml`.
*   **Existing Chirp & AudioRecord Calibration Utilities:** `fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/calibration/{CalibrationCapture,ChirpGenerator,ChirpDetector}.kt`.

## Future Improvements

*   **Cross-Host Multi-AVR Synchronization:** Synchronizing playback across two physically separate `spatialfin-avr` server hosts using a shared PTP/NTP room-clock leader.
*   **Web UI Dark/Light Theme & Multi-Room Group Control:** Linking two rooms on the same `spatialfin-avr` host for synchronized multi-room playback from one source stream.
*   **Automated ESP-IDF CI Build Matrix:** Adding an `espressif/idf:release-v5.3` container job to `spatialfin-avr/.github/workflows/ci.yml` once firmware pinouts stabilize.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

### Additive Protocol & Wire Contracts (Reference)

All additions to Spec 0 `v=1` and the Noise `:8937` wire framing are optional and backward-compatible (`#[serde(default)]` in Rust, default values in Kotlin/TypeScript):

```text
HTTP REST Additions (all protected by Authorization: Bearer):
  GET  /api/v1/clients
       -> PairedClientsResponse {
            "clients": [PairedClientSummary {
              "client_id": String, "name": String, "created_s": u64, "is_current": bool
            }]
          }
  DELETE /api/v1/clients/{client_id}[?force=true]
       -> 204 No Content | 409 cannot_revoke_self | 403 cannot_revoke_static_admin | 404 client_not_found
  POST /api/v1/room/controls                   (header: X-Avr-Room-Id)
       <- RoomControlsUpdateRequest {
            "master_volume"?: f64, "muted"?: bool, "eq_bypass"?: bool, "upmix_mode"?: String
          }
       -> 200 AvrStatusResponse (empty {} = no-op) | 400 invalid_volume | 400 invalid_upmix_mode | 404 room_not_found
       (broadcasts WS event: "room.state_changed" { room_id, master_volume, muted, eq_bypass, upmix_mode })
  GET  /api/v1/settings/music-assistant
       -> MusicAssistantSettingsResponse {
            "configured": bool, "base_url": String, "has_token": bool,
            "auto_resume": bool, "source": "file" | "env" | "none"
          }
  PUT  /api/v1/settings/music-assistant
       <- MusicAssistantSettingsRequest {
            "base_url": String, "token"?: String, "clear_token"?: bool, "auto_resume": bool
          }
       -> 200 MusicAssistantSettingsResponse
          | 400 conflicting_token_flags | 400 token_required_on_origin_change | 400 ssrf_blocked
  POST /api/v1/ws/ticket                       (Phase C — WebXR browser WS auth)
       -> WsTicketResponse { "ticket": String, "expires_in_s": u64 }
       (consumed once on GET /ws/v1/control via Sec-WebSocket-Protocol: spatialfin-avr.v1, ticket.<ticket>)

Additive Fields on Existing Spec 0 DTOs:
  PairPinResponse (+?include_qr_svg=true)
                               += qr_svg_b64: Option<String>, tls_spki_sha256: Option<String>, tls_sas: Option<String>
  TelemetrySessionPayload      += active_oamd_objects: Option<u16>  (fallback_active: bool & fallback_reason already exist)
  AvrStatusResponse            += renderer_mode: Option<String>, active_oamd_objects: Option<u16>,
                                  fallback_active: Option<bool>, fallback_reason: Option<String>
  LocalAudioOutputDevice       += supported_formats: Vec<String>, supported_channel_counts: Vec<u32>,
                                  in_use: bool, capability_warning: Option<String>
  CalibrationLipSyncChirpPayload += bypass_hw_delay: bool (default false for legacy callers, true for auto-cal),
                                    lead_in_ms: Option<u32> (default 1500 for HDMI wake-up)

Android IPC Bridge Additions (:player:core SplitAvTheaterBridge):
  TheaterAudioTelemetry        += active_oamd_objects: Int? (JSON key: "active_oamd_objects")
  TheaterRoomSummaryContract    = { "receiver_id", "room_id", "room_name", "layout", "is_current" }
  TheaterSessionSnapshot       += available_rooms: List<TheaterRoomSummaryContract> (JSON key: "available_rooms")
  TheaterClientEvent.SwitchRoom = { "type": "switch_room", "receiver_id": String }

Noise :8937 Wire Extension (Phase D):
  Outer transport framing stays: u16_be(len)||client_id_utf8 -> Noise KKpsk2 u16_be handshake
                                 -> u32_be(chunk_count) + <= 56 KiB Noise ciphertext chunks
  Inner plaintext packet prefix (backward-compatible):
    0x7B ('{')  -> legacy JSON SendspinWireFrame (hello, clock_ping, clock_pong, audio_chunk, stream_clear, heartbeat)
    0x02        -> binary AudioChunk: [pts_us: i64_le][play_at_avr_us: i64_le][channels: u8][codec_tag: u8][raw_bytes...]
                   (codec_tag: 0 = pcm24le, 1 = flac_frame; 0x03 = flac_streaminfo header on init / StreamClear)
  SendspinWireFrame::Hello     += codec: String (default "pcm24"; "bin_pcm24" or "bin_flac" for ESP32 sinks)
```

Room-switch and lip-sync coordination flow (pseudocode):

```text
In-Player Room Switch (CastSessionManager collecting SplitAvController SwitchRoom events):
  on TheaterClientEvent.SwitchRoom(targetReceiverId):
    switchMutex.withLock:
      gen = ++switchGeneration
      if targetReceiverId == currentReceiverId: return
      wasPlaying = videoMaster.isPlaying()
      videoMaster.pauseAndAwaitSettled()
      posUs = videoMaster.currentPositionMs() * 1000
      if gen != switchGeneration: return
      splitAvController.stopActiveEngine(reason = "room_switch")
      if targetReceiverId == LOCAL_AUDIO_ID:
        videoMaster.unmuteLocalAudio()
        if wasPlaying: videoMaster.setPlaybackSpeed(1.0f); videoMaster.play()
        return
      target = resolvePairedTheaterReceiver(targetReceiverId)
      config = buildSendspinAvrSessionConfig(target, currentMedia, initialTargetPtsUs = posUs)
      result = splitAvController.startSendspinAvr(config)
      if result.isSuccess && gen == switchGeneration:
        pickCastReceiver(target)
        if wasPlaying: videoMaster.setPlaybackSpeed(1.0f); videoMaster.play()
      else:
        emitTheaterToast("Failed to switch room; using local audio")
        videoMaster.unmuteLocalAudio()
        if wasPlaying: videoMaster.setPlaybackSpeed(1.0f); videoMaster.play()

Acoustic Lip-Sync Auto-Calibration (TheaterLipSyncCalibrator over transient WS):
  transport = connectTransientWs(endpoint, token)
  deltaClockUs = SendspinAvrClockSync.measureMedianOffsetUs(transport, pings = 5)  # server_us - local_us
  tofUs = round((frontSpeakerDistanceMeters / 343.0) * 1_000_000)
  captureJob = async { CalibrationCapture(durationS = 9.5, UNPROCESSED -> MIC, TIMEBASE_BOOTTIME).capture() }
  resp = transport.sendLipSyncChirp(repeatCount = 5, spacingMs = 1500, leadInMs = 1500, bypassHwDelay = true)
  capture = captureJob.await()
  require(capture.usedAudioRecordTimestamp)
  onsets = ChirpDetector.detectEnvelopeMatched(capture.samples, 48000, serverLogChirp40msTemplate).onsets
  for (i, scheduledAvrUs) in resp.scheduledAvrUs.withIndex():
    onset = matchNearestOnset(onsets, expectedLocalUs = scheduledAvrUs - deltaClockUs)
    if onset != null && (20 * log10(onset.snr)) >= 12.0:
      heardUs = capture.captureStartBoottimeUs + round(onset.timeSeconds * 1_000_000)
      measuredHwDelayUs += (heardUs - (scheduledAvrUs - deltaClockUs) - tofUs)
  keep values within ±25 ms of median; require count >= 3 and IQR <= 10 ms
  clamp median to [0, 500_000] -> optional flash+chirp confirmation preview (±5 ms trim)
  user confirms -> PUT /api/v1/room/profile (updated fixed_hw_delay_us)
```

### Target Files to Create/Modify

#### Phase A — AVR Persistence, Live Telemetry, Runtime Settings, Web UI & Shared Schemas

*   `spatialfin-avr/schemas/theater-audio/v1/` & `SpatialFin/schemas/theater-audio/v1/` (**Modify**, canonical Spec 0 schemas & `SHA256SUMS`):
    *   Add optional additive properties to shared Spec 0 DTO schemas (`TelemetrySessionPayload`, `AvrStatusResponse`, `LocalAudioOutputDevice`, `PairPinResponse`, `RoomControlsUpdateRequest`, `CalibrationLipSyncChirpPayload`) and regenerate `SHA256SUMS` identically in both repos (Web-UI-only admin endpoints remain outside shared Spec 0 schemas).
    *   *Rationale:* Prevents silent DTO drift across Rust, Kotlin, and TypeScript clients while preserving `v=1` compatibility.
*   `spatialfin-avr/Cargo.toml` (**Modify**, workspace manifest):
    *   Promote `qrcode = { version = "0.14", default-features = false, features = ["svg"] }` to `[workspace.dependencies]`.
    *   *Rationale:* Enables server-side SVG QR rendering in `avr-protocol` without pulling in the `image` crate tree.
*   `spatialfin-avr/crates/avr-protocol/Cargo.toml` (**Modify**, protocol crate):
    *   Add `qrcode.workspace = true`.
    *   *Rationale:* Allows `auth.rs` to generate SVG QR payloads directly.
*   `spatialfin-avr/crates/avr-protocol/src/lib.rs` (**Modify**, protocol DTOs):
    *   Add `active_oamd_objects: Option<u16>` to `TelemetrySessionPayload` (keeping existing non-optional `fallback_active: bool` and `fallback_reason: Option<String>` intact).
    *   Add `renderer_mode`, `active_oamd_objects`, `fallback_active`, and `fallback_reason` to `AvrStatusResponse`.
    *   Add `supported_formats`, `supported_channel_counts`, `in_use`, and `capability_warning` to `LocalAudioOutputDevice`.
    *   Add `qr_svg_b64: Option<String>` to `PairPinResponse` and `bypass_hw_delay` / `lead_in_ms` to `CalibrationLipSyncChirpPayload`.
    *   Add `PairedClientSummary`, `PairedClientsResponse`, `RoomControlsUpdateRequest`, `RoomStateChangedPayload`, `MusicAssistantSettingsResponse`, and `MusicAssistantSettingsRequest`.
    *   *Rationale:* Establishes typed, additive Spec 0 `v=1` contracts for Phase A endpoints and telemetry.
*   `spatialfin-avr/crates/avr-protocol/src/auth.rs` (**Modify**, pairing & token manager):
    *   Add `pub fn paired_client_summaries(&self, current_client_id: Option<&str>) -> Vec<PairedClientSummary>` (preserving existing `paired_clients(&self) -> Vec<PairedClientRecord>`).
    *   Extend `arm_pin(...)` to optionally render base64-encoded SVG QR (`qr_svg_b64`) when requested (`include_qr_svg == true`).
    *   *Rationale:* Supports offline QR rendering in the Web UI and paired-device administration without colliding with existing persistence methods.
*   `spatialfin-avr/crates/avr-protocol/src/http.rs` (**Modify**, REST router & `ControlPlaneBackend` trait):
    *   Extend `ControlPlaneBackend` with `update_room_controls`, `get_music_assistant_settings`, and `put_music_assistant_settings`.
    *   Register `GET /api/v1/clients`, `POST /api/v1/room/controls`, `GET /api/v1/settings/music-assistant`, and `PUT /api/v1/settings/music-assistant` behind `authorize_headers`.
    *   Add `?force=true` self-revocation guard (`409 cannot_revoke_self`, `403 cannot_revoke_static_admin`) to `DELETE /api/v1/clients/{client_id}` and `?include_qr_svg=true` to `POST /api/v1/pair/pin`.
    *   *Rationale:* Exposes authenticated non-session REST endpoints with explicit error codes for the Web UI.
*   `spatialfin-avr/crates/avr-protocol/src/web_ui.html` (**Modify**, embedded Web UI):
    *   Render `<img src="data:image/svg+xml;base64,...">` in the Pair Device modal (`?include_qr_svg=true`).
    *   Add `2 s` `document.visibilityState === "visible"` polling of `GET /api/v1/status` with live codec/Atmos/drift readout and volume, mute, EQ bypass, and 4-mode upmix controls calling `POST /api/v1/room/controls`.
    *   Add interactive 2D top-down SVG floorplan built with `createElementNS` + `textContent` (`+X` right, `-Z` toward screen, `All / Floor / Height` layer filter, preserved `y` + inline numeric `y` input, auto-expanding bounds, Euclidean delay preview, `If-Match` save with `412` reload prompt).
    *   Display startup-cached ALSA `supported_formats`, `supported_channel_counts`, `in_use`, and `capability_warning` badges on Local Output cards.
    *   Add Server Settings tab with Paired Clients revocation table and write-only Music Assistant form (warning banner on non-localhost plaintext HTTP).
    *   *Rationale:* Completes zero-CDN LAN administration under existing CSP rules without fragile raw UTF-8 SVG URIs or WS reconnect complexity.
*   `spatialfin-avr/bins/spatialfin-avr/Cargo.toml` (**Modify**, daemon crate):
    *   Switch `qrcode` to `qrcode.workspace = true`.
    *   *Rationale:* Aligns workspace manifest dependencies while keeping `default-features = false`.
*   `spatialfin-avr/bins/spatialfin-avr/src/storage.rs` (**Modify**, disk persistence):
    *   Add `#[serde(default)]` runtime fields (`master_volume`, `muted`, `eq_bypass`, `upmix_mode`) to `PersistedRoomRecord`.
    *   Add `PersistedMusicAssistantConfig` (`base_url: Option<String>`, `token: Option<String>`, `auto_resume: bool`, `disabled: bool`) loaded/saved via `atomic_write_0600` at `<data_dir>/ma_config.json`.
    *   *Rationale:* Persists per-room runtime preferences backward-compatibly and isolates Music Assistant credentials with an explicit `disabled` tombstone.
*   `spatialfin-avr/bins/spatialfin-avr/src/spatial.rs` (**Modify**, spatial renderer trait & status):
    *   Add `pub active_oamd_objects: Option<u16>` to `RendererStatus` and return `None` in `IdentityBedRenderer::status()`.
    *   *Rationale:* Allows `runtime.rs` to read live object counts through `Box<dyn SpatialRenderer>`.
*   `spatialfin-avr/bins/spatialfin-avr/src/truehd_vbap.rs` (**Modify**, TrueHD Atmos renderer):
    *   Populate `RendererStatus.active_oamd_objects` in `TrueHdAtmosRenderer::status()` by counting dynamic objects in `latest_oamd` above `-inf` gain.
    *   *Rationale:* Surfaces verified live Atmos object rendering alongside existing `fallback_active` and `fallback_reason`.
*   `spatialfin-avr/bins/spatialfin-avr/src/dsp.rs` (**Modify**, DSP engine):
    *   Add `pub fn update_crossovers(&mut self, speakers: &[RoomSpeakerConfig])` mapping configured speaker `crossover_hz` values onto the 12 logical bus channels (`main_hp`, `main_lp`) and `lfe_apf` with a 10–20 ms gain ramp to prevent pops.
    *   *Rationale:* Applies persisted crossover changes acoustically without restarting playback.
*   `spatialfin-avr/bins/spatialfin-avr/src/runtime.rs` (**Modify**, real-time audio pipeline):
    *   Add lock-free `LiveRendererSnapshot` (`AtomicU8` `RendererMode` + `FallbackReasonCode`, `AtomicU16` `active_oamd_objects`, `AtomicU64` `decoded_oamd_payloads`) updated at startup, mode transitions, and 1 Hz cadence with zero allocations or mutex locks on `spatialfin-dsp-rt`.
    *   Propagate `LiveRuntimeControl` crossover updates into the active DSP thread's `TheaterDspEngine`.
    *   *Rationale:* Exposes real-time renderer diagnostics and live crossover edits without threatening the 10 ms RT audio loop.
*   `spatialfin-avr/bins/spatialfin-avr/src/local_alsa.rs` (**Modify**, host ALSA discovery):
    *   In `discover_local_alsa_outputs()` / `discover_local_alsa_outputs_blocking()`, probe `Format::s32()`, `Format::s24_3()`, `Format::s16()` and channel counts `2, 4, 6, 8` using fresh `HwParams::any(&pcm)` instances at startup and idle rescans; cache capabilities per device ID inside `LocalAlsaOutputManager` and return cached values with `in_use: true` on `EBUSY` (emitting `capability_warning` only when a clean probe confirmed `max_channels <= 2` on HDMI).
    *   *Rationale:* Prevents `EBUSY` probe failures and false 2-channel ARC warnings during active HDMI playback.
*   `spatialfin-avr/bins/spatialfin-avr/src/ma_bridge.rs` (**Modify**, Music Assistant HTTP client):
    *   Enforce `SsrfPolicy` at TCP connect time on resolved socket IPs and configure `reqwest::redirect::Policy::none()`.
    *   *Rationale:* Blocks DNS-rebinding and HTTP 302 redirect bypasses of `SsrfPolicy` and prevents forwarding the MA Bearer token to redirected hosts.
*   `spatialfin-avr/bins/spatialfin-avr/src/multi_room.rs` (**Modify**, multi-room backend):
    *   Hydrate each `RoomBackend` from `PersistedRoomRecord` (clamping `master_volume` to finite `[0.0, 1.0]`, validating `upmix_mode`, hydrating crossovers).
    *   Route all `avr_state.json` saves through a single serialized coalescing writer (immediate writes for discrete toggles/profile edits, `500 ms` coalescing for `master_volume`, synchronous flush inside `shutdown_all()`).
    *   Delegate new `ControlPlaneBackend` methods through `MultiRoomAvrBackend`, wire lock-free `LiveRendererSnapshot` into `status()` and `run_watchdog_tick()`, wrap Music Assistant state in `Arc<RwLock<RuntimeMusicAssistantState>>` with origin-change token protection (`400 token_required_on_origin_change`) and `disabled` tombstone, and add `bypass_hw_delay` + `>= 1.5 s` lead-in support to `play_lip_sync_chirp`.
    *   *Rationale:* Unifies persistence, live telemetry, REST controls, and secure Music Assistant reconfiguration across all rooms.
*   `spatialfin-avr/bins/spatialfin-avr/src/calibration_audio.rs` (**Modify**, calibration synthesis):
    *   Preserve existing `generate_lipsync_chirp_signal` (40 ms log chirp) verbatim while supporting `>= 1.5 s` silence lead-in before pulse #1 to wake cold HDMI/ARC soundbars.
    *   *Rationale:* Prevents clipping the first calibration chirp on idle HDMI soundbars without breaking existing WS chirp clients.
*   `spatialfin-avr/bins/spatialfin-avr/src/main.rs` (**Modify**, daemon bootstrap):
    *   Load `<data_dir>/ma_config.json` (respecting `disabled` tombstone, falling back to env vars with `source: "env"`), pass `RuntimeMusicAssistantState` to `MultiRoomAvrBackend::new_with_ma`, and await both `SIGINT` and Unix `SIGTERM` to flush dirty room state via `shutdown_all()` before exit.
    *   *Rationale:* Guarantees `docker compose restart` never drops coalesced volume edits.
*   `spatialfin-avr/bins/spatialfin-avr/tests/multi_room.rs` & `spatialfin-avr/crates/avr-protocol/tests/protocol_contract.rs` (**Modify**, Rust integration & contract tests):
    *   Test persisted room runtime hydration across restart + `SIGTERM` flush, crossover rebuilds, `GET /api/v1/clients` + self-revocation guards, `POST /api/v1/room/controls`, write-only `GET/PUT /api/v1/settings/music-assistant` (origin-change rejection, `SsrfPolicy`, `disabled` tombstone), `?include_qr_svg=true`, lock-free `LiveRendererSnapshot` failover telemetry, and `bypass_hw_delay` + 1.5 s lead-in on `play_lip_sync_chirp`.
    *   *Rationale:* Validates every Phase A contract and persistence invariant in CI.

#### Phase B — Acoustic Lip-Sync Auto-Calibration & In-Player Room Switcher (XR / Beam)

*   `SpatialFin/fcast/src/main/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrProtocolModels.kt` (**Modify**, `:fcast` protocol DTOs):
    *   Add `activeOamdObjects: Int? = null` to `TelemetrySessionPayload` (keeping existing `fallbackActive: Boolean` intact), add `rendererMode`/`activeOamdObjects`/`fallbackActive`/`fallbackReason` to `AvrStatusResponse`, add `supportedFormats`/`supportedChannelCounts`/`inUse`/`capabilityWarning` to `LocalAudioOutputDevice`, add `qrSvgB64`/`tlsSpkiSha256`/`tlsSas` to `PairPinResponse`, add `bypassHwDelay`/`leadInMs` to `CalibrationLipSyncChirpPayload`, and add `RoomControlsUpdateRequest`.
    *   *Rationale:* Keeps Android deserialization aligned with Phase A/C server DTOs.
*   `SpatialFin/fcast/src/main/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrHttpClient.kt` (**Modify**, `:fcast` HTTP client):
    *   Add `updateRoomControls(...)` helper (Phase C adds SPKI `OkHttpClient` selection on `Endpoint`).
    *   *Rationale:* Supports non-session room control updates over authenticated HTTP.
*   `SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/splitav/TheaterAudioTelemetry.kt` (**Modify**, `:player:core` telemetry contract):
    *   Add `val activeOamdObjects: Int? = null` with IPC JSON serialization (`"active_oamd_objects"`).
    *   *Rationale:* Carries live Atmos object count across the `:xrplayer` process boundary alongside existing `rendererMode`, `fallbackActive`, and `fallbackReason`.
*   `SpatialFin/player/core/src/main/java/dev/jdtech/jellyfin/player/core/splitav/SplitAvTheaterBridge.kt` (**Modify**, `:player:core` IPC bridge):
    *   Add `TheaterRoomSummaryContract` (`receiver_id`, `room_id`, `room_name`, `layout`, `is_current`), add `availableRooms: List<TheaterRoomSummaryContract> = emptyList()` (`"available_rooms"`) to `TheaterSessionSnapshot`, and add `TheaterClientEvent.SwitchRoom(val receiverId: String)` (`"type": "switch_room"`).
    *   *Rationale:* Enables XR (`:xrplayer`), Beam, and TV players to display paired rooms and request in-place room switches through the existing IPC bridge.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/SplitAvController.kt` (**Modify**, `:fcast:session-ui` engine controller):
    *   Forward `TheaterClientEvent.SwitchRoom` via a registered listener/SharedFlow to `CastSessionManager` (avoiding circular Hilt DI) and expose `stopActiveEngine(reason: String)`.
    *   *Rationale:* Preserves acyclic DI while keeping engine lifecycle inside `SplitAvController`.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/SendspinAvrSplitAvEngine.kt` (**Modify**, `:fcast:session-ui` AVR session engine):
    *   Map `activeOamdObjects` from `TelemetrySessionPayload` onto `TheaterAudioTelemetry` in every `TheaterSessionSnapshot` update.
    *   *Rationale:* Surfaces live Atmos object counts into XR, Beam, and TV player overlays.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/CastSessionManager.kt` (**Modify**, `:fcast:session-ui` session coordinator):
    *   Populate `availableRooms` on `TheaterSessionSnapshot` from paired online `theaterReceivers`.
    *   Collect `TheaterClientEvent.SwitchRoom` and execute serialized break-before-make transitions (`Mutex` + monotonic `switchGeneration`, pausing ExoPlayer and awaiting settlement before reading `posUs`, calling `splitAvController.stopActiveEngine("room_switch")`, resolving the target `CastReceiver` + `SendspinAvrSessionConfig`, calling `splitAvController.startSendspinAvr(...)`, resuming video at `1.0000×` if previously playing, and folding back to local audio on failure).
    *   *Rationale:* Targets `<= 3 s` two-tap room switching without circular DI, PTS skew, double-tap races, or stranded paused playback.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/calibration/CalibrationCapture.kt` (**Modify**, `:fcast:session-ui` mic capture):
    *   Add `val captureStartBoottimeUs: Long` (`AudioTimestamp.nanoTime / 1_000` minus sample-frame offset in microseconds) alongside existing `captureStartMonotonicMs: Long`.
    *   *Rationale:* Removes 1 ms timestamp quantization during acoustic lip-sync measurement without breaking existing FCast callers.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/calibration/ChirpDetector.kt` (**Modify**, `:fcast:session-ui` chirp correlator):
    *   Add `detectEnvelopeMatched(samples, sampleRateHz, template, thresholdDb = 12.0)` computing phase-invariant rectified/RMS envelope correlation and returning dB-calibrated SNR (`20 * log10(peak / noiseFloor)`).
    *   *Rationale:* Eliminates `lagStrideMs = 1` carrier aliasing on swept chirps and aligns SNR units cleanly to dB.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/calibration/TheaterLipSyncCalibrator.kt` (**Create**, `:fcast:session-ui` lip-sync coordinator):
    *   Opens a transient `SendspinAvrTransport` WS connection, measures `deltaClockUs` via `SendspinAvrClockSync` (5 RTT pings), triggers one 5-chirp `calibration.lip_sync_chirp` burst (`bypass_hw_delay = true`, `lead_in_ms = 1500`), captures once via `CalibrationCapture` (requiring `usedAudioRecordTimestamp == true`), runs `ChirpDetector.detectEnvelopeMatched(...)` against the 40 ms server log-chirp template, subtracts stored MLP-to-speaker acoustic ToF (`distance_m / 343.0`), rejects `±25 ms` outliers, enforces `>= 3` pulses with `IQR <= 10 ms` and `SNR >= 12 dB`, and clamps the resulting `fixed_hw_delay_us` to `[0, 500_000]`.
    *   *Rationale:* Reuses existing WS RPCs, `SendspinAvrClockSync`, `CalibrationCapture`, and `ChirpDetector` to target `±5 ms` room audio hardware delay measurement.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/calibration/TheaterCalibrationViewModel.kt` & `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/calibration/TheaterCalibrationScreen.kt` (**Modify**, `:fcast:session-ui` calibration UI):
    *   Add **Auto-Calibrate Lip-Sync** workflow (suspending XR voice capture during recording, displaying per-pulse spread/confidence, providing an optional post-calibration flash+chirp preview with `±5 ms` trim nudges, and persisting confirmed `fixed_hw_delay_us` via `PUT /api/v1/room/profile`).
    *   Render ALSA `supportedFormats`, `supportedChannelCounts`, `inUse`, and `capabilityWarning` badges in Local Output cards.
    *   *Rationale:* Gives users one in-app calibration workspace for speakers, HDMI capability diagnostics, and acoustic lip-sync timing.
*   `SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlayerControlPanel.kt`, `SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/SpatialPlayerScreen.kt`, & `SpatialFin/player/xr/src/main/java/dev/jdtech/jellyfin/player/xr/PlaybackStatsOverlay.kt` (**Modify**, `:player:xr` HUD):
    *   Add compact Theater room switcher pill (`Room: <name> ▾`) with inline picker row emitting `TheaterClientEvent.SwitchRoom` (including `"This Device"` fold-back); cycle all 4 upmix modes (`native`, `all_channel_stereo`, `dts_height_upmix`, `neural_height`); render live Atmos/OAMD badge (`Atmos VBAP · N obj` or `Bed Fallback`) in both the Theater strip and `PlaybackStatsOverlay`.
    *   *Rationale:* Exposes multi-room switching, full upmix selection, and live Atmos verification inside Full Space playback.
*   `SpatialFin/player/beam/src/main/java/dev/jdtech/jellyfin/player/beam/BeamPlayerActivity.kt` (**Modify**, `:player:beam` HUD):
    *   Add matching room switcher pill (bottom sheet picker), 4-mode upmix cycle, and Atmos/OAMD telemetry badge to Beam's Theater Audio player overlay.
    *   *Rationale:* Keeps XR and phone/tablet Theater controls consistent.
*   `SpatialFin/player/core/src/test/java/dev/jdtech/jellyfin/player/core/splitav/SplitAvBridgeIpcTest.kt`, `SpatialFin/fcast/session-ui/src/test/java/dev/spatialfin/fcast/session/SplitAvEngineSendspinAvrTest.kt`, & `SpatialFin/fcast/session-ui/src/test/java/dev/spatialfin/fcast/session/calibration/CalibrationPureTest.kt` (**Modify**, Android unit tests):
    *   Verify `SwitchRoom`, `availableRooms`, and `activeOamdObjects` IPC round-trip; verify `CastSessionManager` serialized room-switch sequencing and failure fallback; verify `ChirpDetector.detectEnvelopeMatched` and `TheaterLipSyncCalibrator` synthetic chirp residual/ToF/IQR/SNR assertions (within `±5 ms` of injected synthetic delay).
    *   *Rationale:* Validates multi-process IPC stability and acoustic calibration math deterministically on the JVM.

#### Phase C — Optional Dual-Mode TLS, CORS/PNA, Browser WS Tickets, Android TV & WebXR Theater Audio

*   `spatialfin-avr/Cargo.toml` (**Modify**, workspace manifest):
    *   Add `tower-http = { version = "0.6", features = ["cors"] }`, `axum-server = { version = "0.7", features = ["tls-rustls"] }`, `rcgen = "0.13"`, and `rustls = { version = "0.23", default-features = false, features = ["ring"] }`.
    *   *Rationale:* Adds opt-in TLS, self-signed certificate generation, and CORS allowlisting in Phase C without coupling earlier phases.
*   `spatialfin-avr/crates/avr-protocol/Cargo.toml` & `spatialfin-avr/bins/spatialfin-avr/Cargo.toml` (**Modify**, crate manifests):
    *   Add `tower-http.workspace = true` to `avr-protocol`; add `axum-server.workspace = true`, `rcgen.workspace = true`, and `rustls.workspace = true` to `spatialfin-avr`.
    *   *Rationale:* Keeps TLS/CORS dependencies scoped to the crates that use them.
*   `spatialfin-avr/crates/avr-protocol/src/auth.rs` (**Modify**, WS ticket store & TLS pairing metadata):
    *   Add bounded in-memory TTL single-use WS ticket map (`issue_ws_ticket` / `consume_ws_ticket`, 15 s TTL); extend `arm_pin(...)` with optional `tls_spki_sha256: Option<&str>` (`URL_SAFE_NO_PAD` `&spki=` in `qr_uri` and 8-char `tls_sas` on `PairPinResponse`).
    *   *Rationale:* Enables browser WebSocket auth without query-string Bearer tokens and supports both QR SPKI pinning and TV SAS confirmation.
*   `spatialfin-avr/crates/avr-protocol/src/http.rs` & `spatialfin-avr/crates/avr-protocol/src/ws.rs` (**Modify**, CORS/PNA & browser WS upgrade):
    *   Register `POST /api/v1/ws/ticket` behind `authorize_headers`; attach strict `CorsLayer` + Chromium PNA preflight middleware (`Access-Control-Allow-Private-Network: true`) when `cors_origins` is non-empty (failing closed on `*` or malformed origins).
    *   In `ws.rs`, validate the `Origin` header (same-origin or allowlisted CORS origin) against CSWSH and accept `Sec-WebSocket-Protocol: spatialfin-avr.v1, ticket.<id>` alongside native `Authorization: Bearer`.
    *   *Rationale:* Unblocks HTTPS browser WebXR connections safely under Chromium Private Network Access and CSWSH rules.
*   `spatialfin-avr/bins/spatialfin-avr/src/config.rs` (**Modify**, CLI/env config):
    *   Add `tls_cert` (`SPATIALFIN_AVR_TLS_CERT`), `tls_key` (`SPATIALFIN_AVR_TLS_KEY`), `tls_port` (`SPATIALFIN_AVR_TLS_PORT`), `disable_http` (`SPATIALFIN_AVR_DISABLE_HTTP`), and `cors_origins` (`SPATIALFIN_AVR_CORS_ORIGINS`).
    *   *Rationale:* Keeps TLS and CORS opt-in via env vars or CLI flags with optional dual HTTP/HTTPS port coexistence.
*   `spatialfin-avr/bins/spatialfin-avr/src/tls.rs` (**Create**, dual-mode TLS loader) & `spatialfin-avr/bins/spatialfin-avr/src/lib.rs` (**Modify**, crate root):
    *   Export `pub mod tls;` in `lib.rs`.
    *   Load user PEM cert/key via `RustlsConfig::from_pem_file` (fail fast if half-configured; spawn `SIGHUP`/mtime hot-reload task calling `reload_from_pem_file`), or load/generate a 10-year ECDSA P-256 self-signed cert in `<data_dir>/tls/{cert.pem,key.pem}` (`0600`) reusing persisted `key.pem` across SAN re-signing when `advertised_host` changes and returning `tls_spki_sha256` (`URL_SAFE_NO_PAD`) + 8-char SAS.
    *   *Rationale:* Provides zero-proxy HTTPS/WSS for both CA-backed WebXR and SPKI-pinned Android LAN deployments.
*   `spatialfin-avr/bins/spatialfin-avr/src/main.rs` (**Modify**, TLS listener & local CLI subcommands):
    *   Install `rustls::crypto::ring::default_provider()`, bind `axum_server::bind_rustls` on `tls_port` (or `port` when `disable_http == true` / `tls_port` unset), pass `cors_origins` + `tls_spki_sha256` into `ControlPlaneState`/`AuthManager`, and update `Commands::Healthcheck` and `Commands::PairPin` to use HTTPS trusting the local PEM cert when HTTP is disabled.
    *   *Rationale:* Keeps Docker `HEALTHCHECK` and CLI `pair-pin` functional under TLS-only mode.
*   `spatialfin-avr/docker/Dockerfile` & `spatialfin-avr/docker-compose.yaml` (**Modify**, container packaging):
    *   Document optional `SPATIALFIN_AVR_TLS_CERT`, `SPATIALFIN_AVR_TLS_KEY`, `SPATIALFIN_AVR_TLS_PORT`, `SPATIALFIN_AVR_DISABLE_HTTP`, and `SPATIALFIN_AVR_CORS_ORIGINS` env vars and verify `/data/tls` inherits UID `1000:1000`.
    *   *Rationale:* Allows containerized deployments to enable TLS/CORS with mounted or auto-generated certificates.
*   `spatialfin-avr/bins/spatialfin-avr/tests/tls_and_controls.rs` (**Create**, Rust integration tests):
    *   Tests self-signed ECDSA cert generation (`0600` key, keypair reuse on SAN change, SPKI match), PEM load + reload, half-configured PEM fail-fast, HTTPS `healthcheck`, strict CORS + Chrome PNA preflight headers, and `POST /api/v1/ws/ticket` + `Origin` CSWSH enforcement.
    *   *Rationale:* Guards security-sensitive TLS, PNA, CORS, and WS ticket invariants in CI.
*   `SpatialFin/settings/src/main/java/dev/jdtech/jellyfin/settings/domain/TheaterAvrTokenStore.kt` & `SpatialFin/settings/src/test/java/dev/jdtech/jellyfin/settings/domain/TheaterAvrTokenStoreTest.kt` (**Modify**, `:settings` secure token store & tests):
    *   Add optional `tlsSpkiSha256: String? = null` and `requireTls: Boolean = false` to `StoredAvrToken` with backward-compatible JSON persistence and unit tests.
    *   *Rationale:* Persists per-server SPKI pins and sticky anti-downgrade state alongside Bearer tokens in `SharedPreferences` encrypted via `AndroidKeyStore` AES-256-GCM + AAD.
*   `SpatialFin/fcast/src/main/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrTlsPinning.kt` (**Create**, `:fcast` SPKI pinning) & `SpatialFin/fcast/src/main/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrHttpClient.kt`, `SpatialFin/fcast/src/main/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrTransport.kt` (**Modify**, `:fcast` HTTP/WS clients):
    *   Add `tlsSpkiSha256: String? = null` to `SendspinAvrHttpClient.Endpoint`; implement strict leaf SPKI SHA-256 `X509TrustManager` + scoped SPKI-bound `HostnameVerifier` (rejecting mismatches with a typed pin-mismatch exception; never trust-all) and apply to both HTTP and `wss://` WebSocket clients when `useTls && tlsSpkiSha256 != null`.
    *   *Rationale:* Enables encrypted LAN transport without external CA setup while maintaining strict pin validation across DHCP IP changes.
*   `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/TheaterPairingDialog.kt`, `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/SplitAvSessionEngine.kt`, & `SpatialFin/fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/CastSessionManager.kt` (**Modify**, `:fcast:session-ui` pairing & session plumbing):
    *   Add `tlsSpkiSha256: String? = null` to `TheaterPairingRequest` (`TheaterPairingDialog.kt`) and `SendspinAvrSessionConfig` (`SplitAvSessionEngine.kt`); parse `&spki=` in `parseTheaterPairQrUri` (`CastSessionManager.kt`), enforce sticky `requireTls` anti-downgrade on mDNS rediscovery, and surface `TheaterHudBanner(code = "tls_pin_mismatch", requiresRepair = true)` on pin mismatch.
    *   *Rationale:* Completes end-to-end SPKI pin propagation and anti-downgrade UX without breaking existing pairing dialogs.
*   `SpatialFin/fcast/src/test/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrTlsPinningTest.kt` (**Create**), `SpatialFin/fcast/src/test/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrHttpClientTest.kt`, `SpatialFin/fcast/src/test/java/dev/jdtech/jellyfin/cast/adapter/sendspinavr/SendspinAvrTransportTest.kt`, & `SpatialFin/fcast/src/test/java/dev/jdtech/jellyfin/cast/TheaterAudioSchemaContractTest.kt` (**Modify**, `:fcast` unit tests):
    *   Verify exact SPKI match vs mismatch rejection, `&spki=` QR URI parsing, sticky anti-downgrade, and all new Spec 0 additive DTO fields against mirrored golden schemas.
    *   *Rationale:* Locks down TLS pinning security and schema parity on the JVM.
*   `SpatialFin/shell/tv/build.gradle.kts` (**Modify**, TV shell Gradle module):
    *   Add `implementation(projects.fcast)` and `implementation(projects.fcast.sessionUi)` (do **not** add `:fcast:session-ui` to `player/tv/build.gradle.kts`).
    *   *Rationale:* Reuses shared Theater Audio discovery, pairing, and Split-A/V orchestration in `:shell:tv` while keeping `:player:tv` decoupled from XR Compose dependencies.
*   `SpatialFin/app/unified/src/main/java/dev/spatialfin/unified/UnifiedMainActivity.kt` (**Modify**, unified TV root wiring):
    *   Pass injected `fcastSession: CastSessionManager` into `TvNavigationRoot` and provide `LocalFCastSession` in the `DeviceClass.TV` `CompositionLocalProvider`.
    *   *Rationale:* Supplies `TvNavigationRoot` with the shared `CastSessionManager` singleton matching XR and Beam.
*   `SpatialFin/shell/tv/src/main/java/dev/spatialfin/tv/TvTheaterAudioDialog.kt` (**Create**, TV UI), `SpatialFin/shell/tv/src/main/java/dev/spatialfin/tv/TvNavigationRoot.kt`, `SpatialFin/shell/tv/src/main/java/dev/spatialfin/tv/TvTopBar.kt`, & `SpatialFin/shell/tv/src/main/java/dev/spatialfin/tv/TvOverflowSheet.kt` (**Modify**, `:shell:tv`):
    *   Expose a D-pad-focusable **Theater Audio** entry opening `TvTheaterAudioDialog` (mDNS room list, manual `host:port`, 6-digit PIN, and 8-character SAS confirmation when self-signed TLS is active), and launch playback via `dev.spatialfin.fcast.session.launchPlayback(...)`.
    *   *Rationale:* Brings shared multi-room AVR discovery, pairing, and selection to Android TV with full D-pad navigation.
*   `SpatialFin/player/tv/src/main/java/dev/jdtech/jellyfin/player/tv/TvPlayerActivity.kt` (**Modify**, `:player:tv`):
    *   Add `EXTRA_SPLIT_AV_VIDEO_ROLE` and `EXTRA_SPLIT_AV_HOLD_AT_START` intent extras, attach `PlayerSplitAvAdapter` via `:player:core` `SplitAvVideoBridge` / `SplitAvTheaterBridge`, intercept remote `KEYCODE_VOLUME_UP/DOWN/MUTE` while Theater Audio is active, gate `playback.ready` on HDMI display refresh-rate settlement (`<= 2.5 s` cap), and render a D-pad-accessible Theater HUD row reusing Beam's Theater HUD controls.
    *   *Rationale:* Reuses the same Split-A/V playback synchronization and core room controls on Android TV as on Beam and XR.
*   `SpatialFin/spatialfin-webxr/src/theater/TheaterAvrClient.ts` & `SpatialFin/spatialfin-webxr/src/theater/TheaterAvrUi.ts` (**Create**, WebXR Theater client & UI):
    *   Implement Spec 0 `v=1` HTTP + `POST /api/v1/ws/ticket` + `Sec-WebSocket-Protocol` WebSocket client, `localStorage` persistence, HTTPS mixed-content guard, manual `host:port` + PIN/token UI, `video.requestVideoFrameCallback()` `2 Hz` `playback.video_timing` + Web Worker/frame `2 s` `session.heartbeat`, and credential-free direct Jellyfin `stream_url` construction.
    *   *Rationale:* Extends shared multi-room Theater Audio to browser and headset WebXR sessions safely under browser networking policies.
*   `SpatialFin/spatialfin-webxr/src/main.ts`, `SpatialFin/spatialfin-webxr/index.html`, `SpatialFin/spatialfin-webxr/src/BrowserApp.ts`, `SpatialFin/spatialfin-webxr/src/PlayerSpace.ts`, & `SpatialFin/spatialfin-webxr/src/style.css` (**Modify**, `spatialfin-webxr/`):
    *   Mount `TheaterAvrUi` in `#cast-selection-dialog` and Settings via `main.ts` / `index.html`, and wire `TheaterAvrClient` into both `PlayerSpace.ts` (3D WebXR) and `BrowserApp.ts` (2D `<video>` playback).
    *   *Rationale:* Ensures WebXR Theater Audio works across both 2D browser and immersive WebXR playback modes.
*   `SpatialFin/spatialfin-webxr/test/theater-avr.test.mjs` (**Create**, WebXR unit tests):
    *   Tests `TheaterAvrClient` URL normalization, mixed-content guard, room state persistence, WS ticket subprotocol formatting, and Spec 0 WS envelopes under `npm test` (importing compiled JS output or Node `--experimental-strip-types`, matching `test/auth.test.mjs`).
    *   *Rationale:* Validates WebXR Theater protocol logic in Node without needing a live AVR server.

#### Phase D — Unified ESP32-C5/S3 Sink Firmware, Hardware Soak Suite & CI Expansion

*   `spatialfin-avr/crates/avr-protocol/src/sendspin_wire.rs` (**Modify**, Noise `:8937` wire framing):
    *   Add `#[serde(default = "default_codec_pcm24")] pub codec: String` to `SendspinWireFrame::Hello` and add compact binary audio packet framing (`0x02` audio chunk, `0x03` FLAC `STREAMINFO` header) alongside legacy `0x7B` JSON frames.
    *   *Rationale:* Eliminates ~3.5× JSON integer-array overhead on 8-channel audio while keeping wired `spatialfin-sink` backward-compatible on `pcm24`.
*   `spatialfin-avr/bins/spatialfin-sink/src/lib.rs` (**Modify**, Linux reference sink):
    *   Supply `codec: "pcm24".to_string()` in `SendspinWireFrame::Hello` initializer.
    *   *Rationale:* Keeps the workspace binary compiling after adding `codec` to `Hello`.
*   `spatialfin-avr/bins/spatialfin-avr/src/sendspin.rs` & `spatialfin-avr/bins/spatialfin-avr/tests/sendspin_mock.rs` (**Modify**, Noise audio hub & tests):
    *   Track negotiated codec (`pcm24`, `bin_pcm24`, `bin_flac`) and assigned channel pair per connected `client_id`; run stateful `flacenc` (`48 kHz`, 24-bit, `480`-frame blocks, `max_lpc_order = 4`, persistent `STREAMINFO` reset on `stream_clear`) inside each peer's async writer task off `spatialfin-dsp-rt`; add round-trip tests covering JSON `pcm24`, `bin_pcm24`, and `bin_flac`.
    *   *Rationale:* Keeps 8-channel 24-bit/48 kHz Wi-Fi bitrate within `5–7 Mbps` without blocking the 10 ms DSP loop.
*   `spatialfin-avr/firmware/esp32-sink/CMakeLists.txt`, `spatialfin-avr/firmware/esp32-sink/sdkconfig.defaults`, `spatialfin-avr/firmware/esp32-sink/partitions.csv`, `spatialfin-avr/firmware/esp32-sink/README.md`, `spatialfin-avr/firmware/esp32-sink/main/CMakeLists.txt`, `spatialfin-avr/firmware/esp32-sink/main/idf_component.yml`, `spatialfin-avr/firmware/esp32-sink/main/app_main.c`, `spatialfin-avr/firmware/esp32-sink/main/config_store.h`, `spatialfin-avr/firmware/esp32-sink/main/config_store.c`, `spatialfin-avr/firmware/esp32-sink/main/noise_client.h`, `spatialfin-avr/firmware/esp32-sink/main/noise_client.c`, `spatialfin-avr/firmware/esp32-sink/main/kalman_jitter.h`, `spatialfin-avr/firmware/esp32-sink/main/kalman_jitter.c`, `spatialfin-avr/firmware/esp32-sink/main/flac_decoder.h`, `spatialfin-avr/firmware/esp32-sink/main/flac_decoder.c`, `spatialfin-avr/firmware/esp32-sink/main/dr_flac.h`, `spatialfin-avr/firmware/esp32-sink/main/i2s_output.h`, & `spatialfin-avr/firmware/esp32-sink/main/i2s_output.c` (**Create**, unified ESP-IDF v5.3+ C firmware):
    *   Implements `esp32c5` and `esp32s3` Noise `KKpsk2` `:8937` client (`noise-c` + `espressif/libsodium`, `WIFI_PS_NONE`, `TCP_NODELAY`, NVS `sfin_sink` + LittleFS `/littlefs/sink_psk.json`), fixed-point `int64_t` 2D Kalman + Hermite fractional ASRC (`±150 ppm`, RTT outlier gating), persistent `dr_flac.h` decoding with 5 ms ramp on `stream_clear`, and selectable `i2s_std_2ch` / `i2s_tdm_8ch` (`>= 2 MB` PSRAM required for 8-ch TDM) output drivers with default GPIO pinouts.
    *   *Rationale:* Delivers a deployable single-codebase firmware for both multi-node stereo arrays and central 7.1 TDM DAC hardware.
*   `spatialfin-avr/hardware/README.md` (**Modify**, hardware bring-up guide):
    *   Add `esp32c5`/`esp32s3` GPIO pinout tables, PSRAM requirements for 8-ch TDM, DAC wiring diagrams (PCM5102A/MAX98357A stereo and CS42448/PCM1681 8-ch TDM), ESP-IDF build/flash commands, and PSK JSON provisioning steps.
    *   *Rationale:* Documents repeatable bench bring-up for off-the-shelf dev boards.
*   `spatialfin-avr/bins/spatialfin-avr/tests/phase0_gates.rs`, `spatialfin-avr/bins/spatialfin-avr/tests/sendspin_ws.rs`, & `spatialfin-avr/scripts/phase0/run_gates.sh` (**Modify**, hardware soak tests & runner):
    *   Replace the 4-line env-var stubs in `phase0_gates.rs` and expand `sendspin_ws.rs` with real soak/drift/flush/ingest assertions for Gates `0A`, `0B-Noise`, `0B-WS`, and `0C`, and update `scripts/phase0/run_gates.sh` to execute only configured gates with a structured pass/fail/skipped report.
    *   *Rationale:* Aligns the hardware soak harness with the 30-minute underrun, `<= 500 µs` drift, and `StreamClear` acceptance criteria.
*   `spatialfin-avr/.github/workflows/ci.yml` (**Modify**, GitHub Actions CI):
    *   Add `cargo test -p spatialfin-avr --features alsa-native --test multi_room` (reusing existing `libasound2-dev` + `pkg-config`, hardware-free), multi-block `matchAll` + `vm.Script` Web UI JS syntax check, and a host-side container smoke check (`docker run -d -p 1986:1986` + `curl -sfI http://127.0.0.1:1986/` verifying HTTP `200` and `Content-Security-Policy`).
    *   *Rationale:* Catches ALSA-feature compilation regressions, Web UI JS syntax errors, and CSP regressions automatically on every PR.

#### Documentation & User Guides Sync

*   `spatialfin-avr/README.md` (**Modify**, server documentation):
    *   Document persisted room state, upgraded Web UI administration, dual-mode TLS/CORS/PNA env vars, ESP32-C5/S3 firmware, and `scripts/phase0/run_gates.sh`.
    *   *Rationale:* Keeps server operator documentation aligned with all nine planned capabilities.
*   `SpatialFin/GEMINI.md`, `SpatialFin/README.md`, `SpatialFin/site/pages/guides/casting-music.html`, & `SpatialFin/site/pages/guides/webxr.html` (**Modify**, technical reference & user guides):
    *   Update architecture reference and user guides to reflect all nine planned capabilities (rebuilt and validated via `node site/build.mjs && node site/verify.mjs`).
    *   *Rationale:* Keeps contributor context and published website guides synchronized with shipped code per `GEMINI.md` policy.

--------------------------------------------------------------------------------

### Verification Plan

Commands below assume `SPATIALFIN_DIR` and `SPATIALFIN_AVR_DIR` point to local checkouts of `SpatialFin` and `spatialfin-avr`.

#### 1. Compilation & Build Verification

*   **`spatialfin-avr` (Rust workspace, native ALSA & Docker):**
    ```bash
    cd "$SPATIALFIN_AVR_DIR"
    cargo build --workspace
    cargo build -p spatialfin-avr --features alsa-native
    docker compose config -q
    docker build -f docker/Dockerfile -t spatialfin-avr:verify .
    ```
*   **`SpatialFin` (Android XR, Beam & TV flavors):**
    ```bash
    cd "$SPATIALFIN_DIR"
    ./gradlew :settings:assembleDebug :fcast:assembleDebug :fcast:session-ui:assembleDebug \
      :player:core:assembleDebug :player:xr:assembleDebug :player:beam:assembleDebug \
      :player:tv:assembleDebug :shell:tv:assembleDebug \
      :app:unified:assembleLibreDebug :app:unified:assembleTvDebug
    ```
*   **`spatialfin-webxr` (TypeScript + Vite production bundle):**
    ```bash
    cd "$SPATIALFIN_DIR/spatialfin-webxr"
    npm run build
    ```
*   **ESP32 Firmware (when ESP-IDF v5.3+ is sourced):**
    ```bash
    cd "$SPATIALFIN_AVR_DIR/firmware/esp32-sink"
    idf.py set-target esp32c5 && idf.py build
    idf.py set-target esp32s3 && idf.py build
    ```
*   **Website Docs Build:**
    ```bash
    cd "$SPATIALFIN_DIR"
    node site/build.mjs && node site/verify.mjs
    ```

#### 2. Unit & Contract Testing

*   **Rust protocol, persistence, DSP, TLS/CORS/PNA/WS tickets, binary/FLAC framing, and multi-room tests:**
    ```bash
    cd "$SPATIALFIN_AVR_DIR"
    cargo test --workspace
    cargo test -p spatialfin-avr --features alsa-native --test multi_room
    ```
    *   Verifies: `avr_state.json` restart hydration & `SIGTERM` flush, logical-bus crossover rebuilds, `GET /api/v1/clients` & self-revocation guards, `POST /api/v1/room/controls`, write-only `GET/PUT /api/v1/settings/music-assistant` (origin-change guard, `SsrfPolicy`, `disabled` tombstone), `?include_qr_svg=true`, lock-free `LiveRendererSnapshot` object count & bed failover, startup-cached ALSA `EBUSY` handling, dual-mode TLS + `healthcheck` + CORS/PNA + WS tickets + `Origin` validation, and JSON `pcm24` / `bin_pcm24` / `bin_flac` Noise framing.
*   **Android JVM unit tests:**
    ```bash
    cd "$SPATIALFIN_DIR"
    ./gradlew :settings:testDebugUnitTest :fcast:testDebugUnitTest \
      :fcast:session-ui:testDebugUnitTest :player:core:testDebugUnitTest \
      :player:xr:testDebugUnitTest :player:beam:testDebugUnitTest \
      :player:tv:testDebugUnitTest :shell:tv:testDebugUnitTest
    ```
    *   Verifies: `TheaterAvrTokenStoreTest` SPKI & sticky `requireTls` round-trip, `SendspinAvrTlsPinningTest` exact match/mismatch enforcement, `SendspinAvrHttpClientTest` & `TheaterAudioSchemaContractTest` additive DTOs & `&spki=` parsing, `SplitAvBridgeIpcTest` `SwitchRoom` / `availableRooms` / `activeOamdObjects` JSON round-trip, `SplitAvEngineSendspinAvrTest` serialized room-switch & local fallback, and `CalibrationPureTest` synthetic chirp residual/ToF/IQR/SNR assertions (within `±5 ms` of the injected synthetic delay).
*   **WebXR unit tests:**
    ```bash
    cd "$SPATIALFIN_DIR/spatialfin-webxr"
    npm test
    ```
    *   Verifies: `test/theater-avr.test.mjs` mixed-content guard, room storage, WS ticket subprotocol formatting, and Spec 0 WS message envelopes.

#### 3. Static Analysis & Linting

*   **Schema checksum integrity, cross-repo parity & Rust lints:**
    ```bash
    cd "$SPATIALFIN_AVR_DIR/schemas/theater-audio/v1" && sha256sum -c SHA256SUMS
    diff -ru "$SPATIALFIN_DIR/schemas/theater-audio/v1" "$SPATIALFIN_AVR_DIR/schemas/theater-audio/v1"
    cd "$SPATIALFIN_AVR_DIR" && cargo clippy --workspace --all-targets -- -D warnings
    ```
*   **Embedded Web UI JavaScript syntax validation (all `<script>` blocks):**
    ```bash
    cd "$SPATIALFIN_AVR_DIR"
    node -e '
      const fs = require("fs"), vm = require("vm");
      const html = fs.readFileSync("crates/avr-protocol/src/web_ui.html", "utf8");
      const blocks = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)];
      if (blocks.length === 0) throw new Error("Missing <script> block");
      blocks.forEach((m, i) => new vm.Script(m[1], { filename: `web_ui.html#script-${i}` }));
      console.log(`web_ui.html JS syntax OK (${blocks.length} block(s))`);
    '
    ```

#### 4. Integration, Live Device & Hardware Soak Verification

*   **Container Restart Persistence & Host-Side Web UI CSP Smoke Test:**
    *   Run `spatialfin-avr:verify` with a persistent `/data` volume mount and mapped port (`-p 1986:1986 -v <tmp>:/data`), verify host-side `curl -sfI http://127.0.0.1:1986/` returns `200 OK` with `Content-Security-Policy`, update room volume/mute/upmix via authenticated `POST /api/v1/room/controls` (with `Authorization: Bearer` and `X-Avr-Room-Id`), restart the container (`SIGTERM`), and assert authenticated `GET /api/v1/status` (same `X-Avr-Room-Id`) returns the persisted values.
*   **Physical Device Verification (Galaxy XR, Pixel/Beam, Android TV, Desktop Browser):**
    *   Scan Web UI inline base64 SVG QR from Galaxy XR (both plain HTTP and self-signed TLS `&spki=` mode) and pair Android TV via 6-digit PIN + 8-character SAS confirmation.
    *   Switch active movie playback between two configured rooms (e.g., `Big Bedroom` and `Living Room`) from XR and Beam player HUDs and confirm `<= 3 s` handoff without Activity restart, plus automatic fallback to local audio if a target room is unreachable.
    *   Run **Auto-Calibrate Lip-Sync** on Pixel/Beam at the MLP facing an active room output and confirm median pure hardware delay convergence (`IQR <= 10 ms`, `SNR >= 12 dB`) and `PUT /api/v1/room/profile` persistence.
    *   Verify Android TV D-pad room pairing/playback + remote Volume +/-/Mute interception and WebXR HTTPS playback with `SPATIALFIN_AVR_CORS_ORIGINS`, Chrome PNA preflight, and `POST /api/v1/ws/ticket`.
*   **Hardware Soak Gates (when ESP32-C5/S3 or LAN Sendspin speakers are attached):**
    ```bash
    cd "$SPATIALFIN_AVR_DIR"
    TEST_ESP32_IP=<ip> TEST_SENDSPIN_WS_URL=ws://<speaker-ip>:<port>/sendspin \
      ./scripts/phase0/run_gates.sh
    ```
    *   Confirms 30-minute playback without underrun bursts, Kalman + fractional ASRC clock drift `<= 500 µs`, and 5 ms ramped `StreamClear` flush without audio discontinuities.

--------------------------------------------------------------------------------

*Drafted with EGM*
