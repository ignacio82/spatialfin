import * as React from "react";

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  tone?: "accent" | "neutral" | "error" | "success" | "overlay";
  /** Lucide icon name. */
  icon?: string | null;
  /** Render a bare status dot instead of a capsule. */
  dot?: boolean;
  children?: React.ReactNode;
}

/** Small status marker overlaid on media — downloaded, downloading, 4K, watched. */
export function Badge(props: BadgeProps): React.ReactElement;
