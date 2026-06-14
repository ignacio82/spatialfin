import * as React from "react";

export interface SeasonTabsProps {
  /** Season numbers, e.g. [1, 2, 3]. */
  seasons: (number | string)[];
  active?: number | string;
  onChange?: (season: number | string) => void;
  style?: React.CSSProperties;
}

/** Focusable season selector — pill tabs for a TV show. */
export function SeasonTabs(props: SeasonTabsProps): React.ReactElement;
