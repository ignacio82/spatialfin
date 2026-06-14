// TV — Library: filter tabs + dense poster grid
function TvLibrary({ initialFilter = "all", onOpen }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { FocusCard } = NS;
  const [filter, setFilter] = React.useState(initialFilter);
  const all = window.SF_TV_ALL || [];
  const items = filter === "all" ? all : all.filter((it) => (filter === "movies" ? it.kind === "movie" : it.kind === "show"));

  React.useEffect(() => { if (window.__tvFocusInit) window.__tvFocusInit(); }, []);

  const filters = [
    { id: "all", label: "All" },
    { id: "movies", label: "Movies" },
    { id: "shows", label: "TV Shows" },
  ];

  return (
    <div style={{ padding: "48px 56px 56px 132px", minHeight: "100%", boxSizing: "border-box" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 16, marginBottom: 26 }}>
        <span style={{ width: 4, height: 28, borderRadius: 99, background: "var(--primary)" }} />
        <span style={{ fontSize: 40, fontWeight: 700, color: "var(--text-primary)" }}>Library</span>
        <span style={{ flex: 1 }} />
        <div data-row style={{ display: "flex", gap: 12 }}>
          {filters.map((f) => {
            const on = f.id === filter;
            return (
              <button key={f.id} type="button" tabIndex={0} data-focusable="" data-focus-first={f.id === "all" ? "" : undefined}
                onClick={() => setFilter(f.id)} onMouseEnter={(e) => e.currentTarget.focus()} className="tv-lib-tab"
                style={{ height: 48, padding: "0 26px", borderRadius: "var(--radius-full)",
                  border: on ? "1px solid transparent" : "1px solid var(--border-strong)",
                  background: on ? "var(--primary)" : "transparent", color: on ? "var(--on-primary)" : "var(--text-secondary)",
                  fontSize: 16, fontWeight: 600, cursor: "pointer" }}>
                {f.label}
              </button>
            );
          })}
        </div>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(6, 1fr)", gap: "30px 22px" }}>
        {items.map((it) => (
          <FocusCard key={it.kind + it.id} title={it.title} subtitle={it.type} image={it.image}
            width={"100%"} progress={it.progress || null}
            badge={it.downloaded ? <NS.Badge tone="accent" icon="download" /> : null}
            onClick={() => onOpen({ type: it.kind, id: it.id })} />
        ))}
      </div>
      <style>{`.tv-lib-tab:focus{outline:3px solid var(--primary);outline-offset:3px}`}</style>
    </div>
  );
}
window.TvLibrary = TvLibrary;
