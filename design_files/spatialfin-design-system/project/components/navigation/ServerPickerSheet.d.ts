import * as React from "react";

export interface ServerEntry {
  id: string;
  name: string;
  address: string;
  currentUser?: string;
}

export interface ServerPickerSheetProps {
  open?: boolean;
  servers?: ServerEntry[];
  currentId?: string;
  onPick?: (id: string) => void;
  onManage?: () => void;
  onDismiss?: () => void;
  style?: React.CSSProperties;
}

/** Server switcher modal — Jellyfin servers list + Manage. */
export function ServerPickerSheet(props: ServerPickerSheetProps): React.ReactElement | null;
