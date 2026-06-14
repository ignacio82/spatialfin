// XR (Galaxy XR) — glass dialogs that float over the spatial panel.
// Provides a primitive `XrSheet` + option-row helpers, plus the same set of
// sheets used by the phone/TV form factors (Quality, Audio, Subtitles, Cast,
// SyncPlay, User picker, Overflow). Mounted by XrApp and rendered inside the
// `.xr-stage` element so they sit on top of `.xr-panel-wrap`.
(function () {
  const NS = window.SpatialFinDesignSystem_0d3fe7;

  // ---- Centered glass dialog ---------------------------------------------
  function XrSheet({ open, title, subtitle, onClose, children, footer, width = 520 }) {
    if (!open) return null;
    const { Icon } = NS;
    React.useEffect(() => {
      const onKey = (e) => { if (e.key === "Escape") onClose && onClose(); };
      window.addEventListener("keydown", onKey);
      return () => window.removeEventListener("keydown", onKey);
    }, [onClose]);
    return (
      <div onClick={onClose}
        style={{ position: "absolute", inset: 0, zIndex: 60, background: "rgba(2,4,8,0.42)",
          backdropFilter: "blur(6px)", WebkitBackdropFilter: "blur(6px)",
          display: "flex", alignItems: "center", justifyContent: "center",
          animation: "xrFade 180ms ease both" }}>
        <div onClick={(e) => e.stopPropagation()}
          style={{ width, maxWidth: "92vw", maxHeight: "82vh", display: "flex", flexDirection: "column",
            borderRadius: 28, border: "1px solid var(--glass-border)",
            background: "var(--glass-fill-strong)", backdropFilter: "blur(var(--glass-blur))", WebkitBackdropFilter: "blur(var(--glass-blur))",
            boxShadow: "0 24px 80px rgba(0,0,0,0.55)", color: "#fff", overflow: "hidden",
            animation: "xrIn 220ms cubic-bezier(.2,.85,.25,1) both" }}>
          <div style={{ display: "flex", alignItems: "flex-start", gap: 14, padding: "18px 22px 12px" }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="m3-title-large" style={{ fontWeight: 700 }}>{title}</div>
              {subtitle ? <div className="m3-body-small" style={{ color: "rgba(255,255,255,0.7)", marginTop: 2 }}>{subtitle}</div> : null}
            </div>
            <button type="button" onClick={onClose} aria-label="Close"
              style={{ width: 36, height: 36, borderRadius: "50%", border: "none", cursor: "pointer",
                background: "var(--glass-fill)", color: "#fff",
                display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
              <Icon name="x" size={18} />
            </button>
          </div>
          <div style={{ overflowY: "auto", padding: "2px 10px 10px", scrollbarWidth: "none" }}>{children}</div>
          {footer ? <div style={{ padding: "10px 18px 16px", borderTop: "1px solid var(--glass-border)" }}>{footer}</div> : null}
        </div>
        <style>{`@keyframes xrFade{from{opacity:0}to{opacity:1}}
          @keyframes xrIn{from{opacity:0;transform:scale(.96) translateY(8px)}to{opacity:1;transform:scale(1) translateY(0)}}
          .xr-row{transition:background var(--duration-fast) var(--ease-standard)}
          .xr-row:hover{background:rgba(255,255,255,0.06)}`}</style>
      </div>
    );
  }

  function XrOptionRow({ icon, leading, title, subtitle, trailing, selected, danger, dim, onClick }) {
    const { Icon } = NS;
    return (
      <button type="button" onClick={onClick} className="xr-row"
        style={{ display: "flex", alignItems: "center", gap: 14, width: "100%", textAlign: "left",
          padding: "11px 14px", border: "none", background: "transparent", borderRadius: 16,
          cursor: "pointer", color: danger ? "var(--error, #f5667a)" : "#fff", opacity: dim ? 0.55 : 1 }}>
        {leading ? leading : (icon ? (
          <span style={{ width: 40, height: 40, borderRadius: "50%", flexShrink: 0,
            background: danger ? "color-mix(in srgb, var(--error, #f5667a) 16%, transparent)" : "rgba(255,255,255,0.08)",
            color: danger ? "var(--error, #f5667a)" : "rgba(255,255,255,0.85)",
            display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
            <Icon name={icon} size={20} />
          </span>
        ) : null)}
        <span style={{ flex: 1, minWidth: 0 }}>
          <span style={{ display: "block", fontSize: 15, fontWeight: 600 }}>{title}</span>
          {subtitle ? <span style={{ display: "block", fontSize: 12.5, color: "rgba(255,255,255,0.65)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{subtitle}</span> : null}
        </span>
        {trailing !== undefined ? trailing : (selected ? <Icon name="check" size={20} color="var(--accent)" /> : null)}
      </button>
    );
  }

  function XrSection({ children, icon }) {
    const { Icon } = NS;
    return (
      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "14px 16px 4px", color: "rgba(255,255,255,0.7)", fontSize: 11, fontWeight: 700, letterSpacing: 0.7, textTransform: "uppercase" }}>
        {icon ? <Icon name={icon} size={12} /> : null}{children}
      </div>
    );
  }

  // ---- Quality / Audio / Subtitle ----------------------------------------
  const QUALITIES = [
    { id: "auto",  title: "Auto",     sub: "Adapts to network · up to 4K" },
    { id: "orig",  title: "Original", sub: "Direct Play · MKV 14 Mbps" },
    { id: "1080",  title: "1080p",    sub: "8 Mbps" },
    { id: "720",   title: "720p",     sub: "4 Mbps" },
  ];
  function XrQualitySheet({ open, value, onPick, onClose }) {
    return (
      <XrSheet open={open} title="Quality" subtitle="Streaming resolution & bitrate" onClose={onClose}>
        {QUALITIES.map((q) => (
          <XrOptionRow key={q.id} icon={q.id === "auto" ? "gauge" : q.id === "orig" ? "badge-check" : "monitor"}
            title={q.title} subtitle={q.sub} selected={value === q.id}
            onClick={() => { onPick(q.id); onClose(); }} />
        ))}
      </XrSheet>
    );
  }
  const AUDIO_TRACKS = [
    { id: "en51",   title: "English",  sub: "5.1 Surround · AC-3" },
    { id: "enatmos", title: "English", sub: "Dolby Atmos · TrueHD" },
    { id: "en20",   title: "English",  sub: "Stereo · AAC" },
    { id: "comm",   title: "Commentary", sub: "Director" },
    { id: "es",     title: "Spanish",  sub: "Stereo · AAC" },
  ];
  function XrAudioSheet({ open, value, onPick, onClose }) {
    return (
      <XrSheet open={open} title="Audio" subtitle="Audio track" onClose={onClose}>
        {AUDIO_TRACKS.map((a) => (
          <XrOptionRow key={a.id} icon="audio-lines" title={a.title} subtitle={a.sub}
            selected={value === a.id} onClick={() => { onPick(a.id); onClose(); }} />
        ))}
      </XrSheet>
    );
  }
  const SUBS = [
    { id: "off",      title: "Off",             sub: null },
    { id: "ensdh",    title: "English (SDH)",   sub: "Full" },
    { id: "enforced", title: "English (Forced)", sub: "Signs & songs" },
    { id: "es",       title: "Spanish",         sub: "Full" },
  ];
  function XrSubtitleSheet({ open, value, onPick, onClose }) {
    return (
      <XrSheet open={open} title="Subtitles" subtitle="Subtitle track" onClose={onClose}>
        {SUBS.map((s) => (
          <XrOptionRow key={s.id} icon={s.id === "off" ? "captions-off" : "captions"} title={s.title} subtitle={s.sub}
            selected={value === s.id} onClick={() => { onPick(s.id); onClose(); }} />
        ))}
      </XrSheet>
    );
  }

  // ---- Cast / Play on (with split A/V + sync) ----------------------------
  const DTYPE_ICON = { phone: "smartphone", tv: "tv", speaker: "speaker", headphones: "headphones", glasses: "glasses", xr: "monitor" };
  function XrCastSheet({ open, videoId, audioId, syncIds, onPickVideo, onPickAudio, onToggleSync, onClose }) {
    const devices = window.SF_CAST_DEVICES || [];
    const { Icon } = NS;
    const videoDevices = devices.filter((d) => d.video);
    const audioDevices = devices.filter((d) => d.audio);
    const videoDev = devices.find((d) => d.id === videoId) || devices[0];
    const split = !!audioId && audioId !== videoId;
    const effAudioId = audioId || videoId;
    const groupable = audioDevices.filter((d) => d.id !== effAudioId && d.type !== "phone");
    return (
      <XrSheet open={open} title="Cast & audio" subtitle="Send video and audio to your devices" onClose={onClose} width={560}>
        <XrSection icon="tv">Play video on</XrSection>
        {videoDevices.map((d) => (
          <XrOptionRow key={d.id} icon={DTYPE_ICON[d.type] || "monitor"} title={d.name} subtitle={d.hint}
            selected={d.id === videoId} onClick={() => onPickVideo(d.id)} />
        ))}
        <XrSection icon="split">Audio output</XrSection>
        <div style={{ padding: "0 18px 4px", color: "rgba(255,255,255,0.7)", fontSize: 12.5 }}>
          {split ? <>Audio is split — playing on <b>{(devices.find((d) => d.id === effAudioId) || {}).name}</b>.</> : <>Audio follows the video device.</>}
        </div>
        <XrOptionRow icon="link" title="Same as video" subtitle={videoDev ? videoDev.name : ""}
          selected={!split} onClick={() => onPickAudio(null)} />
        {audioDevices.filter((d) => d.id !== videoId).map((d) => (
          <XrOptionRow key={"a-" + d.id} icon={DTYPE_ICON[d.type] || "speaker"} title={d.name} subtitle={d.hint}
            selected={split && audioId === d.id} onClick={() => onPickAudio(d.id)} />
        ))}
        {groupable.length ? (
          <>
            <XrSection icon="radio">Also play in sync</XrSection>
            {groupable.map((d) => {
              const on = (syncIds || []).includes(d.id);
              return (
                <XrOptionRow key={"s-" + d.id} icon={DTYPE_ICON[d.type] || "speaker"} title={d.name}
                  subtitle={on ? "In sync" : d.hint}
                  trailing={<span style={{ width: 22, height: 22, borderRadius: 7,
                    border: "2px solid " + (on ? "var(--accent)" : "rgba(255,255,255,0.55)"),
                    background: on ? "var(--accent)" : "transparent", display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
                    {on ? <Icon name="check" size={14} color="#fff" /> : null}</span>}
                  onClick={() => onToggleSync(d.id)} />
              );
            })}
          </>
        ) : null}
      </XrSheet>
    );
  }

  // ---- SyncPlay ----------------------------------------------------------
  function XrSyncPlaySheet({ open, group, onJoin, onLeave, onCreate, onClose }) {
    const groups = window.SF_SYNCPLAY_GROUPS || [];
    const people = window.SF_SYNCPLAY_PEOPLE || [];
    const { Icon, Button } = NS;
    return (
      <XrSheet open={open} title="SyncPlay" subtitle="Watch in perfect sync with others" onClose={onClose}
        footer={group
          ? <Button variant="glass" fullWidth icon="log-out" onClick={onLeave}>Leave group</Button>
          : <Button variant="filled" fullWidth icon="plus" onClick={onCreate}>New group</Button>}>
        {group ? (
          <>
            <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "10px 14px",
              margin: "4px 8px 8px", borderRadius: 16, background: "rgba(125,218,255,0.15)" }}>
              <span style={{ width: 38, height: 38, borderRadius: "50%", background: "var(--accent)", color: "#fff",
                display: "inline-flex", alignItems: "center", justifyContent: "center" }}><Icon name="users" size={20} /></span>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 15, fontWeight: 700 }}>{group.name}</div>
                <div style={{ fontSize: 12, color: "rgba(255,255,255,0.8)", display: "flex", alignItems: "center", gap: 6 }}>
                  <span style={{ width: 7, height: 7, borderRadius: 99, background: "var(--success, #58c06b)" }} /> In sync · {group.ping || 36} ms
                </div>
              </div>
            </div>
            <XrSection>In this room</XrSection>
            {people.map((p) => (
              <XrOptionRow key={p.id} leading={
                <span style={{ width: 36, height: 36, borderRadius: "50%", background: p.color, color: "#fff",
                  fontSize: 14, fontWeight: 700, display: "inline-flex", alignItems: "center", justifyContent: "center" }}>{p.name[0]}</span>
              } title={p.name + (p.you ? " (you)" : "")} subtitle={p.you ? "Host · this headset" : "Ready"}
                trailing={<Icon name={p.you ? "glasses" : "check"} size={18} color="rgba(255,255,255,0.7)" />} />
            ))}
          </>
        ) : (
          <>
            <XrSection>Join a group</XrSection>
            {groups.map((g) => (
              <XrOptionRow key={g.id} icon="users" title={g.name}
                subtitle={g.members + " watching · " + g.ping + " ms"}
                trailing={<Icon name="chevron-right" size={18} color="rgba(255,255,255,0.6)" />}
                onClick={() => onJoin(g)} />
            ))}
          </>
        )}
      </XrSheet>
    );
  }

  // ---- User picker -------------------------------------------------------
  function XrUserPickerSheet({ open, currentId, onPick, onClose }) {
    const users = window.SF_USERS || [];
    const { Button } = NS;
    return (
      <XrSheet open={open} title="Switch user" subtitle="Choose a Jellyfin profile on this server" onClose={onClose}
        footer={<Button variant="glass" fullWidth icon="user-plus">Add another user</Button>}>
        {users.map((u) => (
          <XrOptionRow key={u.id}
            leading={u.avatar
              ? <img src={u.avatar} alt="" style={{ width: 40, height: 40, borderRadius: "50%", objectFit: "cover", flexShrink: 0 }} />
              : <span style={{ width: 40, height: 40, borderRadius: "50%", flexShrink: 0,
                  background: u.color || "rgba(255,255,255,0.12)", color: u.textColor || "#fff",
                  fontSize: 15, fontWeight: 700, display: "inline-flex", alignItems: "center", justifyContent: "center" }}>{u.initials || u.name[0]}</span>}
            title={u.name} subtitle={u.role}
            selected={u.id === currentId} onClick={() => { onPick(u.id); onClose(); }} />
        ))}
      </XrSheet>
    );
  }

  // ---- Overflow ("More actions") -----------------------------------------
  function XrOverflowSheet({ open, item, kind, castActive, castLabel, onClose,
    onSyncPlay, onPlaybackOptions, onGoToSeries, onGoToSeason, onEditExternalIds,
    onCast, onRefreshMetadata, onShare, onDelete }) {
    if (!open || !item) return null;
    const isEpisode = kind === "episode";
    const { Icon, Pill } = NS;
    const fire = (fn) => () => { onClose(); fn && fn(); };
    const rows = [
      { id: "syncplay", icon: "users", title: "SyncPlay", sub: "Watch in sync with others", onClick: fire(onSyncPlay) },
      { id: "playback", icon: "sliders", title: "Playback options", sub: "Default subtitle & audio", onClick: fire(onPlaybackOptions) },
      isEpisode ? { id: "series", icon: "tv", title: "Go to series", sub: item.seriesTitle, onClick: fire(onGoToSeries) } : null,
      isEpisode ? { id: "season", icon: "list", title: "Go to season", sub: "Season " + (item.season || 1), onClick: fire(onGoToSeason) } : null,
      { id: "extids", icon: "id-card", title: "Edit external IDs", sub: "TMDB · IMDB · TVDB", admin: true, onClick: fire(onEditExternalIds) },
      { id: "cast", icon: "cast", title: "Cast & audio output", sub: castActive ? "On " + castLabel : "This headset", onClick: fire(onCast) },
      { id: "refresh", icon: "refresh-cw", title: "Refresh metadata", sub: "Re-scan from providers", admin: true, onClick: fire(onRefreshMetadata) },
      { id: "share", icon: "share-2", title: "Share", sub: "Send a deeplink", onClick: fire(onShare) },
      { id: "delete", icon: "trash-2", title: "Delete", sub: "Remove from library", admin: true, danger: true, onClick: fire(onDelete) },
    ].filter(Boolean);
    return (
      <XrSheet open={open} title="More actions" subtitle={item.title} onClose={onClose}>
        {rows.map((r) => (
          <XrOptionRow key={r.id} icon={r.icon} title={r.title} subtitle={r.sub} danger={r.danger}
            trailing={r.admin ? <Pill tone="outline">admin</Pill> : <Icon name="chevron-right" size={18} color="rgba(255,255,255,0.6)" />}
            onClick={r.onClick} />
        ))}
      </XrSheet>
    );
  }

  Object.assign(window, { XrSheet, XrOptionRow, XrQualitySheet, XrAudioSheet, XrSubtitleSheet,
    XrCastSheet, XrSyncPlaySheet, XrUserPickerSheet, XrOverflowSheet });
})();
