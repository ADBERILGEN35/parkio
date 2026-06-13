import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '../cn';
import { Icon } from './Icon';

export type BadgeTone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral';

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  /** Solid container badge tone. */
  tone?: BadgeTone;
  /** Optional leading Material Symbols icon. */
  icon?: string;
  children: ReactNode;
}

const TONE_CLASSES: Record<BadgeTone, string> = {
  primary: 'bg-primary-container text-on-primary-container',
  success: 'bg-secondary-container text-on-secondary-container',
  warning: 'bg-tertiary-container text-on-tertiary-container',
  danger: 'bg-error-container text-on-error-container',
  neutral: 'bg-surface-variant text-on-surface-variant',
};

/** Solid pill badge (container colors). For the tinted 10% recipe use {@link SoftBadge}. */
export function Badge({ tone = 'neutral', icon, className, children, ...props }: BadgeProps) {
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
