import React from "react";

/**
 * PointerHint — a small animated pointer used during onboarding to call out a
 * UI element (e.g. "this is the mic FAB"). Renders as an absolutely-positioned
 * wrapper around the target slot with a soft pulse + an angled label callout.
 *
 * Place the wrapper around any element. `direction` controls which side the
 * label appears on.
 */
export function PointerHint({
  label,
  hint,
  direction = "top",      // top | bottom | left | right
  visible = true,
  children,
  style = {},
}) {
  const offset = {
    top: { bottom: "calc(100% + 18px)", left: "50%", transform: "translateX(-50%)" },
    bottom: { top: "calc(100% + 18px)", left: "50%", transform: "translateX(-50%)" },
    left: { right: "calc(100% + 18px)", top: "50%", transform: "translateY(-50%)" },
    right: { left: "calc(100% + 18px)", top: "50%", transform: "translateY(-50%)" },
  }[direction];

  return (
    <span style={{ position: "relative", display: "inline-flex", ...style }}>
      {visible ? (
        <span aria-hidden style={{
          position: "absolute", inset: -10, borderRadius: "var(--radius-full)",
          border: "2px solid var(--primary)", pointerEvents: "none",
          animation: "sf-hint-ring 1.6s var(--ease-standard) infinite",
        }} />
      ) : null}
      {children}
      {visible && (label || hint) ? (
        <span style={{
          position: "absolute", ...offset,
          minWidth: 180, maxWidth: 280,
          padding: "10px 14px", borderRadius: "var(--radius-md)",
          background: "var(--surface-container-highest)",
          color: "var(--text-primary)",
          boxShadow: "var(--elevation-3)",
          border: "1px solid var(--glass-border)",
          textAlign: "center", pointerEvents: "none",
          animation: "sf-hint-bob 2.4s var(--ease-standard) infinite",
        }}>
          {label ? <div className="m3-title-small" style={{ fontWeight: 700 }}>{label}</div> : null}
          {hint ? <div className="m3-body-small" style={{ color: "var(--text-secondary)", marginTop: 2 }}>{hint}</div> : null}
          {/* arrow */}
          <span aria-hidden style={{
            position: "absolute",
            ...(direction === "top" ? { bottom: -7, left: "50%", transform: "translateX(-50%) rotate(45deg)" } : {}),
            ...(direction === "bottom" ? { top: -7, left: "50%", transform: "translateX(-50%) rotate(45deg)" } : {}),
            ...(direction === "left" ? { right: -7, top: "50%", transform: "translateY(-50%) rotate(45deg)" } : {}),
            ...(direction === "right" ? { left: -7, top: "50%", transform: "translateY(-50%) rotate(45deg)" } : {}),
            width: 14, height: 14, background: "var(--surface-container-highest)",
            borderLeft: "1px solid var(--glass-border)", borderBottom: "1px solid var(--glass-border)",
          }} />
        </span>
      ) : null}
      <style>{`
        @keyframes sf-hint-ring { 0%{ transform: scale(1); opacity: 0.9; } 70%{ transform: scale(1.18); opacity: 0; } 100%{ opacity: 0; } }
        @keyframes sf-hint-bob { 0%,100%{ transform: ${direction === "top" || direction === "bottom" ? "translateX(-50%) translateY(0)" : "translateY(-50%) translateX(0)"}; }
          50%{ transform: ${direction === "top" ? "translateX(-50%) translateY(-4px)" : direction === "bottom" ? "translateX(-50%) translateY(4px)" : direction === "left" ? "translateY(-50%) translateX(-4px)" : "translateY(-50%) translateX(4px)"}; } }
      `}</style>
    </span>
  );
}
