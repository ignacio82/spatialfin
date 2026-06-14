import * as React from "react";

export interface HeroBannerProps {
  title: string;
  kind?: string;
  /** Backdrop / landscape image URL. */
  backdrop?: string;
  /** Metadata strings rendered as chips. */
  meta?: string[];
  height?: number;
  /** Override the default Play + Details actions. */
  actions?: React.ReactNode;
  rounded?: boolean;
}

/** Featured spotlight banner — Home hero (phone) or detail banner (XR). */
export function HeroBanner(props: HeroBannerProps): React.ReactElement;
