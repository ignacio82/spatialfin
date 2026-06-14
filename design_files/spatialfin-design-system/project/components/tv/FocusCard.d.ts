import * as React from "react";

export interface FocusCardProps {
  title?: string;
  subtitle?: string;
  /** Artwork URL — portrait 2:3 or landscape 16:9 per `orientation`. */
  image?: string;
  orientation?: "portrait" | "landscape";
  width?: number;
  /** Watched progress 0–100. */
  progress?: number | null;
  /** A <Badge> element for the top-left corner. */
  badge?: React.ReactNode;
  onClick?: () => void;
  /** Receive initial D-pad focus when the screen mounts. */
  focusFirst?: boolean;
  style?: React.CSSProperties;
}

/** Focusable 10-foot media tile. Focus = scale + cyan ring (never shadow). Mark with data-focusable. */
export function FocusCard(props: FocusCardProps): React.ReactElement;
