import * as React from "react";

export interface ProgressBarProps {
  /** 0–100. */
  value?: number;
  height?: number;
  style?: React.CSSProperties;
}

/** Thin watched-progress / scrubber bar with accent fill. */
export function ProgressBar(props: ProgressBarProps): React.ReactElement;
