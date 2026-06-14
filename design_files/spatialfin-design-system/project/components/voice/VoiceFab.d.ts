import * as React from "react";

export interface VoiceFabProps {
  state?: "idle" | "listening" | "processing";
  onClick?: () => void;
  size?: number;
  style?: React.CSSProperties;
}

/** Primary voice-assistant FAB (Beam phone). Pulses while listening. */
export function VoiceFab(props: VoiceFabProps): React.ReactElement;
