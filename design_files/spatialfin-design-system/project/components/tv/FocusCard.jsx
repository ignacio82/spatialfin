import React from "react";
import { ProgressBar } from "../media/ProgressBar.jsx";

/**
 * FocusCard — the focusable media tile for the 10-foot TV UI. Portrait for
 * movies/shows, landscape for episodes/continue-watching. On focus it lifts
 * with a focus-scale (≈1.06), a cyan focus ring + glow, and the title brightens
 * — TV communicates selection by scale + outline, NEVER shadow elevation.
 *
 * Mark with data-focusable so the D-pad focus engine (tvFocus.js) can reach it.
 */
export function FocusCard({
  title,
  subtitle,
  image,
  orientation = "portrait",
  width,
  progress = null,
  badge = null,
  onClick,
  focusFirst = false,
  style = {},
}) {
  const [focused, setFocused] = React.useState(false);
  const portrait = orientation === "portrait";
  const w = width || (portrait ? 168 : 320);
  return (
    <div style={{ width: w, flex: "0 0 auto", ...style }}>
      <div
        tabIndex={0}
        data-focusable=""
        data-focus-first={focusFirst ? "" : undefined}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        onClick={onClick}
        onMouseEnter={(e) => e.currentTarget.focus()}
        style={{
          position: "relative",
          width: "100%",
          aspectRatio: portrait ? "2 / 3" : "16 / 9",
          borderRadius: "var(--radius-md)",
          overflow: "hidden",
          background: "var(--surface-container-low)",
          cursor: "pointer",
          outline: focused ? "3px solid var(--primary)" : "3px solid transparent",
          outlineOffset: 3,
          transform: focused ? "scale(var(--tv-focus-scale))" : "scale(1)",
          transition:
            "transform var(--duration-fast) var(--ease-standard), outline-color var(--duration-fast) var(--ease-standard), box-shadow var(--duration-fast) var(--ease-standard)",
          boxShadow: focused ? "0 14px 44px -8px rgba(125,218,255,0.5)" : "none",
        }}
      >
        {image ? (
          <img src={image} alt={title} style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }} />
        ) : null}
        {badge ? <div style={{ position: "absolute", top: 8, left: 8 }}>{badge}</div> : null}
        {progress != null ? (
          <div style={{ position: "absolute", left: 8, right: 8, bottom: 8 }}>
            <ProgressBar value={progress} />
          </div>
        ) : null}
      </div>
      {title ? (
        <div style={{ marginTop: 10, paddingInline: 2 }}>
          <div
            className="m3-title-small"
            style={{
              color: focused ? "var(--text-primary)" : "var(--text-secondary)",
              fontWeight: 600,
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
              transition: "color var(--duration-fast) var(--ease-standard)",
            }}
          >
            {title}
          </div>
          {subtitle ? (
            <div className="m3-body-small" style={{ color: "var(--text-disabled)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", marginTop: 2 }}>
              {subtitle}
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
