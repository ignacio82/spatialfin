import * as React from "react";

export interface GlassPanelProps extends React.HTMLAttributes<HTMLDivElement> {
  /** "panel" (62%, over passthrough/video) or "strong" (88%, dense controls/dialogs). */
  tone?: "panel" | "strong";
  radius?: "md" | "lg" | "full";
  padding?: "none" | "sm" | "md" | "lg";
  children?: React.ReactNode;
}

/** Translucent floating surface — the signature XR spatial-panel look. */
export function GlassPanel(props: GlassPanelProps): React.ReactElement;
