import React from "react";

/**
 * ProgressBar — thin watched-progress indicator shown under poster art and on
 * the player scrubber. Accent fill on a translucent track.
 */
export function ProgressBar({ value = 0, height = 4, style = {} }) {
  const pct = Math.max(0, Math.min(100, value));
  return (
    <div
      style={{
        width: "100%", height, borderRadius: "var(--radius-full)",
        background: "rgba(255,255,255,0.22)", overflow: "hidden", ...style,
      }}
    >
      <div style={{ width: `${pct}%`, height: "100%", background: "var(--accent)", borderRadius: "var(--radius-full)" }} />
    </div>
  );
}
