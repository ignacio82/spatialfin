// Beam (phone) — Detail screen.
// Action set mirrors the SpatialFin app's MovieScreen / EpisodeScreen:
//   Primary row : Play (or Resume), Restart, Favorite, Mark watched, Download, More (…)
//   More sheet  : SyncPlay, Playback options, Go to series, Go to season,
//                 Edit external IDs, Cast & audio, Refresh metadata, Share, Delete.
function BeamDetail({ item, onBack, onPlay, onOpenCast, onOpenSyncPlay, castActive, castLabel }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { Pill, Button, IconButton, Icon } = NS;
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const [moreOpen, setMoreOpen] = React.useState(false);
  const [sheet, setSheet] = React.useState(null); // playback-options & external-ids inline sheets

  const resumeable = (item.progress || 0) > 0;
  const isEpisode = !!item.seriesTitle;

  // Overflow menu items — `admin` rows are styled the same; in a real shell
  // they'd be filtered by the current user's permissions.
  const overflow = [
    { id: "syncplay", icon: "users", title: "SyncPlay", subtitle: "Watch in sync with others", onClick: () => { setMoreOpen(false); onOpenSyncPlay && onOpenSyncPlay(); } },
    { id: "playback", icon: "sliders", title: "Playback options", subtitle: "Default subtitle & audio tracks", onClick: () => { setMoreOpen(false); setSheet("playback"); } },
    isEpisode ? { id: "series", icon: "tv", title: "Go to series", subtitle: item.seriesTitle, onClick: () => setMoreOpen(false) } : null,
    isEpisode ? { id: "season", icon: "list", title: "Go to season", subtitle: "Season " + (item.season || 1), onClick: () => setMoreOpen(false) } : null,
    { id: "extids", icon: "id-card", title: "Edit external IDs", subtitle: "TMDB · IMDB · TVDB", admin: true, onClick: () => { setMoreOpen(false); setSheet("extids"); } },
    { id: "cast", icon: "cast", title: "Cast & audio output", subtitle: castActive ? "On " + castLabel : "This device", onClick: () => { setMoreOpen(false); onOpenCast && onOpenCast(); } },
    { id: "refresh", icon: "refresh-cw", title: "Refresh metadata", subtitle: "Re-scan from providers", admin: true, onClick: () => setMoreOpen(false) },
    { id: "share", icon: "share-2", title: "Share", subtitle: "Send a deeplink", onClick: () => setMoreOpen(false) },
    { id: "delete", icon: "trash-2", title: "Delete", subtitle: "Remove from library", danger: true, admin: true, onClick: () => setMoreOpen(false) },
  ].filter(Boolean);

  return (
    <div style={{ paddingBottom: 24 }}>
      <div style={{ position: "relative", height: 280 }}>
        <img src={item.backdrop} alt={item.title} style={{ width: "100%", height: "100%", objectFit: "cover" }} />
        <div style={{ position: "absolute", inset: 0, background: "var(--scrim-gradient)" }} />
        <div style={{ position: "absolute", top: 12, left: 12 }}>
          <IconButton icon="arrow-left" variant="glass" label="Back" onClick={onBack} />
        </div>
        <div style={{ position: "absolute", left: 16, right: 16, bottom: 14 }}>
          {isEpisode ? (
            <div className="m3-label-large" style={{ color: "rgba(255,255,255,0.85)", marginBottom: 4 }}>
              {item.seriesTitle} · S{item.season} · E{item.episode}
            </div>
          ) : null}
          <div className="m3-display-small" style={{ color: "#fff", fontWeight: 700 }}>{item.title}</div>
          <div style={{ display: "flex", gap: 8, marginTop: 10, flexWrap: "wrap" }}>
            <Pill tone="rating" icon="star">{item.stars}</Pill>
            <Pill>{item.year}</Pill>
            <Pill>{item.runtime}</Pill>
            <Pill>{item.rating}</Pill>
            {item.tags.map((t) => <Pill key={t} tone="outline">{t}</Pill>)}
          </div>
        </div>
      </div>

      <div style={{ padding: "14px 16px 0", display: "flex", flexDirection: "column", gap: 18 }}>
        {/* Primary actions — Play / Restart / Favorite / Watched / Download / More */}
        <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
          <div style={{ flex: 1, minWidth: 160, display: "flex", gap: 8 }}>
            <Button variant="filled" icon="play" onClick={onPlay} style={{ flex: 1 }}>
              {resumeable ? "Resume" : "Play"}
            </Button>
            {resumeable ? (
              <IconButton icon="rotate-ccw" variant="tonal" label="Restart from beginning" onClick={onPlay} />
            ) : null}
          </div>
          <IconButton icon={fav ? "heart" : "heart"} variant={fav ? "filled" : "outlined"} label="Favorite" onClick={() => setFav(!fav)} />
          <IconButton icon={watched ? "check-check" : "check"} variant={watched ? "filled" : "outlined"} label="Mark watched" onClick={() => setWatched(!watched)} />
          <IconButton icon="download" variant="outlined" label="Download" />
          <IconButton icon="ellipsis" variant="outlined" label="More" onClick={() => setMoreOpen(true)} />
        </div>

        {/* Resume strip */}
        {resumeable ? (
          <div>
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 6 }}>
              <span className="m3-label-medium" style={{ color: "var(--text-secondary)" }}>{Math.round((item.progress || 0))}% watched · {item.remaining || "32 min left"}</span>
            </div>
            <NS.ProgressBar value={item.progress || 0} height={5} />
          </div>
        ) : null}

        <div className="m3-body-large" style={{ color: "var(--text-secondary)" }}>{item.overview}</div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
          <div>
            <div className="m3-label-large" style={{ color: "var(--text-secondary)", letterSpacing: 0.5, marginBottom: 6 }}>GENRES</div>
            <div className="m3-body-medium" style={{ color: "var(--text-primary)" }}>{item.genres}</div>
          </div>
          {item.director ? (
            <div>
              <div className="m3-label-large" style={{ color: "var(--text-secondary)", letterSpacing: 0.5, marginBottom: 6 }}>DIRECTOR</div>
              <div className="m3-body-medium" style={{ color: "var(--text-primary)" }}>{item.director}</div>
            </div>
          ) : null}
        </div>

        <div>
          <div className="m3-label-large" style={{ color: "var(--text-secondary)", letterSpacing: 0.5, marginBottom: 10 }}>CAST &amp; CREW</div>
          <div style={{ display: "flex", gap: 16 }}>
            {["Proog", "Emo", "Director"].map((c, i) => (
              <div key={c} style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6, width: 72 }}>
                <span style={{ width: 56, height: 56, borderRadius: "50%", background: ["#3C4758", "#543F5E", "#1F4876"][i],
                  display: "inline-flex", alignItems: "center", justifyContent: "center", color: "#fff", fontWeight: 600 }}>
                  {c[0]}
                </span>
                <span className="m3-body-small" style={{ color: "var(--text-secondary)", textAlign: "center" }}>{c}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ---- Overflow (…) sheet ---- */}
      <window.BeamSheet open={moreOpen} title="More actions" subtitle={item.title} onClose={() => setMoreOpen(false)}>
        {overflow.map((row) => (
          <window.BeamOptionRow key={row.id} icon={row.icon} title={row.title} subtitle={row.subtitle}
            onClick={row.onClick}
            trailing={row.admin ? <Pill tone="outline">admin</Pill> : <Icon name="chevron-right" size={18} color="var(--text-secondary)" />} />
        ))}
      </window.BeamSheet>

      {/* ---- Playback options sub-sheet ---- */}
      <window.BeamSheet open={sheet === "playback"} title="Playback options" subtitle="Defaults for this title" onClose={() => setSheet(null)}>
        <window.BeamOptionRow icon="captions" title="Default subtitle" subtitle="English (SDH)" />
        <window.BeamOptionRow icon="audio-lines" title="Default audio" subtitle="English 5.1" />
        <window.BeamOptionRow icon="gauge" title="Default quality" subtitle="Auto · up to 4K" />
        <window.BeamOptionRow icon="play" title="Autoplay next" subtitle="On" />
      </window.BeamSheet>

      {/* ---- Edit external IDs sub-sheet ---- */}
      <window.BeamSheet open={sheet === "extids"} title="Edit external IDs" subtitle="Used for metadata sync" onClose={() => setSheet(null)}
        footer={<Button variant="filled" fullWidth icon="check">Save</Button>}>
        {[
          { id: "tmdb", label: "TMDB", value: "10378" },
          { id: "imdb", label: "IMDB", value: "tt1254207" },
          { id: "tvdb", label: "TVDB", value: "—" },
        ].map((row) => (
          <div key={row.id} style={{ padding: "10px 14px" }}>
            <div className="m3-label-medium" style={{ color: "var(--text-secondary)", marginBottom: 6 }}>{row.label}</div>
            <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "0 14px", height: 46,
              borderRadius: 14, background: "var(--surface-container-high)" }}>
              <input defaultValue={row.value} style={{ flex: 1, border: "none", outline: "none", background: "transparent",
                color: "var(--text-primary)", fontSize: 15, fontFamily: "var(--font-sans)" }} />
            </div>
          </div>
        ))}
      </window.BeamSheet>
    </div>
  );
}
window.BeamDetail = BeamDetail;
