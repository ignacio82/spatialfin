// TV — Home / Browse
function TvHome({ onOpen, onPlayMaTrack, onSourceSeeAll, onOpenOverflow }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { FocusCard, Button, Pill, Icon } = NS;
  const F = window.SF_TV_FEATURED;
  const shows = window.SF_TV_SHOWS;
  const movies = window.SF_TV_MOVIES;
  const cont = window.SF_TV_CONTINUE;
  const maShelf = window.SF_MA_SHELF || [];
  const sources = window.SF_TV_SOURCES || [];
  const [fav, setFav] = React.useState(false);
  const [watched, setWatched] = React.useState(false);
  const setMoreOpen = (v) => onOpenOverflow && onOpenOverflow({ kind: "episode", item: { ...F, seriesTitle: F.title, season: 1, episode: 1 } });

  // pre-build a "Next Up" set: first unwatched episode of each show
  const nextUp = (shows || []).map((s) => {
    const seasonKeys = Object.keys(s.seasons).map(Number);
    for (const sk of seasonKeys) {
      const eps = s.seasons[sk];
      const ep = eps.find((e) => (e.progress || 0) < 100);
      if (ep) return { id: "nu-" + s.id + "-" + sk + "-" + ep.n, title: s.title, subtitle: `S${sk} E${ep.n} · ${ep.title}`, image: ep.still, progress: ep.progress || 0, show: s.id };
    }
    return null;
  }).filter(Boolean);

  const Shelf = ({ title, onSeeAll, children }) => (
    <div style={{ marginTop: 28 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "0 56px" }}>
        <span style={{ width: 4, height: 22, borderRadius: 99, background: "var(--primary)" }} />
        <span className="m3-headline-small" style={{ color: "var(--text-primary)", fontWeight: 700 }}>{title}</span>
        <span style={{ flex: 1 }} />
        {onSeeAll ? (
          <button data-focusable type="button" onClick={onSeeAll} onMouseEnter={(e) => e.currentTarget.focus()}
            className="tv-seeall"
            style={{ display: "inline-flex", alignItems: "center", gap: 6, height: 38, padding: "0 16px", borderRadius: 999,
              border: "1px solid var(--border-strong)", background: "transparent", color: "var(--text-secondary)",
              fontSize: 14, fontWeight: 600, cursor: "pointer" }}>
            See all <Icon name="chevron-right" size={16} />
          </button>
        ) : null}
      </div>
      <div data-row style={{ display: "flex", gap: 22, overflowX: "auto", padding: "16px 56px 8px", scrollbarWidth: "none" }}>
        {children}
      </div>
    </div>
  );

  // Music Assistant tile — landscape glass card with artwork + title/artist
  const MaTile = ({ t, onPlay, first }) => {
    const [foc, setFoc] = React.useState(false);
    return (
      <div data-focusable tabIndex={0} data-focus-first={first ? "" : undefined}
        onFocus={() => setFoc(true)} onBlur={() => setFoc(false)}
        onMouseEnter={(e) => e.currentTarget.focus()} onClick={onPlay}
        style={{ width: 220, flex: "0 0 auto", borderRadius: "var(--radius-md)", padding: 10,
          background: "var(--surface-container-high)",
          outline: foc ? "3px solid var(--primary)" : "3px solid transparent", outlineOffset: 3,
          transform: foc ? "scale(var(--tv-focus-scale))" : "scale(1)",
          transition: "transform var(--duration-fast) var(--ease-standard), outline-color var(--duration-fast)",
          boxShadow: foc ? "0 14px 44px -8px rgba(125,218,255,0.5)" : "none",
          cursor: "pointer", display: "flex", gap: 12, alignItems: "center" }}>
        <img src={t.artwork} alt="" style={{ width: 64, height: 64, borderRadius: 10, flexShrink: 0, objectFit: "cover" }} />
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ fontSize: 14, fontWeight: 600, color: "var(--text-primary)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{t.title}</div>
          <div style={{ fontSize: 12, color: "var(--text-secondary)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", marginTop: 2 }}>{t.artist}</div>
          {foc ? (
            <div style={{ marginTop: 6, fontSize: 11, color: "var(--primary)", fontWeight: 600, letterSpacing: 0.5 }}>
              ENTER · PLAY  ·  LONG-PRESS · QUEUE
            </div>
          ) : null}
        </div>
      </div>
    );
  };

  return (
    <div>
      {/* Featured hero */}
      <div style={{ position: "relative", height: 560 }}>
        <img src={F.backdrop} alt={F.title} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(90deg, rgba(6,17,27,0.95) 0%, rgba(6,17,27,0.7) 38%, rgba(6,17,27,0.1) 75%)" }} />
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(0deg, var(--surface-app) 2%, rgba(6,17,27,0) 40%)" }} />
        <div style={{ position: "relative", padding: "0 56px", height: "100%", display: "flex", flexDirection: "column", justifyContent: "center", gap: 18, maxWidth: 820 }}>
          <div className="m3-label-large" style={{ color: "var(--primary)", letterSpacing: 1.4, fontWeight: 700 }}>FEATURED SERIES</div>
          <div style={{ fontSize: 72, lineHeight: "1.02", fontWeight: 700, color: "#fff", textShadow: "0 4px 24px rgba(0,0,0,0.5)" }}>{F.title}</div>
          <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
            <Pill tone="rating" icon="star">{F.stars}</Pill>
            <Pill>{F.year}</Pill>
            <Pill>{F.runtime}</Pill>
            <Pill>{F.rating}</Pill>
            {F.tags.map((t) => <Pill key={t} tone="outline">{t}</Pill>)}
          </div>
          <div className="m3-body-large" style={{ color: "rgba(255,255,255,0.82)", maxWidth: 620 }}>{F.overview}</div>
          <div style={{ display: "flex", gap: 16, marginTop: 6, flexWrap: "wrap" }}>
            <Button variant="filled" icon="play" size="lg" data-focusable data-focus-first onClick={() => onOpen({ type: "show", id: F.id })}>Play S1 E1</Button>
            <Button variant="glass" icon="info" size="lg" data-focusable onClick={() => onOpen({ type: "show", id: F.id })}>More info</Button>
            <Button variant="glass" icon="plus" size="lg" data-focusable>Watchlist</Button>
            <Button variant="glass" icon={fav ? "heart" : "heart"} size="lg" data-focusable onClick={() => setFav(!fav)}>{fav ? "Favorited" : "Favorite"}</Button>
            <Button variant="glass" icon={watched ? "check-check" : "check"} size="lg" data-focusable onClick={() => setWatched(!watched)}>{watched ? "Watched" : "Mark watched"}</Button>
            <Button variant="glass" icon="ellipsis" size="lg" data-focusable onClick={() => setMoreOpen(true)}>More</Button>
          </div>
        </div>
      </div>

      <div style={{ marginTop: -40, position: "relative", paddingBottom: 56 }}>
        {cont.length ? (
          <Shelf title="Continue Watching">
            {cont.map((m) => (
              <FocusCard key={m.id} orientation="landscape" width={340} title={m.title} subtitle={m.subtitle}
                image={m.image} progress={m.progress}
                onClick={() => onOpen(m.show ? { type: "show", id: m.show } : { type: "movie", id: m.movie })} />
            ))}
          </Shelf>
        ) : null}

        {nextUp.length ? (
          <Shelf title="Next Up">
            {nextUp.map((n) => (
              <FocusCard key={n.id} orientation="landscape" width={340} title={n.title} subtitle={n.subtitle}
                image={n.image} progress={n.progress || null}
                onClick={() => onOpen({ type: "show", id: n.show })} />
            ))}
          </Shelf>
        ) : null}

        {maShelf.length ? (
          <Shelf title="Music Assistant">
            {maShelf.map((t, i) => (
              <MaTile key={t.id} t={t} first={i === 0 && cont.length === 0 && nextUp.length === 0}
                onPlay={() => onPlayMaTrack && onPlayMaTrack(t)} />
            ))}
          </Shelf>
        ) : null}

        {sources.map((src) => (
          <Shelf key={src.id} title={src.name} onSeeAll={() => onSourceSeeAll && onSourceSeeAll(src)}>
            {src.items.map((it) => (
              <FocusCard key={it.id} title={it.title} subtitle={it.subtitle} image={it.image} width={210}
                orientation={src.pluginId === "youtube" ? "landscape" : "portrait"}
                onClick={() => onSourceSeeAll && onSourceSeeAll(src)} />
            ))}
          </Shelf>
        ))}

        <Shelf title="TV Shows">
          {shows.map((s) => (
            <FocusCard key={s.id} title={s.title} subtitle={s.kind} image={s.poster} width={190}
              onClick={() => onOpen({ type: "show", id: s.id })} />
          ))}
          {movies.slice(0, 2).map((m) => (
            <FocusCard key={"x" + m.id} title={m.title} subtitle="Movie" image={m.poster} width={190}
              onClick={() => onOpen({ type: "movie", id: m.id })} />
          ))}
        </Shelf>

        <Shelf title="Movies">
          {movies.map((m) => (
            <FocusCard key={m.id} title={m.title} subtitle={`${m.year} · ${m.runtime}`} image={m.poster} width={190}
              progress={m.progress || null}
              onClick={() => onOpen({ type: "movie", id: m.id })} />
          ))}
        </Shelf>
      </div>
      <style>{`.tv-seeall:focus{outline:3px solid var(--primary);outline-offset:3px;background:var(--surface-container-high);color:var(--text-primary)}`}</style>
    </div>
  );
}
window.TvHome = TvHome;
