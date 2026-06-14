import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * TvNavRail — the left navigation rail for the 10-foot UI. Collapsed to an icon
 * column; expands to reveal labels when any item is focused or hovered (the
 * standard Android TV leanback pattern). Active destination gets a tonal pill.
 */
export function TvNavRail({ items = [], active, onChange, profile = "IM", style = {} }) {
  const [open, setOpen] = React.useState(false);
  return (
    <nav
      onFocus={() => setOpen(true)}
      onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget)) setOpen(false); }}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      style={{
        height: "100%", flexShrink: 0,
        width: open ? 248 : 88,
        transition: "width var(--duration-medium) var(--ease-standard)",
        background: open ? "linear-gradient(90deg, rgba(6,17,27,0.96) 0%, rgba(6,17,27,0.82) 70%, rgba(6,17,27,0) 100%)" : "transparent",
        display: "flex", flexDirection: "column", padding: "24px 20px", gap: 6,
        position: "absolute", left: 0, top: 0, bottom: 0, zIndex: 20, boxSizing: "border-box", ...style,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 14, height: 48, marginBottom: 18, paddingLeft: 4 }}>
        <span style={{ width: 40, height: 40, borderRadius: "50%", background: "var(--secondary-container)", color: "var(--on-secondary-container)", display: "inline-flex", alignItems: "center", justifyContent: "center", fontWeight: 700, flexShrink: 0 }}>{profile}</span>
        <span style={{ color: "var(--text-primary)", fontWeight: 600, whiteSpace: "nowrap", opacity: open ? 1 : 0, transition: "opacity var(--duration-fast)" }}>Ignacio</span>
      </div>
      {items.map((it) => {
        const on = it.id === active;
        return (
          <button
            key={it.id}
            type="button"
            tabIndex={0}
            data-focusable=""
            onClick={() => onChange && onChange(it.id)}
            onMouseEnter={(e) => e.currentTarget.focus()}
            className="tv-rail-item"
            style={{
              display: "flex", alignItems: "center", gap: 16, height: 48, padding: "0 14px",
              borderRadius: "var(--radius-full)", border: "none", cursor: "pointer", width: "100%",
              background: on ? "var(--primary-container)" : "transparent",
              color: on ? "var(--on-primary-container)" : "var(--text-secondary)",
              transition: "background var(--duration-fast) var(--ease-standard)",
            }}
          >
            <Icon name={it.icon} size={22} style={{ flexShrink: 0 }} />
            <span style={{ fontFamily: "var(--font-sans)", fontSize: 16, fontWeight: 500, whiteSpace: "nowrap", opacity: open ? 1 : 0, transition: "opacity var(--duration-fast)" }}>{it.label}</span>
          </button>
        );
      })}
      <style>{`.tv-rail-item:focus{outline:3px solid var(--primary);outline-offset:2px;background:var(--surface-container-high);color:var(--text-primary)}`}</style>
    </nav>
  );
}
