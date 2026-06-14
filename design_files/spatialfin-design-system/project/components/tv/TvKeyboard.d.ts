import * as React from "react";

export interface TvKeyboardProps {
  /** Current query string. */
  value?: string;
  onChange?: (next: string) => void;
  style?: React.CSSProperties;
}

/** On-screen QWERTY-grid keyboard for 10-foot Search. Every key is data-focusable. */
export function TvKeyboard(props: TvKeyboardProps): React.ReactElement;
