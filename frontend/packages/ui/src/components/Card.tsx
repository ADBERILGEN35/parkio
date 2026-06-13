import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '../cn';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  title?: string;
  children: ReactNode;
}

/** Level-1 surface: white card, ambient soft shadow, hairline border. */
export function Card({ title, children, className, ...props }: CardProps) {
  return (
    <div
      className={cn(
        'rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-lg shadow-soft',
        className,
      )}
      {...props}
    >
      {title ? <h2 className="m-0 mb-md text-title-lg text-on-surface">{title}</h2> : null}
      {children}
    </div>
  );
}
