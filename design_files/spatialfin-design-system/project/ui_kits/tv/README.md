# Android TV (10-foot) UI kit

A high-fidelity recreation of **SpatialFin on Android TV** — the leanback, D-pad-driven surface. Brighter cyan/amber/mint palette (`.theme-tv`), focus communicated by **scale + cyan ring + glow** (never shadow), fast 120ms tweens.

Open `index.html` and navigate with the **arrow keys** (Enter to select) — or hover with the mouse.

**Home shell**
- **`TvTopBar`** — server switcher tile + Search / Settings / Close, with error / loading-retry chips when relevant (mirrors `HomeHeader.kt`).
- Tap the server tile → **`ServerPickerSheet`** opens with every Jellyfin server you can switch to, plus Manage servers.
- A persistent **`MaMiniPlayer`** bar lives at the bottom while you browse — Preparing → Playing → Paused → Stop (mirrors `MaMiniPlayer.kt`). Tap it to open Now Playing.
- An alternative **`TvNavRail`** component remains in the design system if you prefer a left rail on other surfaces.

**Browse / Home rows**
- **Featured hero** (Play / More Info / Watchlist).
- **Continue Watching** + **Next Up** (separate rows, matching `HomeScreen.kt`'s `resumeSection` + `nextUpSection`).
- **Music Assistant** shelf — every track is focusable; Enter plays via SendSpin and the mini player appears. (Hint shows "ENTER · PLAY · LONG-PRESS · QUEUE" on focus.)
- **Sources** — universal-plugin rows ("From YouTube", "Recent Podcasts") each with a **See all** chip → opens a plugin browse grid.
- **TV Shows** and **Movies** shelves.

**Now Playing (Music Assistant)** — full-screen artwork + scrubber + transport + a **SendSpin · {player}** chip that opens the player picker.

**SendSpin player picker (`MaPlayerPickerSheet`)** — "Auto (this device)" entry at top, every visible MA player below, and an **Also play on (in sync)** section for multi-room sync groups (mirrors `MaPlayerPickerSheet.kt`).

**Search / Library / Show detail / Movie detail / Player / Up Next + voice overlay** — unchanged from the previous build.

Files: `index.html` (shell + focus CSS), `tvFocus.js` (spatial D-pad engine), `TvShell.jsx` (routing + rail + mini player + sheets), `TvHomeScreen.jsx`, `TvSearchScreen.jsx`, `TvLibraryScreen.jsx`, `TvSourceBrowseScreen.jsx`, `ShowDetailScreen.jsx`, `MovieDetailScreen.jsx`, `TvPlayerScreen.jsx`, `TvNowPlayingScreen.jsx`, `data.js` (shows / seasons / episodes / unified browse list + Music Assistant catalog + SendSpin players + servers + universal-plugin sources).

> Note: posters, backdrops and episode stills are on-brand generated placeholders, not the real Blender open-movie frames.
