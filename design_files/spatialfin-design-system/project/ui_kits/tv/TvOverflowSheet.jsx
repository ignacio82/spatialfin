// TV — Overflow ("More actions") sheet used from the Home hero and detail screens.
// Matches the SpatialFin app's overflow menu (MovieScreen / EpisodeScreen):
//   SyncPlay, Playback options, Go to series, Go to season, Edit external IDs,
//   Cast & audio output, Refresh metadata, Share, Delete.
//
// Renders as a centered 10-foot dialog. Rows are focusable for D-pad nav and
// activate on Enter; the dialog itself eats clicks outside the panel.
(function () {
  const NS = window.SpatialFinDesignSystem_0d3fe7;

  function ActionRow({ icon, leading, label, sub, danger, admin, autoFocus, onClick }) {
    const ref = React.useRef(null);
    React.useEffect(() => { if (autoFocus && ref.current) ref.current.focus(); }, [autoFocus]);
    const { Icon, Pill } = NS;
    return (
      <button ref={ref} data-focusable type="button" onClick={onClick}
        onMouseEnter={(e) => e.currentTarget.focus()}
        style={{ display: "flex", alignItems: "center", gap: 18, width: "100%", textAlign: "left",
          padding: "16px 22px", border: "none", background: "transparent",
          borderRadius: 18, color: danger ? "var(--error, #f5667a)" : "var(--text-primary)",
          cursor: "pointer", fontFamily: "var(--font-sans)" }}>
        {leading ? leading : (icon ? (
          <span style={{ width: 48, height: 48, borderRadius: "50%", flexShrink: 0,
            background: danger ? "color-mix(in srgb, var(--error, #f5667a) 14%, transparent)" : "var(--surface-container-highest)",
            color: danger ? "var(--error, #f5667a)" : "var(--text-secondary)",
            display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
            <Icon name={icon} size={22} />
          </span>
        ) : null)}
        <span style={{ flex: 1, minWidth: 0 }}>
          <span style={{ display: "block", fontSize: 18, fontWeight: 600 }}>{label}</span>
          {sub ? <span style={{ display: "block", fontSize: 14, color: "var(--text-secondary)", marginTop: 2 }}>{sub}</span> : null}
        </span>
        {admin ? <Pill tone="outline">admin</Pill> : <Icon name="chevron-right" size={20} color="var(--text-secondary)" />}
      </button>
    );
  }

  function TvOverflowSheet({ open, title, subtitle, actions, onClose }) {
    if (!open) return null;
    const { Icon } = NS;
    // ESC closes
    React.useEffect(() => {
      const onKey = (e) => { if (e.key === "Escape") onClose(); };
      window.addEventListener("keydown", onKey);
      return () => window.removeEventListener("keydown", onKey);
    }, [onClose]);
    return (
      <div onClick={onClose}
        style={{ position: "absolute", inset: 0, zIndex: 50, background: "rgba(0,0,0,0.65)",
          display: "flex", alignItems: "center", justifyContent: "center",
          animation: "tvOvFade 180ms ease both" }}>
        <div onClick={(e) => e.stopPropagation()}
          style={{ width: 640, maxHeight: "80%", display: "flex", flexDirection: "column",
            background: "var(--surface-container)", borderRadius: 28, border: "1px solid var(--border-subtle)",
            boxShadow: "0 30px 80px rgba(0,0,0,0.55)", overflow: "hidden",
            animation: "tvOvIn 240ms cubic-bezier(.2,.85,.25,1) both" }}>
          <div style={{ display: "flex", alignItems: "flex-start", gap: 16, padding: "22px 26px 14px" }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="m3-headline-small" style={{ color: "var(--text-primary)", fontWeight: 700 }}>{title}</div>
              {subtitle ? <div className="m3-body-medium" style={{ color: "var(--text-secondary)", marginTop: 4 }}>{subtitle}</div> : null}
            </div>
            <button data-focusable type="button" onClick={onClose} aria-label="Close"
              onMouseEnter={(e) => e.currentTarget.focus()}
              style={{ width: 44, height: 44, borderRadius: "50%", border: "none", cursor: "pointer",
                background: "var(--surface-container-highest)", color: "var(--text-primary)",
                display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
              <Icon name="x" size={22} />
            </button>
          </div>
          <div style={{ overflowY: "auto", padding: "4px 12px 18px", scrollbarWidth: "none" }}>
            {(actions || []).map((a, i) => (
              <ActionRow key={a.id || a.label} {...a} autoFocus={i === 0}
                onClick={() => { onClose(); a.onClick && a.onClick(); }} />
            ))}
          </div>
        </div>
        <style>{`
          @keyframes tvOvFade { from { opacity: 0 } to { opacity: 1 } }
          @keyframes tvOvIn { from { opacity: 0; transform: scale(0.96) translateY(8px) } to { opacity: 1; transform: scale(1) translateY(0) } }
        `}</style>
      </div>
    );
  }

  // Builder — produces the standard SpatialFin overflow action list for a
  // movie or episode, with optional callbacks the host wires up.
  function buildOverflowActions({ item, kind, castActive, castLabel, onSyncPlay, onPlaybackOptions, onGoToSeries, onGoToSeason, onEditExternalIds, onCast, onRefreshMetadata, onShare, onDelete }) {
    const isEpisode = kind === "episode";
    return [
      { id: "syncplay", icon: "users", label: "SyncPlay", sub: "Watch in sync with others", onClick: onSyncPlay },
      { id: "playback", icon: "sliders", label: "Playback options", sub: "Default subtitle & audio", onClick: onPlaybackOptions },
      isEpisode ? { id: "series", icon: "tv", label: "Go to series", sub: item.seriesTitle, onClick: onGoToSeries } : null,
      isEpisode ? { id: "season", icon: "list", label: "Go to season", sub: "Season " + (item.season || 1), onClick: onGoToSeason } : null,
      { id: "extids", icon: "id-card", label: "Edit external IDs", sub: "TMDB · IMDB · TVDB", admin: true, onClick: onEditExternalIds },
      { id: "cast", icon: "cast", label: "Cast & audio output", sub: castActive ? "On " + castLabel : "This device", onClick: onCast },
      { id: "refresh", icon: "refresh-cw", label: "Refresh metadata", sub: "Re-scan from providers", admin: true, onClick: onRefreshMetadata },
      { id: "share", icon: "share-2", label: "Share", sub: "Send a deeplink", onClick: onShare },
      { id: "delete", icon: "trash-2", label: "Delete", sub: "Remove from library", admin: true, danger: true, onClick: onDelete },
    ].filter(Boolean);
  }

  Object.assign(window, { TvOverflowSheet, tvBuildOverflowActions: buildOverflowActions });
})();
