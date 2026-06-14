import React from "react";
import { ProgressBar } from "./ProgressBar.jsx";
import { Badge } from "../core/Badge.jsx";

/**
 * PosterCard — the portrait media tile used in shelves & grids (phone, XR).
 * Rounded poster art with an optional dark metadata footer, watched-progress
 * bar, and corner status badge. Hover lifts and reveals the accent outline.
 */
export function PosterCard({
  title,
  subtitle,
  poster,
  progress = null,
  badge = null,
  width = 150,
  showFooter = true,
  onClick,
  style = {},
}) {
  const [hover, setHover] = React.useState(false);
  return (
    <div
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        width, cursor: onClick ? "pointer" : "default",
        borderRadius: "var(--radius-md)", overflow: "hidden",
        background: "var(--surface-card)",
        transition: "transform var(--duration-medium) var(--ease-standard), box-shadow var(--duration-medium) var(--ease-standard)",
        transform: hover ? "translateY(-4px)" : "none",
        boxShadow: hover ? "var(--elevation-4)" : "var(--elevation-1)",
        outline: hover ? "2px solid var(--accent)" : "2px solid transparent",
        ...style,
      }}
    >
      <div style={{ position: "relative", width: "100%", aspectRatio: "2 / 3", background: "var(--surface-container-low)" }}>
        {poster ? (
          <img src={poster} alt={title} style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }} />
        ) : null}
        {badge ? <div style={{ position: "absolute", top: 8, right: 8 }}>{badge}</div> : null}
        {progress != null ? (
          <div style={{ position: "absolute", left: 8, right: 8, bottom: 8 }}>
            <ProgressBar value={progress} />
          </div>
        ) : null}
      </div>
      {showFooter ? (
        <div style={{ padding: "10px 12px 12px" }}>
          <div
            className="m3-title-small"
            style={{ color: "var(--text-primary)", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}
          >
            {title}
          </div>
          {subtitle ? (
            <div className="m3-body-small" style={{ color: "var(--text-secondary)", marginTop: 2, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
              {subtitle}
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
