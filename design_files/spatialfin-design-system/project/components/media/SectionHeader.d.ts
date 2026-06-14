import * as React from "react";

export interface SectionHeaderProps {
  title: string;
  /** Trailing action element (e.g. a "See all" button). */
  action?: React.ReactNode;
  size?: "large" | "medium";
  style?: React.CSSProperties;
}

/** Shelf/row title with the signature leading accent bar. */
export function SectionHeader(props: SectionHeaderProps): React.ReactElement;
