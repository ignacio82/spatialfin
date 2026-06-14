import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * VoiceFab — the primary assistant affordance on Beam (phone): a 56dp mic FAB.
 * Pulses a ring while listening; tapping while busy cancels. On XR the same
 * intent is a mic IconButton in the orbiter, and a near-face open-palm hold.
 */
export function VoiceFab({ state = "idle", onClick, size = 56, style = {} }) {
  const listening = state === "listening";
  const processing = state === "processing";
  return (
    <button
      type="button"
      aria-label="Voice assistant"
      onClick={onClick}
      style={{
        position: "relative", width: size, height: size, borderRadius: "var(--radius-full)",
        border: "none", cursor: "pointer",
        background: listening ? "var(--accent)" : "var(--accent-container)",
        color: listening ? "var(--on-primary)" : "var(--on-accent-container)",
        boxShadow: "var(--elevation-3)",
        display: "inline-flex", alignItems: "center", justifyContent: "center",
        transition: "background var(--duration-medium) var(--ease-standard)",
        ...style,
      }}
    >
      {listening ? (
        <span style={{
          position: "absolute", inset: -6, borderRadius: "var(--radius-full)",
          border: "3px solid var(--accent)", opacity: 0.6,
          animation: "sf-voice-pulse 1.4s var(--ease-standard) infinite",
        }} />
      ) : null}
      <Icon name={processing ? "loader" : "mic"} size={size * 0.42}
        style={processing ? { animation: "sf-voice-spin 1s linear infinite" } : undefined} />
      <style>{`
        @keyframes sf-voice-pulse { 0%{transform:scale(1);opacity:.6} 70%{transform:scale(1.35);opacity:0} 100%{opacity:0} }
        @keyframes sf-voice-spin { to { transform: rotate(360deg) } }
      `}</style>
    </button>
  );
}
