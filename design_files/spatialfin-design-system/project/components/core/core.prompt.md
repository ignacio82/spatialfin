# Core controls

Pill-shaped buttons, icon buttons, metadata chips and badges — the M3 action vocabulary shared by all three SpatialFin form factors.

```jsx
import { Button } from "./Button.jsx";
import { IconButton } from "./IconButton.jsx";
import { Pill } from "./Pill.jsx";
import { Badge } from "./Badge.jsx";

<Button variant="filled" icon="play" size="lg">Play</Button>
<Button variant="tonal" icon="download">Download</Button>
<Button variant="glass" icon="cast">Cast</Button>

<IconButton icon="mic" variant="glass" label="Voice" size="lg" />

<Pill tone="rating" icon="star">8.1</Pill>
<Pill>2006 · 10 min · NR</Pill>

<Badge tone="accent" icon="download" />
<Badge tone="overlay">4K</Badge>
```

- **Button** variants by emphasis: `filled` (primary CTA) > `tonal` > `outlined` > `text`; `glass` floats over passthrough/video in XR. Sizes `sm | md | lg`. Always pill (full radius).
- **IconButton** is circular & icon-only — orbiter controls, top-bar actions. `glass` for floating XR chrome.
- **Pill** = static metadata; **Badge** = status overlay on media (downloaded/4K/watched). Pills/badges are non-interactive.
- Icons are Lucide names. The shared `Icon` component handles SVG injection.
