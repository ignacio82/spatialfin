import * as React from "react";

export interface UpNextCardProps {
  title: string;
  subtitle?: string;
  /** Next item's still (landscape). */
  still?: string;
  /** Total countdown length in seconds. */
  seconds?: number;
  /** Seconds left — the ring fills as this drops; host owns the timer. */
  remaining?: number;
  onPlayNow?: () => void;
  onCancel?: () => void;
  style?: React.CSSProperties;
}

/** Autoplay "Up Next" card with countdown ring, shown over the player as content ends. */
export function UpNextCard(props: UpNextCardProps): React.ReactElement;
