import React from "react";

/**
 * QrPairCode — the "scan to pair" QR card used during onboarding on TV and XR,
 * and during companion handoff on Beam. Renders the QR image with a soft glow
 * + a 4-character verification code underneath (so the user can confirm they
 * paired the right thing). For the design system this expects a placeholder
 * QR PNG; consumers swap in a real one.
 *
 * The `pairCode` is shown big and mono — same vocabulary as Quick Connect.
 */
export function QrPairCode({
  qrSrc,
  pairCode = "AX-94K",
  caption = "Scan with the SpatialFin app on your phone",
  size = 220,
  tone = "default",   // default | glass
  style = {},
}) {
  const isGlass = tone === "glass";
  return (
    <div style={{
      display: "inline-flex", flexDirection: "column", alignItems: "center", gap: 14,
      padding: 18, borderRadius: "var(--radius-lg)",
      background: isGlass ? "var(--glass-fill-strong)" : "var(--surface-container-high)",
      border: isGlass ? "1px solid var(--glass-border)" : "1px solid var(--border-subtle)",
      backdropFilter: isGlass ? "blur(var(--glass-blur))" : "none",
      WebkitBackdropFilter: isGlass ? "blur(var(--glass-blur))" : "none",
      boxShadow: isGlass ? "var(--shadow-glass)" : "var(--elevation-2)", ...style,
    }}>
      <div style={{ position: "relative", padding: 12, borderRadius: "var(--radius-md)", background: "#fff", boxShadow: "0 12px 28px -8px rgba(125,218,255,0.22)" }}>
        {qrSrc ? (
          <img src={qrSrc} alt={"Pair code QR — " + pairCode}
            style={{ width: size, height: size, display: "block", imageRendering: "pixelated" }} />
        ) : (
          <div style={{ width: size, height: size, background: "var(--surface-container)", display: "flex", alignItems: "center", justifyContent: "center", color: "var(--text-secondary)", fontFamily: "var(--font-mono)", fontSize: 12 }}>
            QR placeholder
          </div>
        )}
        {/* tiny logo dot in center to show "branded" QR */}
        <span aria-hidden style={{
          position: "absolute", left: "50%", top: "50%", transform: "translate(-50%, -50%)",
          width: size * 0.22, height: size * 0.22, borderRadius: "50%",
          background: "#fff", border: "3px solid var(--primary)",
          display: "flex", alignItems: "center", justifyContent: "center",
          color: "var(--primary)", fontWeight: 800, fontSize: size * 0.1,
        }}>SF</span>
      </div>
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }}>
        <span style={{ fontFamily: "var(--font-mono)", fontSize: 22, fontWeight: 700, letterSpacing: 6, color: "var(--text-primary)" }}>
          {pairCode}
        </span>
        {caption ? <span className="m3-body-small" style={{ color: "var(--text-secondary)", textAlign: "center", maxWidth: size + 80 }}>{caption}</span> : null}
      </div>
    </div>
  );
}
