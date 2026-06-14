// Beam (phone) — Home screen
function BeamHome({ onOpen, serverName, castActive, castLabel, user, onOpenServer, onOpenCast, onOpenUser }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { HeroBanner, SectionHeader, PosterCard, Badge, IconButton, Button, Icon } = NS;
  const F = window.SF_FEATURED, cat = window.SF_CATALOG;
  const cont = cat.filter((m) => m.progress > 0);

  return (
    <div style={{ paddingBottom: 16 }}>
      {/* top app bar */}
      <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "14px 14px 8px" }}>
        <img src="../../assets/logo-mark.png" alt="" style={{ width: 30, height: 30, borderRadius: 8 }} />
        <button type="button" onClick={onOpenServer}
          style={{ display: "flex", flexDirection: "column", alignItems: "flex-start", gap: 0, flex: 1, minWidth: 0,
            background: "transparent", border: "none", cursor: "pointer", padding: 0, textAlign: "left" }}>
          <span className="m3-title-large" style={{ color: "var(--text-primary)", fontWeight: 700, lineHeight: 1.1 }}>SpatialFin</span>
          <span style={{ display: "flex", alignItems: "center", gap: 3, color: "var(--text-secondary)", fontSize: 12, maxWidth: "100%", whiteSpace: "nowrap", overflow: "hidden" }}>
            <span style={{ overflow: "hidden", textOverflow: "ellipsis" }}>{serverName || "Jellyfin"}</span> <Icon name="chevron-down" size={13} />
          </span>
        </button>
        <IconButton icon="cast" variant={castActive ? "filled" : "ghost"} label={castActive ? "Casting to " + castLabel : "Cast"} size="sm" onClick={onOpenCast} />
        <button type="button" onClick={onOpenUser} aria-label={"Switch user — " + ((user && user.name) || "")}
          style={{ border: "none", background: "transparent", padding: 0, cursor: "pointer", display: "inline-flex", borderRadius: "50%" }}>
          <window.BeamUserAvatar user={user} size={34} />
        </button>
      </div>

      <div style={{ padding: "0 16px" }}>
        <HeroBanner title={F.title} kind={F.kind} backdrop={F.backdrop} meta={[F.year, F.runtime, ...F.tags]} height={300}
          actions={<>
            <Button variant="filled" icon="play" onClick={() => onOpen(F, "player")}>Play</Button>
            <Button variant="glass" onClick={() => onOpen(F, "detail")}>Details</Button>
          </>} />
      </div>

      <div style={{ padding: "22px 0 0 16px" }}>
        <SectionHeader title="Suggestions" />
        <div style={{ display: "flex", gap: 14, overflowX: "auto", padding: "14px 16px 6px 0", scrollbarWidth: "none" }}>
          {cat.map((m) => (
            <div key={m.id} style={{ flex: "0 0 auto" }}>
              <PosterCard title={m.title} subtitle={`Movie${m.progress ? " · " + m.progress + "% watched" : ""}`}
                poster={m.poster} progress={m.progress || null} width={132}
                badge={m.downloaded ? <Badge tone="accent" icon="download" /> : null}
                onClick={() => onOpen(m, "detail")} />
            </div>
          ))}
        </div>
      </div>

      <div style={{ padding: "10px 0 0 16px" }}>
        <SectionHeader title="Continue Watching" />
        <div style={{ display: "flex", gap: 14, overflowX: "auto", padding: "14px 16px 6px 0", scrollbarWidth: "none" }}>
          {cont.map((m) => (
            <div key={m.id} style={{ flex: "0 0 auto" }}>
              <PosterCard title={m.title} subtitle={`${m.progress}% watched`} poster={m.poster} progress={m.progress} width={150}
                onClick={() => onOpen(m, "player")} />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
window.BeamHome = BeamHome;
