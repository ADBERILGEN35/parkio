import type { HTMLAttributes } from 'react';
import { cn } from '../cn';
import { getSpotStatusVisual } from '../status';
import { Icon } from './Icon';

export interface StatusBadgeProps extends HTMLAttributes<HTMLSpanElement> {
  /** Spot status (`ACTIVE`/`VERIFIED`/`SUSPICIOUS`/`FILLED`/`EXPIRED`/`REJECTED`). */
  status: string;
  /** Localized label; falls back to the English default from `status.ts`. */
  label?: string;
  /** Hide the leading icon. */
  hideIcon?: boolean;
}

/** Spot-status pill using the central status → visual mapping. */
export function StatusBadge({ status, label, hideIcon, className, ...props }: StatusBadgeProps) {
  const visual = getSpotStatusVisual(status);
  return (
    <span
      className={cn(
        'inline-flex items-center gap-xs rounded-full px-sm py-xs text-label-sm font-semibold',
        visual.className,
        className,
      )}
      {...props}
    >
      {hideIcon ? null : <Icon name={visual.icon} className="text-[14px] leading-none" />}
      {label ?? visual.label}
    </span>
  );
}
