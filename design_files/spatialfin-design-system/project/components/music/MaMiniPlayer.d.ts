import * as React from "react";

export interface MaTrack {
  title?: string;
  artist?: string;
  artwork?: string;
}

export interface MaMiniPlayerProps {
  /** The currently-loaded MA track, or null to hide. */
  track?: MaTrack | null;
  phase?: "preparing" | "playing" | "paused" | "idle";
  /** Name of the selected SendSpin player, e.g. "Living Room". */
  selectedPlayer?: string | null;
  onPlayPause?: () => void;
  onNext?: () => void;
  onStop?: () => void;
  onExpand?: () => void;
  style?: React.CSSProperties;
}

/** Persistent Music Assistant playback bar — works on Beam, XR and TV. */
export function MaMiniPlayer(props: MaMiniPlayerProps): React.ReactElement | null;
