import React from "react";
import { Icon } from "./Icon.jsx";

/**
 * SpatialFin Button — Material 3 action used across XR, Beam and TV.
 * Pill-shaped (full radius) like the player action buttons and the phone
 * hero "Play". Variants map to M3 emphasis: filled > tonal > outlined > text.
 */
export function Button({
  children,
  variant = "filled",
  size = "md",
  icon = null,
  fullWidth = false,
  disabled = false,
  onClick,
  style = {},
  ...rest
}) {
  const sizes = {
    sm: { h: 36, px: 16, fs: 13, gap: 6, ic: 16 },
    md: { h: 44, px: 22, fs: 14, gap: 8, ic: 18 },
    lg: { h: 56, px: 28, fs: 16, gap: 10, ic: 22 },
  };
  const s = sizes[size] || sizes.md;

  const variants = {
    filled: {
      background: "var(--accent)",
      color: "var(--on-primary)",
      border: "1px solid transparent",
    },
    tonal: {
      background: "var(--accent-container)",
      color: "var(--on-accent-container)",
      border: "1px solid transparent",
    },
    outlined: {
      background: "transparent",
      color: "var(--accent)",
      border: "1px solid var(--border-strong)",
    },
    text: {
      background: "transparent",
      color: "var(--accent)",
      border: "1px solid transparent",
    },
    glass: {
      background: "var(--glass-fill-strong)",
      color: "var(--text-primary)",
      border: "1px solid var(--glass-border)",
      backdropFilter: "blur(var(--glass-blur))",
      WebkitBackdropFilter: "blur(var(--glass-blur))",
    },
  };
  const v = variants[variant] || variants.filled;

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      style={{
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        gap: s.gap,
        height: s.h,
        padding: `0 ${s.px}px`,
        width: fullWidth ? "100%" : "auto",
        fontFamily: "var(--font-sans)",
        fontSize: s.fs,
        fontWeight: 500,
        lineHeight: 1,
        letterSpacing: 0.1,
        borderRadius: "var(--radius-full)",
        cursor: disabled ? "not-allowed" : "pointer",
        opacity: disabled ? 0.38 : 1,
        transition: "background var(--duration-fast) var(--ease-standard), transform var(--duration-fast) var(--ease-standard), filter var(--duration-fast) var(--ease-standard)",
        whiteSpace: "nowrap",
        ...v,
        ...style,
      }}
      onMouseDown={(e) => { if (!disabled) e.currentTarget.style.transform = "scale(0.97)"; }}
      onMouseUp={(e) => { e.currentTarget.style.transform = "scale(1)"; }}
      onMouseLeave={(e) => { e.currentTarget.style.transform = "scale(1)"; e.currentTarget.style.filter = "none"; }}
      onMouseEnter={(e) => { if (!disabled) e.currentTarget.style.filter = "brightness(1.08)"; }}
      {...rest}
    >
      {icon ? <Icon name={icon} size={s.ic} /> : null}
      {children}
    </button>
  );
}
