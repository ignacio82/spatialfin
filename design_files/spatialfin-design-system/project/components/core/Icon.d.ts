import * as React from "react";

export interface IconProps {
  /** Lucide icon name, e.g. "play", "settings", "mic". */
  name: string;
  size?: number;
  strokeWidth?: number;
  color?: string;
  style?: React.CSSProperties;
}

/** Lucide line icon rendered as inline SVG. SpatialFin's icon system. */
export function Icon(props: IconProps): React.ReactElement;
