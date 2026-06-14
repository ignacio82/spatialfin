// TV — Search: on-screen keyboard + live results grid
function TvSearch({ onOpen }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { TvKeyboard, FocusCard, Icon } = NS;
  const [q, setQ] = React.useState("");
  const all = window.SF_TV_ALL || [];
  const results = q.trim()
    ? all.filter((it) => it.title.toLowerCase().includes(q.trim().toLowerCase()))
    : all;

  React.useEffect(() => { if (window.__tvFocusInit) window.__tvFocusInit(); }, []);

  return (
    <div style={{ display: "flex", gap: 48, padding: "56px 56px 56px 132px", minHeight: "100%", boxSizing: "border-box" }}>
      {/* left: query field + keyboard */}
      <div style={{ flexShrink: 0, display: "flex", flexDirection: "column", gap: 22, width: 452 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 14, height: 64, padding: "0 22px", borderRadius: "var(--radius-md)",
          background: "var(--surface-container-high)", border: "1px solid var(--border-subtle)" }}>
          <Icon name="search" size={26} color="var(--text-secondary)" />
          <span style={{ fontSize: 24, color: q ? "var(--text-primary)" : "var(--text-disabled)", fontWeight: 500, letterSpacing: 0.5 }}>
            {q || "Search movies & shows"}
          </span>
          <span className="tv-caret" style={{ width: 2, height: 28, background: "var(--primary)", marginLeft: -4 }} />
        </div>
        <TvKeyboard value={q} onChange={setQ} />
        <style>{`@keyframes tv-blink{0%,100%{opacity:1}50%{opacity:0}} .tv-caret{animation:tv-blink 1s step-end infinite}`}</style>
      </div>

      {/* right: results */}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="m3-title-large" style={{ color: "var(--text-secondary)", fontWeight: 600, marginBottom: 18 }}>
          {q.trim() ? `Results for “${q.trim()}” · ${results.length}` : `Browse all · ${results.length}`}
        </div>
        {results.length ? (
          <div data-row style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: "26px 22px" }}>
            {results.map((it) => (
              <FocusCard key={it.kind + it.id} title={it.title} subtitle={it.type} image={it.image}
                width={"100%"} progress={it.progress || null}
                onClick={() => onOpen({ type: it.kind, id: it.id })} />
            ))}
          </div>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: 360, gap: 14, color: "var(--text-disabled)" }}>
            <Icon name="search-x" size={48} color="var(--text-disabled)" />
            <span className="m3-title-medium">No titles match “{q.trim()}”</span>
          </div>
        )}
      </div>
    </div>
  );
}
window.TvSearch = TvSearch;
