import React from "react";
import { IconButton } from "../core/IconButton.jsx";

/**
 * Orbiter — the floating glass control cluster that hovers beside a spatial
 * panel in XR (Material 3 for XR adapts TopAppBar / NavigationRail into these).
 * A rounded-full or pill capsule of icon buttons. ONE orbiter per panel.
 *
 * Pass `items` as [{icon, label, onClick, active}] or supply `children`.
 */
export function Orbiter({ items = [], orientation = "vertical", children, style = {}, ...rest }) {
  const vertical = orientation === "vertical";
  return (
    <div
      style={{
        display: "inline-flex",
        flexDirection: vertical ? "column" : "row",
        gap: "var(--space-sm)",
        padding: "var(--space-sm)",
        background: "var(--glass-fill-strong)",
        WebkitBackdropFilter: "blur(var(--glass-blur))",
        backdropFilter: "blur(var(--glass-blur))",
        border: "1px solid var(--glass-border)",
        borderRadius: "var(--radius-full)",
        boxShadow: "var(--shadow-glass)",
        ...style,
      }}
      {...rest}
    >
      {children || items.map((it, i) => (
        <IconButton
          key={i}
          icon={it.icon}
          label={it.label}
          variant={it.active ? "filled" : "ghost"}
          onClick={it.onClick}
        />
      ))}
    </div>
  );
}
