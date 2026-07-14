import type { ReactNode } from 'react';
import { cn } from '../cn';
import { Icon } from './Icon';

export interface MetricCardProps {
  /** Metric caption, rendered uppercase (e.g. "Total points"). */
  label: string;
  /** The metric value (large number/text). */
  value: ReactNode;
  /** Optional Material Symbols icon shown in a tinted disc. */
  icon?: string;
  /** Optional trailing slot (trend chip, helper text). */
  trend?: ReactNode;
  /** Compact padding/typography for dense mobile grids (e.g. leaderboard). */
  dense?: boolean;
  className?: string;
}

/** KPI tile: tinted icon disc, uppercase label, display-size value (§2.4). */
export function MetricCard({ label, value, icon, trend, dense, className }: MetricCardProps) {
  return (
    <div
      className={cn(
        'flex min-w-0 flex-col justify-between rounded-xl border border-outline-variant/20 bg-surface-container-lowest shadow-soft',
        dense ? 'gap-sm p-sm' : 'gap-md p-lg',
        className,
      )}
    >
      {icon || trend ? (
        <div className="flex items-start justify-between">
          {icon ? (
            <span
              className={cn(
                'inline-flex rounded-lg bg-primary-container text-on-primary-container',
                dense ? 'p-1.5' : 'p-sm',
              )}
            >
              <Icon name={icon} className={dense ? 'text-[18px] leading-none' : undefined} />
            </span>
          ) : (
            <span />
          )}
          {trend}
        </div>
      ) : null}
      <div className="min-w-0">
        <p
          className={cn(
            'm-0 mb-xs uppercase tracking-wider text-on-surface-variant',
            dense ? 'truncate text-label-sm' : 'text-label-md',
          )}
        >
          {label}
        </p>
        <p className={cn('m-0 text-on-surface', dense ? 'text-title-lg' : 'text-headline-md')}>
          {value}
        </p>
      </div>
    </div>
  );
}
