import type { ReactNode } from 'react';
import { cn } from '../cn';
import { Icon } from './Icon';

export interface EmptyStateProps {
  title: string;
  description?: string;
  /** Material Symbols icon shown in the disc (default `inbox`). */
  icon?: string;
  /** Optional action (e.g. a Button). */
  action?: ReactNode;
  className?: string;
}

/** Centered empty state: tinted icon disc, headline, muted copy (§2.6). */
export function EmptyState({ title, description, icon = 'inbox', action, className }: EmptyStateProps) {
  return (
    <div className={cn('flex flex-col items-center justify-center py-xl text-center', className)}>
      <div className="mb-lg flex h-24 w-24 items-center justify-center rounded-full bg-surface-container-high">
        <Icon name={icon} className="text-[48px] leading-none text-primary" />
      </div>
      <h3 className="m-0 mb-sm text-headline-md text-on-surface">{title}</h3>
      {description ? (
        <p className="m-0 max-w-sm text-body-md text-on-surface-variant">{description}</p>
      ) : null}
      {action ? <div className="mt-lg">{action}</div> : null}
    </div>
  );
}
