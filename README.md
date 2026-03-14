# SpatialFin

![SpatialFin](images/SpatialFin-banner.png)

A Jellyfin client built specifically for Android XR, delivering an immersive spatial media experience.

> **Forked from [Findroid](https://github.com/jarnedemeulemeester/findroid)** — SpatialFin started as a fork of Findroid, an open-source native Jellyfin client for Android. We thank the Findroid contributors for their excellent work.

## Features

- **IMAX Scale Experience** — Native Full Space Mode (FSM) with massive, curved spatial panels that fill your field of view for a cinematic theater feel.
- **Glassmorphic UI** — Modern "liquid glass" interface with translucent panels that blend naturally into your environment.
- **Interactive Agency** — Grab and move the entire application or video player window anywhere in your 3D space.
- **Immersive Playback** — Full-space XR mode with an IMAX-style cinema environment.
- **Stereoscopic 3D** — Automatic detection and rendering of SBS, top-bottom, and other 3D formats.
- **Spatial Audio** — High-fidelity positional audio that pins sound to the screen's location.
- **Native XR Controls** — Material 3 for XR orbiters that float secondary controls in space, keeping the screen uncluttered.
- **Jellyfin Integration** — Full Jellyfin server connectivity: browse movies, shows, episodes, and collections.
- **Offline Playback** — Download media for offline viewing.

## Requirements

- Android XR device (requires `android.hardware.xr.immersive` feature)
- Android 9 (API 28) or higher
- A running [Jellyfin](https://jellyfin.org) server

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
