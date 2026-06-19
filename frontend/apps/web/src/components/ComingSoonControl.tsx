import { Icon, cn } from '@parkio/ui';
import type { ReactNode } from 'react';

interface ComingSoonControlProps {
  children: ReactNode;
  icon?: string;
  explanation: string;
  className?: string;
}

export function ComingSoonControl({
  children,
  icon,
  explanation,
  className,
}: ComingSoonControlProps) {
  return (
    <button
      type="button"
      disabled
      title={explanation}
      aria-label={`${children} is coming soon. ${explanation}`}
      className={cn(
        'inline-flex cursor-not-allowed items-center justify-center gap-xs rounded-full border border-outline-variant bg-surface-container px-sm py-xs text-label-md font-semibold text-on-surface-variant opacity-75',
        className,
      )}
    >
      {icon ? <Icon name={icon} className="text-[16px] leading-none" /> : null}
      <span>{children}</span>
      <span className="text-label-sm font-medium">(coming soon)</span>
    </button>
  );
}
