import * as React from "react";

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  /** Visual emphasis. filled = primary CTA; tonal = secondary; glass = floating on passthrough. */
  variant?: "filled" | "tonal" | "outlined" | "text" | "glass";
  size?: "sm" | "md" | "lg";
  /** Lucide icon name for a leading glyph (e.g. "play", "download"). */
  icon?: string | null;
  fullWidth?: boolean;
  disabled?: boolean;
  children?: React.ReactNode;
}

/** Pill-shaped Material 3 action button used across XR, Beam and TV. */
export function Button(props: ButtonProps): React.ReactElement;
