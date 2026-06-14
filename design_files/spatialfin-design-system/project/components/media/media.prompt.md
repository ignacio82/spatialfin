# Media

The content-first building blocks: poster tiles, the featured hero banner, shelf headers and the watched-progress bar. These lead the UI; chrome recedes around them.

```jsx
import { PosterCard } from "./PosterCard.jsx";
import { HeroBanner } from "./HeroBanner.jsx";
import { SectionHeader } from "./SectionHeader.jsx";
import { Badge } from "../core/Badge.jsx";

<HeroBanner title="Sprite Fright" kind="Movie" backdrop={url}
  meta={["2021", "10 min", "4K", "HDR"]} />

<SectionHeader title="Continue Watching" />

<PosterCard title="Elephants Dream" subtitle="Movie · 26% watched"
  poster={url} progress={26} badge={<Badge tone="accent" icon="download" />} />
```

- **PosterCard** — portrait 2:3 tile; hover lifts + reveals accent outline. Pass `progress` for the bar, `badge` for a corner marker.
- **HeroBanner** — full-bleed backdrop + bottom scrim + title + metadata pills + actions. Defaults to Play / Details; override via `actions`.
- **SectionHeader** — the leading-accent-bar row title used above every shelf.
- **ProgressBar** — standalone watched/scrubber bar.
