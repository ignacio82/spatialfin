import React from "react";
import { Icon } from "../core/Icon.jsx";

/**
 * NavBar — the phone (Beam) bottom navigation bar. Material 3 style: the active
 * destination gets a tonal pill behind its icon and an accent label. Pass
 * `items` as [{ id, icon, label }] plus the active id.
 */
export function NavBar({ items = [], active, onChange, style = {} }) {
  return (
    <nav
      style={{
        display: "flex", alignItems: "stretch", justifyContent: "space-around",
        background: "var(--surface-container)", borderTop: "1px solid var(--border-subtle)",
        padding: "10px 4px 12px", gap: 2, ...style,
      }}
    >
      {items.map((it) => {
        const on = it.id === active;
        return (
          <button
            key={it.id}
            type="button"
            onClick={() => onChange && onChange(it.id)}
            style={{
              flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 4,
              background: "transparent", border: "none", cursor: "pointer", padding: 0,
            }}
          >
            <span
              style={{
                display: "inline-flex", alignItems: "center", justifyContent: "center",
                width: 56, height: 30, borderRadius: "var(--radius-full)",
                background: on ? "var(--accent-container)" : "transparent",
                color: on ? "var(--on-accent-container)" : "var(--text-secondary)",
                transition: "background var(--duration-medium) var(--ease-standard)",
              }}
            >
              <Icon name={it.icon} size={20} strokeWidth={on ? 2.4 : 2} />
            </span>
            <span style={{ fontFamily: "var(--font-sans)", fontSize: 11, fontWeight: 500, letterSpacing: 0.4,
              color: on ? "var(--text-primary)" : "var(--text-secondary)" }}>
              {it.label}
            </span>
          </button>
        );
      })}
    </nav>
  );
}
