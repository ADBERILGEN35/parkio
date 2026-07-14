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
 *
 * Ligatures are case-sensitive. Parents often use Tailwind `uppercase` for
 * eyebrow labels; without neutralizing text-transform the glyph name paints
 * as visible UI (e.g. SETTINGS, ADD_LOCATION_ALT). Always force `normal-case`
 * and a lowercase ligature name.
 */
export function Icon({ name, filled, className, style, ...props }: IconProps) {
  const ligature = name.trim().toLowerCase();
  return (
    <span
      aria-hidden
      className={cn(
        'material-symbols-outlined select-none normal-case',
        filled && 'filled',
        className,
      )}
      style={{ textTransform: 'none', ...style }}
      {...props}
    >
      {ligature}
    </span>
  );
}
