import React from "react";

/**
 * GlassPanel — the translucent floating surface that defines the XR look.
 * darkSurface at 62–88% with a 24px backdrop blur, hairline border, large
 * radius and a soft ambient shadow. Use `tone="strong"` for dense controls /
 * dialogs that need extra legibility; `tone="panel"` for content over
 * passthrough or video.
 */
export function GlassPanel({ children, tone = "panel", radius = "lg", padding = "lg", style = {}, ...rest }) {
  const radii = { md: "var(--radius-md)", lg: "var(--radius-lg)", full: "var(--radius-full)" };
  const pads = { none: 0, sm: "var(--space-sm)", md: "var(--space-md)", lg: "var(--space-lg)" };
  return (
    <div
      style={{
        background: tone === "strong" ? "var(--glass-fill-strong)" : "var(--glass-fill)",
        WebkitBackdropFilter: "blur(var(--glass-blur))",
        backdropFilter: "blur(var(--glass-blur))",
        border: "1px solid var(--glass-border)",
        borderRadius: radii[radius] || radii.lg,
        boxShadow: "var(--shadow-glass)",
        padding: pads[padding] ?? pads.lg,
        color: "var(--text-primary)",
        ...style,
      }}
      {...rest}
    >
      {children}
    </div>
  );
}
