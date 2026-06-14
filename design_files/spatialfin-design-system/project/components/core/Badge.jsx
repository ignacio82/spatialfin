import React from "react";
import { Icon } from "./Icon.jsx";

/**
 * Badge — small status marker overlaid on media (downloaded, downloading, 4K,
 * watched count). Filled circle for icon-only, or a labelled tonal capsule.
 */
export function Badge({ children, tone = "accent", icon = null, dot = false, style = {}, ...rest }) {
  const tones = {
    accent: { background: "var(--accent)", color: "var(--on-primary)" },
    neutral: { background: "var(--surface-container-highest)", color: "var(--text-primary)" },
    error: { background: "var(--error)", color: "var(--on-error)" },
    success: { background: "var(--tertiary-container)", color: "var(--on-tertiary-container)" },
    overlay: { background: "rgba(0,0,0,0.62)", color: "#fff" },
  };
  const t = tones[tone] || tones.accent;

  if (dot) {
    return <span style={{ width: 10, height: 10, borderRadius: "50%", display: "inline-block", ...t, ...style }} {...rest} />;
  }
  const iconOnly = icon && !children;
  return (
    <span
      style={{
        display: "inline-flex", alignItems: "center", justifyContent: "center", gap: 4,
        minWidth: iconOnly ? 22 : undefined, height: 22,
        padding: iconOnly ? 0 : "0 8px",
        borderRadius: iconOnly ? "var(--radius-full)" : "var(--radius-sm)",
        fontFamily: "var(--font-sans)", fontSize: 11, fontWeight: 600, letterSpacing: 0.3,
        lineHeight: 1, ...t, ...style,
      }}
      {...rest}
    >
      {icon ? <Icon name={icon} size={13} /> : null}
      {children}
    </span>
  );
}
