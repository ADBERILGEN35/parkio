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
  className?: string;
}

/** KPI tile: tinted icon disc, uppercase label, display-size value (§2.4). */
export function MetricCard({ label, value, icon, trend, className }: MetricCardProps) {
  return (
    <div
      className={cn(
        'flex flex-col justify-between gap-md rounded-xl border border-outline-variant/20 bg-surface-container-lowest p-lg shadow-soft',
        className,
      )}
    >
      {icon || trend ? (
        <div className="flex items-start justify-between">
          {icon ? (
            <span className="inline-flex rounded-lg bg-primary-container p-sm text-on-primary-container">
              <Icon name={icon} />
            </span>
          ) : (
            <span />
          )}
          {trend}
        </div>
      ) : null}
      <div>
        <p className="m-0 mb-xs text-label-md uppercase tracking-wider text-on-surface-variant">
          {label}
        </p>
        <p className="m-0 text-headline-md text-on-surface">{value}</p>
      </div>
    </div>
  );
}
