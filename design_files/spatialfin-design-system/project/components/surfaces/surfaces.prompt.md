# Surfaces

The spatial-UI surfaces unique to SpatialFin's XR experience: the translucent glass panel and the floating orbiter control cluster.

```jsx
import { GlassPanel } from "./GlassPanel.jsx";
import { Orbiter } from "./Orbiter.jsx";

<GlassPanel tone="panel" padding="lg">…content over passthrough…</GlassPanel>

<Orbiter items={[
  { icon: "play", label: "Play", active: true },
  { icon: "captions", label: "Subtitles" },
  { icon: "mic", label: "Voice" },
  { icon: "cast", label: "Cast" },
]} />
```

- **GlassPanel** — darkSurface @ 62–88% + 24px blur + hairline border + large radius. `tone="strong"` for dialogs/dense controls; `tone="panel"` over passthrough/video.
- **Orbiter** — a rounded-full glass capsule of `IconButton`s that floats beside a panel. Rule: **one orbiter per panel.** Active item is `filled`, the rest `ghost`.
