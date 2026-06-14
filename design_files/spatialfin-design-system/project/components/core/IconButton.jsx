import React from "react";
import { Icon } from "./Icon.jsx";

/**
 * IconButton — a circular, icon-only control. Used for orbiter controls (XR),
 * top-bar actions (Beam), and player chrome. `tonal` is the default emphasis;
 * `glass` matches the floating XR orbiter look.
 */
export function IconButton({
  icon,
  variant = "tonal",
  size = "md",
  disabled = false,
  label,
  onClick,
  style = {},
  ...rest
}) {
  const dims = { sm: 36, md: 44, lg: 56 };
  const ic = { sm: 18, md: 22, lg: 26 };
  const d = dims[size] || dims.md;

  const variants = {
    tonal: { background: "var(--surface-container-high)", color: "var(--text-primary)", border: "1px solid transparent" },
    filled: { background: "var(--accent)", color: "var(--on-primary)", border: "1px solid transparent" },
    ghost: { background: "transparent", color: "var(--text-secondary)", border: "1px solid transparent" },
    glass: {
      background: "var(--glass-fill-strong)", color: "var(--text-primary)",
      border: "1px solid var(--glass-border)",
      backdropFilter: "blur(var(--glass-blur))", WebkitBackdropFilter: "blur(var(--glass-blur))",
    },
    outlined: { background: "transparent", color: "var(--text-primary)", border: "1px solid var(--border-strong)" },
  };
  const v = variants[variant] || variants.tonal;

  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      style={{
        display: "inline-flex", alignItems: "center", justifyContent: "center",
        width: d, height: d, borderRadius: "var(--radius-full)",
        cursor: disabled ? "not-allowed" : "pointer", opacity: disabled ? 0.38 : 1,
        transition: "background var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard)",
        ...v, ...style,
      }}
      onMouseEnter={(e) => { if (!disabled) e.currentTarget.style.filter = "brightness(1.12)"; }}
      onMouseLeave={(e) => { e.currentTarget.style.filter = "none"; e.currentTarget.style.transform = "scale(1)"; }}
      onMouseDown={(e) => { if (!disabled) e.currentTarget.style.transform = "scale(0.94)"; }}
      onMouseUp={(e) => { e.currentTarget.style.transform = "scale(1)"; }}
      {...rest}
    >
      <Icon name={icon} size={ic[size] || ic.md} />
    </button>
  );
}
