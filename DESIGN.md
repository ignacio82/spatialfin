---
version: alpha
name: SpatialFin
description: >-
  SpatialFin is a tri-form-factor Jellyfin client (Android XR, phone/Beam Pro,
  Android TV) built from a single :app:unified module. The design system is
  content-first and cinematic: large spatial panels in XR, responsive Material 3
  on phones, and a 10-foot Leanback-friendly surface on TV — all sharing one
  cool blue/gray Material 3 color system.
colors:
  primary: "#3A608F"
  onPrimary: "#FFFFFF"
  primaryContainer: "#D3E3FF"
  onPrimaryContainer: "#001C39"
  secondary: "#545F70"
  onSecondary: "#FFFFFF"
  secondaryContainer: "#D8E3F8"
  onSecondaryContainer: "#111C2B"
  tertiary: "#6D5677"
  onTertiary: "#FFFFFF"
  tertiaryContainer: "#F5D9FF"
  onTertiaryContainer: "#261430"
  error: "#BA1A1A"
  onError: "#FFFFFF"
  errorContainer: "#FFDAD6"
  onErrorContainer: "#410002"
  background: "#F8F9FF"
  onBackground: "#191C20"
  surface: "#F8F9FF"
  onSurface: "#191C20"
  surfaceVariant: "#DFE2EB"
  onSurfaceVariant: "#43474E"
  outline: "#73777F"
  outlineVariant: "#C3C6CF"
  scrim: "#000000"
  # Dark scheme — the default on XR; phone follows system preference.
  darkPrimary: "#A4C9FE"
  darkOnPrimary: "#00315C"
  darkPrimaryContainer: "#1F4876"
  darkOnPrimaryContainer: "#D3E3FF"
  darkSecondary: "#BCC7DB"
  darkOnSecondary: "#263141"
  darkSecondaryContainer: "#3C4758"
  darkOnSecondaryContainer: "#D8E3F8"
  darkTertiary: "#D9BDE3"
  darkOnTertiary: "#3C2947"
  darkTertiaryContainer: "#543F5E"
  darkOnTertiaryContainer: "#F5D9FF"
  darkBackground: "#111318"
  darkOnBackground: "#E1E2E8"
  darkSurface: "#111318"
  darkOnSurface: "#E1E2E8"
  darkSurfaceVariant: "#43474E"
  darkOnSurfaceVariant: "#C3C6CF"
  darkOutline: "#8D9199"
  # TV scheme — distinct from the dark scheme, tuned for 10-foot contrast on weak SoCs.
  # Applied via app/unified/src/main/java/dev/spatialfin/tv/TvTheme.kt.
  tvPrimary: "#7DDAFF"
  tvOnPrimary: "#072538"
  tvPrimaryContainer: "#0F3F5D"
  tvOnPrimaryContainer: "#D9F4FF"
  tvSecondary: "#FFBE74"
  tvOnSecondary: "#422300"
  tvSecondaryContainer: "#5B3611"
  tvOnSecondaryContainer: "#FFE5C6"
  tvTertiary: "#6EE4C5"
  tvOnTertiary: "#012B23"
  tvTertiaryContainer: "#0E473B"
  tvOnTertiaryContainer: "#D5FFF3"
  tvBackground: "#06111B"
  tvOnBackground: "#E5EEF7"
  tvSurface: "#0D1824"
  tvOnSurface: "#E5EEF7"
  tvSurfaceVariant: "#223245"
  tvOnSurfaceVariant: "#B7C6D8"
  tvBorder: "#5C7087"
typography:
  display:
    fontFamily: "sans-serif"
    fontWeight: "400"
    fontSize: "45sp"
    lineHeight: "52sp"
    letterSpacing: "0sp"
  headline:
    fontFamily: "sans-serif"
    fontWeight: "400"
    fontSize: "28sp"
    lineHeight: "36sp"
    letterSpacing: "0sp"
  title:
    fontFamily: "sans-serif"
    fontWeight: "500"
    fontSize: "16sp"
    lineHeight: "24sp"
    letterSpacing: "0.15sp"
  body:
    fontFamily: "sans-serif"
    fontWeight: "400"
    fontSize: "14sp"
    lineHeight: "20sp"
    letterSpacing: "0.25sp"
  label:
    fontFamily: "sans-serif"
    fontWeight: "500"
    fontSize: "11sp"
    lineHeight: "16sp"
    letterSpacing: "0.5sp"
rounded:
  extraSmall: "10dp"
  small: "10dp"
  medium: "16dp"
  large: "32dp"
  full: "9999dp"
spacing:
  extraSmall: "4dp"
  small: "8dp"
  medium: "16dp"
  default: "24dp"
  large: "32dp"
  extraLarge: "64dp"
  orbiterOffset: "20dp"
  xrPanelWidth: "1792dp"
  xrPanelHeight: "1008dp"
  xrSpawnDistance: "1.75m"
  xrDialogPushback: "125dp"
components:
  spatialPanel:
    backgroundColor: "{colors.darkSurface}"
    textColor: "{colors.darkOnSurface}"
    rounded: "{rounded.large}"
    width: "{spacing.xrPanelWidth}"
    height: "{spacing.xrPanelHeight}"
  spatialDialog:
    backgroundColor: "{colors.darkSurface}"
    textColor: "{colors.darkOnSurface}"
    rounded: "{rounded.large}"
    padding: "{spacing.default}"
  orbiter:
    backgroundColor: "{colors.darkSurfaceVariant}"
    textColor: "{colors.darkOnSurfaceVariant}"
    rounded: "{rounded.full}"
    padding: "{spacing.small}"
  tvMediaCard:
    backgroundColor: "{colors.tvSurfaceVariant}"
    textColor: "{colors.tvOnSurfaceVariant}"
    typography: "{typography.title}"
    rounded: "{rounded.medium}"
    padding: "{spacing.medium}"
  beamVoiceFab:
    backgroundColor: "{colors.primaryContainer}"
    textColor: "{colors.onPrimaryContainer}"
    rounded: "{rounded.full}"
    size: "56dp"
  beamVoiceFeedbackOverlay:
    backgroundColor: "{colors.secondaryContainer}"
    textColor: "{colors.onSecondaryContainer}"
    rounded: "{rounded.full}"
    padding: "{spacing.small}"
  subtitlePanel:
    backgroundColor: "#00000000"
    textColor: "#FFFFFF"
    typography: "{typography.headline}"
---

## Overview

SpatialFin is a Jellyfin media client that ships one APK to three form factors:
**Android XR** (Samsung Galaxy XR — the primary target), **phone** (Beam Pro and
Pixel-class via the `libre` bundle), and **Android TV** (Leanback via the `tv`
bundle). The design system is content-first and cinematic — posters, video,
and subtitles lead; chrome recedes.

- **Mood:** cool, calm, cinematic. XR and Beam share one blue/gray scheme; TV
  gets a brighter cyan/amber/mint scheme for 10-foot contrast but holds the
  same calm, content-first character.
- **Theme defaults:** XR is dark-native with optional dynamic color; TV is
  dark-native and locked to the TV scheme; phone follows the system setting.
- **Voice-first parity:** XR, Beam, and TV all surface the same AI assistant
  (`HomeVoiceController`). Visual affordances for listening / processing /
  answered / error states must render identically in intent across surfaces.
- **Accessibility:** All text/background pairings target WCAG AA minimum
  (4.5:1 for body, 3:1 for large display). Subtitle rendering overrides OS-level
  caption preferences through `CaptionStyleCompat` — don't swap primary roles
  without re-checking contrast in both schemes.

## Colors

SpatialFin ships **two** color schemes built on Material 3:

- **Shared light/dark scheme** — used by phone (Beam) and XR. Primary is a
  calming blue (`#3A608F` light / `#A4C9FE` dark), with desaturated secondary
  and tertiary accents to keep attention on media artwork. Defined in
  `core/.../presentation/theme/Color.kt` (`ColorLight` / `ColorDark`).
- **TV-specific dark scheme** — a distinct cyan / amber / mint palette tuned
  for 10-foot contrast on low-end SoCs (Mali-G31 class). Defined in
  `app/unified/.../tv/TvTheme.kt`. The `colors.tv*` tokens in the frontmatter
  above are the source of truth.

Scheme application per form factor:
- **Phone (Beam):** follows the system light/dark preference.
- **XR:** dark-scheme-first. Dynamic color (Material You) is optionally
  user-enabled via `SpatialFinTheme(dynamicColor = state.isDynamicColors)` in
  `UnifiedMainActivity.kt`, which wins over the declared palette when on.
- **TV:** always uses the `TvColorScheme` — dynamic color is disabled and the
  shared dark scheme is not used.

**Translucent XR surfaces** draw from `darkSurface` at 85–92% opacity so
passthrough / video backgrounds remain perceivable behind orbiters and side
panels.

## Typography

SpatialFin uses the default Android system font (Roboto) to stay consistent
with each form factor's OS conventions. The Material 3 scale applies everywhere;
sizing adjusts by form factor:

- **Display / Headline** — primary titles on large spatial panels (XR), hero
  detail banners (Beam), and the TV home shelf row titles. Scale up to 1.25x
  on TV because the viewing distance is ~3m (10-foot UI).
- **Title** — media card titles, list rows, dialog titles. Maps to
  `TitleMedium` on mediaCard (see frontmatter).
- **Body / Label** — metadata, descriptions, chips, badges. Use `LabelSmall` on
  chips and `BodySmall` on secondary metadata rows (runtime, rating).
- **Subtitle panel** — rendered via libass at up to 2.0x density (see
  `LibassRenderer`); typographic tokens here are informative only, since ASS
  subtitles carry their own style.

Keep line height generous on XR (≥ 1.3x font size) — subpixel density is high
but reading at 1.75m means the eye trades off acuity for comfort.

## Layout

### Shared tokens

Spacing strictly follows the scale: `4dp` (extraSmall), `8dp` (small), `16dp`
(medium), `24dp` (default), `32dp` (large), `64dp` (extraLarge). Never invent
ad-hoc dimensions between tiers — drop to the next token down.

### XR (primary)

- **Main panel:** `1792dp × 1008dp` (16:9 cinematic).
- **Spawn distance:** panel centers ~**1.75 m** from the user.
- **Vertical offset:** center ~**5° below eye level**.
- **FOV budget:** keep interactive content within the central **41°**.
- **Depth range:** panels between **0.75 m** and **5 m**.
- **Orbiters:** `20dp` offset from their parent panel. **One per panel** unless
  there is a very strong reason — content fatigue is real.
- **Movement:** apply `MovableComponent` to the root entity only; children
  (subtitle panel, orbiters) follow via `setParent`.

### Phone (Beam Pro / Pixel)

- Standard Material 3 responsive scaffold (compact / medium / expanded).
- Mic FAB is the primary assistant affordance. Voice feedback chip is anchored
  **top-center** so it never sits under the FAB.
- Recommendation results render in a **bottom sheet** that routes taps through
  `BeamPlayerActivity.createIntentForSpatialItem`. Do not fall back to
  transcript→Search.
- Safe-area: respect system bars; edge-to-edge with contrast scrims on video.

### TV (10-foot)

- Safe area: respect Leanback overscan. Current screens use ad-hoc paddings
  (e.g. `(start=32, end=48, top=24, bottom=12)` on the outer scaffold,
  `padding(48.dp)` on the home shelf). A centralized `TvDimens` token set is
  a future want — don't hand-tune new surfaces to asymmetric paddings until
  then; prefer `spacing.default` (24dp) / `spacing.large` (32dp).
- Focus is king: every interactive element must have a visible focus state
  (scale, outline, or elevation change). Tween animations at ~120ms — see the
  performance rule below.
- Grids are row-chunked (`items(rows, key = …)`). Always key list items or
  focus state is lost on scroll recycle.
- Nested prefetch tuned to `LazyListPrefetchStrategy(4)` in
  `rememberTvShelfListState()` so new shelves don't pop as the user D-pads down.

## Elevation & Depth

### XR — depth is physical

- **Dialogs push parent panels back by `125dp`** (Z-axis) to create focus
  (`SpatialDialog` / `SpatialPopup`).
- **Subtitle panels must cover the same projected frame as the video surface.**
  Don't independently clamp subtitle panel size after projecting — positioned
  ASS signs will drift.
- **Empty panels still block raycasts.** Make the `SpatialPanel` /
  `SceneCoreEntity` conditional in the `Subspace` composition.
  `AnimatedVisibility` is not enough.

### Phone — standard M3 elevation

Surfaces use Material 3 elevation levels (0–5). Dialogs = level 3, top app bar
= level 2 on scroll, FAB = level 3. Shadows are cheap on mobile GPUs; don't
over-use them.

### TV — no real elevation, use contrast

- **Do not use Compose `.blur()` on TV backgrounds.** `TvAmbientBackground`
  uses a **scrim + radial gradient** stack instead. Blur radii above ~24dp
  stall focus navigation on Mali-G31 class GPUs.
- The ambient backdrop decodes at `960×540` to keep peak bitmap memory under
  2 MB per frame on 2 GB devices.
- "Elevation" on TV is communicated by **focus scale** (1.00 → ~1.06) and a
  subtle outline, not shadows.

## Shapes

Components use slightly rounded corners for a modern, approachable feel. Small
and extra-small corners are both `10dp`; medium is `16dp`; large (dialogs,
spatial panels, TV cards) is `32dp`; `full` (`9999dp`) is for chips and the
Beam mic FAB.

- **XR dialogs:** `Surface(shape = RoundedCornerShape(32.dp))` as the root
  content inside `SpatialDialog`. Don't nest a 2D `AlertDialog` / `Popup`
  inside — z-fighting + input capture failure.
- **TV focus cards:** `16dp` corners. Larger radii visibly shimmer during the
  focus-scale tween on weak GPUs.

## Components

| Component | Form factor | Notes |
|---|---|---|
| `spatialPanel` | XR | Core 2D canvas in 3D space. Cinema-scale (1792×1008). |
| `spatialDialog` | XR | Pushes parent panel back `125dp`. No nested dialogs. |
| `orbiter` | XR | `TopAppBar` / `NavigationRail` adapt automatically. One per panel. |
| `tvMediaCard` | TV | Poster + title + metadata. D-pad focusable; scales via `animateFloatAsState(animationSpec = tween(120))`. Title → `TitleMedium`, metadata → `BodySmall` / `LabelSmall`. |
| `beamVoiceFab` | Phone | Primary assistant affordance. Tapping while busy cancels listening / processing / TTS. |
| `beamVoiceFeedbackOverlay` | Phone | Voice feedback state indicator. Anchored top-center. |
| `subtitlePanel` | XR | Transparent overlay, libass-rendered. Remove from composition when empty. |

Extend the system by adding entries to the `components:` frontmatter block
above *and* a row here — the frontmatter is what AI agents parse.

## Do's and Don'ts

### Do

- **Do** use `NavigationRail` and `TopAppBar` as Orbiters in XR; Material 3
  for XR auto-adapts them.
- **Do** use standard Material 3 typography, shapes, and color roles for custom
  components.
- **Do** push `SpatialDialog` parents back `125dp` to establish focus in XR.
- **Do** substitute scrims + radial gradients for `.blur()` on TV backgrounds.
- **Do** key lazy list items on TV (`key = { it.id }`) so focus survives
  scroll recycles.
- **Do** anchor the Beam voice feedback chip top-center and let the mic FAB
  cancel any busy state (listening / processing / TTS).
- **Do** keep subtitle panels geometrically aligned to the video surface
  projection at all times.

### Don't

- **Don't** leave empty invisible spatial panels in the composition — they
  block XR raycasts even when visually hidden.
- **Don't** nest spatial dialogs, or put a 2D `AlertDialog` / `Popup` inside a
  `SpatialDialog` — z-fighting and input capture failure.
- **Don't** use multiple orbiters per panel unless absolutely necessary.
- **Don't** use Compose `.blur()` on TV backgrounds.
- **Don't** use spring animations for TV focus scale — use `tween(120)` or the
  Mali-G31 GPU stalls.
- **Don't** enable image crossfade on TV image loads — stacked fades during
  fast D-pad navigation look broken.
- **Don't** apply `MovableComponent` to child entities; attach it to the root
  only and reparent children.
- **Don't** fall back to transcript→Search from the Beam voice path — route
  every query through `HomeVoiceController` so XR / Beam / TV stay in parity.
- **Don't** add per-item rationale to recommendation replies; titles only.
  The model's reasons are weaker than its picks and undermine trust.
