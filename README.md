# SpatialFin

![SpatialFin](images/SpatialFin-banner.png)

A Jellyfin client built specifically for Android XR, delivering an immersive spatial media experience.

> **Forked from [Findroid](https://github.com/jarnedemeulemeester/findroid)** — SpatialFin started as a fork of Findroid, an open-source native Jellyfin client for Android. We thank the Findroid contributors for their excellent work.

## Features

- **IMAX Scale Experience** — Native Full Space Mode (FSM) with massive, spatial panels that fill your field of view for a cinematic theater feel.
- **Glassmorphic UI** — Modern "liquid glass" interface with translucent panels that blend naturally into your environment.
- **Interactive Agency** — Grab and move the entire application or video player window anywhere in your 3D space.
- **Immersive Playback** — Full-space XR mode with an IMAX-style cinema environment.
- **Stereoscopic 3D** — Automatic detection and rendering of SBS, top-bottom, and other 3D formats.
- **Spatial Audio** — High-fidelity positional audio that pins sound to the screen's location.
- **Native XR Controls** — Material 3 for XR orbiters that float secondary controls in space, keeping the screen uncluttered.
- **Pixel-Perfect Anime Subtitles** — Integrated `libass` JNI renderer for flawless ASS/SSA subtitle rendering, supporting complex typesetting and animations.
- **Hands-Free Voice Control** — Pinch-to-talk on the secondary hand or use the mic orbiter button to control playback, audio, subtitles, and search with on-device speech recognition plus Gemini Nano intent parsing.
- **In-Player AI Search** — Voice search now opens as a spatial overlay inside the XR player so you can search and launch new media without breaking immersion.
- **Voice Diagnostics** — A dedicated voice settings section shows enablement, permissions, on-device availability, example commands, and local telemetry summaries for tuning the experience.
- **Jellyfin Integration** — Full Jellyfin server connectivity: browse movies, shows, episodes, and collections.
- **Local Library** — Browse and play videos stored directly on the XR device, with filename-based metadata inference plus local watched/resume tracking.
- **Offline Playback** — Download media for offline viewing.

## Requirements

- Android XR device (requires `android.hardware.xr.immersive` feature)
- Android 12 (API 31) or higher
- Optional: a running [Jellyfin](https://jellyfin.org) server

## Local Library

SpatialFin can now operate as a local-first XR player, even without a Jellyfin server.

- A dedicated `Local` category scans videos stored on the headset through `MediaStore`.
- Local playback works online or offline.
- SpatialFin parses file names to infer cleaner titles and basic season/episode or year metadata when possible.
- Local watch progress and watched/unwatched state are stored on-device, so resume works without any server.
- On first app launch, SpatialFin also requests video-library permission so the local library can be populated immediately.

## Voice Control

SpatialFin now supports hands-free voice control in the XR player.

- Pinch your secondary hand to start listening and release to stop.
- Use the mic button in the player orbiter as a fallback.
- Speech recognition runs on-device and prefers offline recognition.
- Commands are mapped to existing player actions such as play, pause, seek, skip intro, subtitle toggles, audio-track changes, controls visibility, next/previous episode, and search.
- Search results are shown in a spatial in-player overlay with a clear path back to the current video.
- Voice parsing uses both deterministic command handling and richer multimodal player context for compatible on-device AI devices.
- On first app launch, SpatialFin requests the microphone, hand-tracking, and local video permissions needed for voice input and the on-device library.
- Voice settings include status, command examples, and a local telemetry dashboard for iteration and debugging.

## Architecture

SpatialFin is a multi-module Android project:

| Module | Description |
|--------|-------------|
| `app/xr` | Android XR application entry point |
| `player/xr` | Immersive XR player with spatial UI |
| `player/local` | Local playback engine (ExoPlayer) |
| `player/core` | Player abstractions and interfaces |
| `modes/film` | Browse movies, shows, and episodes |
| `data` | Jellyfin API client, database, repository |
| `core` | Shared UI components and utilities |
| `settings` | User preferences |
| `setup` | Server onboarding flow |

## Building

```bash
./gradlew :app:xr:assembleLibreDebug
```

## License

SpatialFin is open source software. See [LICENSE](LICENSE) for details.

Original Findroid license applies to code inherited from that project.
