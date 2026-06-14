// TV — Universal Plugin "Source" browse (See all → grid of source items)
function TvSourceBrowse({ source, onBack }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { FocusCard, Icon } = NS;
  React.useEffect(() => { if (window.__tvFocusInit) window.__tvFocusInit(); }, []);

  return (
    <div style={{ padding: "48px 56px 56px", minHeight: "100%", boxSizing: "border-box" }}>
      <button data-focusable data-focus-first onClick={onBack} onMouseEnter={(e) => e.currentTarget.focus()} className="tv-src-back"
        style={{ display: "inline-flex", alignItems: "center", gap: 8, height: 44, padding: "0 18px 0 14px", borderRadius: 999,
          border: "1px solid var(--border-strong)", background: "var(--surface-container-high)", color: "var(--text-primary)", cursor: "pointer", fontSize: 15, fontWeight: 500 }}>
        <Icon name="chevron-left" size={20} /> Back
      </button>
      <div style={{ display: "flex", alignItems: "baseline", gap: 16, marginTop: 22, marginBottom: 26 }}>
        <span style={{ width: 4, height: 28, borderRadius: 99, background: "var(--primary)" }} />
        <span style={{ fontSize: 40, fontWeight: 700, color: "var(--text-primary)" }}>{source.name}</span>
        <span style={{ fontSize: 16, color: "var(--text-secondary)" }}>via {source.pluginId} · {source.items.length} items</span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(5, 1fr)", gap: "28px 22px" }}>
        {source.items.map((it) => (
          <FocusCard key={it.id} title={it.title} subtitle={it.subtitle} image={it.image} width={"100%"}
            orientation={source.pluginId === "youtube" ? "landscape" : "portrait"} />
        ))}
      </div>
      <style>{`.tv-src-back:focus{outline:3px solid var(--primary);outline-offset:3px;background:var(--primary);color:var(--on-primary)}`}</style>
    </div>
  );
}
window.TvSourceBrowse = TvSourceBrowse;
