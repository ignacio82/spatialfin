import * as React from "react";

export interface VoiceFeedbackProps {
  state?: "listening" | "processing" | "answered" | "error";
  /** Override the default state caption (e.g. the live transcript or AI reply). */
  text?: string;
  style?: React.CSSProperties;
}

/** Assistant state indicator. Top-center on Beam; spatial overlay in XR. */
export function VoiceFeedback(props: VoiceFeedbackProps): React.ReactElement;
