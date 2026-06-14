# XR (spatial) UI kit

A high-fidelity recreation of **SpatialFin on Android XR** (Galaxy XR / headset). The defining SpatialFin experience: a translucent **glass spatial panel** floating over real-world passthrough, with a **floating orbiter** of controls beside it.

Open `index.html`:

- **Spatial panel** — a `GlassPanel` over a passthrough environment. Left side is the hero detail (selected title, metadata pills, Play / Download); right side is a `Library` grid of `PosterCard`s. Click any poster to load it into the hero.
- **Orbiter** — one floating glass control cluster to the right of the panel (Home / Movies / Local / Downloads / Voice / Cast). Rule: **one orbiter per panel.**
- **Voice** — click the orbiter mic to run the listening → thinking → answered cycle; feedback appears as a spatial overlay above the panel.

Files: `index.html` (stage + passthrough), `App.jsx`. Catalog data is shared from `../beam/data.js`. Components come from `window.SpatialFinDesignSystem_0d3fe7`.

> Note: the passthrough backdrop (`assets/xr-environment.png`) is a representative generated environment, not a real camera feed.
