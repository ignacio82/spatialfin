# TV (10-foot)

The focusable primitives for the Android TV / leanback surface. TV communicates selection by **focus-scale (≈1.06) + a cyan focus ring + glow** — never shadow elevation — with a fast 120ms tween (no spring). Wrap the screen in `.theme-tv` for the brighter cyan/amber/mint palette. Every focusable carries `data-focusable` so the D-pad engine (`ui_kits/tv/tvFocus.js`) can reach it via arrow keys; Enter activates.

```jsx
import { FocusCard } from "./FocusCard.jsx";
import { EpisodeCard } from "./EpisodeCard.jsx";
import { TvNavRail } from "./TvNavRail.jsx";
import { SeasonTabs } from "./SeasonTabs.jsx";

<TvNavRail active="home" onChange={setTab} items={[
  { id: "search", icon: "search", label: "Search" },
  { id: "home", icon: "house", label: "Home" },
  { id: "movies", icon: "clapperboard", label: "Movies" },
  { id: "shows", icon: "tv", label: "TV Shows" },
]} />

{/* horizontal shelf — give the wrapper data-row so focus reveals scroll */}
<div data-row style={{ display: "flex", gap: 18, overflowX: "auto" }}>
  <FocusCard title="Cosmos Laundromat" subtitle="Series" image={poster} focusFirst />
  <FocusCard title="Sintel" subtitle="Movie" image={poster} />
</div>

<SeasonTabs seasons={[1, 2]} active={1} onChange={setSeason} />
<EpisodeCard number={1} title="Llama Drama" still={still} runtime="1 min" synopsis="…" progress={100} />
```

- **FocusCard** — portrait (movies/shows) or landscape (continue-watching). `focusFirst` seeds initial focus.
- **EpisodeCard** — landscape episode tile with EP number, progress, and a 2-line synopsis; reveals a play overlay on focus.
- **TvNavRail** — left rail; icon-only until focused, then expands to labels. Active = tonal pill.
- **TvTopBar** — the home top header (server tile + error/loading chips + Search · Settings · Close). Use this on Home / Search / Library where the server identity belongs at the top of frame.
- **SeasonTabs** — pill season selector.
- **TvKeyboard** — on-screen QWERTY-grid keyboard for Search; each key is `data-focusable`, Enter types it.
- **UpNextCard** — autoplay card with a countdown ring, slid in over the player as content ends (host owns the timer via `remaining`).
- Put `data-row` on horizontal scrollers and `data-scroll` on the vertical page scroller so `tvFocus.js` keeps the focused item in view (it uses manual scroll, never `scrollIntoView`).
