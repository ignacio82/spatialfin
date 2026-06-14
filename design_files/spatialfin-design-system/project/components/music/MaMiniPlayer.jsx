import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * MaMiniPlayer — the persistent Music Assistant playback bar shown on Beam, XR
 * and TV. Three states (mirrors `MaMiniPlayer.kt`):
 *   - **preparing** — user just tapped play; MA hasn't echoed yet. Indeterminate
 *     progress strip at top + "Preparing audio…" subtitle. Transport disabled.
 *   - **playing / paused** — live title + artist (or "On <speaker>"), transport
 *     enabled.
 *   - **idle (no track)** — hide entirely (caller decides; pass `track={null}`).
 *
 * Pass `onExpand` for tap-to-open Now Playing. `onStop` dismisses the bar.
 */
export function MaMiniPlayer({
  track,
  phase = "playing",       // preparing | playing | paused | idle
  selectedPlayer,
  onPlayPause, onNext, onStop, onExpand,
  style = {},
}) {
  if (!track) return null;
  const preparing = phase === "preparing";
  const playing = phase === "playing";

  const subtitle = preparing
    ? "Preparing audio…"
    : (track.artist || (selectedPlayer ? "On " + selectedPlayer : null));

  const btn = (icon, label, onClick, opts = {}) => (
    <button
      type="button" tabIndex={0} data-focusable=""
      onClick={(e) => { e.stopPropagation(); onClick && onClick(); }}
      onMouseEnter={(e) => e.currentTarget.focus()}
      disabled={opts.disabled}
      aria-label={label} title={label}
      className="ma-mini-btn"
      style={{
        width: 44, height: 44, borderRadius: "var(--radius-full)",
        border: "none", background: "transparent",
        color: opts.disabled ? "var(--text-disabled)" : "var(--text-primary)",
        cursor: opts.disabled ? "not-allowed" : "pointer",
        display: "inline-flex", alignItems: "center", justifyContent: "center",
      }}
    >
      <Icon name={icon} size={22} />
    </button>
  );

  return (
    <div
      role="region" aria-label="Music Assistant mini player"
      onClick={onExpand}
      tabIndex={0} data-focusable=""
      onMouseEnter={(e) => e.currentTarget.focus()}
      className="ma-mini"
      style={{
        position: "relative", display: "flex", alignItems: "stretch",
        background: "var(--surface-container-high)",
        borderRadius: "var(--radius-md)", overflow: "hidden",
        boxShadow: "var(--elevation-3)", cursor: "pointer",
        ...style,
      }}
    >
      {preparing ? (
        <div style={{ position: "absolute", top: 0, left: 0, right: 0, height: 2, background: "rgba(255,255,255,0.08)" }}>
          <div className="ma-prep-bar" style={{ height: "100%", width: "40%", background: "var(--primary)" }} />
        </div>
      ) : null}
      <div style={{ width: 56, height: 56, flexShrink: 0, padding: 6, boxSizing: "border-box" }}>
        <div style={{ width: 44, height: 44, borderRadius: 8, overflow: "hidden", background: "var(--accent-container)",
          display: "flex", alignItems: "center", justifyContent: "center" }}>
          {track.artwork
            ? <img src={track.artwork} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
            : <Icon name="music" size={20} color="var(--on-accent-container)" />}
        </div>
      </div>
      <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", justifyContent: "center", padding: "8px 4px" }}>
        <div className="m3-title-small" style={{ color: "var(--text-primary)", fontWeight: 600,
          whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
          {track.title || "Unknown track"}
        </div>
        {subtitle ? (
          <div className="m3-body-small" style={{ color: "var(--text-secondary)",
            whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
            {subtitle}
          </div>
        ) : null}
      </div>
      <div style={{ display: "flex", alignItems: "center", padding: "0 8px", gap: 2, flexShrink: 0 }}>
        {btn(playing ? "pause" : "play", playing ? "Pause" : "Play", onPlayPause, { disabled: preparing })}
        {btn("skip-forward", "Next", onNext, { disabled: preparing })}
        {btn("square", "Stop", onStop)}
      </div>
      <style>{`
        .ma-mini{ transition: outline-color var(--duration-fast), background var(--duration-fast); }
        .ma-mini:focus{ outline: 3px solid var(--primary); outline-offset: 3px; }
        .ma-mini-btn:focus{ outline: 3px solid var(--primary); outline-offset: 2px; background: var(--surface-container-highest); }
        @keyframes ma-prep { 0%{ margin-left: -40% } 100%{ margin-left: 100% } }
        .ma-prep-bar{ animation: ma-prep 1.5s var(--ease-standard) infinite; }
      `}</style>
    </div>
  );
}
