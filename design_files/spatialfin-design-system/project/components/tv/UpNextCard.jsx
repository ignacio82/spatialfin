import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * UpNextCard — the autoplay card that slides in over the player as content ends.
 * Shows the next item's still, title, a "Up next" label and an auto-advancing
 * countdown ring around a Play Now button; a Cancel/secondary action dismisses.
 * The countdown is driven by the `seconds` + `remaining` props (the host owns
 * the timer); the ring fills as `remaining` drops.
 */
export function UpNextCard({
  title,
  subtitle,
  still,
  seconds = 10,
  remaining = 10,
  onPlayNow,
  onCancel,
  style = {},
}) {
  const pct = Math.max(0, Math.min(1, remaining / seconds));
  const R = 26, C = 2 * Math.PI * R;

  return (
    <div
      style={{
        display: "flex", gap: 18, width: 460, padding: 16,
        background: "var(--glass-fill-strong)",
        WebkitBackdropFilter: "blur(var(--glass-blur))", backdropFilter: "blur(var(--glass-blur))",
        border: "1px solid var(--glass-border)", borderRadius: "var(--radius-lg)",
        boxShadow: "var(--shadow-glass)", ...style,
      }}
    >
      <div style={{ width: 150, aspectRatio: "16 / 9", borderRadius: "var(--radius-md)", overflow: "hidden", flexShrink: 0, background: "var(--surface-container-low)" }}>
        {still ? <img src={still} alt={title} style={{ width: "100%", height: "100%", objectFit: "cover" }} /> : null}
      </div>
      <div style={{ flex: 1, display: "flex", flexDirection: "column", minWidth: 0 }}>
        <div className="m3-label-medium" style={{ color: "var(--primary)", letterSpacing: 1, fontWeight: 700 }}>UP NEXT</div>
        <div className="m3-title-large" style={{ color: "var(--text-primary)", fontWeight: 700, marginTop: 2, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{title}</div>
        {subtitle ? <div className="m3-body-small" style={{ color: "var(--text-secondary)", marginTop: 2 }}>{subtitle}</div> : null}
        <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: "auto" }}>
          <button
            type="button" tabIndex={0} data-focusable="" data-focus-first="" onClick={onPlayNow}
            onMouseEnter={(e) => e.currentTarget.focus()} className="tv-upnext-play"
            style={{ display: "inline-flex", alignItems: "center", gap: 10, height: 48, padding: "0 8px 0 4px",
              borderRadius: "var(--radius-full)", border: "none", background: "var(--primary)", color: "var(--on-primary)",
              fontSize: 16, fontWeight: 600, cursor: "pointer" }}
          >
            <span style={{ position: "relative", width: 40, height: 40, flexShrink: 0 }}>
              <svg width="40" height="40" style={{ position: "absolute", inset: 0, transform: "rotate(-90deg)" }}>
                <circle cx="20" cy="20" r={R} fill="none" stroke="rgba(0,0,0,0.2)" strokeWidth="3" />
                <circle cx="20" cy="20" r={R} fill="none" stroke="var(--on-primary)" strokeWidth="3"
                  strokeDasharray={C} strokeDashoffset={C * (1 - pct)} strokeLinecap="round" />
              </svg>
              <span style={{ position: "absolute", inset: 0, display: "flex", alignItems: "center", justifyContent: "center" }}>
                <Icon name="play" size={18} />
              </span>
            </span>
            Play Now · {Math.ceil(remaining)}s
          </button>
          <button
            type="button" tabIndex={0} data-focusable="" onClick={onCancel}
            onMouseEnter={(e) => e.currentTarget.focus()} className="tv-upnext-cancel"
            style={{ height: 48, padding: "0 20px", borderRadius: "var(--radius-full)",
              border: "1px solid var(--border-strong)", background: "transparent", color: "var(--text-secondary)",
              fontSize: 16, fontWeight: 600, cursor: "pointer" }}
          >
            Cancel
          </button>
        </div>
      </div>
      <style>{`.tv-upnext-play:focus,.tv-upnext-cancel:focus{outline:3px solid var(--primary);outline-offset:3px}
        .tv-upnext-cancel:focus{background:var(--surface-container-high);color:var(--text-primary)}`}</style>
    </div>
  );
}
