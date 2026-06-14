// TV — Media player with 10-foot transport chrome, Up Next autoplay & voice overlay
function TvPlayer({ item, episode, onBack, onPlayNext }) {
  const NS = window.SpatialFinDesignSystem_0d3fe7;
  const { ProgressBar, Pill, Icon, UpNextCard, VoiceFeedback } = NS;
  const [playing, setPlaying] = React.useState(true);
  const [voice, setVoice] = React.useState("idle");
  const [upNext, setUpNext] = React.useState(null);     // resolved next item or null
  const [remaining, setRemaining] = React.useState(10);
  const title = item.title;
  const sub = episode ? `S${episode.season || 1} E${episode.n} · ${episode.title}` : item.genres;
  const still = episode ? episode.still : (item.backdrop || item.poster);

  // resolve what plays next: next episode, else a suggested movie
  const resolveNext = React.useCallback(() => {
    if (episode) {
      const nx = window.SF_TV_NEXT_EP ? window.SF_TV_NEXT_EP(item, episode) : null;
      if (nx) return { kind: "episode", show: item, ep: nx.ep, title: nx.ep.title, subtitle: `S${nx.season} E${nx.ep.n} · ${item.title}`, still: nx.ep.still };
    }
    const movies = window.SF_TV_MOVIES || [];
    const other = movies.find((m) => m.id !== item.id);
    if (other) return { kind: "movie", movie: other, title: other.title, subtitle: `Movie · ${other.year}`, still: other.poster };
    return null;
  }, [item, episode]);

  // demo: surface the Up Next card a few seconds in (as content "nears the end")
  React.useEffect(() => {
    const n = resolveNext();
    const t = setTimeout(() => { if (n) { setUpNext(n); setRemaining(10); } }, 5000);
    return () => clearTimeout(t);
  }, [resolveNext]);

  // countdown while Up Next is showing
  React.useEffect(() => {
    if (!upNext) return;
    if (remaining <= 0) { advanceNext(); return; }
    const t = setTimeout(() => setRemaining((r) => Math.max(0, +(r - 0.1).toFixed(1))), 100);
    return () => clearTimeout(t);
  }, [upNext, remaining]);

  const advanceNext = () => {
    const n = upNext; setUpNext(null); setRemaining(10);
    if (n && onPlayNext) onPlayNext(n);
  };
  const cancelNext = () => { setUpNext(null); setRemaining(10); };

  const cycleVoice = () => {
    if (voice !== "idle") { setVoice("idle"); return; }
    setVoice("listening");
    setTimeout(() => setVoice("processing"), 1500);
    setTimeout(() => setVoice("answered"), 2900);
    setTimeout(() => setVoice("idle"), 5000);
  };

  const ctrl = (icon, label, opts = {}) => (
    <button data-focusable="" onClick={opts.onClick} onMouseEnter={(e) => e.currentTarget.focus()} className="tv-pctrl"
      aria-label={label} title={label}
      style={{ width: 56, height: 56, borderRadius: "50%", border: "1px solid var(--glass-border)",
        background: opts.active ? "var(--primary)" : "var(--glass-fill-strong)", color: opts.active ? "var(--on-primary)" : "#fff",
        cursor: "pointer", display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
      <Icon name={icon} size={24} />
    </button>
  );

  return (
    <div style={{ position: "absolute", inset: 0, background: "#000" }}>
      <img src={still} alt="" style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover", opacity: 0.92 }} />
      <div style={{ position: "absolute", inset: 0, background: "linear-gradient(180deg, rgba(0,0,0,0.55) 0%, rgba(0,0,0,0) 22%, rgba(0,0,0,0) 50%, rgba(0,0,0,0.85) 100%)" }} />

      {/* top: title */}
      <div style={{ position: "absolute", top: 0, left: 0, right: 0, padding: "40px 56px", display: "flex", alignItems: "flex-start", justifyContent: "space-between" }}>
        <div>
          <div style={{ fontSize: 40, fontWeight: 700, color: "#fff", textShadow: "0 2px 12px rgba(0,0,0,0.5)" }}>{title}</div>
          <div className="m3-title-medium" style={{ color: "rgba(255,255,255,0.8)", marginTop: 6 }}>{sub}</div>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <Pill tone="accent">{(item.tags && item.tags[0]) || "4K"}</Pill>
          <Pill tone="outline">HDR</Pill>
        </div>
      </div>

      {/* voice overlay — top center, parity with phone + XR */}
      {voice !== "idle" ? (
        <div style={{ position: "absolute", top: 120, left: 0, right: 0, display: "flex", justifyContent: "center", zIndex: 12 }}>
          <VoiceFeedback state={voice === "listening" ? "listening" : voice === "processing" ? "processing" : "answered"}
            text={voice === "listening" ? "“turn on subtitles”" : voice === "answered" ? "Subtitles on — English." : undefined} />
        </div>
      ) : null}

      {/* Up Next autoplay card — bottom right, above transport */}
      {upNext ? (
        <div style={{ position: "absolute", right: 56, bottom: 168, zIndex: 14 }}>
          <UpNextCard title={upNext.title} subtitle={upNext.subtitle} still={upNext.still}
            seconds={10} remaining={remaining} onPlayNow={advanceNext} onCancel={cancelNext} />
        </div>
      ) : null}

      {/* bottom transport */}
      <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, padding: "0 56px 48px", display: "flex", flexDirection: "column", gap: 22 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
          <span style={{ color: "#fff", fontFamily: "var(--font-mono)", fontSize: 16, minWidth: 56 }}>04:12</span>
          <div style={{ flex: 1 }}><ProgressBar value={episode ? (episode.progress || 38) : (item.progress || 38)} height={8} /></div>
          <span style={{ color: "rgba(255,255,255,0.7)", fontFamily: "var(--font-mono)", fontSize: 16, minWidth: 56, textAlign: "right" }}>{(episode && episode.runtime) || item.runtime || "11 min"}</span>
        </div>

        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
            {ctrl("rotate-ccw", "Back 10s")}
            <button data-focusable data-focus-first onClick={() => setPlaying(!playing)} onMouseEnter={(e) => e.currentTarget.focus()} className="tv-pctrl" aria-label="Play/Pause"
              style={{ width: 84, height: 84, borderRadius: "50%", border: "1px solid var(--glass-border)", background: "var(--primary)", color: "var(--on-primary)", cursor: "pointer", display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
              <Icon name={playing ? "pause" : "play"} size={36} />
            </button>
            {ctrl("rotate-cw", "Forward 10s")}
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
            {ctrl("mic", "Voice", { onClick: cycleVoice, active: voice === "listening" })}
            {ctrl("captions", "Subtitles")}
            {ctrl("audio-lines", "Audio")}
            {ctrl("list-video", "Episodes")}
            {ctrl("settings", "Settings")}
            {ctrl("chevron-left", "Exit", { onClick: onBack })}
          </div>
        </div>
      </div>
      <style>{`.tv-pctrl{transition:transform var(--duration-fast) var(--ease-standard),outline-color var(--duration-fast)}
        .tv-pctrl:focus{outline:3px solid var(--primary);outline-offset:4px;transform:scale(1.08)}`}</style>
    </div>
  );
}
window.TvPlayer = TvPlayer;
