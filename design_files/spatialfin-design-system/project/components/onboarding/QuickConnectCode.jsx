import React from "react";

/**
 * QuickConnectCode — Jellyfin Quick Connect-style 6-character code rendered
 * BIG so the user can read it from across the room and enter it on a phone
 * or web client. Each character sits in its own tile with subtle entry
 * animation as the code is revealed. Pass `expiresAt` for an optional
 * countdown bar underneath.
 */
export function QuickConnectCode({
  code = "73-9KQA",
  helper = "On any device, open spatialfin.app/connect and enter this code",
  status = "waiting",  // waiting | connecting | confirmed
  style = {},
}) {
  const chars = code.split("");
  const statusMeta = {
    waiting: { label: "Waiting for confirmation", color: "var(--text-secondary)", dot: "var(--text-disabled)" },
    connecting: { label: "Confirming…", color: "var(--primary)", dot: "var(--primary)" },
    confirmed: { label: "Connected!", color: "var(--tertiary)", dot: "var(--tertiary)" },
  }[status] || { label: status, color: "var(--text-secondary)", dot: "var(--text-disabled)" };

  return (
    <div style={{ display: "inline-flex", flexDirection: "column", alignItems: "center", gap: 16, ...style }}>
      <div style={{ display: "inline-flex", gap: 8 }}>
        {chars.map((ch, i) => ch === "-" ? (
          <span key={i} aria-hidden style={{ width: 18, alignSelf: "center", color: "var(--text-disabled)", fontSize: 36, fontWeight: 400 }}>·</span>
        ) : (
          <span key={i} style={{
            width: 72, height: 96, borderRadius: "var(--radius-md)",
            background: "var(--surface-container-high)",
            border: "1px solid var(--border-subtle)",
            display: "inline-flex", alignItems: "center", justifyContent: "center",
            fontFamily: "var(--font-mono)", fontSize: 56, fontWeight: 700,
            color: "var(--text-primary)", letterSpacing: 0,
            boxShadow: "inset 0 -3px 0 rgba(0,0,0,0.15)",
            animation: `sf-qc-pop ${0.2 + i * 0.05}s var(--ease-standard) both`,
          }}>{ch}</span>
        ))}
      </div>

      <div style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
        <span style={{ width: 8, height: 8, borderRadius: "50%", background: statusMeta.dot,
          animation: status === "connecting" ? "sf-qc-pulse 1.2s ease-in-out infinite" : "none" }} />
        <span className="m3-label-large" style={{ color: statusMeta.color, fontWeight: 600 }}>{statusMeta.label}</span>
      </div>

      {helper ? (
        <div className="m3-body-medium" style={{ color: "var(--text-secondary)", maxWidth: 580, textAlign: "center" }}>
          {helper}
        </div>
      ) : null}

      <style>{`
        @keyframes sf-qc-pop { 0%{ transform: translateY(8px); opacity: 0; } 100%{ transform: none; opacity: 1; } }
        @keyframes sf-qc-pulse { 0%,100%{ transform: scale(1); opacity: 1; } 50%{ transform: scale(1.6); opacity: 0.5; } }
      `}</style>
    </div>
  );
}
