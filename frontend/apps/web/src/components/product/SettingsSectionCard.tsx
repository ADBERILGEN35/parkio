import type { ReactNode } from 'react';
import { Icon, Surface, cn } from '@parkio/ui';

export interface SettingsSectionCardProps {
  title: string;
  description?: string;
  icon?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}

export function SettingsSectionCard({
  title,
  description,
  icon,
  action,
  children,
  className,
}: SettingsSectionCardProps) {
  return (
    <Surface level="card" className={cn('p-lg', className)}>
      <div className="mb-md flex flex-wrap items-start justify-between gap-sm border-b border-outline-variant/20 pb-md">
        <div className="min-w-0">
          <h2 className="m-0 flex items-center gap-sm text-title-lg text-on-surface">
            {icon ? <Icon name={icon} className="text-[20px] leading-none text-primary" /> : null}
            {title}
          </h2>
          {description ? (
            <p className="m-0 mt-xs text-body-md text-on-surface-variant">{description}</p>
          ) : null}
        </div>
        {action ? <div className="shrink-0">{action}</div> : null}
      </div>
      {children}
    </Surface>
  );
}
