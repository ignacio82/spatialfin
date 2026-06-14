// TV — Show detail: hero + seasons + episodes
function TvShowDetail({ show, onBack, onPlay, onOpenOverflow }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { Button, Pill, SeasonTabs, EpisodeCard } = NS;
  const seasonKeys = Object.keys(show.seasons).map(Number);
  const [season, setSeason] = React.useState(seasonKeys[0]);
  const eps = show.seasons[season] || [];
  const next = eps.find((e) => e.progress > 0 && e.progress < 100) || eps[0];
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const resumeable = next && (next.progress || 0) > 0 && (next.progress || 0) < 100;

  return (
    <div>
      <div style={{ position: "relative", height: 520 }}>
        <img src={show.backdrop} alt={show.title} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(90deg, rgba(6,17,27,0.96) 0%, rgba(6,17,27,0.72) 40%, rgba(6,17,27,0.15) 78%)" }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(0deg, var(--surface-app) 2%, rgba(6,17,27,0) 42%)" }} />
        <div style={{ position: "relative", padding: "0 56px", height: "100%", display: "flex", flexDirection: "column", justifyContent: "center", gap: 16, maxWidth: 720 }}>
          <button data-focusable data-focus-first onClick={onBack} className="tv-back"
            style={{ position: "absolute", top: 28, left: 56, display: "inline-flex", alignItems: "center", gap: 8, height: 44, padding: "0 18px 0 14px", borderRadius: 999, border: "1px solid var(--glass-border)", background: "var(--glass-fill-strong)", color: "#fff", cursor: "pointer", fontSize: 15, fontWeight: 500 }}>
            ‹ Back
          </button>
          <div className="m3-label-large" style={{ color: "var(--primary)", letterSpacing: 1.4, fontWeight: 700 }}>SERIES</div>
          <div style={{ fontSize: 60, lineHeight: "1.04", fontWeight: 700, color: "#fff" }}>{show.title}</div>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
            <Pill tone="rating" icon="star">{show.stars}</Pill>
            <Pill>{show.year}</Pill>
            <Pill>{seasonKeys.length} season{seasonKeys.length > 1 ? "s" : ""}</Pill>
            <Pill>{show.rating}</Pill>
            {show.tags.map((t) => <Pill key={t} tone="outline">{t}</Pill>)}
          </div>
          <div className="m3-body-large" style={{ color: "rgba(255,255,255,0.82)", maxWidth: 600 }}>{show.overview}</div>
          <div style={{ display: "flex", gap: 14, marginTop: 4, flexWrap: "wrap" }}>
            <Button variant="filled" icon="play" size="lg" data-focusable onClick={() => onPlay(show, next)}>
              {resumeable ? `Resume S${season} E${next.n}` : `Play S${season} E1`}
            </Button>
            {resumeable ? (
              <Button variant="glass" icon="rotate-ccw" size="lg" data-focusable onClick={() => onPlay(show, { ...next, progress: 0 })}>Restart episode</Button>
            ) : null}
            <Button variant="glass" icon={fav ? "heart" : "heart"} size="lg" data-focusable onClick={() => setFav(!fav)}>{fav ? "Favorited" : "Favorite"}</Button>
            <Button variant="glass" icon={watched ? "check-check" : "check"} size="lg" data-focusable onClick={() => setWatched(!watched)}>{watched ? "Watched" : "Mark watched"}</Button>
            <Button variant="glass" icon="plus" size="lg" data-focusable>Watchlist</Button>
            <Button variant="glass" icon="ellipsis" size="lg" data-focusable onClick={() => onOpenOverflow && onOpenOverflow({ kind: "series", item: show })}>More</Button>
          </div>
        </div>
      </div>

      <div style={{ marginTop: -28, position: "relative", padding: "0 56px 56px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 18, marginBottom: 6 }}>
          <span className="m3-headline-small" style={{ color: "var(--text-primary)", fontWeight: 700 }}>Episodes</span>
          <SeasonTabs seasons={seasonKeys} active={season} onChange={setSeason} style={{ flex: 1 }} />
        </div>
        <div data-row style={{ display: "flex", gap: 22, overflowX: "auto", padding: "18px 0 12px", scrollbarWidth: "none" }}>
          {eps.map((e) => (
            <EpisodeCard key={e.n} number={e.n} title={e.title} still={e.still} runtime={e.runtime}
              synopsis={e.synopsis} progress={e.progress || null} width={360}
              onClick={() => onPlay(show, e)} />
          ))}
        </div>
      </div>
      <style>{`.tv-back:focus{outline:3px solid var(--primary);outline-offset:3px}`}</style>
    </div>
  );
}
window.TvShowDetail = TvShowDetail;
