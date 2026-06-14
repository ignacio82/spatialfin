import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * VoiceFeedback — the assistant state indicator. On Beam it anchors top-center
 * (never under the FAB); in XR it's a spatial overlay. Renders identically in
 * INTENT across surfaces (DESIGN.md voice-parity rule). States: listening,
 * processing, answered, error.
 */
const CONFIG = {
  listening:  { icon: "mic",         tint: "var(--accent)",     label: "Listening…" },
  processing: { icon: "loader",      tint: "var(--tertiary)",   label: "Thinking…" },
  answered:   { icon: "sparkles",    tint: "var(--tertiary)",   label: "Here you go" },
  error:      { icon: "triangle-alert", tint: "var(--error)",   label: "Didn't catch that" },
};

export function VoiceFeedback({ state = "listening", text, style = {} }) {
  const c = CONFIG[state] || CONFIG.listening;
  return (
    <div
      role="status"
      style={{
        display: "inline-flex", alignItems: "center", gap: 12,
        maxWidth: 520, padding: "12px 18px 12px 14px",
        background: "var(--glass-fill-strong)",
        WebkitBackdropFilter: "blur(var(--glass-blur))", backdropFilter: "blur(var(--glass-blur))",
        border: "1px solid var(--glass-border)", borderRadius: "var(--radius-full)",
        boxShadow: "var(--shadow-glass)", color: "var(--text-primary)", ...style,
      }}
    >
      <span style={{
        width: 34, height: 34, borderRadius: "var(--radius-full)", flexShrink: 0,
        display: "inline-flex", alignItems: "center", justifyContent: "center",
        background: "color-mix(in oklab, " + c.tint + " 22%, transparent)", color: c.tint,
      }}>
        <Icon name={c.icon} size={18} style={state === "processing" ? { animation: "sf-vf-spin 1s linear infinite" } : undefined} />
      </span>
      <span className="m3-body-medium" style={{ color: text ? "var(--text-primary)" : "var(--text-secondary)" }}>
        {text || c.label}
      </span>
      <style>{`@keyframes sf-vf-spin { to { transform: rotate(360deg) } }`}</style>
    </div>
  );
}
