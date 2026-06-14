// XR (Galaxy XR) — spatial home over passthrough
function XrApp() {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { GlassPanel, Orbiter, PosterCard, Pill, Button, IconButton, Badge, VoiceFeedback } = NS;
  const cat = window.SF_CATALOG, F = window.SF_FEATURED;
  const [sel, setSel] = React.useState(F);
  const [voice, setVoice] = React.useState("idle");

  const cycleVoice = () => {
    if (voice !== "idle") { setVoice("idle"); return; }
    setVoice("listening");
    setTimeout(() => setVoice("processing"), 1600);
    setTimeout(() => setVoice("answered"), 3000);
    setTimeout(() => setVoice("idle"), 5400);
  };

  const cont = cat.filter((m) => m.progress > 0);
  const recent = cat.slice().reverse();

  // Dense browse shelf — small poster tiles, horizontal scroll, click selects.
  const Shelf = ({ title, items, count }) => (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 9, padding: "0 20px" }}>
        <span style={{ width: 3, height: 16, borderRadius: 99, background: "var(--accent)" }} />
        <span className="m3-title-medium" style={{ fontWeight: 700 }}>{title}</span>
        <span className="m3-body-small" style={{ color: "var(--text-disabled)" }}>{count != null ? count : items.length}</span>
      </div>
      <div style={{ display: "flex", gap: 12, overflowX: "auto", padding: "2px 20px 4px", scrollbarWidth: "none" }}>
        {items.map((m) => (
          <div key={m.id} style={{ flex: "0 0 auto" }}>
            <PosterCard title={m.title} subtitle={m.progress ? m.progress + "% watched" : m.year}
              poster={m.poster} progress={m.progress || null} width={100}
              badge={m.downloaded ? <Badge tone="accent" icon="download" /> : null}
              style={{ outline: sel.id === m.id ? "2px solid var(--accent)" : undefined, outlineOffset: 2, borderRadius: "var(--radius-md)" }}
              onClick={() => setSel(m)} />
          </div>
        ))}
      </div>
    </div>
  );

  return (
    <div className="xr-stage">
      <img className="xr-env" src="../../assets/xr-environment.png" alt="" />

      {/* main spatial panel */}
      <div className="xr-panel-wrap">
        <GlassPanel tone="panel" padding="none" radius="lg" style={{ width: "100%", height: "86vh", maxHeight: 820, overflow: "hidden", display: "flex", flexDirection: "column" }}>
          {/* panel top bar */}
          <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "12px 20px", borderBottom: "1px solid var(--glass-border)", flexShrink: 0 }}>
            <img src="../../assets/logo-mark.png" alt="" style={{ width: 26, height: 26, borderRadius: 7 }} />
            <span className="m3-title-medium" style={{ flex: 1, fontWeight: 600 }}>SpatialFin</span>
            <Button variant="glass" icon="search" size="sm">Search</Button>
            <Button variant="glass" icon="settings" size="sm">Settings</Button>
            <IconButton icon="x" variant="glass" label="Close" size="sm" />
          </div>

          {/* compact hero strip — detail for the selected title */}
          <div style={{ position: "relative", height: 184, flexShrink: 0, borderBottom: "1px solid var(--glass-border)" }}>
            <img src={sel.backdrop} alt={sel.title} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />
            <div style={{ position: "absolute", inset: 0, background: "linear-gradient(90deg, rgba(17,19,24,0.95) 0%, rgba(17,19,24,0.6) 52%, rgba(17,19,24,0.1) 100%)" }} />
            <div style={{ position: "relative", padding: "0 24px", display: "flex", flexDirection: "column", gap: 9, height: "100%", boxSizing: "border-box", justifyContent: "center", maxWidth: 600 }}>
              <div className="m3-headline-small" style={{ fontWeight: 700 }}>{sel.title}</div>
              <div style={{ display: "flex", gap: 7, flexWrap: "wrap" }}>
                <Pill tone="rating" icon="star">{sel.stars}</Pill>
                <Pill>{sel.year}</Pill>
                {sel.runtime ? <Pill>{sel.runtime}</Pill> : null}
                {(sel.tags || []).map((t) => <Pill key={t} tone="outline">{t}</Pill>)}
              </div>
              <div className="m3-body-small" style={{ color: "var(--text-secondary)", maxWidth: 520, display: "-webkit-box", WebkitLineClamp: 1, WebkitBoxOrient: "vertical", overflow: "hidden" }}>{sel.overview}</div>
              <div style={{ display: "flex", gap: 10, marginTop: 2 }}>
                <Button variant="filled" icon="play" size="sm">Play</Button>
                <Button variant="glass" icon="download" size="sm">Download</Button>
                <Button variant="glass" icon="info" size="sm">Details</Button>
              </div>
            </div>
          </div>

          {/* dense browse — multiple shelves, vertical scroll */}
          <div style={{ flex: 1, minHeight: 0, overflowY: "auto", padding: "14px 0 18px", display: "flex", flexDirection: "column", gap: 16, scrollbarWidth: "none" }}>
            {cont.length ? <Shelf title="Continue Watching" items={cont} /> : null}
            <Shelf title="Movies" items={cat} />
            <Shelf title="Recently Added" items={recent} />
          </div>
        </GlassPanel>

        {voice !== "idle" ? (
          <div style={{ position: "absolute", top: -56, left: "50%", transform: "translateX(-50%)", zIndex: 8 }}>
            <VoiceFeedback state={voice === "listening" ? "listening" : voice === "processing" ? "processing" : "answered"}
              text={voice === "answered" ? "Playing " + sel.title + "." : voice === "listening" ? "“what should I watch?”" : undefined} />
          </div>
        ) : null}
      </div>

      {/* orbiter — one per panel, floats to the right */}
      <div className="xr-orbiter">
        <Orbiter items={[
          { icon: "house", label: "Home", active: true },
          { icon: "clapperboard", label: "Movies" },
          { icon: "folder", label: "Local" },
          { icon: "download", label: "Downloads" },
          { icon: "mic", label: "Voice", onClick: cycleVoice, active: voice === "listening" },
          { icon: "cast", label: "Cast" },
        ]} />
      </div>
    </div>
  );
}
window.XrApp = XrApp;
