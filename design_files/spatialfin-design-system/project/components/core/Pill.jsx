import React from "react";
import { Icon } from "./Icon.jsx";

/**
 * Pill — non-focusable rounded chip for static metadata (year, runtime, rating,
 * codec, resolution). Mirrors MetadataPill.kt (full radius, translucent fill).
 * `tone` picks the fill; pass `icon` for leading glyphs (e.g. a star on rating).
 */
export function Pill({ children, tone = "chip", icon = null, style = {}, ...rest }) {
  const tones = {
    chip: { background: "var(--chip-fill)", color: "var(--text-primary)" },
    neutral: { background: "var(--surface-container-high)", color: "var(--text-secondary)" },
    accent: { background: "var(--accent-container)", color: "var(--on-accent-container)" },
    outline: { background: "transparent", color: "var(--text-secondary)", boxShadow: "inset 0 0 0 1px var(--border-subtle)" },
    rating: { background: "var(--chip-fill)", color: "var(--rating-star)" },
  };
  const t = tones[tone] || tones.chip;
  return (
    <span
      style={{
        display: "inline-flex", alignItems: "center", gap: 5,
        padding: "5px 10px", borderRadius: "var(--radius-full)",
        fontFamily: "var(--font-sans)", fontSize: 11, fontWeight: 500,
        letterSpacing: 0.4, lineHeight: 1, whiteSpace: "nowrap",
        ...t, ...style,
      }}
      {...rest}
    >
      {icon ? <Icon name={icon} size={13} /> : null}
      {children}
    </span>
  );
}
