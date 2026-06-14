import React from "react";

/**
 * SectionHeader — a shelf/row title with the signature leading accent bar
 * (see phone Home: "Suggestions", "Continue Watching"). Optional trailing
 * action (e.g. "See all").
 */
export function SectionHeader({ title, action = null, size = "large", style = {} }) {
  const cls = size === "large" ? "m3-headline-small" : "m3-title-large";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 12, ...style }}>
      <span style={{ width: 4, height: size === "large" ? 26 : 20, borderRadius: "var(--radius-full)", background: "var(--accent)", flexShrink: 0 }} />
      <span className={cls} style={{ color: "var(--text-primary)", fontWeight: 700 }}>{title}</span>
      <span style={{ flex: 1 }} />
      {action}
    </div>
  );
}
