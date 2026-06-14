# Beam (phone) UI kit

A high-fidelity recreation of **SpatialFin Beam** — the phone form factor of the SpatialFin Jellyfin client. Material 3, dark scheme, content-first.

Open `index.html`. It runs an interactive flow inside a phone bezel:

- **Home** — top app bar with **server switcher** + **Cast** action, featured `HeroBanner`, "Suggestions" + "Continue Watching" shelves of `PosterCard`s.
- **Detail** — backdrop hero, metadata pills, Play / Download / Favorite, overview, cast.
- **Player** — full-bleed video surface with glass transport chrome, scrubber, in-player mic, and bottom controls for **Subtitles, Audio track, Quality, SyncPlay and Voice**. The top-right **Cast** chip shows the active output (incl. split audio).
- **Sources** — universal-plugin browse (YouTube, Podcasts, NAS/WebDAV, Local files), one shelf per source.
- **Search** — live title search with genre filter chips and a results grid.
- **Downloads** — in-progress queue + on-device library with a storage meter.
- **Settings** — account, server, cast/audio output, default quality, download & autoplay toggles.
- **Cast & audio** — "Play on" sheet: pick a video device, optionally **split audio** to a separate speaker/AVR/headphones, and group extra speakers for **multi-room sync**.
- **SyncPlay** — watch-together: join/create a group and see participants in sync.
- **Voice** — tap the mic FAB (or the player mic) to run the listening → thinking → answered cycle. Feedback anchors top-center.
- **Bottom nav** — Home / Search / Sources / Downloads / Settings.

Files: `index.html` (shell + bezel), `BeamApp.jsx` (routing + voice + cast/syncplay/server state), `HomeScreen.jsx`, `DetailScreen.jsx`, `PlayerScreen.jsx`, `PlayerSheets.jsx` (quality/audio/subtitle/cast/syncplay sheets), `MoreScreens.jsx` (Sources/Search/Downloads/Settings), `data.js` (shared fake catalog + cast devices + sources). All screens compose the design-system components from `window.SpatialFinDesignSystem_0d3fe7`.
