# SpatialFin

![SpatialFin](docs/images/SpatialFin-banner.png)

A Jellyfin client built specifically for Android XR, delivering an immersive spatial media experience.

> **Forked from [Findroid](https://github.com/jarnedemeulemeester/findroid)** — SpatialFin started as a fork of Findroid, an open-source native Jellyfin client for Android. We thank the Findroid contributors for their excellent work.

## Community

Join the [SpatialFin Discord](https://discord.gg/9MHD52r9) for beta updates, testing feedback, and support.

## Features

- **Instant Playback** — Smarter Media3 preloading fetches the next likely items while you browse, making streaming playback from Next Up, search, and detail screens feel instantaneous.
- **Multitask Mode (Home Space)** — SpatialFin launches in Android XR Home Space by default, so you can keep it open side-by-side with other apps while browsing your library, adjusting settings, or searching for content. A clear in-app toggle switches between Home Space and Full Space at any time.
- **Immersive Mode (Full Space)** — Switch to Full Space for a premium theater-like XR experience with massive spatial panels, movable root entity, orbiters, and spatial audio. Optionally auto-enter Full Space whenever you start playback, and optionally return to Home Space when playback ends.
- **Immersive Experience** — Native Full Space Mode (FSM) with massive, spatial panels that fill your field of view for a cinematic theater feel.
- **Glassmorphic UI** — Modern "liquid glass" interface with translucent panels that blend naturally into your environment.
- **Interactive Agency** — Grab and move the entire application or video player window anywhere in your 3D space.
- **Stereoscopic 3D** — Automatic detection and rendering of SBS, top-bottom, and other 3D formats.
- **Spatial Audio** — High-fidelity positional audio that pins sound to the screen's location.
- **Native XR Controls** — Material 3 for XR orbiters that float secondary controls in space, keeping the screen uncluttered.
- **XR-First Action Layouts** — Detail screens use larger readable typography and labeled action buttons instead of dense icon-only controls.
- **Pixel-Perfect Anime Subtitles** — Integrated `libass` JNI renderer for flawless ASS/SSA subtitle rendering, supporting complex typesetting and animations.
- **Smart Audio And Subtitle Selection** — Ordered spoken languages, optional original-audio preference, automatic subtitle fallback when the chosen audio is not one you speak, smarter subtitle-track ranking, and per-series memory for manual audio/subtitle corrections.
- **Version Selection (Media Source)** — Choose between different versions of the same movie or episode (e.g., 3D vs. 2D, 4K vs. 1080p) before playing or during playback.
- **Dynamic Quality Selection** — Control streaming bitrate on the fly. Use the sparkle icon in the player or the media info screen to switch between bitrates (from 480 Kbps up to 120 Mbps) or stay on "Auto" for direct play.
- **Hands-Free Voice Control** — Hold an open palm near your face on a configurable hand, or use the mic orbiter button, to control playback, audio, subtitles, quality, SyncPlay, and search with on-device speech recognition plus deterministic parsing, on-device AI when available, and cloud fallback when a user supplies a Gemini API key.
- **Global Bitrate Settings** — Set a default maximum streaming bitrate in the app settings to optimize for your network conditions across all media.
- **In-Player AI Search** — Voice search now opens as a spatial overlay inside the XR player so you can search, disambiguate, and launch new media without breaking immersion.
- **Voice Diagnostics** — A dedicated voice settings section shows enablement, permissions, on-device availability, example commands, and local telemetry summaries for tuning the experience.
- **SyncPlay Watch Parties** — Create, join, refresh, and leave Jellyfin SyncPlay groups directly from the XR player orbiter. SpatialFin now listens for Jellyfin playstate and SyncPlay websocket commands and mirrors pause, resume, seek, and stop changes back to the group.
- **Seerr Integration** — Connect your [Jellyseerr](https://github.com/fallenbagel/jellyseerr) or [Overseerr](https://github.com/sct/overseerr) instance to search for and request new media directly from the SpatialFin search screen when items aren't already in your library.
- **Large-Target Playback UI** — The XR player exposes larger controls and a dedicated chapter picker for easier hand-first interaction.
- **Jellyfin Integration** — Full Jellyfin server connectivity: browse movies, shows, episodes, and collections.
- **Local Library** — Browse and play videos stored directly on the XR device, with filename-based metadata inference plus local watched/resume tracking.
- **Network Shares (SMB/NFS)** — Browse and stream media from SMB and NFS network shares on your local network. Autodiscovery finds shares via mDNS, and TMDB metadata enrichment adds posters, descriptions, ratings, and proper watch tracking — bringing network content close to the Jellyfin experience.
- **Automatic Offline Mode** — SpatialFin monitors server reachability and automatically switches into offline mode when the selected Jellyfin server is unavailable, then switches back online when the server becomes reachable again.
- **Offline Playback** — Download media for offline viewing with a new reliable, resumable engine that continues interrupted downloads automatically.
- **Full Offline Metadata Mirroring** — SpatialFin now downloads everything needed for a complete offline experience, including posters, backdrops, and scrubber thumbnails (trickplay).
- **Smart Download Reconciliation** — If files disappear from the download folder outside the app, SpatialFin removes the stale entries from its catalog automatically.
- **Configurable Downloads** — Download the original server file or request a smaller transcoded version with a selected bitrate, audio track, and subtitle track.
- **In-App Download Management** — Delete downloads from the item screen or directly from the Downloads tab.
- **SpatialFin Companion Support** — Effortlessly configure your headset by scanning a QR code from the [SpatialFin Companion](https://github.com/ignacio82/SpatialFin-Companion) app. Centrally manage servers, users, and network shares without using the XR keyboard.
- **Privacy-First App Lock & Encrypted Downloads** — Unique among Jellyfin clients (as of this writing): optional AES-256-CTR encryption of downloaded video, tied to an app-lock mode you choose. Three modes — **Off**, **Biometric / device credential**, or a **SpatialFin-managed PIN** (4–8 digits, PBKDF2-HMAC-SHA256, 600 000 iterations) with configurable wipe-on-wrong-PIN (1–3 attempts). Companion admins can enforce the mode globally or per device. Off by default; the encryption key stays hardware-backed in Keystore and, in Biometric/PIN modes, is unwrappable only after you authenticate.

## SpatialFin Companion

To significantly reduce the friction of setting up an XR device, we recommend using the **[SpatialFin Companion](https://github.com/ignacio82/SpatialFin-Companion)**.

- **Zero-Type Setup**: Point your headset at the companion dashboard to instantly import servers and users.
- **Fleet Management**: Manage multiple headsets and accounts from any web browser.
- **Network Share Hub**: Pre-configure SMB and NFS shares for the entire fleet.
- **PWA Ready**: Install the companion hub on your phone or desktop for quick access.

## Offline And Downloads

SpatialFin now treats offline support as a first-class mode instead of a manual fallback.

- The app continuously checks whether the active Jellyfin server is reachable.
- If the server or network becomes unavailable, SpatialFin automatically falls back to offline mode.
- When connectivity returns, SpatialFin switches back to online mode and resumes normal Jellyfin-backed behavior.
- Downloaded items remain available from the Downloads tab while offline.
- Local headset videos remain available from the Local tab regardless of server state.

### Download Storage

All app-managed downloads are stored in the public Android downloads area under:

```text
Downloads/SpatialFin
```

This makes the files easier to inspect, copy, back up, or manage from outside the app. If you delete the file manually from this folder, SpatialFin will detect it and remove the stale entry automatically.

### Reliable Resumable Downloads

SpatialFin has moved beyond the standard system `DownloadManager` to a custom, `WorkManager`-backed engine. This provides:

- **Resume Support**: If your network drops or the headset restarts, downloads resume from exactly where they left off using HTTP `Range` headers.
- **Parallel Background Tasks**: Media files, external subtitles, posters, and scrubber thumbnails (trickplay) are all downloaded in parallel in the background.
- **Real-Time Progress**: A system-level foreground notification keeps you updated on download progress without needing to stay in the app.
- **Network Awareness**: Downloads respect your settings for mobile data and roaming, pausing and resuming automatically as connectivity changes.

### Download Types

SpatialFin supports two download strategies:

1. **Original file**
Keeps the media exactly as stored on the Jellyfin server for maximum fidelity.

2. **Smaller transcoded file**
Requests a server-side transcode so you can save space by choosing:
- a lower target bitrate
- a specific audio track
- a specific subtitle track

### Deleting Downloads

You can remove downloads in two ways:

1. From the media detail screen using the trash/delete action.
2. From the Downloads tab using the inline delete action on each downloaded item.

If you delete the file manually from `Downloads/SpatialFin`, SpatialFin detects that the file is gone and removes the stale entry from the app automatically.

### Behavior Summary

- Online browsing uses the Jellyfin server when available.
- Offline browsing uses cached/downloaded media when the server is unavailable.
- Playback progress and media state continue to work for local and downloaded content.
- Reconnection triggers a sync pass so offline state can be pushed back when the server is available again.

## Network Shares

SpatialFin can browse and stream media directly from SMB and NFS shares on your local network, turning a NAS or file server into a media source without requiring a Jellyfin server.

- A dedicated `Network` tab lists saved shares and continues-watching items.
- **Autodiscovery** uses mDNS to find SMB (`_smb._tcp.local.`) and NFS (`_nfs._tcp.local.`) services automatically.
- Add shares manually or tap a discovered share to pre-fill connection details.
- SMB discovery pre-fills the host and protocol; the share name still needs to be entered because mDNS advertises the SMB service, not individual shares.
- NFS discovery pre-fills the host, export path, and protocol.
- SMB supports guest and authenticated access (username, password, domain). NFS uses standard v4.1 mounts.
- **Recursive scanning** walks share directories and indexes video files (mkv, mp4, avi, m4v, ts, wmv, mov).
- **TMDB metadata enrichment** parses filenames for titles, years, and season/episode numbers, then matches against TMDB to fetch posters, backdrops, descriptions, and ratings. Provide a TMDB API key in Settings → Network to enable this.
- Videos are grouped by Movies, TV Shows, and Uncategorized within each share.
- Watch progress and played/unplayed state are tracked locally per video.
- Playback uses a local HTTP proxy that bridges SMB/NFS streams to ExoPlayer with full seek support via HTTP Range headers.

## Requirements

- Android XR device (requires `android.hardware.xr.immersive` feature)
- Android 12 (API 31) or higher
- Optional: a running [Jellyfin](https://jellyfin.org) server

## Android Packaging

SpatialFin now ships as a single unified Android app:

- Gradle app module: `app/unified`
- Application ID: `dev.spatialfin`
- App name: `SpatialFin`

Release signing uses the standard SpatialFin variables:

- `SPATIALFIN_KEYSTORE`
- `SPATIALFIN_KEYSTORE_PASSWORD`
- `SPATIALFIN_KEY_ALIAS`
- `SPATIALFIN_KEY_PASSWORD`

These values can be provided through Gradle properties, `local.properties`, or environment variables.

The legacy per-device packaging shells under `app/xr`, `app/beam`, and `app/tv` have been consolidated into `app/unified`. Their remaining Kotlin sources are still staged into the unified app at build time, but separate manifests, launcher assets, and per-form-factor `Application` / `MainActivity` entrypoints are no longer used.

## Local Library

SpatialFin can now operate as a local-first XR player, even without a Jellyfin server.

- A dedicated `Local` category scans videos stored on the headset through `MediaStore`.
- Local playback works online or offline.
- SpatialFin parses file names to infer cleaner titles and basic season/episode or year metadata when possible.
- Local watch progress and watched/unwatched state are stored on-device, so resume works without any server.
- On first app launch, SpatialFin also requests video-library permission so the local library can be populated immediately.

## Voice Control

SpatialFin now supports hands-free voice control in the XR player.

- **Unified Smart Assistant:** Hold an open palm near your face on your configured voice hand to start voice capture, then speak. You can issue direct player commands or ask natural language questions about the movie.
- **AI Chat And Parsing:** SpatialFin includes an on-device LiteRT Gemma integration for private local inference, an Android AICore (Gemini Nano) integration path, and falls back to cloud Gemini (`gemini-3.1-flash-lite-preview`) when the user provides an API key in settings.
- **Contextual Awareness:** The assistant maintains a 60-second rolling buffer of recent subtitles, allowing you to ask "What just happened?" or clarify confusing dialogue.
- **Spatial Voice Feedback:** The AI responds out loud using Android's native Text-To-Speech engine. Media volume is automatically ducked while the assistant is speaking, and its text response remains visible until the speech finishes.
- Use the mic button in the player orbiter as a fallback.
- Speech recognition runs on-device and prefers offline recognition.
- Gesture activation now uses a single near-face open-palm hold, frame smoothing, a dedicated arming zone, a short cooldown, and in-view guidance to reduce resting-hand false positives without adding an extra confirmation step.
- Commands are mapped to existing player actions such as play, pause, seek, skip intro, subtitle toggles, audio-track changes, quality/bitrate adjustments, controls visibility, next/previous episode, SyncPlay actions, search, going home, and closing SpatialFin.
- Voice search results are shown in a spatial in-player overlay, and follow-up commands like "play the first one" now resolve against the active result set.
- SyncPlay voice commands support opening the panel, creating a group, joining a named or indexed group, refreshing groups, and leaving the current watch party.
- Voice parsing uses deterministic command handling first, then attempts richer AI parsing on compatible runtimes.
- Current Galaxy XR testing found that the shipped firmware does not expose `com.google.android.aicore` to third-party apps, so SpatialFin uses a locally downloaded LiteRT Gemma model for on-device AI. It also supports deterministic handling and cloud Gemini fallback.
- Voice settings now show the status of the Gemma model, whether AICore is installed on the device, and include a bring-your-own Gemini API key field for cloud fallback features.
- On first app launch, SpatialFin requests the microphone, hand-tracking, and local video permissions needed for voice input and the on-device library.
- Voice settings include status, command examples, configurable gesture handedness, and a local telemetry dashboard for iteration and debugging.

## Smart Playback

SpatialFin now includes a smarter language and subtitle system aimed at real-world multilingual libraries.

- Users can define an ordered list of spoken languages instead of a single preferred language.
- The first spoken language defaults to the headset OS language until the user customizes it.
- Users can search for and add more languages from a dedicated XR dialog, then reorder them by priority.
- A `prefer original audio when known` option allows SpatialFin to keep likely-original audio for multilingual content instead of always preferring a dub.
- If the chosen audio is not in the user’s spoken-language list, SpatialFin automatically enables the best subtitle track it can find in one of the user’s spoken languages.
- When several subtitle tracks share the same language, SpatialFin now ranks them instead of blindly taking the first one, preferring better candidates over forced/signs-only style tracks where the metadata allows that distinction.
- If the user manually changes audio or subtitle tracks while watching a series, SpatialFin stores that override for the whole series so future episodes can start closer to what the user actually wants.

Current limitation:

- Jellyfin metadata in this app does not always expose a definitive original-audio field, so `prefer original audio` is still a best-effort inference based on the available tracks.

## XR Usability

- Media detail screens now favor larger headings, bigger metadata, and labeled action buttons so core actions are readable from a distance.
- The XR player now includes a direct chapters dialog with previous/next chapter controls instead of relying only on scrub-bar markers.
- Player panel placement is now restored from the last saved pose.
- Media, downloads, and settings now use larger XR browse headers, roomier grids, and larger settings rows instead of compact phone-style top bars and list density.
- Collection browsing, About, and local video detail screens now follow the same XR-first header and spacing model, with larger action surfaces and more readable metadata.
- Setup and onboarding screens now use wider XR forms, larger cards, larger confirmation dialogs, and more readable login/server selection flows instead of phone-scale layouts.
- Smart language configuration now uses a larger XR dialog with searchable language adding, ordering controls, and clearer visibility for available results.

## SyncPlay

SpatialFin now includes native Jellyfin SyncPlay support in the XR player.

- Start playback for any Jellyfin-backed movie or episode.
- You can also enter SyncPlay directly from movie and episode detail screens, which opens the XR player with the SyncPlay panel ready.
- Open the right-side orbiter in the player and select the TV/SyncPlay button.
- Create a new group for the currently playing item, or refresh and join an existing group on the same server.
- If the group is already watching a different supported Jellyfin item, SpatialFin switches the XR player over to that item automatically.
- SpatialFin now adopts Jellyfin SyncPlay queue updates as authoritative, including next/previous transitions, current-item changes, and host-driven playlist reordering or replacement.
- While connected, SpatialFin reacts to Jellyfin SyncPlay websocket updates and forwards local pause, resume, seek, and stop events back to the server group.
- If the websocket connection drops and comes back, SpatialFin refreshes SyncPlay group state and surfaces reconnect status in the player.

Current scope:

- SyncPlay is available for Jellyfin streams, not local-only files.
- Group management currently lives inside the player rather than the media detail screens.

## App Lock & Encrypted Downloads

SpatialFin is the only Jellyfin client known to us that offers on-device content encryption for downloaded media, tied to an app-lock gate you choose. The feature is off by default — enabling it is a deliberate choice about your threat model.

### Three authentication modes

Configured under **Settings → Security → App lock**:

- **Off** (default) — no prompt on launch. Stored data is readable by anyone with device access.
- **Biometric / device credential** — the app unlocks via Android's `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`. When content encryption is also on, the Keystore key that wraps the data-encryption key (DEK) requires a successful biometric authentication to be usable, via `BiometricPrompt.CryptoObject`. The plaintext DEK is cached in process memory only for the current session and is zeroed when the app is backgrounded.
- **SpatialFin PIN** — a numeric PIN (4–8 digits) managed by SpatialFin, hashed with PBKDF2-HMAC-SHA256 (600 000 iterations, random per-user salt). The PIN is *also* the encryption-key-derivation material: the DEK is wrapped with a KEK derived from `PBKDF2(pin, salt)`, and the Keystore aliases are deleted on transition into this mode. There is no recovery path. You can configure how many consecutive wrong PIN entries (1–3) trigger a full data wipe via `ActivityManager.clearApplicationUserData()` plus deletion of the external `Downloads/SpatialFin` folder.

### Encrypted downloads

Independent toggle under **Settings → Security → Encrypt new downloads**:

- Newly finalized primary-video downloads are encrypted with AES-256-CTR using a random 16-byte IV per file. The IV is stored on the `downloadtasks` row.
- The content DEK is a random 32-byte key, wrapped on disk by a KEK chosen by the app-lock mode (see above). The wrapped DEK file is useless without the matching KEK.
- Playback uses a custom Media3 `DataSource` in `player/core/security` that reads the IV from the DB row and performs on-the-fly AES-CTR decryption with proper seek support. Plaintext video bytes never hit disk.
- Pre-existing (unencrypted) downloads stay playable. A Settings action shows how many are still plain and can delete them in bulk — re-download to get them encrypted.
- Subtitle downloads are not encrypted; they are not considered sensitive and go through `libass` JNI which reads files directly.

### Centrally enforced via the companion

`pref_content_encryption_enabled`, `pref_app_lock_mode`, `pref_app_lock_wipe_on_fail`, and `pref_app_lock_max_attempts` flow through the normal SpatialFin Companion sync. Administrators can enforce encryption globally, or per device using the `devicePreferences` map in the companion config. When the admin changes a setting the companion broadcasts `{ type: "config_changed" }` over WebSocket, and SpatialFin's live-sync client triggers an immediate re-pull.

### What this protects

- **Offline file-dump** against `Downloads/SpatialFin` or the app's private data directory — the ciphertext is meaningless without the KEK (hardware-backed in Keystore on capable devices) or the PIN (never stored; only the PBKDF2 hash is).
- **Process-level execution while the app is locked** (Biometric or PIN mode) — the DEK is not in memory; an attacker would need the PIN or a valid biometric auth to unwrap it.

### What this does not protect

- **A rooted device actively running SpatialFin with the DEK already in memory.** No app-level encryption defends against arbitrary code execution inside its own process. Backgrounding zeroes the DEK to shrink the attack window.
- **Streaming playback.** Encryption applies to downloaded content; Jellyfin streams and local (non-downloaded) files pass through unchanged.

### Behavior notes

- **Existing users upgrade cleanly** — the feature stays off until opted into.
- **Re-auth on resume** — the app re-locks on background. Returning from the launcher (or cold start) shows a blocking unlock screen before the UI renders.
- **No re-auth on rotation / config changes.**
- **Does not interrupt background audio** — the lock gates the UI only; audio/video already streaming in the background is not paused.
- **Mode transitions rekey the wrapped DEK atomically**, so previously-encrypted downloads remain playable across mode changes — *except* transitioning *into* PIN mode, which is one-way: the Keystore aliases are deleted, and a forgotten PIN is unrecoverable by design.
- **Toggle lives in the XR settings screen.** The lock still gates TV and phone access once enabled, but toggling it from those form factors is a future wiring task.

## Architecture

SpatialFin is a multi-module Android project. `:app:unified` is the only application module — it ships a single APK that branches at runtime on device class (XR / phone / TV).

| Module | Description |
|--------|-------------|
| `:app:unified` | The only application module. Application id `dev.spatialfin`. Stages the legacy `app/xr`, `app/tv`, and `app/beam` source trees into the build. |
| `:player:xr` | Immersive XR player, libass JNI subtitle pipeline, voice subsystem, spatial UI. |
| `:player:local` | Media3 / ExoPlayer wrapper for local and Jellyfin playback. |
| `:player:session` | Typed player actions, voice command execution, SyncPlay orchestration. |
| `:player:core` | Player abstractions and domain models. |
| `:player:beam` | Phone-form-factor player (re-exports `:player:xr` for the libass libs). |
| `:player:tv` | TV-form-factor (Leanback) player. |
| `:modes:film` | Browse and detail screens for movies, shows, episodes, and collections. |
| `:data` | Jellyfin / TMDB / Seerr API clients, Room database, downloads, network shares (SMB/NFS), mDNS discovery. |
| `:core` | Shared UI components, LLM model manager, WorkManager workers. |
| `:settings` | DataStore-based preferences and voice telemetry. |
| `:setup` | Server onboarding and login flows. |

The directories `app/xr/`, `app/beam/`, and `app/tv/` exist on disk but are **not** Gradle modules. Their Kotlin sources are staged into `:app:unified` at build time by the `StageSourcesTask` in `app/unified/build.gradle.kts`. Editing those files affects the unified APK on the next build; no extra Gradle wiring is required.

## AI Usage

If you are using this repository with an LLM (Claude, Gemini, Codex, etc.), the canonical technical context is in **[`GEMINI.md`](GEMINI.md)**. It contains the architecture, voice/AI pipeline notes, build quirks, and known footguns that are easy to miss from source alone.

`GEMINI.md` includes a self-update mandate: any AI assistant making non-trivial changes to SpatialFin should update `GEMINI.md` in the same change when its content drifts (modules added/removed, new debugging recipes, repeated bugs worked around, etc.). Keeping that file fresh keeps every future AI session productive.

When pointing an AI at this repo, focus it on source files, Gradle files, and `GEMINI.md`. Usually ignore these paths unless the task explicitly targets them:

- `.git/`, `.kotlin/`
- `**/build/`
- `build_native_work/`
- `release/`
- `fastlane/metadata/android/en-US/images/`
- `androidx/`

Note: `player/xr/src/main/jniLibs/` contains intentionally checked-in prebuilt `libass_jni.so` binaries so a fresh clone still builds without an NDK toolchain.

## Building

```bash
./gradlew :app:unified:assembleLibreDebug
```

The debug APK is generated at:

```text
app/unified/build/outputs/apk/libre/debug/spatialfin-libre-arm64-v8a-debug.apk
```

There is one product flavor (`libre`). Build types are `debug`, `staging`, and `release`. Release is currently shipped unminified — see `GEMINI.md` for the R8 / `androidx.xr` rationale and the keep rules that must stay in `app/unified/proguard-rules.pro`.

### Native Subtitle Library

SpatialFin keeps prebuilt `libass_jni.so` binaries under `player/xr/src/main/jniLibs` so a fresh clone builds without native toolchain setup. To rebuild from source:

```bash
./player/xr/build_native.sh /path/to/android-ndk
```

`build_native_work/` is reproducible scratch output for the subtitle toolchain and is not required in Git.

### Versioning

`buildSrc/src/main/kotlin/Versions.kt` is the source of truth. Increment **both** `APP_CODE` (integer) and `APP_NAME` (semver) before producing a Play Store bundle.

## License

SpatialFin is open source software. See [LICENSE](LICENSE) for details.

Original Findroid license applies to code inherited from that project.
