import * as React from "react";

export interface EpisodeCardProps {
  number: number | string;
  title: string;
  /** Episode still (landscape 16:9). */
  still?: string;
  runtime?: string;
  synopsis?: string;
  progress?: number | null;
  width?: number;
  onClick?: () => void;
  focusFirst?: boolean;
  style?: React.CSSProperties;
}

/** Focusable landscape episode tile for TV season rows. */
export function EpisodeCard(props: EpisodeCardProps): React.ReactElement;
