import React from "react";

/**
 * OnboardingStepper — a compact dots-and-line progress indicator for multi-step
 * setup flows. The active dot is filled with the accent; completed dots are
 * filled muted; upcoming dots are hollow. Optional labels under each dot.
 *
 * Use the same component on all three surfaces — sizes scale via `size`.
 */
export function OnboardingStepper({ steps = 3, active = 0, labels = null, size = "md", style = {} }) {
  const dims = { sm: { dot: 8, gap: 24, fs: 12 }, md: { dot: 10, gap: 36, fs: 13 }, lg: { dot: 14, gap: 64, fs: 15 } }[size] || { dot: 10, gap: 36, fs: 13 };
  return (
    <div style={{ display: "inline-flex", flexDirection: "column", alignItems: "center", gap: 10, ...style }}>
      <div style={{ display: "inline-flex", alignItems: "center", gap: 0 }}>
        {Array.from({ length: steps }).map((_, i) => {
          const done = i < active, on = i === active;
          return (
            <React.Fragment key={i}>
              <span style={{
                width: on ? dims.dot * 2.4 : dims.dot, height: dims.dot,
                borderRadius: "var(--radius-full)",
                background: on ? "var(--primary)" : done ? "var(--accent-container)" : "transparent",
                border: !on && !done ? "1.5px solid var(--border-strong)" : "none",
                transition: "all var(--duration-medium) var(--ease-standard)",
                flexShrink: 0,
              }} />
              {i < steps - 1 ? <span style={{ width: dims.gap, height: 2, background: done ? "var(--accent-container)" : "var(--border-subtle)" }} /> : null}
            </React.Fragment>
          );
        })}
      </div>
      {labels ? (
        <div style={{ display: "inline-flex", alignItems: "center", gap: 0 }}>
          {labels.map((l, i) => (
            <React.Fragment key={i}>
              <span style={{
                fontSize: dims.fs, fontWeight: 500,
                color: i === active ? "var(--text-primary)" : "var(--text-secondary)",
                width: dims.dot * 2.4 + dims.gap, textAlign: "center",
                marginLeft: i === 0 ? -dims.gap / 2 : 0, marginRight: i === labels.length - 1 ? -dims.gap / 2 : 0,
              }}>{l}</span>
            </React.Fragment>
          ))}
        </div>
      ) : null}
    </div>
  );
}
