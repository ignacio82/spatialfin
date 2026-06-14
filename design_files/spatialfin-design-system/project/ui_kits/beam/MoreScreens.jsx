// Beam (phone) — Sources, Search, Downloads, Settings screens.
(function () {
  const NS = window.SpatialFinDesignSystem_0d3fe7;

  function TopBar({ title, right }) {
    return (
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "16px 16px 8px" }}>
        <span className="m3-headline-small" style={{ color: "var(--text-primary)", fontWeight: 700, flex: 1 }}>{title}</span>
        {right}
      </div>
    );
  }

  // ---- Sources (universal plugins) ---------------------------------------
  function BeamSources({ onOpen }) {
    const { SectionHeader, PosterCard, Icon, Pill } = NS;
    const sources = window.SF_BEAM_SOURCES || [];
    return (
      <div style={{ paddingBottom: 24 }}>
        <TopBar title="Sources" right={<Pill tone="accent" icon="puzzle">{sources.length} plugins</Pill>} />
        <div style={{ padding: "0 16px 6px", color: "var(--text-secondary)" }} className="m3-body-medium">
          Browse content from your connected Jellyfin plugins and on-device files.
        </div>
        {sources.map((src) => (
          <div key={src.id} style={{ padding: "16px 0 2px 16px" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 12, paddingRight: 16, marginBottom: 4 }}>
              <span style={{ width: 36, height: 36, borderRadius: 10, background: src.tint, color: "#fff",
                display: "inline-flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
                <Icon name={src.icon} size={20} />
              </span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="m3-title-medium" style={{ color: "var(--text-primary)", fontWeight: 700 }}>{src.name}</div>
                <div className="m3-body-small" style={{ color: "var(--text-secondary)" }}>via {src.pluginId} · {src.items.length} items</div>
              </div>
              <button type="button"
                style={{ display: "inline-flex", alignItems: "center", gap: 4, background: "transparent", border: "none",
                  color: "var(--accent)", fontSize: 13, fontWeight: 600, cursor: "pointer", fontFamily: "var(--font-sans)" }}>
                See all <Icon name="chevron-right" size={16} />
              </button>
            </div>
            <div style={{ display: "flex", gap: 12, overflowX: "auto", padding: "10px 16px 6px 0", scrollbarWidth: "none" }}>
              {src.items.map((it) => (
                <div key={it.id} style={{ flex: "0 0 auto" }}>
                  <PosterCard title={it.title} subtitle={it.subtitle} poster={it.image} width={128}
                    onClick={() => onOpen && onOpen(it)} />
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    );
  }

  // ---- Search ------------------------------------------------------------
  function BeamSearch({ onOpen }) {
    const { Icon, PosterCard, Pill } = NS;
    const cat = window.SF_CATALOG || [];
    const [q, setQ] = React.useState("");
    const [genre, setGenre] = React.useState("All");
    const genres = ["All", "Animation", "Comedy", "Science Fiction", "Drama", "Action"];
    const results = cat.filter((m) =>
      (genre === "All" || (m.genres || "").includes(genre)) &&
      (q.trim() === "" || m.title.toLowerCase().includes(q.toLowerCase())));
    return (
      <div style={{ paddingBottom: 24 }}>
        <TopBar title="Search" />
        <div style={{ padding: "4px 16px 0" }}>
          <div style={{ display: "flex", alignItems: "center", gap: 10, height: 48, padding: "0 14px",
            borderRadius: "var(--radius-full)", background: "var(--surface-container-high)" }}>
            <Icon name="search" size={20} style={{ color: "var(--text-secondary)" }} />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search movies, shows, people"
              style={{ flex: 1, border: "none", outline: "none", background: "transparent", color: "var(--text-primary)",
                fontSize: 15, fontFamily: "var(--font-sans)" }} />
            {q ? <button onClick={() => setQ("")} aria-label="Clear" style={{ border: "none", background: "transparent", cursor: "pointer", color: "var(--text-secondary)", display: "inline-flex" }}><Icon name="x" size={18} /></button> : null}
          </div>
        </div>
        <div style={{ display: "flex", gap: 8, overflowX: "auto", padding: "14px 16px 4px", scrollbarWidth: "none" }}>
          {genres.map((g) => (
            <button key={g} onClick={() => setGenre(g)} style={{ flex: "0 0 auto", height: 34, padding: "0 16px",
              borderRadius: "var(--radius-full)", cursor: "pointer", fontSize: 13, fontWeight: 600, fontFamily: "var(--font-sans)",
              border: "1px solid " + (genre === g ? "transparent" : "var(--border-subtle)"),
              background: genre === g ? "var(--accent)" : "transparent",
              color: genre === g ? "var(--on-primary)" : "var(--text-secondary)" }}>{g}</button>
          ))}
        </div>
        <div style={{ padding: "8px 16px 0", color: "var(--text-secondary)" }} className="m3-label-large">
          {results.length} result{results.length === 1 ? "" : "s"}
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: 14, padding: "12px 16px 0" }}>
          {results.map((m) => (
            <PosterCard key={m.id} title={m.title} subtitle={m.year} poster={m.poster} width={"100%"}
              progress={m.progress || null} onClick={() => onOpen && onOpen(m)} />
          ))}
        </div>
        {results.length === 0 ? (
          <div style={{ textAlign: "center", color: "var(--text-secondary)", padding: 48 }}>No matches for “{q}”.</div>
        ) : null}
      </div>
    );
  }

  // ---- Downloads ---------------------------------------------------------
  function BeamDownloads({ onOpen }) {
    const { Icon, ProgressBar, Pill, IconButton } = NS;
    const cat = window.SF_CATALOG || [];
    const done = cat.filter((m) => m.downloaded);
    const queued = cat.filter((m) => !m.downloaded).slice(0, 2);
    const Item = ({ m, pct }) => (
      <button type="button" onClick={() => onOpen && onOpen(m)}
        style={{ display: "flex", alignItems: "center", gap: 14, width: "100%", textAlign: "left",
          padding: "10px 16px", border: "none", background: "transparent", cursor: "pointer" }}>
        <img src={m.poster} alt="" style={{ width: 58, height: 82, borderRadius: 10, objectFit: "cover", flexShrink: 0 }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="m3-title-small" style={{ color: "var(--text-primary)", fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{m.title}</div>
          <div className="m3-body-small" style={{ color: "var(--text-secondary)" }}>{m.runtime} · {m.tags.join(" · ")}</div>
          {pct != null ? (
            <div style={{ marginTop: 8 }}><ProgressBar value={pct} height={5} /></div>
          ) : (
            <div style={{ marginTop: 6 }}><Pill tone="accent" icon="circle-check">Ready · 1.2 GB</Pill></div>
          )}
        </div>
        <IconButton icon={pct != null ? "x" : "trash-2"} variant="ghost" size="sm" label={pct != null ? "Cancel" : "Delete"} />
      </button>
    );
    return (
      <div style={{ paddingBottom: 24 }}>
        <TopBar title="Downloads" right={<Pill tone="neutral" icon="hard-drive">3.6 GB used</Pill>} />
        <div style={{ margin: "6px 16px 4px", padding: "12px 14px", borderRadius: 16, background: "var(--surface-container-high)" }}>
          <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 8 }}>
            <span className="m3-label-large" style={{ color: "var(--text-primary)" }}>Storage</span>
            <span className="m3-label-large" style={{ color: "var(--text-secondary)", fontFamily: "var(--font-mono)" }}>3.6 / 64 GB</span>
          </div>
          <ProgressBar value={6} height={6} />
        </div>
        {queued.length ? <div style={{ padding: "12px 16px 2px", color: "var(--text-secondary)" }} className="m3-label-large">DOWNLOADING</div> : null}
        {queued.map((m, i) => <Item key={m.id} m={m} pct={[62, 24][i]} />)}
        <div style={{ padding: "12px 16px 2px", color: "var(--text-secondary)" }} className="m3-label-large">ON THIS DEVICE</div>
        {done.map((m) => <Item key={m.id} m={m} />)}
      </div>
    );
  }

  // ---- Settings ----------------------------------------------------------
  function BeamSettings({ serverName, user, onOpenServer, onOpenCast, onOpenUser, castLabel, quality }) {
    const { Icon } = NS;
    const [wifiOnly, setWifiOnly] = React.useState(true);
    const [autoplay, setAutoplay] = React.useState(true);
    const Row = ({ icon, title, value, trailing, onClick }) => (
      <button type="button" onClick={onClick} disabled={!onClick && !trailing}
        style={{ display: "flex", alignItems: "center", gap: 16, width: "100%", textAlign: "left",
          padding: "14px 16px", border: "none", background: "transparent",
          cursor: onClick ? "pointer" : "default", color: "var(--text-primary)" }}>
        <span style={{ width: 40, height: 40, borderRadius: "50%", background: "var(--surface-container-high)",
          color: "var(--text-secondary)", display: "inline-flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
          <Icon name={icon} size={20} />
        </span>
        <span style={{ flex: 1 }}>
          <span style={{ display: "block", fontSize: 16, fontWeight: 600 }}>{title}</span>
          {value ? <span style={{ display: "block", fontSize: 13, color: "var(--text-secondary)" }}>{value}</span> : null}
        </span>
        {trailing !== undefined ? trailing : (onClick ? <Icon name="chevron-right" size={20} color="var(--text-secondary)" /> : null)}
      </button>
    );
    const Toggle = ({ on, onClick }) => (
      <span onClick={onClick} style={{ width: 46, height: 28, borderRadius: 99, cursor: "pointer", flexShrink: 0,
        background: on ? "var(--accent)" : "var(--surface-container-highest)", position: "relative",
        transition: "background var(--duration-fast)" }}>
        <span style={{ position: "absolute", top: 3, left: on ? 21 : 3, width: 22, height: 22, borderRadius: "50%",
          background: "#fff", transition: "left var(--duration-fast) var(--ease-standard)" }} />
      </span>
    );
    const Group = ({ label, children }) => (
      <div style={{ margin: "8px 12px", borderRadius: 18, background: "var(--surface-container)", overflow: "hidden" }}>
        {label ? <div style={{ padding: "12px 16px 4px", color: "var(--text-secondary)", fontSize: 12, fontWeight: 700, letterSpacing: 0.6 }}>{label}</div> : null}
        {children}
      </div>
    );
    const qualLabel = { auto: "Auto", orig: "Original", "1080": "1080p", "720": "720p", "480": "480p" }[quality] || "Auto";
    return (
      <div style={{ paddingBottom: 24 }}>
        <TopBar title="Settings" />
        <button type="button" onClick={onOpenUser}
          style={{ display: "flex", alignItems: "center", gap: 14, width: "100%", padding: "6px 18px 10px",
            background: "transparent", border: "none", cursor: "pointer", textAlign: "left" }}>
          {window.BeamUserAvatar ? <window.BeamUserAvatar user={user} size={52} /> : null}
          <div style={{ flex: 1 }}>
            <div className="m3-title-medium" style={{ color: "var(--text-primary)", fontWeight: 700 }}>{user ? user.name : "Sign in"}</div>
            <div className="m3-body-small" style={{ color: "var(--text-secondary)" }}>{user ? "Signed in · " + (user.role || "user").toLowerCase() : ""}</div>
          </div>
          <Icon name="chevron-right" size={20} color="var(--text-secondary)" />
        </button>
        <Group label="SERVER & PLAYBACK">
          <Row icon="server" title="Jellyfin server" value={serverName} onClick={onOpenServer} />
          <Row icon="cast" title="Cast & audio output" value={castLabel} onClick={onOpenCast} />
          <Row icon="gauge" title="Default streaming quality" value={qualLabel} onClick={() => {}} />
        </Group>
        <Group label="DOWNLOADS">
          <Row icon="wifi" title="Download over Wi-Fi only" trailing={<Toggle on={wifiOnly} onClick={() => setWifiOnly(!wifiOnly)} />} />
          <Row icon="play" title="Autoplay next episode" trailing={<Toggle on={autoplay} onClick={() => setAutoplay(!autoplay)} />} />
        </Group>
        <Group label="ABOUT">
          <Row icon="info" title="SpatialFin Beam" value="v3.2.0 · Jellyfin 10.9" />
        </Group>
      </div>
    );
  }

  Object.assign(window, { BeamSources, BeamSearch, BeamDownloads, BeamSettings });
})();
