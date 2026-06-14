import * as React from "react";

export interface TvNavItem {
  id: string;
  icon: string;
  label: string;
}

export interface TvNavRailProps {
  items: TvNavItem[];
  active?: string;
  onChange?: (id: string) => void;
  /** Two-letter profile initials shown at the top. */
  profile?: string;
  style?: React.CSSProperties;
}

/** Left navigation rail for the 10-foot UI — collapses to icons, expands on focus. */
export function TvNavRail(props: TvNavRailProps): React.ReactElement;
