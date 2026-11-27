# Spec 2: `spatialfin-avr` Standalone Rust Theater-Audio Service, DSP Engine & Solder-Free Hardware Deployment

*   **Author:** Ignacio Martinez <ignacio@martinez.fyi>
*   **Status:** Proposed
*   **Date:** 2026-11-26

--------------------------------------------------------------------------------

## Overview

This document defines **Spec 2**, the server-side architecture, 48 kHz Digital Signal Processing (DSP) pipeline, room-calibration engine, optional Phase 2 Music Assistant virtual-zone bridge, and solder-free viewing-room speaker deployment for **`spatialfin-avr`**. It implements the server half of **[Spec 0](./theater-audio-protocol-v1.md)** (see [Spec 0 Glossary & External Dependencies](./theater-audio-protocol-v1.md#glossary--external-dependencies) for shared ecosystem terms) and pairs with the **[Spec 1](./spatialfin-theater-audio-android-client.md)** Android client.

`spatialfin-avr` uses a **Split-A/V architecture**: the Android client on Samsung Galaxy XR (Full Space) or a Beam Android phone renders video locally, while `spatialfin-avr`—an optional, standalone multi-arch (`linux/amd64`, `linux/arm64`) single-room **Rust** Docker daemon running on a wired LAN host in another room—pulls audio directly from the user's **Jellyfin** media server over HTTP and streams synchronized 24-bit/48 kHz audio to physical theater speakers over **Sendspin 1.0** (Noise `KKpsk2` encrypted sessions locked to a common clock via Sendspin's 2D Kalman filter). Downstream speakers stay persistently paired with `spatialfin-avr`, keeping room EQ, Linkwitz-Riley crossovers, and satellite high-pass protection centralized in the host daemon (`spatialfin-sink` drives a wired multi-channel front USB DAC, while PoE or 5 GHz speakers handle surrounds and heights).

## The Problem

Deploying a lossless `5.1–7.1.4` Dolby Atmos wired/wireless theater from a remote Docker host introduces six constraints that shell-piped multi-runtime pipelines fail to satisfy:

1. **Docker host isolation vs. Companion auto-updates:** **SpatialFin Companion** (`SpatialFin-Companion` repo, port `1982`) is a Node.js AI/onboarding server running a `node:22-slim` image with a 24-hour `git fetch`/`npm ci` auto-update loop, Helmet CSP, and a `64 KiB` global JSON body limit (`5 MiB` on allowlisted admin/log JSON routes). Embedding FFmpeg, Rust binaries, `.NET`, or ALSA inside `SpatialFin Companion` breaks its update loop and penalizes onboarding-only users; meanwhile, a remote closet host cannot physically connect to a viewing-room USB DAC, UMIK-1 measurement mic, or HDMI cable.
2. **Multi-runtime IPC fragility & Cavern license conflict:** Piping raw PCM across Python (`aiosendspin`), C (`FFmpeg`), Rust (`truehdd` CLI, `CamillaDSP`), and `.NET` (`Cavern`) introduces `.NET` GC pauses, erases exact media presentation timestamps (PTS) across stdin/stdout pipes, and creates licensing conflicts—`VoidXH/Cavern` uses a custom non-commercial share-alike license incompatible with both permissive (`ISC OR Apache-2.0`) `spatialfin-avr` distribution and GPLv3 `SpatialFin`.
3. **Music Assistant speaker contention & driver damage:** Letting **Music Assistant (MA)** (home music server on ports `8095/8927/8928`) and a separate theater daemon contend for physical speakers forces duplicate Sendspin 1.0 `KKpsk2` pre-shared keys (PSKs) and multi-second cold-start handshakes on Play, and sends unfiltered full-range stereo (up to `0 dBFS`) into small satellites/heights that depend on host high-pass protection.
4. **Clock-locked front stage vs. Sendspin 1.0's 1–2 ch baseline & MCU RAM limits:** `Optical S/PDIF PCM` and stereo USB DACs carry at most 2 channels (splitting `FL/FR`, `FC`, and `LFE1` across unsynchronized crystals, breaking LCR imaging and `80 Hz` crossover phase alignment). Low-cost Wi-Fi 6 microcontrollers such as `ESP32-C5` expose only `384 KiB` internal SRAM over a 1×1 SISO 20 MHz radio sharing airtime with `60–120 Mbps` 4K UHD streams.
5. **Remote free-running UMIK-1 drift during room sweeps:** Because a USB-C `UMIK-1` calibrated microphone connects in the viewing room (Beam phone OTG or laptop) while `spatialfin-avr` plays from another room, a `20–50 ppm` quartz mismatch accumulates `100–250 µs` of drift over a `5 s` log sweep (`>= 180°` phase error above `2–5 kHz`) unless measured and resampled before deconvolution.
6. **Boot thumps, flash-delay misuse, and dropout gaps:** Writing main listening position (MLP) room-geometry delays into endpoint flash (`static_delay`) corrupts whole-home MA sync; unmuted `PCM5102A` DACs pop during the ESP32 boot-ROM floating-GPIO window without an external hardware pull-down (`~10 kΩ`) on `XSMT`; and a dropped surround channel leaves an audible channel gap unless isolated and rerouted in host DSP.

## Goals

*   **Incremental 4-Phase Delivery (`Phase 0 -> Phase 1 -> Phase 2 -> Phase 3`):** Keep every milestone independently buildable and verifiable in its own Cargo/Docker cycle, unblocking **Phase 1** playback from optional DIY microcontroller gates (`Phase 0A`).
*   **3-Crate Compiled Rust Core (`ISC OR Apache-2.0` Source):** Consolidate the Spec 0 control plane, `FFmpeg` (`libav*` dynamically linked) demux/bed decode, `f32`-bus/`f64`-biquad 48 kHz DSP, room calibration, and Sendspin 1.0 server into a three-crate workspace (`avr-protocol`, `spatialfin-avr`, `spatialfin-sink`).
*   **Clean Decoder Licensing & E-AC-3 Bed Continuity:** Omit `"eac3-joc"` from `supported_audio_codecs` in Phases 1–3 so FFmpeg decodes the lossy `5.1/7.1` E-AC-3 bed directly with zero non-commercial `.NET` runtime dependency; embed Apache-2.0 `truehd`/`oamd` + native 3D Vector Base Amplitude Panning (VBAP) in **Phase 3** with `< 200 ms` 1-Major-Sync lazy failover to FFmpeg's `5.1/7.1` TrueHD bed.
*   **Continuous Fractional-ppm Lip-Sync & `< 15 ms` Flush:** Absorb `±20 to ±50 ppm` video-host clock drift inside host DSP (`±100 ppm` range), compute causal `t_dac_0` and `t_play_avr_us` and burst-fill `500–750 ms` endpoint buffers **before** replying `playback.ready` `rpc.ok`, and flush queued room audio in `< 15 ms` via Sendspin `stream/clear`.
*   **Tier 0 Default, Tier 1 CamillaDSP Import (Phase 1) & Tier 2 UMIK-1 Solver (Phase 2):** Provide always-on Tier 0 crossover/limiter safety defaults, Phase 1 Tier 1 REW / Cavern QuickEQ `CamillaDSP` Room EQ bundle imports, and Phase 2 Tier 2 tripod-mounted remote `UMIK-1` sweeps (`< 0.5 ppm` estimation residual error, Farina delay/polarity/trim, `60–100 Hz` LFE phase-sum, bounded minimum-phase parametric EQ (`PEQ`) keeping total `dsp_group_delay_us <= 100 ms` with `<= 95 ms` EQ budget), `EQ Bypass` (`0 µs` lip-sync delta), and a lightweight `ffmpeg`/`curl` laptop capture script (`scripts/capture-sweep.sh`).
*   **Two-Phase Optional MA Virtual Zone (Phase 2):** Validate whole-home Sendspin latency and `44.1/96 -> 48 kHz` inbound asynchronous sample-rate conversion (`ASRC`) in Phase 0B; in Phase 2 expose one optional upstream MA zone (`ma_zone_state`), fading MA out `30 ms` + issuing `stream/clear` on `session.start` and burst-filling empty buffers on `playback.ready`.
*   **Unified N-Ch `spatialfin-sink` & Honest Solder-Free BOM:** Stream a single interleaved N-ch (`2–8`) 24-bit/48 kHz PCM/FLAC session to `spatialfin-sink` driving a viewing-room USB DAC (`FL, FC, FR, LFE1`), split surrounds into **Solder-Free Recommended** (PoE Home Assistant Connect AUX-2 or 5 GHz Linux SBC + USB-C DAC) vs. **DIY Hobbyist Optional** (`ESP32-C5`/`S3` with `10 kΩ` `XSMT` pull-down), use WMM `DSCP AF41` (`AC_VI`) on wireless FLAC level 0–2 streams, and keep `room_geom_delay_us` in host DSP.

## Non-Goals

*   Modifying `SpatialFin-Companion` (its Node.js code, SQLite database, `entrypoint.sh` auto-update loop, or Compose stack).
*   Shipping a `.NET` Cavern E-AC-3 JOC worker, a compiled 3-OS `cpal` desktop capture binary (`avr-capture-cli`), `DtsHeightUpmixer`, or a Tier 2 excess-phase FIR / multi-sub optimizer in Phases 1–3 (see [Future Improvements](#future-improvements)).
*   Granting `SCHED_FIFO` / Elevated Real-Time capabilities to `spatialfin-avr`, or embedding crossover DSP / autonomous MA zones inside `spatialfin-sink`.
*   Supporting a USB DAC, local UMIK-1, or local ALSA/PipeWire HDMI bitstream passthrough attached to the remote Docker host.
*   Writing custom C++ ESP32 firmware or requiring soldering for the Recommended viewing-room speaker BOM.
*   Writing `room_geom_delay_us` into endpoint flash `static_delay`, streaming `> 48 kHz` audio to Wi-Fi speakers, or multi-room multi-tenancy inside one daemon instance.

## Open Questions

*   **Phase 0B (Sendspin Crate Maturity & Phase 2 MA Whole-Home Sync):** Verify `syncspin-core` / `syncspin-server` / `syncspin-client` interop against ESPHome `2026.4+` (`|clock_skew_us| < 200 µs`, `< 15 ms` `stream/clear` stop), single-session N-ch PCM framing (`Sendspin/spec#350` alignment), and whether whole-home MA group static-delay compensation supports cascaded host DSP + Sendspin buffering cleanly before enabling Phase 2's optional MA zone. If upstream crates miss required Noise `KKpsk2` or `stream/clear` hooks, vendor patches and upstream them cleanly.
*   **Phase 0C (Jellyfin Audio Stream Layout & HTTP Range Seeking):** Confirm Jellyfin `10.10/10.11/12` `audioCodec=copy` (`single_audio_stream`) OAMD preservation and `?startTimeTicks=` seeking + container `start_time` offset retention; if Jellyfin forces transcode or drops offsets/seeks, default to `static=true` (`multi_stream_container`, HTTP range-seekable with empirically tuned Range-skip threshold and documented `2×` LAN read budget).
*   **Phase 0A — Optional DIY Gate (`ESP32-C5` SRAM Soak):** Non-blocking for Phase 1. Before recommending `surround-esp32c5.yaml` as a DIY template, run a 2-hour `24-bit/48 kHz` FLAC level 0–2 + 50 Noise `KKpsk2` reconnect soak verifying whether ESPHome buffers compressed FLAC vs. decoded PCM and maintains `>= 40 KiB` min free internal heap with `0` Watchdog Timer (WDT) resets.

## Critical User Journeys (CUJs)

### CUJ 1: Cinema Launch, Pause/Seek/Drift Lock & Optional Phase 2 MA Handover

*   **Given:** Viewing-room speakers are persistently paired to `spatialfin-avr` (`cinema session state: "idle"`; in Phase 2, MA may be streaming stereo audio via virtual zone `server_name` with `ma_zone_state: "playing_ma"`).
*   **When:** The user starts playback on Galaxy XR or Beam, pauses (`playback.pause`), seeks, or plays a full 2-hour movie.
*   **Then:** On `session.start`, if MA is active, `spatialfin-avr` fades MA out over `30 ms`, sends a non-blocking Sendspin `controller` pause to MA (`ma_zone_state: "paused_for_cinema"`), and emits `stream/clear`. On `playback.ready`, `spatialfin-avr` runs demand-driven burst-fill into empty endpoint buffers before returning `rpc.ok`, renders cinema audio (5.1/7.1 bed in Phase 1; TrueHD Atmos VBAP onto measured `coord_m` in Phase 3) faded in over `30 ms` at `t_play_avr_us`, absorbs `±100 ppm` video-host drift silently, clears buffers in `< 15 ms` on Pause/Seek, and returns to silence / `ma_zone_state: "idle"` on `session.stop` (or `600_000 ms` `pause_idle_release_ms` / `6_000 ms` `heartbeat_ttl_ms` expiry).

### CUJ 2: Room Calibration — Tier 1 Import (Phase 1) & Tier 2 Tripod UMIK-1 Sweep (Phase 2)

*   **Given:** The user has either an exported REW / Cavern QuickEQ `CamillaDSP` bundle (Tier 1, Phase 1) or a **tripod-mounted** `UMIK-1` at the `MLP` plugged into a Beam phone OTG or laptop running `scripts/capture-sweep.sh` (Tier 2, Phase 2).
*   **When:** For Tier 1, the user uploads `config_yaml` + `fir_wavs[]` via `POST /api/v1/calibration/bundles`, activates with `/apply`, and can roll back with `/revert`. For Tier 2, after tap-to-chime SPL headroom checks (`POST /api/v1/calibration/chime`, `-30.0` to `-12.0 dBFS`), the user starts a bracketed sweep (`POST /api/v1/calibration/sweeps`, `2.0–10.0 s`, `5 s` default, `>= 1.0 s` inter-channel decay gaps, `gain_dbfs <= -12.0`).
*   **Then:** `spatialfin-avr` plays `[Wired FL Ref @ T_0] -> [Sweep Ch 1..N] -> [Wired FL Ref @ T_end]`, locks `clock_ratio_ppm` within `< 0.5 ppm` estimation residual, performs Farina FFT deconvolution (`realfft`), optimizes `60–100 Hz` `LFE*` phase-sum vs. `FL+FR`, computes per-speaker `room_geom_delay_us`, trims, polarity, crossovers, and bounded minimum-phase PEQ (`eq_group_delay_us <= 95 ms`, total `dsp_group_delay_us <= 100 ms`), returns `200 OK` flagging `needs_remeasure: true` (`SNR < 25 dB` or peak `>= -0.5 dBFS`) while memoizing `clock_ratio_ppm` in the `calibration_id` draft for single-channel re-sweeps, activates on `/apply`, and provides delay-invariant `EQ Bypass` (`0 µs` lip-sync delta).

### CUJ 3: Wireless Surround Dropout Detection, Isolation & Phase 2 Recovery

*   **Given:** Active `5.1–7.1.4` playback across wired `spatialfin-sink` (`FL, FC, FR, LFE1`) and wireless surround/height endpoints.
*   **When:** Wireless `SL` drops off 5 GHz Wi-Fi for `8 s`.
*   **Then:** Within the Sendspin disconnect detection threshold (`300–500 ms` missing ping/TCP write stall), `avr-sendspin` isolates `SL`'s write queue without blocking healthy speakers and emits `speaker.degraded` (`rerouted_to_roles: []` in Phase 1; in Phase 2, DSP feeds a static neighbor table `SL -> FL/BL` with a `20 ms` constant-power ramp upstream of Crossover taking acoustic effect over healthy `~500–750 ms` endpoint buffers). When `SL` reconnects and its Kalman lock stabilizes, `spatialfin-avr` crossfades `SL` back in over `20 ms` and emits `speaker.restored`. Critical front-stage unavailability (`FL`/`FC`/`FR` offline on `session.start`) rejects with `503 speaker_offline_critical`; mid-movie critical `FL/FC/FR/LFE1` drops pause and release the session cleanly (`session.released`).

### CUJ 4: Phase 3 In-Process TrueHD Atmos -> Bed Failover

*   **Given:** Active Phase 3 TrueHD Atmos playback via `TrueHdAtmosRenderer` (`truehd`/`oamd` + 3D VBAP).
*   **When:** An Atmos frame/OAMD parse or VBAP error occurs mid-stream.
*   **Then:** `spatialfin-avr::spatial` replays the cached last 1 TrueHD Major Sync packet (`~1–2 KiB`) through a lazily initialized FFmpeg `truehd` bed decoder in `< 200 ms`, maps the decoded `5.1/7.1` bed directly to the room speakers, updates `telemetry.session` (`codec_active: "truehd"`, `renderer_mode: "bed"`, `active_decode_chain`), and pushes `decoder.fallback` without halting playback. *(Optional E-AC-3 JOC object decoding is deferred; omitting `"eac3-joc"` upfront in Phases 1–3 directs Jellyfin/FFmpeg to play the lossy E-AC-3 `5.1/7.1` bed directly.)*

## Technical Plan (Architecture)

### 1. Four-Stage In-Process Rust Pipeline (3-Crate Workspace)

```text
[SpatialFin Spec 0 WS/HTTP :1984]       [Phase 2 Optional: MA Virtual Zone]
               |                                         |
               v                                         v
  +--------------------------+                 +------------------------+
  | Stage 1: ingest          |                 | MA Inbound Bus         |
  | std::thread FFmpeg       |                 | (rubato 44.1/96->48k   |
  | (io_open SSRF, DRC=0,    |                 |  Kalman-slaved ASRC)   |
  |  Range/StartTicks PTS)   |                 +-----------+------------+
  +------------+-------------+                             |
               v                                           |
  +--------------------------+                             |
  | Stage 2: spatial         |                             |
  | P1: IdentityBedRenderer  |                             |
  | P3: TrueHdAtmosRenderer  |                             |
  |  (truehd+oamd+3D VBAP +  |                             |
  |   1-Sync bed failover)   |                             |
  +------------+-------------+                             |
               v                                           v
  +-------------------------------------------------------------------+
  | Stage 3: dsp (std::thread, 48 kHz, 10 ms frame, f32/f64-biquad)   |
  | [Logical 12-ch Bus] Input ASRC / Slow PI (+-100 ppm Drift)        |
  |  -> Mains -10 dB Pad -> P2 Static Neighbor Reroute                |
  |  -> Always-On 24 dB/oct LR4 Bass Mgmt (+10 dB discrete LFE + APF) |
  | [Physical Outputs: <= 13 roles / 16 chains]                       |
  |  -> Switchable Room EQ (PEQ/FIR <= 95 ms) + eq_group_delay_us Pad |
  |  -> Polarity / Trim / room_geom_delay_us -> Master Vol            |
  |  -> Always-On -1.5 dBFS 5 ms Lookahead Limiter (LFE-decoupled)    |
  +------------------------------+------------------------------------+
                                 v
  +-------------------------------------------------------------------+
  | Stage 4: sendspin (Downstream Sendspin 1.0 Server :8937)          |
  | Noise KKpsk2 + 2D Kalman + role -> (client_id, channel_index)     |
  | N-ch PCM -> spatialfin-sink | Wi-Fi FLAC L0-2 (AF41) | stream/clr |
  +-------------------------------------------------------------------+
```

*Caption:* Diagram depicts the real-time audio data path (`Stages 1–4`). **Phase 1** delivers `AVR Protocol`, `ingest`, `IdentityBedRenderer`, core `dsp` + Tier 0/Tier 1 `calibration` filters (`<= 95 ms`), and downstream `sendspin` -> `spatialfin-sink` / ESPHome; **Phase 2** adds Tier 2 `calibration` sweeps, static neighbor surround reroute, and optional MA bridge; **Phase 3** adds `TrueHdAtmosRenderer`.

### 2. Module Responsibilities, Protocol Compliance & Contracts

#### Workspace Topology & Pinned Crates

Collapse the daemon into **3 Cargo workspace members** under `ISC OR Apache-2.0`:
*   **`crates/avr-protocol`** (pure `serde`, URL/SSRF validator, auth/FSM types, zero C/FFmpeg dependencies so `cargo test -p avr-protocol --test protocol_contract` executes sub-second matching Spec 0 Phase 0.2b).
*   **`bins/spatialfin-avr`** (`ingest`, `spatial`, `dsp`, `calibration`, `sendspin` internal Rust modules + Axum control plane): pinned crates `axum`, `reqwest` + `rustls` (custom DNS `Resolve`), `mdns-sd`, `ffmpeg-next` (`default-features = false, features = ["format", "codec"]`) + `ffmpeg-sys-next`, `rubato`, `realfft`, `flacenc`, `zeroize`, Phase 3 `truehd`/`oamd`, and `syncspin-core` / `syncspin-server` / `syncspin-client` (evaluated in Phase 0B).
*   **`bins/spatialfin-sink`** (lightweight viewing-room Linux receiver): `syncspin-client`, `rubato`, `alsa` (`hw:` direct access).

#### Control Plane, Spec 0 Compliance & Security (`avr-protocol`)

*   **Clocking, Discovery & Binding:** Samples `CLOCK_BOOTTIME` in µs; serves Axum HTTP/WS on `SPATIALFIN_AVR_PORT` (default `1984`, clear of Companion `1982`, SearXNG `8898`, FCast `46899`, MA `8095/8927/8928`). Linux `mdns-sd` uses `SO_REUSEADDR` (**not** `SO_REUSEPORT`, which collides `EADDRINUSE` with `avahi-daemon` across mismatched UIDs under `network_mode: host`), logging a non-fatal warning if UDP `5353` fails, and advertises `_spatialfin-avr._tcp.local.` (`v=1`, `id`, `name`, `layout`, `auth=bearer`, `pair=pin|static`, `sw`).
*   **Spec 0 §2–§8 Server Compliance Checklist:** Implements all wire rules by direct reference to Spec 0 (`L13`):
    *   *Auth & PIN:* `POST /api/v1/pair/pin`, `POST /api/v1/pair/claim` (`120 s` single-use 6-digit PIN, canonical `qr_uri` `spatialfin-avr://pair?host=<ip>&port=<port>&id=<id>&pin=<6digits>&tls=<0|1>`, `5` per-IPv4-`/32` / IPv6-`/64` per `10 min` + `5` global lockouts), SHA-256 Bearer tokens (`DELETE /api/v1/clients/{client_id}`), `SPATIALFIN_AVR_TOKEN` (`>= 32` chars -> `403 pairing_disabled`), `GET /health` unauthenticated, `GET /api/v1/info` matching `hello` static manifest.
    *   *WebSocket & REST Routes:* All WS RPCs (`hello`, `clock.ping`, `session.start`/`heartbeat`/`stop`, `playback.load`/`ready`/`pause`/`stall`/`video_timing`, `room.set_volume` clamped at `max_safe_volume` perceptual taper, `room.set_eq_bypass`, `room.set_upmix_mode`, `calibration.center_snap`, `calibration.lip_sync_chirp`) and pushes (`telemetry.session`, `playback.resync_required` max `3 / 30 s`, `session.released`, `room.state_changed`, `room.profile_updated`, `speaker.degraded`/`restored`, `decoder.fallback`, `ma.state_changed`); all REST routes (`/api/v1/status` with `connected_endpoints`, `GET`/`PUT /api/v1/room/profile` with `ETag` + `If-Match` `409 version_conflict`, `/calibration/chime` supporting `"pink_noise"`, `/calibration/center-snap`, `/calibration/sweeps`, `DELETE /api/v1/calibration/sweeps/{sweep_id}`, `/upload`, `/bundles`, `/apply`, `/revert`, rejecting calibration mutations with `409 session_busy` during active cinema playback).
    *   *Envelopes, Guards & Caps:* Additive forward-compat deserialization (forbid `#[serde(deny_unknown_fields)]`), WS `"code"` vs HTTP `"error_code"`, `256 KiB` WS frame cap -> RFC 6455 close `1009`, `64 MiB` disk-streamed multipart cap -> `413 payload_too_large`, max `16` concurrent WS observers / `16` speaker slots, monotonic `barrier_seq` fencing, `6_000 ms` `heartbeat_ttl_ms`, `600_000 ms` `pause_idle_release_ms`, `5_000 ms` `seek_timeout` (`504`), `503 speaker_offline_critical` (if `FL`/`FC`/`FR` offline on start), `409 calibration_in_progress`, `422 codec_unsupported` / calibration errors, `502`/`400` errors.
    *   *Token Protection & SSRF:* Wraps volatile `jellyfin_token` inside `RedactedSecret(Zeroizing<String>)` (explicit `Debug`/`Display` `"[REDACTED]"`), injects `X-Emby-Token`, rejects `\r`/`\n`/`\0`, userinfo, and case-insensitive `?`/`&` `api_key=` / `token=`. Enforces full Spec 0 §3 DNS-pinned SSRF allowlist (RFC1918/ULA private LAN + `SPATIALFIN_ALLOWED_JELLYFIN_ORIGINS`, blocking `169.254.0.0/16`, `fe80::/10`, `fd00:ec2::254`, `::`, `0.0.0.0/8`, `100.100.100.200`, `224.0.0.0/4`, `ff00::/8`, `::ffff:0:0/96`, `64:ff9b::/96`, `240.0.0.0/4`, `fec0::/10`, IPv6 zone-IDs `%`, deprecated tunnels, self listening port `SPATIALFIN_AVR_PORT`, and `127.0.0.0/8`/`::1`/localhost unless `SPATIALFIN_ALLOW_LOOPBACK_JELLYFIN=true` scoped away from `:1982`/`:8898`) with **HTTP redirects disabled outright**.

#### Configuration, Persistence, CLI & `SIGTERM` Shutdown

*   **Environment Variables:** `SPATIALFIN_AVR_PORT` (`1984`), `SPATIALFIN_AVR_SENDSPIN_PORT` (`8937`), `SPATIALFIN_AVR_DATA_DIR` (`/data`), `SPATIALFIN_AVR_ROOM_NAME` (`"Living Room Theater"`), `SPATIALFIN_AVR_TOKEN` (optional static `>= 32`-char secret), `SPATIALFIN_ALLOWED_JELLYFIN_ORIGINS` (comma-separated `scheme://host:port`), `SPATIALFIN_ALLOW_LOOPBACK_JELLYFIN` (`false`), `RUST_LOG` (`info`).
*   **`/data/` Persistence Schemas & Atomic Writes (`0600`):** Every JSON write uses temp-file + `fsync` + atomic `rename`:
    *   `/data/avr_state.json`: `{ schema_version, server_id, clients: [{ client_id, name, token_sha256, created_s }], room_profile: <Spec 0 RoomProfile> }`.
    *   `/data/sendspin_keys.json`: `{ schema_version, server_noise_priv_b64, server_noise_pub_b64, endpoints: [{ client_id, psk_b64, noise_pub_b64, roles, transport_tier, fixed_hw_delay_us }] }`.
    *   `/data/calibrations/{active,backup,draft}/`: Filter JSON metadata + uploaded `fir_wavs[]`, removing orphaned sweep/staging temp WAVs via `finally` and startup sweep.
*   **Binary CLIs & Shutdown:** `spatialfin-avr` exposes `serve`, `pair-pin` (arms a `120 s` Spec 0 PIN via local admin control pipe and prints PIN/QR), `sendspin-pair --client-id <id> --roles <...> --tier <...>` (generates Noise `KKpsk2` PSKs in `/data/sendspin_keys.json` + prints ESPHome `!secret` / sink config), and `healthcheck` (probes `127.0.0.1:$SPATIALFIN_AVR_PORT/health`). `spatialfin-sink` accepts `--channels <2..8> --alsa-device <hw:...> --client-id <id> --server-host <host> --port <8937> --psk-file <path>`. On `SIGTERM`, `spatialfin-avr` emits `session.released { reason: "server_shutdown" }`, flushes speakers via `stream/clear`, zeroes active `RedactedSecret` tokens, and exits cleanly.

#### Media Ingest (`spatialfin-avr::ingest`)

*   Runs on a dedicated named OS thread (`std::thread`, **not** Tokio `spawn_blocking`) feeding bounded backpressure channels.
*   Uses `unsafe` `ffmpeg-sys-next` (`avio_alloc_context` with FFI panic guards) overriding both top-level `pb` and `AVFormatContext.io_open`/`io_close2`, locking `protocol_whitelist` + `format_whitelist`, disabling redirects, and validating every HLS playlist/segment/key URL through the DNS-pinned SSRF client with `X-Emby-Token`.
*   Implements `AVSEEK_SIZE` + keep-alive HTTP `Range` seeking (tuning forward-skip drain vs Range-jump threshold from Phase 0C measurements), verifies `streams[idx].codec_type == AVMEDIA_TYPE_AUDIO`, probes video `start_time` (on `multi_stream_container`) before setting `AVDISCARD_ALL` on video/subtitles, sets `libavcodec option drc_scale=0`, tracks decoded sample offsets to exact `actual_pts_us` post-seek, and supports `single_audio_stream` seeking via `?startTimeTicks=` reconnect + timestamp rebasing if verified in Phase 0C (else defaulting VOD to `static=true`).
*   Emits `IngestPacket::{Compressed, DecodedPcm}` downstream, applying `rubato` inbound ASRC whenever source sample rate != `48 kHz`.

#### Spatial Renderers (`spatialfin-avr::spatial`)

*   Exposes `trait SpatialRenderer` and advertises Spec 0's 19-token union (matching `:fcast`'s `AudioCodecTokens` — 13 `UNIVERSAL_SOFTWARE`: `aac, mp3, opus, vorbis, flac, alac, pcm, pcm_s16le, pcm_s24le, pcm_s32le, pcm_f32le, pcm_mulaw, pcm_alaw`; 6 `ALL_PASSTHROUGH_TOKENS`: `ac3, eac3, eac3-joc, truehd, dts, dts-hd`), omitting `"eac3-joc"` in Phases 1–3 so FFmpeg decodes the lossy E-AC-3 bed natively.
*   *Phase 1 (`IdentityBedRenderer`):* Maps FFmpeg-decoded `<= 7.1` beds onto the 12-channel logical bus (`FL, FR, FC, LFE, SL, SR, BL, BR, TFL, TFR, TBL, TBR`), verifying spatial input against physical outputs.
*   *Phase 3 (`TrueHdAtmosRenderer`):* Embeds Apache-2.0 Rust `truehd` (from the same upstream project as `truehdd`) + `oamd` crates in-process, projecting OAMD normalized `[0..1, 0..1, -1..1]` object coordinates onto `RoomProfile.speakers[].coord_m` via Pulkki **3D VBAP** (adding virtual ghost zenith/nadir speakers for hull closure, falling back to 2D horizontal VBAP for coplanar 5.1/7.1 rooms, excluding `LFE*`, and smoothing per-frame gains). Retains the last **1 TrueHD Major Sync packet** (`~1–2 KiB`) so parser/VBAP errors lazily initialize FFmpeg's `truehd` bed decoder in `< 200 ms`.

#### DSP Engine (`spatialfin-avr::dsp`)

*   Dedicated named `std::thread` processing fixed `480`-sample (`10 ms`) blocks at `48 kHz` (`f32` audio bus/convolver; `f64` IIR biquad state/coefficients) with pre-allocated scratch buffers; filter/profile swaps arrive over a bounded command channel and crossfade over `10–30 ms` without heap allocation or locks.
*   **Timing & Drift Control:** `t_dac_0 >= now_avr_us + sendspin_buffer_us + max_uncompensated_hw_us + launch_margin_us` (`launch_margin_us` sized dynamically from measured burst compute + LAN fan-out: `~20–40 ms` wired, `~200–350 ms` 8-node Wi-Fi) and `t_play_avr_us = t_dac_0 + dsp_group_delay_us + max_air_flight_us` (where `dsp_group_delay_us` includes common-mode DSP latency and **excludes** per-speaker `room_geom_delay_us` and Sendspin `static_delay`; reported inside `playback.ready` `rpc.ok` `lead_breakdown_us`). Emitted chunks populate a compact `(t_mlp_avr_us, media_pts_us, rate_ppm)` ring-map so `playback.video_timing` evaluates `drift_us = media_pts_us - audio_heard_pts_us(visible_avr_us)` across the 600 ms buffer lag, driving a heavily damped slow PI loop (`tau >= 5–10 s`, clamped `±100 ppm`, freezing integration inside `[-10_000, +15_000] µs` deadband or `clock_uncertainty_us > 5_000 µs`) and emitting `playback.resync_required` (max `3 / 30 s`) at `|drift_us| > 80_000 µs` sustained `> 1_000 ms`.
*   **Logical vs. Physical Signal Chain:** Applies a `-10 dB` mains pad relative to unity discrete LFE (`+10 dB` LFE boost applied **only** to discrete `.1`, never bass-managed music) and optional Phase 2 static neighbor surround fold-down (`SL -> FL/BL`, etc., `20 ms` constant-power ramp) **upstream** of the always-on `24 dB/oct` LR4 crossover + 2nd-order LFE all-pass phase-alignment filter. Outputs expand per canonical speaker chain (up to 13 roles / 16 outputs including separate `LFE1`/`LFE2` EQ & delays): switchable Room EQ (`eq_group_delay_us <= 95 ms`, total `dsp_group_delay_us <= 100 ms`; `room.set_eq_bypass` pads strictly by `eq_group_delay_us` + `10–20 ms` wet/dry crossfade), polarity/trim/`room_geom_delay_us`, perceptual volume (`max_safe_volume` clamp), and `-1.5 dBFS` 5 ms lookahead sample-peak limiter (mains-linked, LFE-decoupled).

#### Room Calibration (`spatialfin-avr::calibration`)

*   *Tier 0 (Always-On Default):* Playable crossover/limiter safety profile, tap-to-chime speaker ID, manual stepped `-30.0..-12.0 dBFS` `chime`/`pink_noise` SPL checks (no mic sweeps), and delay-invariant `EQ Bypass`.
*   *Tier 1 (Phase 1 CamillaDSP Import):* Validates `<= 256 KiB` YAML (rejecting YAML aliases), enforces basename-only `fir_wavs[]` lookup, caps FIR length at `<= 9600` taps (`48 kHz`) and `eq_group_delay_us <= 95_000 µs`, maps SMPTE indices `0..N-1` to Room EQ filters + optional trims/delays only (crossovers/LFE gain/limiter stay single-sourced in `dsp`), and stages into 1-slot `/apply` & `/revert`.
*   *Tier 2 (Phase 2 Native UMIK-1 Solver):* Requires tripod mic placement and wired `FL` reference chirps with `>= 1.0 s` inter-channel decay gaps. Uses `POST /api/v1/calibration/sweeps` (`2.0–10.0 s`, `5 s` default, `gain_dbfs <= -12.0`), `DELETE /api/v1/calibration/sweeps/{sweep_id}`, `/upload` (`recording_wav`, `umik_cal_txt`, `metadata_json`), `/apply`, `/revert`. Derives UMIK-1 `clock_ratio_ppm` (`< 0.5 ppm` estimation residual error; memoized in `calibration_id` drafts for single-channel re-sweeps with `±20 ppm` check), flags `needs_remeasure` (`SNR < 25 dB` or peak `>= -0.5 dBFS`) at `200 OK`, runs Farina `realfft` deconvolution with first-arrival onset detection + `0°/90°` `.txt` compensation, solves `LFE*` delay/polarity via `60–100 Hz` complex phase-sum vs. `FL+FR`, computes `room_geom_delay_us` in `dsp` (verifying `fixed_hw_delay_us` only when metric `coord_m` exists), and synthesizes 8–12 band bounded (`+3..+6 dB`, `1/6`-oct smoothed) minimum-phase biquad PEQs. Accompanied by `scripts/capture-sweep.sh` (~50-line `ffmpeg`/`arecord` + `curl` laptop helper alongside Beam OTG).

#### Sendspin 1.0 Bridge (`spatialfin-avr::sendspin`)

*   **Downstream Server (`SPATIALFIN_AVR_SENDSPIN_PORT=8937`):** Noise `KKpsk2` (`/data/sendspin_keys.json` `0600`), 2D Kalman clock master, `role -> (client_id, channel_index)` fan-out, single interleaved N-ch 24-bit/48 kHz PCM/FLAC stream to `spatialfin-sink`, 24-bit/48 kHz FLAC level `0–2` (`~500–800 kbps/ch` typical) with unprivileged `IP_TOS = 0x88` / `IPV6_TCLASS` (`DSCP AF41` / `AC_VI`, preserving AP A-MPDU aggregation) and `<= 3–4×` burst pacing into `500–750 ms` ring buffers, `< 15 ms` `stream/clear` flush, and `300–500 ms` disconnect detection.
*   **Phase 2 Optional Upstream MA Client:** Registers virtual zone (`SPATIALFIN_AVR_ROOM_NAME`) with MA using Sendspin `controller` pause (`MusicAssistantHandoffAdapter`) and an inbound `Clock_MA -> Clock_AVR` `rubato` ASRC (`44.1/96 -> 48 kHz`).

### 3. Solder-Free Viewing-Room Hardware BOM & Unified `spatialfin-sink`

*   **Front Stage (`FL, FC, FR, LFE1` — Clock-Locked):** Viewing-room Raspberry Pi 4/5 (or mini-PC) on wired Ethernet attached via USB to a **`>= 4`-channel class-compliant USB DAC** (prefer fixed line-level outputs such as `Behringer UMC404HD` outs 1–4 or 8-ch `miniDSP U-DAC8`; if using `MOTU M4` or `Focusrite Scarlett 4i4`, lock the front-panel Monitor Volume knob affecting Outputs 1–2 so front-to-center/subwoofer trim calibration cannot drift). Runs **`spatialfin-sink`** (`--channels N --alsa-device hw:...`), receiving **one** interleaved N-ch (`2–8`) Sendspin stream over one Noise `KKpsk2` session + shared 2D Kalman clock + fractional rate-adjust resampling directly to ALSA `hw:` with `EPIPE` underrun recovery and `systemd` supervision (matching `Sendspin/spec#350` and Spec 0 `client_id` + `channel_index` 1:1).
*   **Surrounds & Heights (`48 kHz` — Recommended vs. DIY Tiers):**
    *   *Solder-Free Recommended:* Stereo PoE **Home Assistant Connect AUX-2** (stock ESPHome `2026.4+` YAML, moves paired surrounds/heights off Wi-Fi entirely onto wired Ethernet) or a **5 GHz Linux SBC** (e.g., Raspberry Pi 3A+/4/5 or Radxa Zero 3W — **not** 2.4 GHz-only Pi Zero 2 W; optional read-only OverlayFS root) + USB-C DAC dongle running `spatialfin-sink`.
    *   *DIY Hobbyist Optional (Soldering Required):* Waveshare `ESP32-C5-WIFI6-KIT` (optional Phase 0A `>= 40 KiB` heap gate) or `ESP32-S3-16R8` (explicitly documented **2.4 GHz-only** degraded radio density) wired to a `PCM5102A` breakout via conflict-free GPIO pinouts (avoiding ESP32-C5 strapping/JTAG pins and ESP32-S3 Octal-PSRAM GPIOs `26–37`) with soldered `H1L–H4L` bridges, an **external `~10 kΩ` pull-down resistor on `XSMT`** to prevent boot-ROM pops before GPIO init, firmware mute automation LOW in idle/OTA, and an isolated `5 V` DC-DC supply. (`poe-connect-aux2.yaml` uses integrated mute circuitry, not `PCM5102A XSMT`.)
*   **Wi-Fi Band & Delay Discipline:** Keep Galaxy XR on **6 GHz (Wi-Fi 6E/7)**, wireless Linux SBCs / C5 surrounds on **5 GHz** (`DSCP AF41`), store `room_geom_delay_us` in `spatialfin-avr::dsp`, and reserve `static_delay` strictly for `fixed_hw_delay_us`.

### 4. Standalone Docker Deployment & Maintenance

*   **Self-Contained `spatialfin-avr` Repo Deployment:** Multi-stage `Dockerfile` (`Rust >= 1.88` bookworm builder with `libavformat-dev libavcodec-dev libavutil-dev libclang-dev pkg-config` -> `debian:bookworm-slim` runtime with `libavformat59 libavcodec59 libavutil57`) and standalone `docker-compose.yaml`, running non-root UID `1000`, `cap_drop: [ALL]` (no elevated RT capabilities), `network_mode: host`, `restart: unless-stopped`, `json-file` logging (`1m` × 3), named volume `spatialfin-avr-data:/data` pre-owned `1000:1000` in the image (avoiding root-owned host bind-mount `EACCES`), `SPATIALFIN_AVR_PORT=1984`, `SPATIALFIN_AVR_SENDSPIN_PORT=8937`, and Docker `HEALTHCHECK` calling `spatialfin-avr healthcheck`.
*   **Zero Companion Coupling & Maintenance Procedure:** `SpatialFin-Companion` is untouched. Document planned-maintenance steps clearly (stop `spatialfin-avr` via `docker compose stop spatialfin-avr` and switch speakers manually to a backup direct MA profile with HPF/limiter enabled on any small satellites).

## Alternatives Considered

### Alternative A: Embedding FFmpeg, Python, CamillaDSP & `.NET` Cavern Inside `SpatialFin-Companion`

*   **Pros:** Single stack alongside Companion.
*   **Cons:** Breaks Companion's `node:22-slim` image and 24-hour `git fetch`/`npm ci` auto-update loop, introduces non-commercial Cavern dependencies for onboarding-only users, and violates Spec 0's standalone decoupling rule.
*   **Reason for Rejection:** A separate `spatialfin-avr` repository and Compose stack eliminate multi-repo release coupling while leaving `SpatialFin Companion` untouched.

### Alternative B: Multi-Process Container (`aiosendspin` + External `CamillaDSP` Crates/Daemon + `truehdd` CLI) vs. Single Compiled Rust Core

*   **Pros:** Reuses existing Python/CLI tools without writing internal audio/control integration.
*   **Cons:** Erases sample-exact `media_pts_us` across stdin/stdout pipes, cannot synchronize `< 15 ms` `stream/clear` flushes + external 2 Hz video-beacon `±100 ppm` drift resampling across processes, complicates delay-invariant live `eq_bypass`, and wrapping GPLv3 `CamillaDSP` directly conflicts with `ISC OR Apache-2.0` core Rust licensing.
*   **Reason for Rejection:** Embedding proven Rust building blocks (`rubato`, `realfft`, `syncspin-*`, `truehd`/`oamd`) inside one compiled Rust daemon achieves sample-exact PTS tracking and permissive source licensing cleanly.

### Alternative C: On-Demand Speaker Connect/Release for Music Assistant Coexistence

*   **Pros:** MA talks directly to speakers when cinema is idle.
*   **Cons:** Duplicate Noise `KKpsk2` keys, multi-second cold-start Noise/Kalman reconnects on Play, handoff races, and unfiltered full-range bass sent to small satellites.
*   **Reason for Rejection:** Phase 2's optional virtual AVR zone gives controlled two-phase handover while keeping host crossovers active.

### Alternative D: Optical S/PDIF PCM Everywhere or Running `spatialfin-avr` Locally on the Viewing-Room Pi

*   **Pros:** Uniform 2-ch endpoints everywhere (`S/PDIF`) or zero `spatialfin-sink` hop (`AVR on Pi`).
*   **Cons:** `S/PDIF PCM` and stereo DACs un-lock `FL/FR/FC/LFE1` crystals; running 80 GB UHD demux, FFT FIR DSP, and Phase 3 Atmos decoding on a living-room Pi introduces fan heat/noise and starves Pi USB bus power.
*   **Reason for Rejection:** Keeping compute in another room (`spatialfin-avr`) paired with silent fanless viewing-room `spatialfin-sink` + USB DAC gives clock-locked front channels without living-room noise.

### Alternative E: Tier 1 External REW-Only Calibration vs. Built-In Phase 2 Tier 2 Solver

*   **Pros:** Eliminates sweep/Farina code from Rust.
*   **Cons:** Forces Beam phone OTG users without a laptop/REW license through manual multi-tool workflows and cannot measure remote free-running USB-C clock drift automatically.
*   **Reason for Rejection:** Keeping Phase 1 Tier 1 CamillaDSP imports while adding scoped Phase 2 PEQ/LFE-sum UMIK-1 solving serves both power users and Beam-only setups.

## Risks & Limits

| Risk / Failure Mode | Category | Impact / Limit | Mitigation |
| --- | --- | --- | --- |
| **Sendspin crate / Phase 2 MA sync gap** | Dependencies | Upstream crates may miss `stream/clear` or Noise `KKpsk2`; cascaded ~700 ms MA lag may exceed MA group compensation. | Phase 0B validation gate; defer optional MA zone to Phase 2; vendor minimal upstreamed patches cleanly. |
| **Jellyfin `audioCodec=copy` gaps** | Media Ingest | OAMD/seek may require `static=true`, adding a second `60–120 Mbps` NAS/LAN read (`2×` total bandwidth per UHD stream). | Phase 0C check; tune HTTP Range skip threshold vs. `?startTimeTicks=`; document `2×` wired LAN budget. |
| **Scale & DoS exhaustion** | Scalability | Oversized WS frames, calibration uploads, or unbounded observers can OOM the host. | Enforce `256 KiB` WS close `1009`, `64 MiB` disk-streamed upload `413`, max `16` WS observers / `16` speaker slots, `1` calibration draft, `<= 256 KiB` YAML / `<= 9600` taps. |
| **`network_mode: host` SSRF / CLEARTEXT** | Security | HLS child playlists/redirects could reach `127.0.0.1:8898` / `:1982` / `file://`; LAN `Authorization: Bearer <avr_token>` & `jellyfin_token` travel unencrypted without TLS proxy. | Override `io_open`/`io_close2`, lock whitelists, disable redirects, enforce full Spec 0 §3 SSRF guard, use `RedactedSecret(Zeroizing<String>)`, support reverse-proxy `wss://`/`https://`. |
| **1×1 SISO Wi-Fi contention & dropouts** | Transport | Concurrent 4K UHD Wi-Fi load causes wireless bursts/jitter (~200–350 ms fill window) or surround drops. | Keep XR on 6 GHz and SBCs on 5 GHz (`DSCP AF41`, `<= 3–4×` pacing); PoE AUX-2 option; Phase 1 isolation (`speaker.degraded`) + Phase 2 static neighbor ramp. |
| **Free-running UMIK-1 sway / noise & DIY MCU limits** | Calibration / Hardware | Hand sway (`5–10 ppm`), short re-sweeps, `RT60` bleed, `ESP32-C5` `384 KiB` RAM, or `ESP32-S3` 2.4 GHz break results. | Mandate tripod, wired `FL` ref, `>= 1.0 s` decay gaps, memoized `clock_ratio_ppm`, `60–100 Hz` LFE phase-sum; promote Solder-Free BOM & gate optional DIY `0A`. |
| **Remote host downtime stops speakers** | Availability | Stopping `spatialfin-avr` interrupts viewing-room speaker audio. | Standalone `restart: unless-stopped`, `spatialfin-avr healthcheck`, and documented non-destructive backup maintenance procedure. |

## Non-Functional Requirements

*   **Security & Licensing:** Permissive `ISC OR Apache-2.0` Rust source repository (`spatialfin-avr` binary dynamically links Debian GPL `libav*` libraries inside the container image; custom LGPL FFmpeg noted as optional build); Bearer auth + `120 s` single-use PIN (`5` per-IP `/32` or `/64` per `10 min` + `5` global lockout); full Spec 0 §3 DNS-pinned SSRF allowlist with `AVFormatContext.io_open` locking and redirects disabled; `RedactedSecret(Zeroizing<String>)` tokens (`"[REDACTED]"` `Debug`/`Display`); `/data/*.json` `0600` atomic writes; `SIGTERM` releases sessions (`"server_shutdown"`), flushes speakers, and zeroes secrets.
*   **Performance & Latency:** Target `< 15%` of one core on an Intel N100-class x86_64 or Cortex-A76-class ARM64 host for 12-ch->16-out `48 kHz` (`f32` bus / `f64` biquad) DSP + Phase 3 3D VBAP + FLAC level `0–2`; `< 15 ms` stop via `stream/clear`; `|clock_skew_us| < 200 µs`; total `dsp_group_delay_us <= 100 ms` (`<= 95 ms` EQ + `5 ms` limiter); zero heap allocations inside the `10 ms` DSP thread.
*   **Scalability & Rollout/Rollback:** Hard caps (`256 KiB` WS, `64 MiB` disk-streamed HTTP upload, `16` WS observers, `16` speaker slots, single active room/session); Tier 0 safety active out of the box; 1-slot `/revert` calibration rollback and additive Phase 0 -> 1 -> 2 -> 3 rollout (`capabilities` flags).
*   **Reliability & Observability:** Docker `restart: unless-stopped`, `spatialfin-avr healthcheck`, `6_000 ms` `heartbeat_ttl_ms` + `600_000 ms` `pause_idle_release_ms`, `< 200 ms` Phase 3 TrueHD->bed failover, `300–500 ms` disconnect detection -> `speaker.degraded` / `speaker.restored`, and structured JSON logs correlated by Spec 0 `session_id` (all secrets redacted).

## Additional Resources

*   Spec 0 wire contract & glossary: [theater-audio-protocol-v1.md](./theater-audio-protocol-v1.md)
*   Mirrored contract fixtures: `schemas/theater-audio/v1/` (`SHA256SUMS` + `fixtures/`)
*   Spec 1 Android client: [spatialfin-theater-audio-android-client.md](./spatialfin-theater-audio-android-client.md)
*   SpatialFin architecture notes: [GEMINI.md](../../GEMINI.md)
*   Key Rust crates: `axum`, `mdns-sd`, `ffmpeg-next` / `ffmpeg-sys-next`, `rubato`, `realfft`, `flacenc`, `zeroize`, `alsa`, `truehd` / `oamd`, `syncspin-*`

## Future Improvements

*   **Opt-In E-AC-3 JOC Object Decoder:** Support `VoidXH/Cavern` or clean-room Rust JOC decoding behind `spatialfin-spatial` once licensing/native support allows.
*   **Compiled Cross-Platform Capture CLI (`avr-capture-cli`):** Dedicated Rust `cpal` desktop capture binary superseding `scripts/capture-sweep.sh`.
*   **DTS:X / Neural Height Upmixer (`DtsHeightUpmixer`) & MDAP Spread:** Decorrelated matrix height extractor (`capabilities.dts_height_upmix: true`) and multi-directional amplitude panning spread.
*   **Late-Night Binaural Mode:** Head-Related Transfer Function (`HRTF`) binaural profile streaming 2-ch 48 kHz over Sendspin 1.0 Noise back to SpatialFin once `:sendspin` upgrades past `sendspin-jvm:0.2.3`.
*   **Bias-Lighting Sync & Host-in-Room HDMI AVR Bridge:** Sendspin visualizer role for `WLED`/ESPHome strips and a dedicated local HDMI bitstream bridge for hardware Atmos/DTS:X AVRs.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

### Target Files to Create/Modify (`Repository: spatialfin-avr`)

*Note: Zero modifications to `SpatialFin` or `SpatialFin-Companion`.*

#### Phase 0 — Standalone Pre-BOM-Lock & Protocol Spike Gates

1. `spatialfin-avr/scripts/phase0/` & `crates/phase0-spikes/` (Create) — Standalone Phase `0B` (`syncspin-*` interop vs. ESPHome `2026.4+` + live MA latency) and `0C` (Jellyfin `audioCodec=copy` vs. `static=true` HTTP Range-skip benchmark + 80 GB seek test), plus optional non-blocking `0A` (`ESP32-C5` `24-bit/48 kHz` FLAC level `0–2` soak harness).

#### Phase 1 — Core 3-Crate Workspace, Spec 0 Server, Bed DSP, Tier 1 Import & Unified Sink

2. `spatialfin-avr/{Cargo.toml,LICENSE-ISC,LICENSE-APACHE,README.md}` (Create) — 3-member workspace (`crates/avr-protocol`, `bins/spatialfin-avr`, `bins/spatialfin-sink`) under `ISC OR Apache-2.0` source licensing with project setup docs.
3. `spatialfin-avr/schemas/theater-audio/v1/{SHA256SUMS,fixtures/{manifest.json,valid/,additive-forward-compat/,invalid/}}` (Create) — Byte-identical mirror of `SpatialFin/schemas/theater-audio/v1/`.
4. `spatialfin-avr/crates/avr-protocol/{Cargo.toml,src/{lib.rs,auth.rs,ws.rs,http.rs,ssrf.rs,mdns.rs}}` (Create) — Axum `SPATIALFIN_AVR_PORT` (`1984`) Spec 0 §2–§8 HTTP/WS control server (`CLOCK_BOOTTIME`), additive deserialization, `120 s` PIN/Bearer auth (`/pair/pin`, `/pair/claim`, `/clients/{id}`, `/info`, `/status`, `/room/profile`, calibration routes, canonical `qr_uri`), full Spec 0 §3 + `safe-fetch.js` DNS-pinned SSRF guard (redirects disabled), `RedactedSecret(Zeroizing<String>)`, Linux `SO_REUSEADDR` `_spatialfin-avr._tcp.local.`, `6_000 ms` `heartbeat_ttl_ms`, `600_000 ms` `pause_idle_release_ms`, and `256 KiB` / `64 MiB` / `16`-slot caps.
5. `spatialfin-avr/bins/spatialfin-avr/{Cargo.toml,src/{main.rs,config.rs,storage.rs,ingest.rs,spatial.rs,dsp.rs,calibration_tier1.rs,sendspin.rs}}` (Create) — Main daemon wiring atomic `0600` `/data` schemas (`avr_state.json`, `sendspin_keys.json`, `calibrations/`), CLI subcommands (`serve`, `pair-pin`, `sendspin-pair`, `healthcheck`), `SIGTERM` teardown (`session.released` `"server_shutdown"`, `stream/clear`, secret zeroization), `std::thread` `unsafe` `ffmpeg-sys-next` `io_open`/`io_close2` + HTTP `Range` ingest (`libavcodec` `drc_scale=0`, `IngestPacket` + `rubato` inbound ASRC), 19-token `IdentityBedRenderer`, `std::thread` `f32`/`f64`-biquad DSP engine (`-10 dB` mains pad, LR4 + discrete `+10 dB` LFE + APF, `<= 95 ms` `eq_group_delay_us` + `0 µs` `eq_bypass`, `room_geom_delay_us`, emitted-PTS ring-map + slow `tau >= 5–10 s` PI, `-1.5 dBFS` sample-peak limiter), hardened Tier 1 CamillaDSP Room EQ importer, and downstream Sendspin 1.0 server (`:8937`, Noise `KKpsk2`, 2D Kalman, `DSCP AF41`, `300–500 ms` disconnect isolation).
6. `spatialfin-avr/bins/spatialfin-sink/{Cargo.toml,src/main.rs}` & `spatialfin-avr/deploy/spatialfin-sink.service` (Create) — Unified viewing-room Linux binary (`--channels 2..8`, `--alsa-device hw:...`) receiving one interleaved N-ch Sendspin PCM/FLAC stream behind one Noise `KKpsk2` + 2D Kalman receiver (`rubato` fractional rate-adjust resampling -> `alsa` `hw:` with `EPIPE` recovery) plus `systemd` unit.
7. `spatialfin-avr/{docker/Dockerfile,docker-compose.yaml}` & `spatialfin-avr/hardware/{README.md,esphome/{poe-connect-aux2.yaml,surround-esp32c5.yaml,surround-esp32s3.yaml}}` (Create) — Multi-stage (`Rust >= 1.88` -> `debian:bookworm-slim` + `libavformat59`/`libavcodec59`/`libavutil57`) standalone Docker image (`HEALTHCHECK` via `spatialfin-avr healthcheck`, UID `1000`, `cap_drop: [ALL]`, named `spatialfin-avr-data:/data` volume) plus BOM/GPIO/Monitor-knob docs (`10 kΩ` `XSMT` pull-down for optional DIY ESP32/`PCM5102A` templates; integrated mute on PoE Connect AUX-2).
8. `spatialfin-avr/crates/avr-protocol/tests/{protocol_contract.rs,security_ssrf.rs}` & `spatialfin-avr/bins/{spatialfin-avr/tests/{ingest_seek.rs,dsp_physics.rs,sendspin_mock.rs,phase0_gates.rs},spatialfin-sink/tests/sink_alsa.rs}` (Create) — Offline unit/contract tests across Spec 0 fixtures, SSRF/redirect rejection, `RedactedSecret` drop zeroization + redacted `Debug`/log output, HTTP `Range` seek mocks, `±100 ppm` slow-PI lock, `-1.5 dBFS` limiter, `0 µs` `eq_bypass`, CamillaDSP hardening (`<= 256 KiB` / `<= 9600` taps / `<= 95 ms`), mock Sendspin disconnect/`stream/clear`, N-ch `sink` interleaving, plus `#[ignore]`-gated `phase0_gates.rs`.

#### Phase 2 — Tier 2 Native UMIK-1 Calibration, Static Neighbor Reroute & Optional MA Zone

9. `spatialfin-avr/bins/spatialfin-avr/src/{calibration_tier2.rs,ma_bridge.rs}` & `spatialfin-avr/scripts/capture-sweep.sh` (Create) — Bracketed wired-`FL` sweep generator (`2.0–10.0 s`, `>= 1.0 s` gaps), `< 0.5 ppm` estimation residual solver (memoized per `calibration_id`), Farina `realfft` + `0°/90°` `.txt` compensation, `60–100 Hz` LFE phase-sum, bounded PEQ (`+3..+6 dB`), `SNR`/`clipping` `needs_remeasure`, laptop `ffmpeg`/`curl` capture script (`recording_wav`, `umik_cal_txt`, `metadata_json`), static neighbor surround reroute table (`20 ms` upstream-of-crossover ramp), and optional Sendspin MA virtual zone + `controller` pause + inbound ASRC.
10. `spatialfin-avr/bins/spatialfin-avr/tests/calibration_synth.rs` (Create) — Synthetic `35 ppm` clock-drifted Farina recovery (`< 0.5 ppm` residual), LFE phase-sum, and single-channel `calibration_id` patch tests.

#### Phase 3 — In-Process TrueHD Atmos VBAP & `< 200 ms` Lazy Bed Failover

11. `spatialfin-avr/bins/spatialfin-avr/src/truehd_vbap.rs` & `tests/failover.rs` (Create) — `TrueHdAtmosRenderer` (`truehd`/`oamd` + Pulkki 3D VBAP hull closure, 2D fallback, `LFE*` exclusion, gain ramp) with 1-Major-Sync packet cache (`~1–2 KiB`), lazy FFmpeg bed failover tests (`< 200 ms`), and explicit `> 1_000 ms` drift resync integration checks.

### Verification Plan

*   **Phase 0 Hardware & Protocol Spike Gates (Pre-BOM-Lock / Pre-Phase-2; run explicitly via `cargo test -p spatialfin-avr --test phase0_gates -- --ignored` in `spatialfin-avr/`):**
    *   `0A` *(Optional DIY Gate)*: 2-hour `ESP32-C5` `24-bit/48 kHz` FLAC level `0–2` + `50` Noise `KKpsk2` reconnect soak reports `>= 40 KiB` min free internal heap and `0` WDT resets (`TEST_ESP32_IP`).
    *   `0B`: `syncspin` interop harness confirms steady-state Kalman `|clock_skew_us| < 200 µs` against ESPHome `2026.4+` / `spatialfin-sink`, output silence within `< 15 ms` of `stream/clear`, and acceptable whole-home MA group static-delay offset before enabling Phase 2 MA zone (`TEST_SENDSPIN_TARGET`).
    *   `0C`: Jellyfin `10.10+`/`12` ingest check verifies whether `audioCodec=copy` preserves OAMD + `?startTimeTicks=` offsets (else locks VOD default to `static=true` with Range-skip tuning and documents `2×` wired LAN budget), and `20` random seeks on an `80 GB` UHD TrueHD Atmos MKV achieve `< 1.5 s` p95 seek-reset with `<= 1` frame decoded-sample drift post-seek (`TEST_JELLYFIN_URL`).
*   **Offline Contract, Security, DSP & Container CI (`in spatialfin-avr/`):**
    *   `cmp schemas/theater-audio/v1/SHA256SUMS ../SpatialFin/schemas/theater-audio/v1/SHA256SUMS` exits `0`, and `(cd schemas/theater-audio/v1 && sha256sum -c SHA256SUMS)` exits `0`.
    *   `cargo test -p avr-protocol --test protocol_contract` and `cargo test --workspace` (excluding `#[ignore]` hardware gates) pass across Phases 1–3: Spec 0 fixture parity, full §3/`safe-fetch.js` SSRF & redirect blocking, `RedactedSecret` heap zeroization + `"[REDACTED]"` `Debug`/log output, HTTP `Range` seek mocks, `±100 ppm` slow-PI lock (`> 1_000 ms` resync sustain), `-1.5 dBFS` sample-peak limiter, `0 µs` `eq_bypass` (`<= 95 ms` EQ / `<= 100 ms` total `dsp_group_delay_us`), CamillaDSP YAML hardening, `< 0.5 ppm` synthetic `35 ppm`-drifted calibration recovery, N-ch `spatialfin-sink` alignment, and `< 200 ms` Phase 3 TrueHD->bed failover.
    *   `docker compose config -q` and `docker build -f docker/Dockerfile .` pass cleanly with non-root UID `1000` owning `/data`.
*   **End-to-End Live Theater Validation (`in spatialfin-avr/`):**
    *   `docker compose up -d` starts `spatialfin-avr` (`GET http://127.0.0.1:1984/health` returns `200`, `status: "ok"`) without touching `SpatialFin Companion`.
    *   Live playback confirms `0` Noise/Kalman drops across `spatialfin-sink` + wireless nodes during Seek/Pause (`< 15 ms` stop) and optional Phase 2 MA handover, `300–500 ms` disconnect isolation + `speaker.degraded`/`speaker.restored` on node power-pull, `< 1 ms` inter-channel impulse alignment across active `5.1–7.1.4` channels post-`/apply`, and `0 µs` `dsp_group_delay_us` delta when toggling `EQ Bypass`.

--------------------------------------------------------------------------------

*Drafted with EGM*
