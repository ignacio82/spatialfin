import * as React from "react";

export interface PillProps extends React.HTMLAttributes<HTMLSpanElement> {
  tone?: "chip" | "neutral" | "accent" | "outline" | "rating";
  /** Lucide icon name for a leading glyph. */
  icon?: string | null;
  children?: React.ReactNode;
}

/** Non-focusable rounded metadata chip (year, runtime, rating, codec). Mirrors MetadataPill.kt. */
export function Pill(props: PillProps): React.ReactElement;
