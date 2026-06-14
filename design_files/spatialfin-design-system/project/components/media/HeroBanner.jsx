import React from "react";
import { Button } from "../core/Button.jsx";
import { Pill } from "../core/Pill.jsx";

/**
 * HeroBanner — the featured spotlight at the top of Home (phone) / a detail
 * banner (XR). Full-bleed backdrop with a bottom protection scrim, title,
 * metadata pills and primary actions. Content-first: chrome recedes.
 */
export function HeroBanner({
  title,
  kind = "Movie",
  backdrop,
  meta = [],
  height = 320,
  actions = null,
  rounded = true,
  style = {},
}) {
  return (
    <div
      style={{
        position: "relative", width: "100%", height, overflow: "hidden",
        borderRadius: rounded ? "var(--radius-lg)" : 0,
        background: "var(--surface-container-low)", ...style,
      }}
    >
      {backdrop ? (
        <img src={backdrop} alt={title} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", objectFit: "cover" }} />
      ) : null}
      <div style={{ position: "absolute", inset: 0, background: "var(--scrim-gradient)" }} />
      <div style={{ position: "absolute", left: 0, right: 0, bottom: 0, padding: "var(--space-lg)", display: "flex", flexDirection: "column", gap: 12 }}>
        {kind ? <div className="m3-label-large" style={{ color: "rgba(255,255,255,0.82)", letterSpacing: 0.6 }}>{kind.toUpperCase()}</div> : null}
        <div className="m3-display-small" style={{ color: "#fff", fontWeight: 700, textShadow: "0 2px 12px rgba(0,0,0,0.5)" }}>{title}</div>
        {meta.length ? (
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {meta.map((m, i) => <Pill key={i} tone="chip">{m}</Pill>)}
          </div>
        ) : null}
        <div style={{ display: "flex", gap: 12, marginTop: 4 }}>
          {actions || (
            <>
              <Button variant="filled" icon="play" size="lg">Play</Button>
              <Button variant="glass" size="lg">Details</Button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
