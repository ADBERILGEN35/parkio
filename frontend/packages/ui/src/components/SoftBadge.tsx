import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '../cn';
import { Icon } from './Icon';
import type { BadgeTone } from './Badge';

export interface SoftBadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  /** Optional leading Material Symbols icon. */
  icon?: string;
  children: ReactNode;
}

const TONE_CLASSES: Record<BadgeTone, string> = {
  primary: 'bg-primary/10 text-primary',
  success: 'bg-secondary/10 text-secondary',
  warning: 'bg-tertiary-container/20 text-tertiary',
  danger: 'bg-error/10 text-error',
  neutral: 'bg-on-surface-variant/10 text-on-surface-variant',
};

/**
 * Canonical soft status badge: 10–20% tinted background with solid status
 * text (DESIGN_SYSTEM.md §2.6).
 */
export function SoftBadge({ tone = 'neutral', icon, className, children, ...props }: SoftBadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-xs rounded-full px-sm py-xs text-label-sm font-semibold',
        TONE_CLASSES[tone],
        className,
      )}
      {...props}
    >
      {icon ? <Icon name={icon} className="text-[14px] leading-none" /> : null}
      {children}
    </span>
  );
}
