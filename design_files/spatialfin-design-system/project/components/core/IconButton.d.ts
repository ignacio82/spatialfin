import * as React from "react";

export interface IconButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  /** Lucide icon name. */
  icon: string;
  variant?: "tonal" | "filled" | "ghost" | "glass" | "outlined";
  size?: "sm" | "md" | "lg";
  /** Accessible label (also the tooltip). */
  label?: string;
  disabled?: boolean;
}

/** Circular icon-only control — orbiter buttons (XR), top-bar actions (Beam), player chrome. */
export function IconButton(props: IconButtonProps): React.ReactElement;
