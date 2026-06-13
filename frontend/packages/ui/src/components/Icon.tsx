import type { HTMLAttributes } from 'react';
import { cn } from '../cn';

export interface IconProps extends HTMLAttributes<HTMLSpanElement> {
  /** Material Symbols Outlined icon name (e.g. `verified`). */
  name: string;
  /** Render the filled (active) variant. */
  filled?: boolean;
}

/**
 * Material Symbols Outlined icon. Decorative by default (`aria-hidden`) —
 * pass `aria-hidden={false}` plus an `aria-label` for meaningful icons.
 */
export function Icon({ name, filled, className, ...props }: IconProps) {
  return (
    <span
      aria-hidden
      className={cn('material-symbols-outlined select-none', filled && 'filled', className)}
      {...props}
    >
      {name}
    </span>
  );
}
