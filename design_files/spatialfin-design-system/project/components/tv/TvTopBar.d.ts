import * as React from "react";

export interface TvTopBarProps {
  serverName?: string;
  /** Optional logo image URL shown next to the server name. */
  logoSrc?: string | null;
  /** Current Jellyfin user shown in the right-side avatar chip. */
  user?: {
    name: string;
    initials?: string;
    /** Jellyfin profile-image URL when available. */
    avatar?: string | null;
    color?: string;
    textColor?: string;
  } | null;
  isLoading?: boolean;
  isError?: boolean;
  onServerClick?: () => void;
  onErrorClick?: () => void;
  onRetryClick?: () => void;
  onSearchClick?: () => void;
  onSettingsClick?: () => void;
  onUserClick?: () => void;
  onCloseClick?: () => void;
  style?: React.CSSProperties;
}

/** TV home top header — server switcher + user switcher + Search · Settings · Close. */
export function TvTopBar(props: TvTopBarProps): React.ReactElement;
