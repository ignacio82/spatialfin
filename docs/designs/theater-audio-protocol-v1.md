# Spec 0: SpatialFin Theater-Audio Wire & Capability Contract (v1)

*   **Author:** Ignacio Martinez <ignacio@martinez.fyi>
*   **Status:** Accepted
*   **Date:** 2026-11-26

--------------------------------------------------------------------------------

## Overview

This document defines **Spec 0** (`theater-audio-v1`), the versioned local-network (LAN) wire and capability contract between the **SpatialFin App** ([Spec 1](./spatialfin-theater-audio-android-client.md), running on Galaxy XR headsets and Beam phones), the standalone Rust **Theater-Audio Service** (`spatialfin-avr`, [Spec 2](./spatialfin-avr-rust-service-and-hardware.md), running in Docker on a wired LAN host in another room), and room-side **Tier 2** UMIK-1 calibration capture tools.

Spec 0 is a strictly two-party wire specification (`SpatialFin` / capture client ↔ `spatialfin-avr`). Neither the Android repository (`SpatialFin`) nor the Rust service repository (`spatialfin-avr`) MUST redefine discovery records, authentication rules, playback synchronization messages, capability flags, telemetry payloads, or calibration endpoints in prose; both implement the contract defined here.

### Normative Conventions

The key words `MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, and `MAY` in this specification are interpreted as described in RFC 2119 / BCP 14.

### Glossary & External Dependencies

| Term | Definition |
| --- | --- |
| **Sendspin 1.0** | Open timed-audio network streaming protocol using Noise `KKpsk2` encryption, 2D Kalman clock synchronization, `stream/clear` playout flushes, and endpoint `static_delay` hardware compensation (`spatialfin-avr` speaks Sendspin 1.0 to speakers and Music Assistant). |
| **Music Assistant (MA)** | Self-hosted whole-home music server on the same LAN. `spatialfin-avr` registers with MA as one virtual Sendspin zone while permanently managing physical theater speakers. |
| **FCast v4** | Existing unauthenticated LAN media-casting protocol (`_fcast._tcp`, `32 KiB` frame cap) currently used by SpatialFin Split A/V. |
| **Beam Phones** | SpatialFin's Android phone and USB-C external glasses (`Presentation`) form factor. |
| **App Lock** | SpatialFin's PIN/biometric screen lock requiring immediate teardown of active playback and in-memory tokens. |
| **Tier 0 / Tier 1 / Tier 2 Calibration** | **Tier 0:** playable default crossover/limiter safety profile. **Tier 1:** imported external Room EQ Wizard (REW) or Cavern QuickEQ CamillaDSP filter bundles. **Tier 2:** guided room-side UMIK-1 USB calibrated measurement microphone sweeps solved on `spatialfin-avr` (a required v1 server capability; optional per room). |
| **Home Assistant Connect AUX-2** | Stereo PoE Ethernet Sendspin 1.0 audio endpoint hardware referenced in Spec 2. |

## The Problem

Today, Split A/V playback in SpatialFin requires a second Android device running SpatialFin wired directly to a home theater AV receiver (AVR) over FCast v4. Evolving SpatialFin to drive a standalone Rust software AVR over Sendspin 1.0 speakers up to 7.1.4 Dolby Atmos exposes five wire-protocol gaps:

1. **No authentication or credential boundary:** FCast runs over unauthenticated LAN TCP with a `32 KiB` frame limit. Current Split A/V appends the user's Jellyfin access token to stream URLs (`?api_key=`), risking credential exposure in logs and process arguments on remote hosts.
2. **Mismatched playback and seek synchronization:** Independent video and audio seeks land on different IDR keyframes, while Sendspin's `500–750 ms` wireless playout buffer conflicts with fast pause/seek and native `24p`/`90 Hz` XR video pacing. Existing Split A/V trims client video speed and applies hardcoded `+35–60 ms` AVR latency guesses, introducing lip-sync error when the AVR already knows its exact pipeline lead times.
3. **Missing cinema session lifecycle signals:** Mid-movie audio track changes, episode transitions, abrupt client exit, App Lock teardown, central master volume, delay-invariant room EQ bypass, multi-client session arbitration, and MA virtual-zone handoff have no representation on FCast.
4. **No calibration or room-geometry contract:** Hosting the AVR in another room requires an authenticated LAN interface for speaker-ID chimes, bracketed UMIK-1 log sweeps, multi-megabyte calibration WAV and serial calibration `.txt` uploads, Tier 1 filter bundle uploads, and a metric Room Coordinate Frame shared between Galaxy XR and Beam phones.
5. **Cross-repo schema drift risk:** Defining wire fields independently in Android and Rust design docs causes mismatched clock domains, codec enums, and error handling across releases.

## Goals

*   **Single Authoritative Wire Contract:** Define LAN discovery, authentication, WebSocket RPCs, HTTP REST endpoints, enums, error codes, and golden test fixtures in one versioned contract (`protocol_version: 1`).
*   **Credential-Safe Jellyfin Handoff:** Carry the Jellyfin stream reference, `audio_stream_index`, `media_source_id`, and `jellyfin_token` over authenticated payload fields—never in query strings, CLI arguments, or plaintext logs—while keeping SpatialFin the sole Jellyfin session and watch-progress owner.
*   **Exact Codec Parity Without Translation:** Reuse the canonical lowercase codec tokens defined in SpatialFin's `ReceiverAudioCodecs` so lossless Jellyfin streams direct-stream without transcode penalties.
*   **Native `1.0000×` Video Cadence & Causal `T_play` Sync:** Define client-to-AVR clock sync, two-step keyframe-first `playback.ready` barriers with causal acoustic lead-time calculation, `< 15 ms` pause flushes, delay-invariant live `EQ Bypass`, and client-normalized video-timing beacons (`visible_avr_us`) so `spatialfin-avr` absorbs quartz drift via audio resampling.
*   **Fail-Closed Session Lifecycle & Multi-Client Rules:** Guarantee that abrupt client exit, App Lock, user switch, or network loss tears down cinema playback, zeroes in-memory Jellyfin tokens, and returns `spatialfin-avr` to MA virtual-zone idle within `6_000 ms`, while arbitrating concurrent clients deterministically.
*   **Pure-HTTP Calibration & Unified Room Geometry:** Expose speaker discovery, chimes, Tier 1 bundle upload, Tier 2 UMIK-1 sweep orchestration and single-channel draft patching, and Room Coordinate Frame CRUD over authenticated HTTP REST so capture tools require no WebSocket stack.

## Non-Goals

*   **Redefining Sendspin 1.0 Wire Framing:** Communication between `spatialfin-avr` and downstream speakers or upstream MA follows Sendspin 1.0.
*   **Android Client & Rust Server Internals:** Module boundaries, Binder IPC, ExoPlayer/ARCore mechanics, Tokio task graphs, FFmpeg/DSP math, Farina deconvolution, opt-in `.NET` Cavern Joint Object Coding (JOC) worker IPC, and hardware BOM belong in [Spec 1](./spatialfin-theater-audio-android-client.md) and [Spec 2](./spatialfin-avr-rust-service-and-hardware.md).
*   **`SpatialFin Companion` API Coupling (v1):** `SpatialFin Companion` does not expose discovery endpoints, sync bearer tokens, or proxy traffic in Spec 0 v1; segmented VLANs use QR pairing or manual `host:port` entry.
*   **Non-Jellyfin Media Sources (v1):** Offline downloads, SMB/NFS shares, local device files, universal source plugins, and inbound FCast streams are excluded in v1.
*   **Host-in-Room Hardware Sinks & Unsupported Surfaces (v1):** Direct host USB DAC, local UMIK-1, local HDMI passthrough, Galaxy XR Home Space, Android TV, WebXR, and direct Wear OS AVR links are excluded in v1.
*   **Deferred Roadmap Features:** Late-Night Binaural Mode (Head-Related Transfer Function, HRTF) and Sendspin bias-lighting control are excluded from v1 wire requirements.

## Open Questions

Empirical hardware and media-server measurements are gated by Spec 2 Phase 0 validation:

*   **Phase 0 Check 1 (Jellyfin Audio-Only Copy):** Verify on Jellyfin `10.10+`/`12` whether `audioCodec=copy` preserves TrueHD Object Audio Metadata (OAMD) and E-AC-3 JOC metadata; Spec 0 supports both multi-stream containers and single-stream audio URLs via `stream_layout`.
*   **Phase 0 Check 2 (HLS Video Transcode Priming Offset):** Measure residual presentation-timestamp (PTS) offset on Jellyfin HLS fMP4/TS transcodes relative to direct containers so client resume-offset normalization remains accurate across post-resume seeks.

## Critical User Journeys (CUJs)

### CUJ 1: Discover, Pair & Start Lossless Split A/V Playback (XR & Beam)

*   **Given:** `spatialfin-avr` runs on the wired LAN in another room, persistently connected to calibrated 5.1 or 7.1.4 Sendspin speakers and registered as a virtual MA zone. SpatialFin on Galaxy XR or a Beam phone holds an AVR Bearer token.
*   **When:** The user picks the `spatialfin-avr` room from SpatialFin's Split A/V picker and plays a Jellyfin TrueHD Atmos or DTS-HD MA movie.
*   **Then:**
    1. SpatialFin confirms `source_type == "jellyfin"`, picks the lossless direct stream matching `supported_audio_codecs`, disables local audio, holds the target IDR frame at `actual_pts_us`, and sends `session.start` (carrying `stream_url` without query credentials plus `jellyfin_token`).
    2. `spatialfin-avr` validates `stream_url` against its SSRF allowlist, opens the Jellyfin stream with header `X-Emby-Token`, pauses MA's virtual zone, seeks to `actual_pts_us` on `playback.ready`, computes `t_dac_0` and acoustic arrival `t_play_avr_us`, stamps and burst-fills Sendspin buffers, and returns `rpc.ok` with `t_play_avr_us`.
    3. SpatialFin releases the target IDR frame at `t_play_avr_us - theta_us - display_offset_us`, `spatialfin-avr` crossfades into cinema audio over `30 ms` at `t_play_avr_us`, and video runs at `1.0000×` cadence.

### CUJ 2: Seek, Pause, Resume, Track Switch & Steady-State Drift Control

*   **Given:** An active `theater-audio-v1` playback session is running.
*   **When:** The user pauses, resumes, seeks, switches audio tracks, auto-advances episodes, adjusts volume, or toggles `EQ Bypass`.
*   **Then:**
    1. On Pause, SpatialFin sends `playback.pause` with `flush: true`; `spatialfin-avr` broadcasts Sendspin `stream/clear` (stopping room sound within `15 ms`) while holding speaker sessions open. Resuming from `paused` runs `playback.ready` to re-align audio and video.
    2. On Track Switch or Episode Change, SpatialFin sends `playback.load` (incrementing `barrier_seq`, with replacement `stream_url` when required) followed by `playback.ready` on the decoded target IDR frame.
    3. During playback, SpatialFin emits `2 Hz` `playback.video_timing` beacons (`visible_avr_us`) at `1.0000×` speed while `spatialfin-avr` absorbs quartz drift via audio resampling (`±100 ppm`, `~0.0017 semitones`) and pushes `1 Hz` `telemetry.session` updates. Toggling `EQ Bypass` keeps `dsp_group_delay_us` unchanged (`0 µs` sync jump).

### CUJ 3: Abrupt Exit, App Lock, Speaker Drop & Local Fold-Back

*   **Given:** An active `theater-audio-v1` playback session is running.
*   **When:** The player process dies, App Lock triggers, the Jellyfin user signs out, the user taps local audio fold-back, or one wireless surround drops.
*   **Then:**
    1. Clean stop, App Lock, user switch, or fold-back sends `session.stop`; `spatialfin-avr` stops decoding, zeroes `jellyfin_token`, and returns to MA idle.
    2. On abrupt crash or Wi-Fi drop, `heartbeat_ttl_ms` (`6_000 ms`) expires, stopping playback, zeroing `jellyfin_token`, and restoring MA idle.
    3. If a wireless surround/height drops mid-movie, `spatialfin-avr` reroutes its energy to healthy neighbors without stalling and pushes `speaker.degraded` (and `speaker.restored` on rejoin).

### CUJ 4: Tier 2 UMIK-1 Calibration & Tier 1 Filter Import (Pure HTTP)

*   **Given:** `spatialfin-avr` reports connected Sendspin endpoints in `GET /api/v1/status` and a user attaches a UMIK-1 to a Beam phone (USB-C OTG) or laptop CLI (or brings a REW / Cavern QuickEQ CamillaDSP bundle).
*   **When:** The user maps speakers, runs stepped chime checks to verify safe Sound Pressure Level (SPL) headroom locally, initiates a bracketed sweep via HTTP, and uploads the continuous 24-bit/48 kHz WAV + UMIK-1 serial calibration `.txt` file (or Tier 1 bundle).
*   **Then:**
    1. `spatialfin-avr` schedules `[Ref Chirp Front-L @ T_0] → [Log Sweep Ch 1..N] → [Ref Chirp Front-L @ T_end]` and returns schedule timestamps.
    2. Uploading the recording returns `200 OK` with per-channel SNR, clipping, delay, polarity, trim, and `needs_remeasure` flags (allowing single-channel re-measure against the staged `calibration_id` draft), ready for `POST /api/v1/calibration/apply` or `/revert`.

### CUJ 5: Room Coordinate Frame Sync & Dark-Room Acoustic Center-Snap

*   **Given:** A configured room profile exists on `spatialfin-avr`.
*   **When:** Galaxy XR saves Left-Center-Right (LCR) and speaker coordinates (or a Beam phone edits them manually) via `PUT /api/v1/room/profile`, and later opens a movie in `0 lux` where optical tracking fails.
*   **Then:** SpatialFin triggers a `200 ms` Front Center (`FC`) localization cue (`POST /api/v1/calibration/center-snap` or WS `calibration.center_snap`) so the user can align the virtual cinema screen to physical LCR.

## Technical Plan (Architecture)

### 1. Dual-Plane LAN Transport

`spatialfin-avr` listens on one configurable TCP port (default `1984`, env `SPATIALFIN_AVR_PORT`, avoiding collisions with `SpatialFin Companion` `1982`, `FCast` `46899`, and MA `8095`/`8927`/`8928`). Transport URLs are scheme-agnostic (`ws://`/`http://` on a trusted LAN, or `wss://`/`https://` behind an operator TLS proxy).

1. **Real-Time Control & Telemetry Plane (`GET /ws/v1/control`):** Authenticated WebSocket carrying JSON envelopes (`<= 256 KiB` per frame) for handshake, clock pings, session lifecycle, `playback.ready` barriers, `2 Hz` video beacons, live volume/EQ/upmix control, lip-sync chirps, and `1 Hz` telemetry.
2. **HTTP REST & Binary Upload Plane (`/api/v1/*` + `/health`):** Authenticated HTTP/1.1 endpoints (`<= 64 MiB` multipart uploads) for static server info, live status + connected Sendspin endpoint discovery, pairing/revocation, Room Coordinate Frame CRUD, speaker chimes, Tier 2 sweeps + WAV/`.txt` upload, Tier 1 bundle upload, and draft apply/revert. Keeping all setup and calibration flows on HTTP allows standalone capture tools (`avr-capture-cli`) to run without a WebSocket client.

```text
+--------------------------------+        mDNS: _spatialfin-avr._tcp :1984        +-------------------------------+
|  SpatialFin (XR / Beam)        | <--------------------------------------------- |  spatialfin-avr (Rust Host)   |
|  - Video @ 1.0000x cadence     |                                                |  - Default port 1984          |
|  - Clock A: CLOCK_BOOTTIME     | == WS /ws/v1/control (Bearer, <= 256 KiB) ===> |  - Clock B: CLOCK_BOOTTIME    |
|  - Local audio disabled        |    hello, clock.ping, session.*, playback.*,   |  - Virtual AVR Intermediary   |
|                                |    room.*, calibration.lip_sync_chirp, events  |  - Sendspin 1.0 Server (down) |
|  Calibration Capture Client    |                                                |  - Sendspin 1.0 Client (MA)   |
|  (Beam OTG / Laptop CLI)       | == HTTP /api/v1/* (Bearer, <= 64 MiB) =======> |                               |
+--------------------------------+    /info, /status, /pair/*, /clients/*,        +---------------+---------------+
                                      /room/profile, /calibration/*                               |
                                                                                                  | Jellyfin HTTP
                                                                                                  | X-Emby-Token
                                                                                                  v
                                                                                         [Jellyfin Server (LAN)]
```

### 2. LAN Discovery (`_spatialfin-avr._tcp.local.`)

`spatialfin-avr` advertises one mDNS service instance per room with `SO_REUSEADDR` enabled on Linux. For segmented VLANs where mDNS is blocked, clients pair via QR URI or manual `host:port` entry.

| TXT Key | Required | Allowed Values / Format | Meaning |
| --- | --- | --- | --- |
| `v` | Yes | `"1"` | Supported major `protocol_version`. |
| `id` | Yes | `^avr-[0-9a-f-]{8,64}$` | Persistent server identifier (maps to `CastReceiver.id = "SendspinAvr:<id>"`). |
| `name` | Yes | UTF-8 string (`1..64` chars) | Human-readable room name shown in picker UI. |
| `layout` | Yes | `^\d+\.\d+(\.\d+)?$` (e.g., `5.1`, `7.2.4`) | Active calibrated speaker layout summary. |
| `auth` | Yes | `"bearer"` | Required HTTP/WS authentication scheme. |
| `pair` | Yes | `"pin"` \| `"static"` | `"pin"` when bootstrap/operator PIN claiming is enabled; `"static"` when `SPATIALFIN_AVR_TOKEN` is pinned. |
| `sw` | Yes | SemVer string (e.g., `"0.1.0"`) | `spatialfin-avr` software version. |

### 3. Authentication, Pairing & Jellyfin SSRF Guard

#### Client Authentication (`Authorization: Bearer <avr_token>`)

All `/ws/v1/control` upgrades and `/api/v1/*` routes MUST require `Authorization: Bearer <avr_token>`, except `POST /api/v1/pair/claim`. `GET /health` (outside `/api/v1/*`) is unauthenticated and returns only liveness state. Tokens MUST NOT be accepted in URL query parameters.

1. **Per-Client Token Minting + Single-Use `120 s` PIN:** When `SPATIALFIN_AVR_TOKEN` is unset, `spatialfin-avr` opens a single-use 6-digit pairing PIN (`120 s` TTL) **only** on initial unpaired boot (zero paired clients) or when explicitly armed via host CLI or an authenticated `POST /api/v1/pair/pin` call from an already-paired client. The PIN and QR payload (`spatialfin-avr://pair?host=<ip>&port=1984&id=<id>&pin=<6digits>&tls=<0|1>`) are printed exclusively to container stdout / the authenticated `POST /api/v1/pair/pin` response—never on any unauthenticated HTTP route. `POST /api/v1/pair/claim` enforces both per-IP rate limiting (max `5` failed attempts per `10 min` per IPv4 `/32` or IPv6 `/64`) and global PIN lockout (`5` total failed attempts invalidates the active PIN). Claiming mints a distinct 256-bit Bearer token per client (`{ avr_token, server_id, client_id, protocol_version: 1 }`), stored server-side only as a SHA-256 hash (`0600` state file) and revocable via `DELETE /api/v1/clients/{client_id}`.
2. **Static Env Override (`SPATIALFIN_AVR_TOKEN`):** Setting `SPATIALFIN_AVR_TOKEN` (minimum 32 characters) disables `POST /api/v1/pair/claim` and `POST /api/v1/pair/pin` (returning HTTP `403`, `error_code: "pairing_disabled"`).

#### Jellyfin Credential Boundary & SSRF Rules

*   SpatialFin remains the sole Jellyfin session owner (authentication, playback reporting, media selection). `spatialfin-avr` MUST NOT persist Jellyfin credentials or call Jellyfin `/Sessions/Playing*`.
*   `session.start` and `playback.load` pass `stream_url` with **no** embedded credentials and pass the session credential in `jellyfin_token` (`string`, non-empty). `spatialfin-avr` MUST hardcode HTTP header `X-Emby-Token`, MUST reject tokens containing `\r`, `\n`, or `\0` (`invalid_request`), and MUST reject any `stream_url` containing URL userinfo or case-insensitive `api_key=` / `token=` query parameters (`?` or `&`) with `url_rejected`.
*   `spatialfin-avr` MUST perform all outbound Jellyfin HTTP reads through a DNS-pinned HTTP client (never an uncontrolled demuxer socket dialer) with HTTP redirects disabled—or validated hop-by-hop with DNS pinning, HTTPS→HTTP downgrade blocking, and cross-origin `X-Emby-Token` stripping. Only `http`/`https` URLs resolving to RFC1918/ULA private LAN addresses or operator-configured `SPATIALFIN_ALLOWED_JELLYFIN_ORIGINS` (`scheme://host:port`) are permitted; link-local (`169.254.0.0/16`, `fe80::/10`), AWS IPv6 IMDS (`fd00:ec2::254`), IPv4-mapped IPv6 (`::ffff:0:0/96`), NAT64 (`64:ff9b::/96`), non-canonical IPv4 literals, unspecified (`0.0.0.0/8`, `::`), multicast (`224.0.0.0/4`, `ff00::/8`), CGNAT metadata (`100.100.100.200`), loopback (`127.0.0.0/8`, `::1`, unless `SPATIALFIN_ALLOW_LOOPBACK_JELLYFIN=true`), and the AVR's own listening port (`1984`) MUST be rejected with `url_rejected`.
*   `jellyfin_token` MUST remain strictly in volatile RAM scoped to `session_id`, MUST be scrubbed from logs/errors, and MUST be zeroed on `session.stop`, error, or `heartbeat_ttl_ms` expiry.

### 4. Wire Framing, Uniform RPC Replies & Static Capabilities

#### WebSocket Envelope (`/ws/v1/control`)

Every WebSocket message uses one flat JSON envelope:

```json
{
  "v": 1,
  "type": "session.start",
  "id": "req-42",
  "session_id": "9f2c7b1e-4c1a-4b11-9d2a-1e72c8b0f4a1",
  "ts_us": 200123456,
  "payload": {}
}
```

*   `v` (`uint32`, required): Major protocol version (`1`). During handshake, clients send the server's advertised `v` (or `min_protocol_version` if unknown).
*   `type` (`string`, required): Dot-namespaced message identifier.
*   `id` (`string`, optional): Correlation ID on request/response RPCs; omitted on one-way notifications (`playback.video_timing`, `session.heartbeat`, `telemetry.session`, async server events).
*   `session_id` (`string`, optional): Client-generated UUIDv4 identifying one Split A/V playback session. Carried **strictly** on the WS envelope (never duplicated inside WS `payload`); required on all `session.*`, `playback.*`, and active `telemetry.session` messages.
*   `ts_us` (`int64`, optional): Sender monotonic uptime (`CLOCK_BOOTTIME`) in microseconds for trace ordering.
*   **Uniform RPC Reply Rule:** Every client WS request carrying `id` MUST receive exactly one reply echoing `id` with either `type: "rpc.ok"` (result object in `payload`) or `type: "rpc.error"` (`payload: { code, message, retryable, details }`). Oversized WS frames (`> 256 KiB`) MUST close the socket with RFC 6455 status `1009` (Message Too Big).
*   **HTTP REST Error Envelope:** Every non-2xx REST response returns `application/json`: `{ "error_code": "<code_from_registry>", "message": "<human_string>", "retryable": false, "details": {} }`.

#### Static Capability Manifest (`hello` `rpc.ok` & `GET /api/v1/info`)

Clients MUST complete `hello` before issuing `clock.ping` or any session/room RPC. `hello` request payload: `{ client_id, client_name, form_factor, app_version, min_protocol_version, max_protocol_version }`. Both `hello`'s `rpc.ok.payload` and `GET /api/v1/info` return the identical **static** server manifest (mutable runtime state belongs strictly in `GET /api/v1/status` and `telemetry.session`):

```json
{
  "protocol_version": 1,
  "server_id": "avr-550e8400e29b41d4a716446655440000",
  "server_name": "Living Room Theater",
  "software_version": "0.1.0",
  "layout": "7.1.4",
  "bus_sample_rate_hz": 48000,
  "sendspin_buffer_us": 600000,
  "max_safe_volume": 0.90,
  "heartbeat_ttl_ms": 6000,
  "pause_idle_release_ms": 600000,
  "video_timing_cadence_hz": 2,
  "supported_audio_codecs": [
    "truehd", "eac3", "eac3-joc", "dts", "dts-hd", "ac3",
    "flac", "alac", "aac", "mp3", "opus", "vorbis",
    "pcm", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le",
    "pcm_mulaw", "pcm_alaw"
  ],
  "capabilities": {
    "truehd_atmos_vbap": true,
    "dts_height_upmix": true
  }
}
```

*   **Canonical Codec Tokens:** `supported_audio_codecs` uses the exact 19 lowercase tokens declared in SpatialFin's `ReceiverAudioCodecs` (`ALL_PASSTHROUGH_TOKENS ∪ UNIVERSAL_SOFTWARE`). `spatialfin-avr` MUST omit `"eac3-joc"` whenever the opt-in E-AC-3 JOC worker is absent or unhealthy (retaining `"eac3"` so DD+ Atmos still direct-streams its 5.1 bed).
*   **Capabilities Scope:** `capabilities` exposes only runtime-variable v1 switches (`truehd_atmos_vbap`, `dts_height_upmix`); mandatory v1 features are implied by `protocol_version: 1`, and deferred features are added additively under Section 8.
*   **Source Eligibility:** Clients MUST start v1 sessions only for `source_type: "jellyfin"`; servers MUST reject any other value with `source_ineligible`.

### 5. Clock Sync, Causal `T_play` Launch, Inverted Drift & Session FSM

#### Clock Synchronization (`clock.ping` → `rpc.ok`)

Both Android clients (Clock A) and Linux `spatialfin-avr` (Clock B) MUST sample `CLOCK_BOOTTIME` in microseconds (avoiding suspend divergence). Sendspin endpoints lock to Clock B via Sendspin's 2D Kalman filter.

1. Client sends `clock.ping { t1_client_us }`; server replies `rpc.ok { t1_client_us, t2_avr_rx_us, t3_avr_tx_us }`.
2. Client records `t4_client_rx_us`, computes `rtt_us = (t4 - t1) - (t3 - t2)` and `theta_sample_us = ((t2 - t1) + (t3 - t4)) / 2` such that `t_avr_us ≈ t_client_us + theta_us`.
3. Clients MUST use a **bounded sliding-window** min-RTT / skew-tracking filter (not a non-decaying global `min_rtt_us`, which starves pings under 60–120 Mbps 4K UHD Wi-Fi load) achieving `< 2_000 µs` uncertainty under `<= 4 ms` LAN RTT spread, emitting `1 Hz` maintenance pings during active sessions.

#### Normalized Media Timeline (`media_pts_us`)

Every media timestamp (`target_pts_us`, `actual_pts_us`, `media_pts_us`) is **zero-based microseconds from the start of the media item** anchored to the container's **video track** reference origin:

*   **Direct-play video:** `media_pts_us = presentation_time_us` relative to video start; `spatialfin-avr` subtracts the container's video-track start origin (probed before discarding video streams) so muxed audio-vs-video delays in MKV/MP4 containers are preserved.
*   **HLS transcode video:** The client adds the active stream's resume start offset (re-captured per `barrier_seq` whenever Jellyfin restarts a transcode stream) to Media3's presentation time.
*   **Stream Layout (`multi_stream_container` vs `single_audio_stream`):** When `stream_layout == "multi_stream_container"`, `spatialfin-avr` selects `audio_stream_index`; when `stream_layout == "single_audio_stream"` (for `audioCodec=copy`), `spatialfin-avr` selects stream `0`, and track changes supply a replacement `stream_url` in `playback.load`.

#### Causal Two-Step Launch & Seek Barrier

```text
SpatialFin (XR / Beam)                                        spatialfin-avr (Rust)
        |                                                               |
        | 1. session.start (stream_url, stream_layout,                  |
        |    audio_stream_index, media_source_id, source_codec,         |
        |    target_pts_us, jellyfin_token, force_takeover)             |
        |-------------------------------------------------------------->| SSRF check + open/probe stream
        |                    rpc.ok (session_accepted)                  | Pause MA virtual zone
        |<--------------------------------------------------------------|
        | [Client seeks locally, decodes & holds target IDR frame       |
        |  at exact keyframe actual_pts_us]                             |
        |                                                               |
        | 2. playback.ready (barrier_seq=1, actual_pts_us)              |
        |-------------------------------------------------------------->| Seek & prime decode at actual_pts_us
        |                                                               | Compute t_dac_0 & t_play_avr_us FIRST
        |                                                               | Stamp chunk 0 @ t_dac_0 & burst-fill
        | 3. rpc.ok (barrier_seq=1, actual_pts_us, t_play_avr_us,       |
        |            lead_breakdown_us, codec_active, renderer_mode,    |
        |            active_decode_chain)                               |
        |<--------------------------------------------------------------|
        | [Release IDR frame at:                                        | [Crossfade 30 ms into cinema @
        |  t_play_client_us = t_play_avr_us - theta_us                  |  t_play_avr_us on Sendspin clock]
        |                   - display_offset_us]                        |
        |                                                               |
        | 4. playback.video_timing (2 Hz: barrier_seq, media_pts_us,    |
        |    visible_avr_us, clock_uncertainty_us)                      |
        |-------------------------------------------------------------->| drift_us = media_pts_us -
        |                                                               |   audio_heard_pts_us(visible_avr_us)
        |<------------------- telemetry.session (1 Hz) -----------------|
```

*   `session.start` opens and probes headers (never performing a redundant sample-accurate seek ahead of `playback.ready`).
*   **Causal Lead-Time Equations (computed after decode prime, BEFORE Sendspin burst transmission):**
    *   `t_dac_0 >= now_avr_us + sendspin_buffer_us + max_uncompensated_hw_us + launch_margin_us` (where `max_uncompensated_hw_us` is `0` when every endpoint's fixed DAC latency is already compensated server-side by Sendspin `static_delay`).
    *   `t_play_avr_us = t_dac_0 + dsp_group_delay_us + max_air_flight_us`, where `max_air_flight_us = max_i(||coord_m_i||) / 343.0 * 1e6` is acoustic flight time from the farthest speaker to the main listening position (MLP).
    *   `lead_breakdown_us` reports `{ decode_prime_us, dsp_group_delay_us, max_air_flight_us, sendspin_buffer_us, max_uncompensated_hw_us, launch_margin_us }` (`decode_prime_us` is informational only and MUST NOT be added after priming).
*   `playback.ready` MUST reply within `5_000 ms` (`seek_timeout` via `rpc.error` on timeout). If `t_play_avr_us - theta_us - display_offset_us - now_client_us < 1_000 µs` upon arrival, the client MUST retry with `playback.ready` (`barrier_seq + 1`).

#### Session Finite State Machine, Heartbeats & Multi-Client Arbitration

*   **States:** `idle` → `starting` → `awaiting_go` → `playing` ↔ `paused` / `stalled` → `idle`.
*   **Monotonic `barrier_seq` Fencing:** The client MUST increment `barrier_seq` on every state-changing control RPC (`session.start` initializes `1`; `playback.load`, `playback.pause`, `playback.stall`, `playback.ready` on seek/resume/resync all increment). Both peers MUST silently discard responses, stale beacons, and superseded in-flight seeks whose `barrier_seq != active_barrier_seq`.
*   **Pause & Resume:** `playback.pause { barrier_seq, flush: true }` triggers Sendspin `stream/clear` (stopping room audio within `15 ms`). Resuming from `paused` transitions back to `playing` by running `playback.ready` (`barrier_seq + 1`). Holding `paused` beyond `pause_idle_release_ms` (`600_000 ms`) releases the session back to MA idle and pushes `session.released { reason: "pause_timeout" }`.
*   **Video Buffering Stall:** When local video stalls for `> 100 ms`, the client sends `playback.stall { barrier_seq }` (server flushes to silence) and re-runs `playback.ready` once buffering clears.
*   **State-Independent Heartbeat Lease (`6_000 ms`):** `playback.video_timing` refreshes the lease during `playing`; the client MUST emit `session.heartbeat { barrier_seq, state }` every `2_000 ms` during `starting`, `awaiting_go`, `paused`, and `stalled` (`clock.ping` does not refresh the session lease). If `heartbeat_ttl_ms` (`6_000 ms`) elapses with no lease-refreshing message (including after a TCP/WS disconnect), `spatialfin-avr` stops decoding, zeroes `jellyfin_token`, flushes speakers, and returns to MA idle.
*   **Inverted Drift Control (`playback.video_timing`, `2 Hz`):** Video remains locked at `1.0000×`. Payload: `{ barrier_seq, media_pts_us, visible_avr_us, clock_uncertainty_us }`, where `visible_avr_us = release_boottime_us + display_offset_us + theta_us` (client-side display latency compensation plus clock offset). Server evaluates `drift_us = media_pts_us - audio_heard_pts_us(visible_avr_us)`, freezes drift integration whenever `clock_uncertainty_us > 5_000 µs`, targets the ITU-R BT.1359 hold band (`-10_000 µs` to `+15_000 µs`), and emits `playback.resync_required { barrier_seq, drift_us }` if `|drift_us| > 80_000 µs` persists for `> 1_000 ms` (capped at `3` resyncs per `30 s`).
*   **Multi-Client Concurrency:** Only one active playback session or calibration sweep runs at a time. A second client calling `session.start` receives `session_busy` (`409` / `rpc.error`) unless `force_takeover: true`, which stops the previous session with `session.released { reason: "preempted" }`. Active playback rejects calibration sweeps with `session_busy`, and an active sweep rejects `session.start` with `calibration_in_progress`. Authenticated secondary clients MAY call `room.set_volume`, `room.set_eq_bypass`, and `room.set_upmix_mode`; `spatialfin-avr` broadcasts `room.state_changed` to all connected sockets.

### 6. Authoritative WebSocket & HTTP REST Catalogs

#### WebSocket Message Catalog (`/ws/v1/control`)

| Message `type` | Dir | Reply / Framing | Key Payload Fields |
| --- | --- | --- | --- |
| `hello` | C → S | `rpc.ok` (static manifest) | `{ client_id, client_name, form_factor, app_version, min_protocol_version, max_protocol_version }` |
| `clock.ping` | C → S | `rpc.ok` | Req: `{ t1_client_us }`; Ok: `{ t1_client_us, t2_avr_rx_us, t3_avr_tx_us }` |
| `session.start` | C → S | `rpc.ok` | Req: `{ source_type: "jellyfin", stream_url, stream_layout, media_source_id, audio_stream_index, source_codec, target_pts_us, jellyfin_token, force_takeover? }`; Ok: `{ accepted: true, barrier_seq: 1 }` |
| `session.heartbeat` | C → S | One-way | `{ barrier_seq, state }` (`state` in `"starting" \| "awaiting_go" \| "paused" \| "stalled"`) |
| `session.stop` | C → S | `rpc.ok` | Req: `{ reason }` (`"user_stop" \| "fold_back" \| "app_lock" \| "user_switch" \| "activity_exit"`); Ok: `{ stopped: true }` |
| `playback.load` | C → S | `rpc.ok` | Req: `{ barrier_seq, audio_stream_index, target_pts_us, stream_url?, stream_layout?, media_source_id?, source_codec?, jellyfin_token? }`; Ok: `{ barrier_seq }` |
| `playback.ready` | C → S | `rpc.ok` (`<= 5 s`) | Req: `{ barrier_seq, actual_pts_us }`; Ok: `{ barrier_seq, actual_pts_us, t_play_avr_us, lead_breakdown_us, codec_active, renderer_mode, active_decode_chain }` |
| `playback.pause` | C → S | `rpc.ok` | Req: `{ barrier_seq, flush: true }`; Ok: `{ barrier_seq, flushed: true }` |
| `playback.stall` | C → S | `rpc.ok` | Req: `{ barrier_seq }`; Ok: `{ barrier_seq, flushed: true }` |
| `playback.video_timing` | C → S | One-way (`2 Hz`) | `{ barrier_seq, media_pts_us, visible_avr_us, clock_uncertainty_us }` |
| `room.set_volume` | C → S | `rpc.ok` | Req: `{ volume: 0.0..1.0, muted: bool }` (perceptual taper, clamped silently to `max_safe_volume`); Ok: `{ master_volume, muted }` |
| `room.set_eq_bypass` | C → S | `rpc.ok` | Req: `{ bypass: bool }` (delay-invariant during playback: `0 µs` lip-sync delta); Ok: `{ eq_bypass, dsp_group_delay_us }` |
| `room.set_upmix_mode` | C → S | `rpc.ok` | Req: `{ mode: "native" \| "dts_height_upmix" }`; Ok: `{ upmix_mode }` |
| `calibration.center_snap` | C → S | `rpc.ok` | Req: `{ duration_ms: 200, gain_dbfs: -18.0 }` (`<= -12.0` cap, `FC` only); Ok: `{ scheduled_avr_us }` |
| `calibration.lip_sync_chirp` | C → S | `rpc.ok` | Req: `{ roles: ["FL"], mode: "midband" \| "subwoofer", start_avr_us, repeat_count: 1..10, interval_ms: 500..3000, gain_dbfs: -24.0 }` (`<= -12.0`); Ok: `{ scheduled_avr_us: [...] }` |
| `telemetry.session` | S → C | Push (`1 Hz`) | Live playback, drift, DSP, room control, MA state, and per-speaker health (see §7). |
| `playback.resync_required` | S → C | Push | `{ barrier_seq, drift_us }` |
| `session.released` | S → C | Push | `{ reason }` (`"pause_timeout" \| "heartbeat_timeout" \| "preempted" \| "server_shutdown" \| "fatal_decode_error"`) |
| `room.state_changed` | S → C | Push | `{ master_volume, muted, eq_bypass, upmix_mode }` |
| `room.profile_updated` | S → C | Push | `{ room_profile_version, layout }` |
| `speaker.degraded` / `speaker.restored` | S → C | Push | Degraded: `{ role, client_id, rerouted_to_roles, reason }`; Restored: `{ role, client_id }` |
| `decoder.fallback` | S → C | Push | `{ from_chain, to_chain, codec_active, renderer_mode, reason }` |
| `ma.state_changed` | S → C | Push | `{ ma_zone_state: "idle" \| "playing_ma" \| "paused_for_cinema" }` |

#### HTTP REST Endpoint Catalog (`/api/v1/*` + `/health`)

| Method & Path | Auth | Content / Cap | Purpose & Key Payloads |
| --- | --- | --- | --- |
| `GET /health` | None | JSON | Liveness probe: `{ status: "ok", uptime_s }`. |
| `GET /api/v1/info` | Bearer | JSON | Identical static capability manifest as `hello` `rpc.ok`. |
| `GET /api/v1/status` | Bearer | JSON | Live state (`session_id: string \| null`, `state`, room controls, `ma_zone_state`, `speakers`, plus `connected_endpoints: [{ client_id, name, channel_count, transport_tier, online }]` for speaker mapping). |
| `POST /api/v1/pair/pin` | Bearer | JSON | Arms a new `120 s` single-use 6-digit PIN for pairing an additional device: `{ pin, qr_uri, expires_at_s }`. |
| `POST /api/v1/pair/claim` | PIN | JSON | Req: `{ pin, client_id, client_name }`; Res: `{ avr_token, server_id, client_id, protocol_version: 1 }`. |
| `DELETE /api/v1/clients/{client_id}` | Bearer | JSON | Revokes a paired client's Bearer token hash: `{ revoked: true }`. |
| `GET /api/v1/room/profile` | Bearer | JSON | Returns canonical metric Room Coordinate Frame (`ETag: "<room_profile_version>"`). |
| `PUT /api/v1/room/profile` | Bearer | JSON (`If-Match`) | Updates Room Coordinate Frame; rejects stale `If-Match` version with `409` (`version_conflict`). |
| `POST /api/v1/calibration/chime` | Bearer | JSON | Req: `{ role, signal: "chime" \| "pink_noise", gain_dbfs: -30.0..-12.0, duration_ms: 200..5000 }`. Also drives client-guided progressive SPL headroom checks. |
| `POST /api/v1/calibration/center-snap` | Bearer | JSON | HTTP equivalent of `calibration.center_snap` (`200 ms` `FC` cue, `gain_dbfs <= -12.0`). |
| `POST /api/v1/calibration/sweeps` | Bearer | JSON | Starts Tier 2 bracketed sweep. Req: `{ roles, ref_role: "FL", sweep_duration_s: 2.0..10.0, gain_dbfs: -36.0..-12.0, sample_rate_hz: 48000, calibration_id? }`. Res: `{ sweep_id, calibration_id, t0_ref_avr_us, channel_schedule: [{ role, start_avr_us, end_avr_us }], tend_ref_avr_us, sample_rate_hz: 48000 }`. |
| `DELETE /api/v1/calibration/sweeps/{sweep_id}` | Bearer | JSON | Immediately aborts an active sweep and flushes speakers: `{ aborted: true }`. |
| `POST /api/v1/calibration/sweeps/{sweep_id}/upload` | Bearer | Multipart (`<= 64 MiB`) | Parts: `recording_wav` (1-ch 24-bit/48 kHz PCM), `umik_cal_txt`, `metadata_json` (`{ mic_angle_deg: 0 \| 90, mic_serial, capture_platform }`). Returns `200 OK` `{ calibration_id, ready_to_apply, clock_ratio_ppm, dsp_group_delay_us, channels: [{ role, snr_db, clipped, total_delay_us, polarity_invert, trim_db, needs_remeasure }] }` (`SNR < 25 dB` or peak `>= -0.5 dBFS` flags `needs_remeasure: true` while preserving passing channels in the staged draft; returns `422` only on unrecoverable `ref_role` or format failure). |
| `POST /api/v1/calibration/bundles` | Bearer | Multipart (`<= 64 MiB`) | Tier 1 REW / Cavern QuickEQ CamillaDSP upload. Parts: `config_yaml`, optional `fir_wavs[]`. Validates `48 kHz` and `dsp_group_delay_us <= 100_000` (`422` otherwise) and stages draft `{ calibration_id, ready_to_apply: true, dsp_group_delay_us }`. |
| `POST /api/v1/calibration/apply` | Bearer | JSON | Activates staged draft (Tier 1 or Tier 2): Req `{ calibration_id }`; Res `{ active_calibration_id, dsp_group_delay_us }`. |
| `POST /api/v1/calibration/revert` | Bearer | JSON | Restores previous active calibration (1-slot rollback backup): Res `{ active_calibration_id, dsp_group_delay_us }`. |

#### Canonical Room Coordinate Frame & Enum Domains

Coordinates use a right-handed metric frame rooted at the **main listening position (MLP) at ear height** (`-Z` = toward physical Front Center, `+X` = right, `+Y` = up). Physical `lcr_screen_plane` (`center_m`, `width_m`, `height_m`, `normal_vec`) is optional/nullable; Android XR UI lock preferences remain client-local.

```json
{
  "schema_version": 1,
  "room_profile_version": 4,
  "room_name": "Living Room Theater",
  "layout": "7.1.4",
  "lcr_screen_plane": {
    "center_m": [0.0, 0.05, -2.85],
    "width_m": 2.40,
    "height_m": 1.35,
    "normal_vec": [0.0, 0.0, 1.0]
  },
  "speakers": [
    {
      "role": "FL",
      "client_id": "pi-front-dac",
      "channel_index": 0,
      "transport_tier": "wired_front_multich",
      "coord_m": [-1.25, 0.0, -2.80],
      "fixed_hw_delay_us": 12000,
      "room_geom_delay_us": 1450,
      "trim_db": -1.2,
      "polarity_invert": false,
      "crossover_hz": 80,
      "crossover_slope_db_oct": 24
    }
  ]
}
```

*   **Canonical Speaker Roles (13):** `FL`, `FR`, `FC`, `LFE1`, `LFE2`, `SL`, `SR`, `BL`, `BR`, `TFL`, `TFR`, `TBL`, `TBR`. Every speaker routes via `(client_id, channel_index)`. Listening-position `room_geom_delay_us` lives strictly in host DSP (never written to speaker flash `static_delay`).
*   **Canonical Enum Domains:**
    *   `form_factor`: `"xr_full_space" | "beam_phone" | "capture_cli"`
    *   `capture_platform`: `"android_otg" | "linux_alsa" | "macos_coreaudio" | "windows_wasapi"`
    *   `transport_tier`: `"wired_front_multich" | "poe_stereo" | "wifi_mono" | "wifi_stereo"`
    *   `renderer_mode`: `"bed" | "truehd_vbap" | "eac3_joc" | "dts_height_upmix"`
    *   `state`: `"idle" | "starting" | "awaiting_go" | "playing" | "paused" | "stalled"`

### 7. Live Telemetry & Canonical Error Registry

#### `telemetry.session` (`1 Hz` Push) & `GET /api/v1/status`

```json
{
  "state": "playing",
  "codec_active": "truehd",
  "renderer_mode": "truehd_vbap",
  "active_decode_chain": "truehd-atmos -> truehd+vbap(7.1.4) -> rust-dsp -> sendspin",
  "fallback_active": false,
  "fallback_reason": null,
  "drift_us": 2100,
  "resampler_ppm": -18.4,
  "dsp_group_delay_us": 18500,
  "master_volume": 0.65,
  "muted": false,
  "eq_bypass": false,
  "upmix_mode": "native",
  "ma_zone_state": "paused_for_cinema",
  "speakers": [
    {
      "role": "FL",
      "client_id": "pi-front-dac",
      "channel_index": 0,
      "online": true,
      "degraded_crossfade": false,
      "clock_skew_us": 45,
      "underruns": 0,
      "wifi_rssi_dbm": null,
      "codec": "pcm",
      "bits_per_sample": 24,
      "sample_rate_hz": 48000
    }
  ]
}
```

When `state == "idle"`, `GET /api/v1/status` sets `session_id: null`, `codec_active: null`, `renderer_mode: null`, `active_decode_chain: null`, `drift_us: 0`, and `resampler_ppm: 0.0`, while keeping live room controls, `ma_zone_state`, `speakers`, and `connected_endpoints` populated. Client-owned clock metrics (`clock_offset_us`, `clock_uncertainty_us`, `t_play_launch_error_us`) are recorded directly by the client rather than echoed by the server.

#### Canonical Error Code Registry (`rpc.error.payload.code` & HTTP `error_code`)

| Error Code | HTTP | Retryable | Meaning & Client Action |
| --- | --- | --- | --- |
| `auth_required` | `401` | No | Missing `Authorization: Bearer` header. |
| `auth_invalid` | `401` | No | Rejected or revoked token; prompt client to re-pair. |
| `pairing_disabled` | `403` | No | PIN pairing disabled (`SPATIALFIN_AVR_TOKEN` pinned or no active PIN armed). |
| `pin_expired` | `400` | Yes | Pairing PIN expired, used, or invalidated after 5 failures; arm a new PIN. |
| `rate_limited` | `429` | Yes | Per-IP brute-force limit hit; honor `Retry-After`. |
| `protocol_version_unsupported` | `400` | No | No overlap with server `v=1`; surface upgrade prompt. |
| `invalid_request` | `400` | No | Malformed JSON, invalid enum, CRLF in token, `gain_dbfs > -12.0`, or past `start_avr_us`. |
| `url_rejected` | `400` | No | `stream_url` failed SSRF allowlist or contained query credentials / userinfo. |
| `source_ineligible` | `400` | No | Non-Jellyfin `source_type` rejected in v1. |
| `session_busy` | `409` | Yes | Active cinema session owned by another client (retry with `force_takeover: true`) or playback blocks calibration. |
| `session_not_found` | `404` | No | Unknown or expired `session_id`. |
| `version_conflict` | `409` | Yes | Stale `If-Match` `room_profile_version` on `PUT /api/v1/room/profile`; re-fetch and retry. |
| `calibration_in_progress` | `409` | Yes | Active sweep blocks `session.start` or concurrent sweep. |
| `sweep_not_found` | `404` | No | Unknown or expired `sweep_id` / `calibration_id`. |
| `jellyfin_auth_failed` | `502` | No | Upstream Jellyfin rejected `X-Emby-Token` (`401`/`403`). |
| `jellyfin_unreachable` | `502` | Yes | Upstream Jellyfin connection/DNS failure. |
| `codec_unsupported` | `422` | No | Neither spatial renderer nor FFmpeg bed opened stream; offer local fold-back. |
| `seek_timeout` | `504` | Yes | Demux/burst-fill missed `5_000 ms` `playback.ready` deadline. |
| `speaker_offline_critical` | `503` | Yes | Front L/C/R endpoint offline at session start; name offline speaker. |
| `calibration_clipped` | `422` | Yes | Reference chirp clipped (`>= -0.5 dBFS`); lower `gain_dbfs` and retry. |
| `calibration_low_snr` | `422` | Yes | Reference chirp SNR `< 25 dB`; reduce ambient noise and retry. |
| `calibration_delay_exceeded` | `422` | No | Tier 1 FIR filter exceeds `100_000 µs` (`100 ms`) group-delay cap or non-`48 kHz` rate. |
| `payload_too_large` | `413` | No | HTTP multipart upload exceeded `64 MiB`. |
| `internal_error` | `500` | Yes | Unexpected server fault. |

### 8. Schema Versioning & Backward Compatibility

*   **Major version (`v`)** increments only for breaking removals, type changes, or semantic inversions negotiated via `hello`.
*   **Additive evolution within `v=1`:** New optional fields, capability flags, and server events MAY be added without bumping `v`. Kotlin (`Json { ignoreUnknownKeys = true }`) and Rust (`serde` structs omitting `#[serde(deny_unknown_fields)]`, with `#[serde(default)]` for optional fields) MUST tolerate unknown keys without failing deserialization.
*   **Cross-repo verification:** Wire compatibility will be enforced via shared golden JSON fixtures (`valid/`, `additive-forward-compat/`, `invalid/` + `manifest.json`) verified directly by the native `:fcast` (`kotlinx.serialization`) and `spatialfin-avr` (`serde`) test suites.

## Alternatives Considered

### Alternative A: Extending SpatialFin's Existing FCast v4 Wire Protocol

*   **Pros:** Reuses existing FCast framing and `_fcast._tcp` discovery with minimal Android transport changes.
*   **Cons:** FCast enforces a `32 KiB` frame limit, has no authentication boundary, lacks calibration upload or per-speaker 7.1.4 telemetry channels, and couples Split A/V tightly to FCast-specific playback assumptions.
*   **Reason for Rejection:** The `32 KiB` frame cap, absence of authentication, and FCast-only coupling block structured 7.1.4 telemetry, calibration orchestration, and out-of-band credential isolation.

### Alternative B: Passing Jellyfin Credentials via Query Strings (`?api_key=`)

*   **Pros:** Matches existing media URL conventions used on Google Cast and AirPlay targets.
*   **Cons:** Exposes long-lived Jellyfin user tokens in HTTP server logs, error traces, and process command lines on remote hosts.
*   **Reason for Rejection:** Carrying permanent account tokens in query strings violates credential isolation; `theater-audio-v1` transmits `jellyfin_token` strictly inside authenticated payload fields and enforces in-memory `X-Emby-Token` header injection.

### Alternative C: Client Video Speed Trimming (`setPlaybackSpeed`) as Primary Drift Actuator

*   **Pros:** Reuses client-side speed-trim loops without server-side audio resampling.
*   **Cons:** Perturbs `24p`/`23.976p` and `90 Hz` Galaxy XR stereoscopic frame pacing, conflicts with display frame-rate switching, and relies on inaccurate hardcoded latency estimates (`+35–60 ms`) when the AVR already knows exact DSP and Sendspin delays.
*   **Reason for Rejection:** Native `1.0000×` video cadence is required for stable XR stereoscopic rendering; inverting drift adjustment to `±100 ppm` audio resampling on `spatialfin-avr` leaves video cadence untouched.

### Alternative D: Proxying Control or Calibration Through `SpatialFin Companion` (or Carrying WAVs on WebSocket)

*   **Pros:** Reuses `SpatialFin Companion` (`:1982`) or collapses all messages onto one WebSocket connection.
*   **Cons:** Streaming `8–15 MiB` calibration WAVs over WebSocket causes head-of-line blocking on real-time clock pings and playback control; routing through Companion collides with Companion's JSON-only `64 KiB` global / `5 MiB` allowlisted body parsers (`index.js:2888-2904`), couples Companion's 24-hour auto-update release cycle (`connectSrc: ["'self'", 'ws:', 'wss:']`, `index.js:2818`) into theater audio, and conflicts with per-AVR token storage (`config-redaction.js` `SCOPED_ONLY_SECRET_PREF_KEYS`).
*   **Reason for Rejection:** Keeping real-time JSON control (`/ws/v1/control`, `<= 256 KiB`) separate from direct authenticated HTTP multipart routes (`/api/v1/calibration/*`, `<= 64 MiB`) on `spatialfin-avr` avoids head-of-line blocking and eliminates three-repository release coupling.

## Risks & Limits

| Risk / Failure Mode | Category | Impact / Limit | Mitigation |
| --- | --- | --- | --- |
| **Cleartext LAN transport (`ws://`/`http://`)** | Security | `avr_token` and `jellyfin_token` traverse LAN unencrypted when no TLS proxy is configured. | Trusted home-LAN scope documented explicitly; protocol is scheme-agnostic so operators can front port `1984` with `wss://`/`https://` reverse proxy; tokens are zeroed from RAM on stop/expiry. |
| **DNS rebinding / HTTP redirect SSRF** | Security | Malicious `stream_url` could probe `network_mode: host` ports or exfiltrate `X-Emby-Token`. | DNS-pinned Rust HTTP client (`AVIOContext`), redirects disabled (or hop-validated with cross-origin auth stripping), strict IP/self-SSRF blocklist (§3). |
| **Wi-Fi RTT asymmetry under 4K UHD bursts** | Timing / Sync | Downlink video traffic (`60–120 Mbps`) elevates RTT and biases naive NTP samples. | Sliding-window min-RTT clock filter (§5), drift freeze when `clock_uncertainty_us > 5_000 µs`, and `±80 ms` resync guardrail. |
| **Multi-client contention & lost profile updates** | Concurrency | Concurrent XR/Beam/CLI clients could fight over playback or overwrite room profiles. | Single-active-session lease (`session_busy` / `force_takeover`), calibration mutual exclusion, and `If-Match` `room_profile_version` optimistic locking (`409`). |
| **Scale bounds & resource exhaustion** | Scalability | Large uploads or many sockets could exhaust container RAM. | Hard caps: `256 KiB` WS frame (`1009` close), `64 MiB` multipart streamed to disk, max `16` concurrent WS observers, max `16` speaker slots, 1 active + 1 staged calibration draft. |
| **Segmented VLANs blocking mDNS** | Usability | `_spatialfin-avr._tcp` discovery fails across L3 subnets. | Pairing QR URI (`host` + `port`) and manual `host:port` entry in SpatialFin cover non-mDNS networks without Companion coupling. |

## Non-Functional Requirements

| Area | Target / Constraint | Authoritative Reference |
| --- | --- | --- |
| **Security & Auth** | Bearer header auth on all routes except `GET /health` & `POST /api/v1/pair/claim`; `120 s` single-use PIN (`5` per-IP / `10 min` + `5` global lockout); DNS-pinned SSRF guard; volatile RAM `jellyfin_token`. | §3 |
| **Clock & Launch Latency** | `< 2_000 µs` clock uncertainty under `<= 4 ms` LAN RTT spread; `< 15 ms` pause stop via `stream/clear`; `5_000 ms` `playback.ready` deadline (typical `40–150 ms` LAN burst-fill). | §5 |
| **Lip-Sync & DSP Cap** | ITU-R BT.1359 hold band (`-10 ms` to `+15 ms`), `±80 ms` resync trigger, `0 µs` live `EQ Bypass` shift, `dsp_group_delay_us <= 100_000 µs`. | §5, §6 |
| **Payload & Rollback** | `<= 256 KiB` WS frames (`2 Hz` beacons `< 500 B/s`; `1 Hz` telemetry `< 2 KiB/s`), `<= 64 MiB` HTTP uploads; additive `v=1` unknown-key tolerance; existing FCast Split A/V untouched for instant rollback. | §1, §4, §8 |

## Additional Resources

*   Spec 1 Android client design: [spatialfin-theater-audio-android-client.md](./spatialfin-theater-audio-android-client.md)
*   Spec 2 Rust service & hardware design: [spatialfin-avr-rust-service-and-hardware.md](./spatialfin-avr-rust-service-and-hardware.md)
*   Android codec tokens: [ReceiverAudioCodecs.kt](../../player/core/src/main/java/dev/jdtech/jellyfin/player/core/splitav/ReceiverAudioCodecs.kt)
*   Android HLS resume-offset helper: [SplitAvStreamUrlPolicy.kt](../../fcast/session-ui/src/main/java/dev/spatialfin/fcast/session/SplitAvStreamUrlPolicy.kt)
*   Android rendered-frame timing source: [PlayerSplitAvAdapter.kt](../../player/core/src/main/java/dev/jdtech/jellyfin/player/core/splitav/PlayerSplitAvAdapter.kt)

## Future Improvements

*   **Late-Night Binaural Output Profile:** Once SpatialFin's `:sendspin` module upgrades from `sendspin-jvm:0.2.3` to Sendspin 1.0 Noise, add `room.set_output_profile` (`"room_speakers" | "binaural_headset"`) for real-time HRTF binaural Atmos playback.
*   **Sendspin Bias-Lighting / Visualizer Role:** Add additive capability and zone fields for synchronized ambient room lighting.
*   **Native Multichannel Sendspin 1.0 (`Sendspin/spec#350`):** Front-stage endpoints can adopt native `>2`-channel Sendspin streams without changing Spec 0's `(client_id, channel_index)` mapping.

--------------------------------------------------------------------------------

## Detailed Implementation Plan

Spec 0 owns the versioned golden JSON fixtures, cross-repo digest manifest, and contract test harnesses. Implementation is partitioned into three independently buildable phases across the two repositories (`SpatialFin` and `spatialfin-avr`). No root Node.js / `ajv` toolchain is introduced into `SpatialFin`; all validation runs inside existing Gradle (`:fcast`) and Cargo (`avr-protocol`) test suites.

### Phase 0.1 — `SpatialFin` Spec 0 Contract Fixtures & Docs (Repository: `SpatialFin`)

1. `docs/designs/theater-audio-protocol-v1.md` (Modify) — Update `Status` from `Proposed` to `Accepted` upon ratification.
2. `schemas/theater-audio/v1/fixtures/manifest.json` (Create) — Machine-readable index mapping each golden fixture path to `{ message_type, direction, expect_valid, enforced_by: "client" | "server" | "both", expected_error_code? }`.
3. `schemas/theater-audio/v1/fixtures/valid/` (Create — exact enumerated files):
   * `hello_req.json`, `hello_ok.json` (also covers `GET /api/v1/info`)
   * `clock_ping_req.json`, `clock_ping_ok.json`
   * `session_start_req.json`, `session_start_ok.json`, `session_heartbeat.json`, `session_stop_req.json`, `session_released.json`
   * `playback_load_req.json`, `playback_ready_req.json`, `playback_ready_ok.json`, `playback_pause_req.json`, `playback_stall_req.json`, `playback_video_timing.json`, `playback_resync_required.json`
   * `room_set_volume_req.json`, `room_set_eq_bypass_req.json`, `room_set_upmix_mode_req.json`, `room_state_changed.json`, `room_profile_get_ok.json`, `room_profile_updated.json`
   * `calibration_chime_req.json`, `calibration_center_snap_req.json`, `calibration_lip_sync_chirp_req.json`, `calibration_sweep_start_req.json`, `calibration_sweep_upload_ok.json`, `calibration_bundles_ok.json`, `calibration_apply_req.json`, `calibration_revert_ok.json`
   * `telemetry_session_playing.json`, `status_idle.json`, `speaker_degraded.json`, `speaker_restored.json`, `decoder_fallback.json`, `ma_state_changed.json`, `pair_claim_req.json`, `pair_claim_ok.json`, `rpc_error.json`, `http_error.json`
4. `schemas/theater-audio/v1/fixtures/additive-forward-compat/` (Create — exact enumerated files):
   * `hello_ok_unknown_capability.json`, `telemetry_session_extra_fields.json`, `room_profile_extra_fields.json` (verify Kotlin `ignoreUnknownKeys = true` and Rust `serde` structs without `#[serde(deny_unknown_fields)]` deserialize all known fields without error).
5. `schemas/theater-audio/v1/fixtures/invalid/` (Create — exact enumerated files):
   * `session_start_query_apikey.json` (`?api_key=`, `enforced_by: "both"`, `url_rejected`)
   * `session_start_amp_apikey.json` (`&api_key=`, `enforced_by: "both"`, `url_rejected`)
   * `playback_load_query_token.json` (`?token=`, `enforced_by: "both"`, `url_rejected`)
   * `session_start_crlf_token.json` (`\r\n` in `jellyfin_token`, `enforced_by: "both"`, `invalid_request`)
   * `session_start_non_jellyfin.json` (`source_type: "smb"`, `enforced_by: "both"`, `source_ineligible`)
   * `hello_unsupported_min_version.json` (`min_protocol_version: 2`, `enforced_by: "both"`, `protocol_version_unsupported`)
   * `calibration_chime_gain_too_high.json` (`gain_dbfs: -6.0`, `enforced_by: "server"`, `invalid_request`)
   * `calibration_bundle_delay_exceeded.json` (`dsp_group_delay_us: 120000`, `enforced_by: "server"`, `calibration_delay_exceeded`)
6. `schemas/theater-audio/v1/SHA256SUMS` (Create) — Path-sorted `sha256sum` manifest with paths relative to `schemas/theater-audio/v1/`.
7. `GEMINI.md` (Modify) — Add one link to `docs/designs/theater-audio-protocol-v1.md` and `schemas/theater-audio/v1/` under `## Repository Navigation` (staying under the 700-line ceiling).

### Phase 0.2a — Android Contract Suite (Repository: `SpatialFin`, Executed with Spec 1)

8. `fcast/build.gradle.kts` (Modify — Spec 1) — Pass `-Dspatialfin.schemas.dir=${rootProject.file("schemas/theater-audio/v1").absolutePath}` inside `testOptions.unitTests.all` so `:fcast` unit tests resolve repo-root fixtures reliably regardless of module working directory.
9. `fcast/src/test/java/dev/jdtech/jellyfin/cast/TheaterAudioSchemaContractTest.kt` (Create — Spec 1) — Load `manifest.json` and every fixture under `valid/`, `additive-forward-compat/`, and `invalid/` (`enforced_by` in `client`/`both`); assert `kotlinx.serialization` round-trips every defined message type cleanly, tolerates unknown future fields, rejects client-enforced invalid fixtures, and asserts `hello_ok.json`'s `supported_audio_codecs` equals `AudioCodecTokens.ALL_PASSTHROUGH_TOKENS ∪ AudioCodecTokens.UNIVERSAL_SOFTWARE` directly in `:fcast` (`fcast/src/main/java/dev/jdtech/jellyfin/cast/splitav/AudioCodecTokens.kt`), which `ReceiverAudioCodecs` (`player/core/src/main/java/dev/jdtech/jellyfin/player/core/splitav/ReceiverAudioCodecs.kt`) delegates to.

### Phase 0.2b — Rust Contract Suite (Repository: `spatialfin-avr`, Executed with Spec 2)

10. `spatialfin-avr/schemas/theater-audio/v1/` (Create — Spec 2) — Exact byte-identical mirror of `SpatialFin/schemas/theater-audio/v1/` (`SHA256SUMS` + `fixtures/**`).
11. `spatialfin-avr/crates/avr-protocol/tests/protocol_contract.rs` (Create — Spec 2) — Verify `avr-protocol` `serde` structs deserialize all `valid/` and `additive-forward-compat/` fixtures (confirming absence of `#[serde(deny_unknown_fields)]` for unknown keys and `#[serde(default)]` for omitted optional fields) and verify `avr-protocol` validators map all `server`/`both` `invalid/` fixtures to the expected canonical `error_code` enum values.

### Verification Plan

*   **Phase 0.1 (Fixture Integrity):** `(cd schemas/theater-audio/v1 && sha256sum -c SHA256SUMS)` exits `0` in `SpatialFin`, and `manifest.json` references every file in `fixtures/{valid,additive-forward-compat,invalid}/` with valid JSON syntax.
*   **Phase 0.2a (Android Contract Suite — Spec 1):** `./gradlew :fcast:testDebugUnitTest --tests "dev.jdtech.jellyfin.cast.TheaterAudioSchemaContractTest"` passes, covering every message type in `manifest.json`, forward-compatibility unknown-key tolerance, client-enforced invalid-fixture rejection, and direct Kotlin `AudioCodecTokens` / `ReceiverAudioCodecs` token-set equality.
*   **Phase 0.2b (Rust Contract Suite — Spec 2):** `(cd schemas/theater-audio/v1 && sha256sum -c SHA256SUMS)` exits `0` (with byte-identical `SHA256SUMS` to `SpatialFin`) and `cargo test -p avr-protocol --test protocol_contract` passes, covering all `valid/` and `additive-forward-compat/` fixtures and `server`/`both` `invalid/` error-code mappings.

--------------------------------------------------------------------------------

*Drafted with EGM*
