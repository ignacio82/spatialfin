import * as React from "react";

export interface OrbiterItem {
  icon: string;
  label?: string;
  onClick?: () => void;
  active?: boolean;
}

export interface OrbiterProps extends React.HTMLAttributes<HTMLDivElement> {
  items?: OrbiterItem[];
  orientation?: "vertical" | "horizontal";
  children?: React.ReactNode;
}

/** Floating glass control cluster beside an XR spatial panel. One per panel. */
export function Orbiter(props: OrbiterProps): React.ReactElement;
