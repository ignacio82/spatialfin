import * as React from "react";

export interface NavItem {
  id: string;
  icon: string;
  label: string;
}

export interface NavBarProps {
  items: NavItem[];
  active?: string;
  onChange?: (id: string) => void;
  style?: React.CSSProperties;
}

/** Phone (Beam) bottom navigation bar — M3 active pill + accent label. */
export function NavBar(props: NavBarProps): React.ReactElement;
