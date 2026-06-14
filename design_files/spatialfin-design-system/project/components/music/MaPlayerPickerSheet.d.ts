import * as React from "react";

export interface MaPlayer {
  id: string;
  name: string;
  provider: string;
  isPlaying?: boolean;
  supportsGrouping?: boolean;
  syncedToPlayerId?: string | null;
  groupMemberIds?: string[];
  canGroupWith?: string[];
}

export interface MaPlayerPickerSheetProps {
  open?: boolean;
  players?: MaPlayer[];
  /** null = "Auto (this device)". */
  selectedId?: string | null;
  onPick?: (id: string | null) => void;
  onToggleGroupMember?: (leaderId: string, memberId: string, currentlyGrouped: boolean) => void;
  onDismiss?: () => void;
  style?: React.CSSProperties;
}

/** SendSpin "Play on" picker — speaker chooser + multi-room sync groups. */
export function MaPlayerPickerSheet(props: MaPlayerPickerSheetProps): React.ReactElement | null;
