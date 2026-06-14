import * as React from "react";

export interface PosterCardProps {
  title: string;
  /** Secondary line, e.g. "Movie · 26% watched". */
  subtitle?: string;
  /** Poster image URL (portrait 2:3). */
  poster?: string;
  /** Watched progress 0–100; renders a bar over the art. */
  progress?: number | null;
  /** A <Badge> element for the top-right corner (downloaded, 4K…). */
  badge?: React.ReactNode;
  width?: number;
  showFooter?: boolean;
  onClick?: () => void;
}

/** Portrait media tile for shelves & grids. */
export function PosterCard(props: PosterCardProps): React.ReactElement;
