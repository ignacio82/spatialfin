// Beam (phone) — bottom sheets for player & app chrome:
// Quality, Audio, Subtitles, Cast (+ split audio/video), SyncPlay.
(function () {
  const NS = window.SpatialFinDesignSystem_0d3fe7;

  // ---- generic phone bottom sheet ----------------------------------------
  function BeamSheet({ open, title, subtitle, onClose, children, footer }) {
    if (!open) return null;
    const { Icon } = NS;
    return (
      <div onClick={onClose}
        style={{ position: "absolute", inset: 0, zIndex: 70, background: "rgba(0,0,0,0.55)",
          display: "flex", alignItems: "flex-end", animation: "sfFade 160ms ease both" }}>
        <div onClick={(e) => e.stopPropagation()}
          style={{ width: "100%", maxHeight: "84%", background: "var(--surface-container)",
            borderTopLeftRadius: 26, borderTopRightRadius: 26, borderTop: "1px solid var(--border-subtle)",
            boxShadow: "0 -18px 50px rgba(0,0,0,0.5)", display: "flex", flexDirection: "column",
            animation: "sfSlide 240ms cubic-bezier(.2,.85,.25,1) both" }}>
          <div style={{ display: "flex", justifyContent: "center", paddingTop: 10 }}>
            <span style={{ width: 38, height: 4, borderRadius: 99, background: "var(--border-strong)" }} />
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "10px 16px 8px" }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="m3-title-large" style={{ fontWeight: 700, color: "var(--text-primary)" }}>{title}</div>
              {subtitle ? <div className="m3-body-small" style={{ color: "var(--text-secondary)", marginTop: 2 }}>{subtitle}</div> : null}
            </div>
            <button type="button" onClick={onClose} aria-label="Close"
              style={{ width: 38, height: 38, borderRadius: "50%", border: "none", cursor: "pointer",
                background: "var(--surface-container-high)", color: "var(--text-primary)",
                display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
              <Icon name="x" size={20} />
            </button>
          </div>
          <div style={{ overflowY: "auto", padding: "2px 8px 8px", scrollbarWidth: "none" }}>{children}</div>
          {footer ? <div style={{ padding: "8px 16px calc(16px + env(safe-area-inset-bottom))", borderTop: "1px solid var(--border-subtle)" }}>{footer}</div> : null}
          <div style={{ height: "env(safe-area-inset-bottom)" }} />
        </div>
        <style>{`@keyframes sfSlide{from{transform:translateY(100%)}to{transform:translateY(0)}}
          @keyframes sfFade{from{opacity:0}to{opacity:1}}
          .sf-row{transition:background var(--duration-fast) var(--ease-standard)}
          .sf-row:active{background:var(--surface-container-highest)}`}</style>
      </div>
    );
  }

  // ---- a selectable list row ---------------------------------------------
  function OptionRow({ icon, iconBg, leading, title, subtitle, trailing, selected, dim, onClick }) {
    const { Icon } = NS;
    return (
      <button type="button" onClick={onClick} className="sf-row"
        style={{ display: "flex", alignItems: "center", gap: 14, width: "100%", textAlign: "left",
          padding: "12px 12px", border: "none", background: "transparent", borderRadius: 16,
          cursor: "pointer", color: "var(--text-primary)", opacity: dim ? 0.55 : 1 }}>
        {leading ? leading : (icon ? (
          <span style={{ width: 42, height: 42, borderRadius: "50%", flexShrink: 0,
            background: iconBg || "var(--surface-container-highest)",
            color: iconBg ? "#fff" : "var(--text-secondary)",
            display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
            <Icon name={icon} size={21} />
          </span>
        ) : null)}
        <span style={{ flex: 1, minWidth: 0 }}>
          <span style={{ display: "block", fontSize: 16, fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{title}</span>
          {subtitle ? <span style={{ display: "block", fontSize: 13, color: "var(--text-secondary)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{subtitle}</span> : null}
        </span>
        {trailing !== undefined ? trailing : (selected ? <NS.Icon name="check" size={22} color="var(--primary)" /> : null)}
      </button>
    );
  }

  function SectionLabel({ children, style }) {
    return (
      <div style={{ padding: "14px 16px 6px", color: "var(--text-secondary)", fontSize: 12,
        fontWeight: 700, letterSpacing: 0.7, textTransform: "uppercase", ...style }}>{children}</div>
    );
  }

  function Checkbox({ on }) {
    const { Icon } = NS;
    return (
      <span style={{ width: 24, height: 24, borderRadius: 7, flexShrink: 0,
        border: "2px solid " + (on ? "var(--primary)" : "var(--border-strong)"),
        background: on ? "var(--primary)" : "transparent", color: "var(--on-primary)",
        display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
        {on ? <Icon name="check" size={15} /> : null}
      </span>
    );
  }

  // ---- Quality -----------------------------------------------------------
  const QUALITIES = [
    { id: "auto",  title: "Auto",        sub: "Adapts to network · up to 4K" },
    { id: "orig",  title: "Original",    sub: "Direct Play · MKV 14 Mbps" },
    { id: "1080",  title: "1080p",       sub: "8 Mbps" },
    { id: "720",   title: "720p",        sub: "4 Mbps" },
    { id: "480",   title: "480p",        sub: "1.5 Mbps · data saver" },
  ];
  function QualitySheet({ open, value, onPick, onClose }) {
    return (
      <BeamSheet open={open} title="Quality" subtitle="Streaming resolution & bitrate" onClose={onClose}>
        {QUALITIES.map((q) => (
          <OptionRow key={q.id} icon={q.id === "auto" ? "gauge" : q.id === "orig" ? "badge-check" : "monitor"}
            title={q.title} subtitle={q.sub} selected={value === q.id}
            onClick={() => { onPick(q.id); onClose(); }} />
        ))}
      </BeamSheet>
    );
  }

  // ---- Audio tracks ------------------------------------------------------
  const AUDIO_TRACKS = [
    { id: "en51",  title: "English",            sub: "5.1 Surround · AC-3" },
    { id: "enatmos", title: "English",          sub: "Dolby Atmos · TrueHD" },
    { id: "en20",  title: "English",            sub: "Stereo · AAC" },
    { id: "comm",  title: "Director commentary", sub: "Stereo" },
    { id: "es",    title: "Spanish",            sub: "Stereo · AAC" },
  ];
  function AudioSheet({ open, value, onPick, onClose }) {
    return (
      <BeamSheet open={open} title="Audio" subtitle="Audio track" onClose={onClose}>
        {AUDIO_TRACKS.map((a) => (
          <OptionRow key={a.id} icon="audio-lines" title={a.title} subtitle={a.sub}
            selected={value === a.id} onClick={() => { onPick(a.id); onClose(); }} />
        ))}
      </BeamSheet>
    );
  }

  // ---- Subtitles ---------------------------------------------------------
  const SUBS = [
    { id: "off", title: "Off", sub: null },
    { id: "ensdh", title: "English (SDH)", sub: "Full · burned-in off" },
    { id: "enforced", title: "English (Forced)", sub: "Signs & songs" },
    { id: "es", title: "Spanish", sub: "Full" },
    { id: "fr", title: "French", sub: "Full" },
  ];
  function SubtitleSheet({ open, value, onPick, onClose }) {
    return (
      <BeamSheet open={open} title="Subtitles" subtitle="Subtitle track" onClose={onClose}>
        {SUBS.map((s) => (
          <OptionRow key={s.id} icon={s.id === "off" ? "captions-off" : "captions"} title={s.title} subtitle={s.sub}
            selected={value === s.id} onClick={() => { onPick(s.id); onClose(); }} />
        ))}
      </BeamSheet>
    );
  }

  const DTYPE_ICON = { phone: "smartphone", tv: "tv", speaker: "speaker", headphones: "headphones" };

  // ---- Cast / Play on (+ split audio/video + multi-room sync) ------------
  function CastSheet({ open, videoId, audioId, syncIds, onClose, onPickVideo, onPickAudio, onToggleSync }) {
    const devices = window.SF_CAST_DEVICES || [];
    const videoDevices = devices.filter((d) => d.video);
    const audioDevices = devices.filter((d) => d.audio);
    const videoDev = devices.find((d) => d.id === videoId) || devices[0];
    // split is active when audio is explicitly routed somewhere other than the video sink
    const split = !!audioId && audioId !== videoId;
    const effAudioId = audioId || videoId;
    // speakers available to add to a synced multi-room group (exclude the primary audio sink)
    const groupable = audioDevices.filter((d) => d.id !== effAudioId && d.type !== "phone");

    return (
      <BeamSheet open={open} title="Cast & audio" subtitle="Send video and audio to your devices" onClose={onClose}>
        <SectionLabel>Play video on</SectionLabel>
        {videoDevices.map((d) => (
          <OptionRow key={d.id} icon={DTYPE_ICON[d.type]} title={d.name} subtitle={d.hint}
            selected={d.id === videoId} onClick={() => onPickVideo(d.id)} />
        ))}

        <SectionLabel style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <NS.Icon name="split" size={13} /> Audio output
        </SectionLabel>
        <div style={{ padding: "0 16px 6px", color: "var(--text-secondary)", fontSize: 12.5, lineHeight: 1.4 }}>
          {split
            ? <>Audio is split from video — playing on <b style={{ color: "var(--text-primary)" }}>{(devices.find((d) => d.id === effAudioId) || {}).name}</b>.</>
            : <>Audio follows the video device. Pick another to split it.</>}
        </div>
        <OptionRow icon="link" title="Same as video"
          subtitle={videoDev ? videoDev.name : ""} selected={!split}
          onClick={() => onPickAudio(null)} />
        {audioDevices.filter((d) => d.id !== videoId).map((d) => (
          <OptionRow key={"a-" + d.id} icon={DTYPE_ICON[d.type]} title={d.name} subtitle={d.hint}
            selected={split && audioId === d.id} onClick={() => onPickAudio(d.id)} />
        ))}

        {groupable.length ? (
          <>
            <SectionLabel style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <NS.Icon name="radio" size={13} /> Also play in sync
            </SectionLabel>
            <div style={{ padding: "0 16px 6px", color: "var(--text-secondary)", fontSize: 12.5 }}>
              Group extra speakers for multi-room playback.
            </div>
            {groupable.map((d) => {
              const on = (syncIds || []).includes(d.id);
              return (
                <OptionRow key={"s-" + d.id} icon={DTYPE_ICON[d.type]} title={d.name}
                  subtitle={on ? "In sync" : d.hint} trailing={<Checkbox on={on} />}
                  onClick={() => onToggleSync(d.id)} />
              );
            })}
          </>
        ) : null}
      </BeamSheet>
    );
  }

  // ---- SyncPlay (watch-together) -----------------------------------------
  function Avatar({ name, color, size = 34 }) {
    return (
      <span style={{ width: size, height: size, borderRadius: "50%", background: color || "#3C4758",
        color: "#fff", fontSize: size * 0.4, fontWeight: 700, flexShrink: 0,
        display: "inline-flex", alignItems: "center", justifyContent: "center" }}>{name[0]}</span>
    );
  }
  function SyncPlaySheet({ open, group, onClose, onJoin, onLeave, onCreate }) {
    const groups = window.SF_SYNCPLAY_GROUPS || [];
    const people = window.SF_SYNCPLAY_PEOPLE || [];
    const { Icon, Button } = NS;
    return (
      <BeamSheet open={open} title="SyncPlay" subtitle="Watch in perfect sync with others"
        onClose={onClose}
        footer={group
          ? <Button variant="tonal" fullWidth icon="log-out" onClick={onLeave}>Leave group</Button>
          : <Button variant="filled" fullWidth icon="plus" onClick={onCreate}>New group</Button>}>
        {group ? (
          <div>
            <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "10px 14px",
              margin: "4px 8px 8px", borderRadius: 16, background: "var(--accent-container)" }}>
              <span style={{ width: 40, height: 40, borderRadius: "50%", background: "var(--accent)",
                color: "var(--on-primary)", display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
                <Icon name="users" size={20} />
              </span>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 16, fontWeight: 700, color: "var(--on-accent-container)" }}>{group.name}</div>
                <div style={{ fontSize: 12.5, color: "var(--on-accent-container)", opacity: 0.85, display: "flex", alignItems: "center", gap: 6 }}>
                  <span style={{ width: 7, height: 7, borderRadius: 99, background: "var(--success, #58c06b)" }} /> In sync · {group.ping || 36} ms
                </div>
              </div>
            </div>
            <SectionLabel>In this room</SectionLabel>
            {people.map((p) => (
              <OptionRow key={p.id} icon={null}
                trailing={<Icon name={p.you ? "smartphone" : "check"} size={18} color="var(--text-secondary)" />}
                title={p.name + (p.you ? " (you)" : "")} subtitle={p.you ? "Host · this device" : "Ready"}
                onClick={() => {}} />
            ))}
          </div>
        ) : (
          <div>
            <SectionLabel>Join a group</SectionLabel>
            {groups.map((g) => (
              <OptionRow key={g.id} icon="users" title={g.name}
                subtitle={g.members + " watching · " + g.ping + " ms"}
                trailing={<Icon name="chevron-right" size={20} color="var(--text-secondary)" />}
                onClick={() => onJoin(g)} />
            ))}
          </div>
        )}
      </BeamSheet>
    );
  }

  // ---- User avatar + profile switcher ------------------------------------
  function UserAvatar({ user, size = 40, ring }) {
    const base = { width: size, height: size, borderRadius: "50%", flexShrink: 0,
      boxShadow: ring ? "0 0 0 2px var(--surface-app), 0 0 0 4px var(--accent)" : "none" };
    if (user && user.avatar) {
      return <img src={user.avatar} alt={user.name} style={{ ...base, objectFit: "cover" }} />;
    }
    return (
      <span style={{ ...base, background: (user && user.color) || "var(--surface-container-highest)",
        color: (user && user.textColor) || "#fff", fontSize: Math.round(size * 0.38), fontWeight: 700,
        display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
        {user ? user.initials : "?"}
      </span>
    );
  }
  function UserPickerSheet({ open, currentId, onPick, onClose }) {
    const users = window.SF_USERS || [];
    const { Button } = NS;
    return (
      <BeamSheet open={open} title="Switch user" subtitle="Choose a Jellyfin profile on this server" onClose={onClose}
        footer={<Button variant="tonal" fullWidth icon="user-plus" onClick={onClose}>Add another user</Button>}>
        {users.map((u) => (
          <OptionRow key={u.id} leading={<UserAvatar user={u} size={44} />}
            title={u.name} subtitle={u.role} selected={u.id === currentId}
            onClick={() => { onPick(u.id); onClose(); }} />
        ))}
      </BeamSheet>
    );
  }

  Object.assign(window, { BeamSheet, BeamOptionRow: OptionRow, BeamUserAvatar: UserAvatar, QualitySheet, AudioSheet, SubtitleSheet, CastSheet, SyncPlaySheet, UserPickerSheet });
})();
