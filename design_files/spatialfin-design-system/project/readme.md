# SpatialFin Design System

A complete design system for **SpatialFin** — an XR-native, voice-first media client for [Jellyfin](https://jellyfin.org), built for the next generation of Android devices.

> **Source of truth:** [github.com/ignacio82/spatialfin](https://github.com/ignacio82/spatialfin)
> Companion repos worth exploring to design more faithfully:
> [ignacio82/SpatialFin-Companion](https://github.com/ignacio82/SpatialFin-Companion) and [ignacio82/OrcaXR](https://github.com/ignacio82/OrcaXR).
> This system was reconstructed from that codebase — its Material 3 theme (`Color.kt`, `Spacings.kt`), `DESIGN.md`, `AI_CONTEXT.md`, component sources, and store screenshots. If you have access, read `DESIGN.md` in the repo for the canonical product rules.

---

## What SpatialFin is

SpatialFin is a free, open-source Jellyfin client that runs **one shared experience across three form factors**:

| Surface | Name | Character |
| --- | --- | --- |
| **Android XR** (headset / Galaxy XR) | the hero experience | Translucent **glass spatial panels** floating over real-world passthrough, with a **floating orbiter** of controls. Depth is physical. |
| **Phone** | **Beam** | A polished Material 3 phone app. Content-first browse → detail → player. A mic **FAB** is the primary voice affordance. |
| **Android TV** | — | A focusable 10-foot "leanback" UI with a brighter cyan/amber/mint palette and focus-scale instead of shadow. |

Three pillars define the product:

1. **Spatial-native** — on XR it is not a flat app projected into space; it uses real panels, orbiters and depth.
2. **Voice-first, with parity** — the assistant ("play something funny under 90 minutes") works identically in *intent* across all three surfaces, even though the affordance differs (orbiter mic + palm-hold in XR, FAB on phone, remote button on TV).
3. **Content-first** — chrome recedes; media artwork leads. The palette is deliberately calm so posters and backdrops carry the color.

It plays the Blender open movies (Big Buck Bunny, Sintel, Spring, Sprite Fright, Elephants Dream) as demo content, supports downloads/offline, SyncPlay, casting (FCast), and trick-play.

---

## Content fundamentals

How SpatialFin writes copy. Match this voice in any SpatialFin surface.

- **Voice & person.** Warm, direct, second-person where it addresses the user ("Didn't catch that", "Here you go"). The app refers to itself in the first person only through the assistant, sparingly. Never corporate.
- **Tone.** Calm, confident, a little playful — never hypey. It trusts the content to be the star. Short fragments over full sentences in UI chrome ("Listening…", "2006 · 10 min · NR").
- **Casing.** Title Case for screen titles, section headers, and button labels that are short nouns/verbs ("Continue Watching", "Download", "See all"). Sentence case for descriptive/assistant text ("Starting Big Buck Bunny."). Technical metadata is UPPERCASE or mono ("4K", "HDR", "HEVC", "AES-256-CTR").
- **Metadata style.** Middle-dot separated runs: `2006 · 10 min · NR`. Ratings as a star + number. Codecs/bitrates/timecodes in **Roboto Mono**.
- **Assistant replies.** Action-first and terse. For recommendations, give **titles only — no per-item rationale** (an explicit DESIGN.md rule). "Skipping intro." not "I'll go ahead and skip the introduction for you now."
- **Emoji.** Not used in product UI. The brand mascot (a reclining alien in a VR headset) carries personality instead.
- **Examples.** Section headers: "Suggestions", "Continue Watching", "Library". Empty/error voice: "Didn't catch that". Buttons: "Play", "Details", "Download", "Add Favorite".

---

## Visual foundations

- **Color.** Material 3, dark by default. The hero scheme is a **calming desaturated blue** (`--primary #A4C9FE`) on near-black blue-gray surfaces (`--background #111318`). Secondary/tertiary are intentionally muted (cool gray, soft mauve) so nothing competes with poster art. The **only warm accent in normal UI is the rating star** (`#F2C94C`). TV swaps to a brighter cyan/amber/mint scheme for 10-foot contrast; phone light mode inverts to a `#3A608F` blue. Neon cyan/purple from the mascot are reserved for **logos & marketing**, never UI.
- **Type.** **Roboto** (the Android system face) on the full Material 3 type scale; **Roboto Mono** for technical readouts. Display/Headline are regular weight; Titles and Labels are medium (500). Display & Headline scale ~1.25× on TV.
- **Spacing.** A **fixed scale only** — 4 / 8 / 16 / 24 / 32 / 64. 24 is the workhorse gutter. Never invent values between tiers; drop to the next token down.
- **Backgrounds.** Solid tonal surfaces on phone/TV — **no decorative gradients in UI**. On XR, the "background" is the real world (passthrough); a representative environment image stands in for it here. Media uses full-bleed artwork with a bottom **protection scrim** (`--scrim-gradient`) for legible overlaid text.
- **The glass system (XR).** The signature look: `darkSurface` at **62–88% opacity** + **24px backdrop blur** + a hairline border (`--glass-border`) + large 32px radius + a soft ambient shadow (`--shadow-glass`). 62% over passthrough/video; 88% for dense controls and dialogs.
- **Corners.** 10px small, **16px media cards & TV focus cards**, **32px dialogs & spatial panels**, full-pill for chips/buttons/FABs.
- **Elevation.** On **phone**, M3 shadow levels 1–5. On **XR**, depth is physical (no shadows except the panel's ambient one). On **TV**, elevation is communicated by **focus-scale (≈1.06) + outline**, never shadow.
- **Borders.** Hairline `--outline-variant` for dividers/cards; `--outline` for stronger separation; translucent `--glass-border` on glass.
- **Motion.** Material standard easing `cubic-bezier(0.2,0,0,1)`. Fades and gentle slides; **no bounce**. **TV focus uses a fast 120ms tween — never a spring.** Reduced-motion shows end-states. Decorative loops are avoided (the one exception: the voice "listening" pulse).
- **Hover (phone/pointer).** Buttons brighten (`brightness(1.08–1.12)`); cards lift 4px, gain elevation and an accent outline.
- **Press.** Subtle scale-down (0.94–0.97) — never a color-only change.
- **Transparency & blur.** Used purposefully on XR glass and on floating chips/feedback over media — not as decoration on opaque screens.
- **Imagery vibe.** Cinematic, slightly warm-graded film stills against the cool UI; vignetted; artwork is the brightest thing on screen.
- **Cards.** Media card = 16px radius, art-forward, optional dark footer, watched-progress bar, corner status badge. Surface card = tonal `--surface-container`, hairline border, M3 shadow on phone only.

---

## Iconography

- **System:** **Lucide** line icons — a 1:1 match for the app's Material-style vector drawables (24px grid, **stroke-2**, round caps/joins). They inherit `currentColor`.
- **In this system:** the `Icon` component injects Lucide SVGs; every component takes Lucide icon names (`"play"`, `"mic"`, `"download"`, `"cast"`, `"captions"`, `"settings"`, `"sparkles"`). Cards/kits load the Lucide UMD from CDN.
- **Brand glyphs:** the **cast** icon is the only one that bypasses Lucide — it renders the app's own `core/src/main/res/drawable/ic_cast.xml` drawable verbatim, so every form factor (Beam, TV, XR) shows the exact same mark. Add brand glyphs to `CUSTOM_ICONS` in `components/core/Icon.jsx`.s load Lucide from CDN (`unpkg.com/lucide`).
- **Substitution flag:** the app ships its own Android vector drawables; on the web we use **Lucide** as the closest faithful equivalent (same geometry & weight). If you need the exact in-app glyphs, export the drawables from `:settings`/`:core` `res/drawable`.
- **No emoji** as icons; no icon font; no unicode-glyph icons. The rating **★** is the one decorative glyph used in UI.
- **Logos:** the SpatialFin app mark and banner mascot live in `assets/` (`logo-mark.png`, `logo-banner.png`, `beam-icon.png`).

---

## Index / manifest

**Root**
- `styles.css` — the single entry point consumers link (`@import`s only).
- `tokens/` — `colors.css` (3 schemes: dark default, light, TV), `typography.css` (M3 scale in Roboto), `spacing.css` (spacing/radius/elevation/motion), `fonts.css` (Roboto + Roboto Mono).
- `assets/` — logos, mascot, generated posters/backdrops, the XR passthrough environment, reference screenshots.
- `guidelines/cards/` — foundation specimen cards (Type, Colors, Spacing, Brand) shown in the Design System tab.
- `SKILL.md` — portable skill manifest (works as an Agent Skill in Claude Code).

**Components** (`window.SpatialFinDesignSystem_0d3fe7.*`)
- `components/core/` — `Button`, `IconButton`, `Pill`, `Badge`, `Icon`.
- `components/media/` — `HeroBanner`, `PosterCard`, `SectionHeader`, `ProgressBar`.
- `components/surfaces/` — `GlassPanel`, `Orbiter` (the XR spatial vocabulary).
- `components/navigation/` — `NavBar` (phone bottom nav), `ServerPickerSheet` (Jellyfin server switcher modal).
- `components/voice/` — `VoiceFab`, `VoiceFeedback` (the voice-first affordances).
- `components/music/` — `MaMiniPlayer` (persistent Music Assistant playback bar), `MaPlayerPickerSheet` (the SendSpin "Play on" picker, with multi-room sync groups).
- `components/tv/` — `FocusCard`, `EpisodeCard`, `TvNavRail`, `TvTopBar` (server-switcher home header), `SeasonTabs`, `TvKeyboard`, `UpNextCard` (the 10-foot focusable primitives).

**UI kits**
- `ui_kits/beam/` — interactive phone app: Home → Detail → Player, bottom nav, voice cycle.
- `ui_kits/xr/` — spatial app: **compact hero + dense browse shelves** over passthrough, floating orbiter, voice overlay.
- `ui_kits/tv/` — Android TV (10-foot): Browse with **TvTopBar (server switcher)**, **Sources** rows (See all → plugin browse), **Music Assistant** shelf and persistent **`MaMiniPlayer`** (+ Now Playing screen + **SendSpin player picker**), Show (seasons + episodes) / Movie detail → Player, **D-pad arrow-key focus** (`tvFocus.js`).

Each component directory has a `*.prompt.md` (how & when), a `*.d.ts` props contract, and a `*.card.html` specimen.
