// TV — Music Assistant Now Playing (full screen).
// Tap-target for tapping the MaMiniPlayer; mirrors MaNowPlayingScreen.kt in spirit
// (large artwork, scrubber, transport, SendSpin player tag, queue panel access).
function TvNowPlaying({ track, phase, selectedPlayer, queue, position, duration, onBack, onPlayPause, onNext, onPrev, onStop, onPickPlayer, onOpenQueue }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { Icon, ProgressBar } = NS;
  if (!track) return null;
  const fmt = (s) => {
    s = Math.max(0, Math.floor(s));
    return Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
  };

  const ctrl = (icon, label, opts = {}) => (
    <button data-focusable="" onClick={opts.onClick} onMouseEnter={(e) => e.currentTarget.focus()}
      aria-label={label} title={label} className="tv-pctrl"
      style={{ width: opts.big ? 84 : 56, height: opts.big ? 84 : 56, borderRadius: "50%",
        border: "1px solid var(--glass-border)",
        background: opts.active ? "var(--primary)" : "var(--glass-fill-strong)",
        color: opts.active ? "var(--on-primary)" : "#fff", cursor: "pointer",
        display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
      <Icon name={icon} size={opts.big ? 36 : 24} />
    </button>
  );

  return (
    <div style={{ position: "absolute", inset: 0, background: "var(--surface-app)", display: "flex", alignItems: "center", justifyContent: "center", overflow: "hidden" }}>
      {/* atmospheric blurred-artwork backdrop */}
      <img src={track.artwork} alt="" style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover", filter: "blur(60px) saturate(1.2)", transform: "scale(1.15)", opacity: 0.55 }} />
      <div style={{ position: "absolute", inset: 0, background: "linear-gradient(180deg, rgba(6,17,27,0.3) 0%, rgba(6,17,27,0.8) 100%)" }} />

      {/* back chip */}
      <button data-focusable="" data-focus-first onClick={onBack} onMouseEnter={(e) => e.currentTarget.focus()} className="tv-pctrl"
        aria-label="Back" style={{ position: "absolute", top: 32, left: 56, zIndex: 5, height: 56, padding: "0 22px 0 18px", borderRadius: 999, border: "1px solid var(--glass-border)", background: "var(--glass-fill-strong)", color: "#fff", display: "inline-flex", alignItems: "center", gap: 8, fontSize: 16, fontWeight: 500, cursor: "pointer" }}>
        <Icon name="chevron-down" size={22} /> Close
      </button>

      <div style={{ position: "relative", display: "flex", gap: 56, alignItems: "center", padding: "0 80px", width: "100%", maxWidth: 1380, boxSizing: "border-box" }}>
        {/* artwork */}
        <div style={{ width: 360, height: 360, borderRadius: "var(--radius-lg)", overflow: "hidden", boxShadow: "0 30px 80px -10px rgba(0,0,0,0.6)", flexShrink: 0, background: "var(--surface-container-low)" }}>
          <img src={track.artwork} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
        </div>

        {/* metadata + transport */}
        <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 18 }}>
          <div className="m3-label-large" style={{ color: "var(--primary)", letterSpacing: 1.4, fontWeight: 700 }}>NOW PLAYING</div>
          <div style={{ fontSize: 56, lineHeight: "1.04", fontWeight: 700, color: "#fff", textShadow: "0 2px 18px rgba(0,0,0,0.5)" }}>{track.title}</div>
          <div className="m3-headline-small" style={{ color: "rgba(255,255,255,0.82)", fontWeight: 500 }}>{track.artist}{track.album ? " · " + track.album : ""}</div>

          <div style={{ display: "flex", alignItems: "center", gap: 16, marginTop: 12 }}>
            <span style={{ color: "#fff", fontFamily: "var(--font-mono)", fontSize: 16, minWidth: 50 }}>{fmt(position)}</span>
            <div style={{ flex: 1 }}><ProgressBar value={(position / duration) * 100} height={8} /></div>
            <span style={{ color: "rgba(255,255,255,0.7)", fontFamily: "var(--font-mono)", fontSize: 16, minWidth: 50, textAlign: "right" }}>{fmt(duration)}</span>
          </div>

          <div style={{ display: "flex", alignItems: "center", gap: 18, marginTop: 10 }}>
            {ctrl("skip-back", "Previous", { onClick: onPrev })}
            {ctrl(phase === "playing" ? "pause" : "play", phase === "playing" ? "Pause" : "Play", { big: true, onClick: onPlayPause })}
            {ctrl("skip-forward", "Next", { onClick: onNext })}
            <span style={{ flex: 1 }} />
            {ctrl("square", "Stop", { onClick: onStop })}
            {ctrl("list-music", "Queue", { onClick: onOpenQueue })}
            <button data-focusable="" onClick={onPickPlayer} onMouseEnter={(e) => e.currentTarget.focus()} className="tv-pctrl"
              aria-label="Send to" title="Send to"
              style={{ height: 56, padding: "0 22px 0 18px", borderRadius: 999, border: "1px solid var(--glass-border)",
                background: "var(--glass-fill-strong)", color: "#fff", display: "inline-flex", alignItems: "center", gap: 10, fontSize: 15, fontWeight: 600, cursor: "pointer" }}>
              <Icon name="cast" size={20} />
              SendSpin · {selectedPlayer || "This device"}
            </button>
          </div>

          {/* preparing state banner */}
          {phase === "preparing" ? (
            <div style={{ marginTop: 18, padding: "12px 16px", borderRadius: 12, background: "var(--accent-container)", color: "var(--on-accent-container)", display: "inline-flex", alignItems: "center", gap: 10, fontSize: 14, alignSelf: "flex-start" }}>
              <Icon name="loader" size={18} style={{ animation: "tv-spin 1s linear infinite" }} />
              Preparing audio… Music Assistant is loading the queue.
            </div>
          ) : null}

          {/* tiny queue preview row */}
          {queue && queue.length > 1 ? (
            <div style={{ marginTop: 22, display: "flex", alignItems: "center", gap: 12 }}>
              <span className="m3-label-large" style={{ color: "var(--text-secondary)", letterSpacing: 1, marginRight: 6 }}>UP NEXT</span>
              {queue.slice(1, 4).map((t) => (
                <span key={t.id} style={{ display: "inline-flex", alignItems: "center", gap: 8, padding: "6px 12px 6px 6px", borderRadius: 999, background: "var(--glass-fill-strong)", border: "1px solid var(--glass-border)", color: "#fff", fontSize: 13 }}>
                  <img src={t.artwork} alt="" style={{ width: 26, height: 26, borderRadius: 6 }} />
                  <span style={{ maxWidth: 180, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{t.title} <span style={{ opacity: 0.7 }}>· {t.artist}</span></span>
                </span>
              ))}
            </div>
          ) : null}
        </div>
      </div>
      <style>{`@keyframes tv-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
window.TvNowPlaying = TvNowPlaying;
