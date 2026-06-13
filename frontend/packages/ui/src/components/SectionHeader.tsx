import type { ReactNode } from 'react';
import { cn } from '../cn';
import { Icon } from './Icon';

export interface SectionHeaderProps {
  title: string;
  /** Muted one-line description under the title. */
  description?: string;
  /** Material Symbols icon shown before the title (primary tinted). */
  icon?: string;
  /** Right-aligned actions (buttons, links). */
  actions?: ReactNode;
  className?: string;
}

/** Section heading row: title-lg + optional icon, description and actions. */
export function SectionHeader({ title, description, icon, actions, className }: SectionHeaderProps) {
  return (
    <div className={cn('mb-md flex flex-wrap items-start justify-between gap-sm', className)}>
      <div className="min-w-0">
        <h2 className="m-0 flex items-center gap-sm text-title-lg text-on-surface">
          {icon ? <Icon name={icon} className="text-primary" /> : null}
          {title}
        </h2>
        {description ? (
          <p className="m-0 mt-xs text-body-md text-on-surface-variant">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-sm">{actions}</div> : null}
    </div>
  );
}
