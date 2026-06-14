// TV — Movie detail
function TvMovieDetail({ movie, onBack, onPlay, onOpenOverflow }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { Button, Pill, FocusCard } = NS;
  const more = (window.SF_TV_MOVIES || []).filter((m) => m.id !== movie.id);
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const resumeable = (movie.progress || 0) > 0;

  return (
    <div>
      <div style={{ position: "relative", height: 540 }}>
        <img src={movie.backdrop || movie.poster} alt={movie.title} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(90deg, rgba(6,17,27,0.96) 0%, rgba(6,17,27,0.72) 40%, rgba(6,17,27,0.15) 78%)" }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(0deg, var(--surface-app) 2%, rgba(6,17,27,0) 42%)" }} />
        <div style={{ position: "relative", padding: "0 56px", height: "100%", display: "flex", flexDirection: "column", justifyContent: "center", gap: 16, maxWidth: 720 }}>
          <button data-focusable data-focus-first onClick={onBack} className="tv-back2"
            style={{ position: "absolute", top: 28, left: 56, display: "inline-flex", alignItems: "center", gap: 8, height: 44, padding: "0 18px 0 14px", borderRadius: 999, border: "1px solid var(--glass-border)", background: "var(--glass-fill-strong)", color: "#fff", cursor: "pointer", fontSize: 15, fontWeight: 500 }}>
            ‹ Back
          </button>
          <div className="m3-label-large" style={{ color: "var(--primary)", letterSpacing: 1.4, fontWeight: 700 }}>MOVIE</div>
          <div style={{ fontSize: 64, lineHeight: "1.03", fontWeight: 700, color: "#fff" }}>{movie.title}</div>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
            <Pill tone="rating" icon="star">{movie.stars}</Pill>
            <Pill>{movie.year}</Pill>
            <Pill>{movie.runtime}</Pill>
            <Pill>{movie.rating}</Pill>
            {movie.tags.map((t) => <Pill key={t} tone="outline">{t}</Pill>)}
          </div>
          <div className="m3-body-large" style={{ color: "rgba(255,255,255,0.82)", maxWidth: 600 }}>{movie.overview}</div>
          <div style={{ color: "rgba(255,255,255,0.6)", fontSize: 15 }}>{movie.genres}</div>
          <div style={{ display: "flex", gap: 14, marginTop: 4, flexWrap: "wrap" }}>
            <Button variant="filled" icon="play" size="lg" data-focusable onClick={() => onPlay(movie)}>
              {resumeable ? `Resume · ${movie.progress}%` : "Play"}
            </Button>
            {resumeable ? (
              <Button variant="glass" icon="rotate-ccw" size="lg" data-focusable onClick={() => onPlay(movie)}>Restart</Button>
            ) : null}
            <Button variant="glass" icon={fav ? "heart" : "heart"} size="lg" data-focusable onClick={() => setFav(!fav)}>{fav ? "Favorited" : "Favorite"}</Button>
            <Button variant="glass" icon={watched ? "check-check" : "check"} size="lg" data-focusable onClick={() => setWatched(!watched)}>{watched ? "Watched" : "Mark watched"}</Button>
            <Button variant="glass" icon="download" size="lg" data-focusable>Download</Button>
            <Button variant="glass" icon="plus" size="lg" data-focusable>Watchlist</Button>
            <Button variant="glass" icon="ellipsis" size="lg" data-focusable onClick={() => onOpenOverflow && onOpenOverflow({ kind: "movie", item: movie })}>More</Button>
          </div>
        </div>
      </div>

      <div style={{ marginTop: -28, position: "relative", padding: "0 56px 56px" }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <span style={{ width: 4, height: 22, borderRadius: 99, background: "var(--primary)" }} />
          <span className="m3-headline-small" style={{ color: "var(--text-primary)", fontWeight: 700 }}>More Like This</span>
        </div>
        <div data-row style={{ display: "flex", gap: 22, overflowX: "auto", padding: "18px 0 12px", scrollbarWidth: "none" }}>
          {more.map((m) => (
            <FocusCard key={m.id} title={m.title} subtitle={`${m.year} · ${m.runtime}`} image={m.poster} width={190}
              onClick={() => onPlay(m)} />
          ))}
        </div>
      </div>
      <style>{`.tv-back2:focus{outline:3px solid var(--primary);outline-offset:3px}`}</style>
    </div>
  );
}
window.TvMovieDetail = TvMovieDetail;
