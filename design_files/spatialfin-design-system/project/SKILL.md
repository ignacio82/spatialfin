---
name: spatialfin-design
description: Use this skill to generate well-branded interfaces and assets for SpatialFin (an XR-native, voice-first Jellyfin media client for Android XR, phone/Beam and Android TV), either for production or throwaway prototypes/mocks/etc. Contains essential design guidelines, colors, type, fonts, assets, and UI kit components for prototyping.
user-invocable: true
---

Read the README.md file within this skill, and explore the other available files.
If creating visual artifacts (slides, mocks, throwaway prototypes, etc), copy assets out and create static HTML files for the user to view. If working on production code, you can copy assets and read the rules here to become an expert in designing with this brand.
If the user invokes this skill without any other guidance, ask them what they want to build or design, ask some questions, and act as an expert designer who outputs HTML artifacts _or_ production code, depending on the need.

## Quick orientation

- **Brand:** SpatialFin — XR-native, voice-first, content-first Jellyfin client. Calm Material 3 dark UI so media artwork leads.
- **Three surfaces:** Android XR (glass panels over passthrough + orbiters — the hero look), phone "Beam" (M3, mic FAB), Android TV (focus-scale, brighter cyan/amber/mint).
- **Tokens:** link `styles.css`. Dark scheme is `:root`; add `.theme-light` or `.theme-tv` on a wrapper for the others.
- **Type:** Roboto + Roboto Mono (technical readouts). M3 type scale via `.m3-*` classes.
- **Spacing:** fixed scale 4/8/16/24/32/64 — never between tiers.
- **Icons:** Lucide (24px, stroke-2, never filled). No emoji in UI.
- **Components:** mount from `window.SpatialFinDesignSystem_0d3fe7` after loading `_ds_bundle.js`. See each `components/<group>/*.prompt.md`.
- **TV focus:** the 10-foot UI is D-pad driven — every focusable carries `data-focusable`; `ui_kits/tv/tvFocus.js` maps arrow keys to nearest-neighbor focus (Enter activates). Put `data-row` on horizontal shelves and `data-scroll` on the page scroller. Focus = scale + cyan ring, never shadow.
- **TV home shell:** use `TvTopBar` (server switcher + Search/Settings/Close) at the top — not a left rail. Tapping the server tile opens `ServerPickerSheet`. Long-press on Music-Assistant tiles → queue actions (stub). The persistent `MaMiniPlayer` sits above the bottom of the frame whenever an MA track is loaded, and tap-expands to Now Playing.
- **SendSpin / Music Assistant:** `MaMiniPlayer` (Preparing → Playing → Paused → Stop, with the optimistic "Preparing audio…" state), and `MaPlayerPickerSheet` for choosing a speaker (Auto / direct players / multi-room sync group).
- **Signature glass:** `--glass-fill` (62%) / `--glass-fill-strong` (88%) + `blur(var(--glass-blur))` + `--glass-border` + 32px radius + `--shadow-glass`.

## Key rules (from the product DESIGN.md)

- Content-first: chrome recedes, posters/backdrops carry the color. The only warm UI accent is the rating star.
- Voice parity: the assistant's *intent* renders the same across surfaces; replies are terse and give recommendation **titles only, no rationale**.
- One orbiter per XR panel. TV uses focus-scale (≈1.06) + outline, not shadow, and a fast 120ms tween — never a spring.
- No decorative gradients in UI; full-bleed media uses a bottom protection scrim for overlaid text.

Start from the UI kits in `ui_kits/beam/` and `ui_kits/xr/` for full-screen examples.
