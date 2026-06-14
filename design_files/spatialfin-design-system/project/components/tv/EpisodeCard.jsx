import React from "react";
import { ProgressBar } from "../media/ProgressBar.jsx";
import { Icon } from "../core/Icon.jsx";

/**
 * EpisodeCard — a focusable landscape episode tile for TV season rows. Shows a
 * still with the episode number, a watched-progress bar, and a footer with
 * "E# · Title", runtime and a one-line synopsis. Focus reveals a play overlay
 * and brightens the title (focus-scale + cyan ring, never shadow).
 */
export function EpisodeCard({
  number,
  title,
  still,
  runtime,
  synopsis,
  progress = null,
  width = 340,
  onClick,
  focusFirst = false,
  style = {},
}) {
  const [focused, setFocused] = React.useState(false);
  return (
    <div style={{ width, flex: "0 0 auto", ...style }}>
      <div
        tabIndex={0}
        data-focusable=""
        data-focus-first={focusFirst ? "" : undefined}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onMouseEnter={(e) => e.currentTarget.focus()}
        onClick={onClick}
        style={{
          position: "relative",
          width: "100%",
          aspectRatio: "16 / 9",
          borderRadius: "var(--radius-md)",
          overflow: "hidden",
          background: "var(--surface-container-low)",
          cursor: "pointer",
          outline: focused ? "3px solid var(--primary)" : "3px solid transparent",
          outlineOffset: 3,
          transform: focused ? "scale(var(--tv-focus-scale))" : "scale(1)",
          transition: "transform var(--duration-fast) var(--ease-standard), outline-color var(--duration-fast) var(--ease-standard), box-shadow var(--duration-fast) var(--ease-standard)",
          boxShadow: focused ? "0 14px 44px -8px rgba(125,218,255,0.5)" : "none",
        }}
      >
        {still ? <img src={still} alt={title} style={{ width: "100%", height: "100%", objectFit: "cover" }} /> : null}
        <div style={{ position: "absolute", inset: 0, background: "linear-gradient(180deg, rgba(0,0,0,0.35) 0%, rgba(0,0,0,0) 35%, rgba(0,0,0,0.55) 100%)" }} />
        <span style={{ position: "absolute", top: 8, left: 10, color: "#fff", fontWeight: 700, fontSize: 13, textShadow: "0 1px 4px rgba(0,0,0,0.6)" }}>
          EP {number}
        </span>
        {focused ? (
          <span style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center" }}>
            <span style={{ width: 52, height: 52, borderRadius: "50%", background: "var(--glass-fill-strong)", border: "1px solid var(--glass-border)", color: "#fff", display: "inline-flex", alignItems: "center", justifyContent: "center" }}>
              <Icon name="play" size={24} />
            </span>
          </span>
        ) : null}
        {progress != null ? (
          <div style={{ position: "absolute", left: 8, right: 8, bottom: 8 }}><ProgressBar value={progress} /></div>
        ) : null}
      </div>
      <div style={{ marginTop: 10, paddingInline: 2 }}>
        <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", gap: 8 }}>
          <span className="m3-title-small" style={{ color: focused ? "var(--text-primary)" : "var(--text-secondary)", fontWeight: 600, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
            {number}. {title}
          </span>
          {runtime ? <span className="m3-body-small" style={{ color: "var(--text-disabled)", flexShrink: 0 }}>{runtime}</span> : null}
        </div>
        {synopsis ? (
          <div className="m3-body-small" style={{ color: "var(--text-disabled)", marginTop: 3, display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}>
            {synopsis}
          </div>
        ) : null}
      </div>
    </div>
  );
}
