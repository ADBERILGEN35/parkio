import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '../cn';

export interface SurfaceProps extends HTMLAttributes<HTMLDivElement> {
  /**
   * Elevation level per the design system:
   * - `flat` — tonal panel, no shadow (surface-container-low)
   * - `card` — Level 1: white + ambient soft shadow
   * - `raised` — Level 2: white + deep ambient shadow
   * - `glass` — translucent blurred panel (over maps/imagery)
   */
  level?: 'flat' | 'card' | 'raised' | 'glass';
  children: ReactNode;
}

const LEVEL_CLASSES: Record<NonNullable<SurfaceProps['level']>, string> = {
  flat: 'bg-surface-container-low',
  card: 'bg-surface-container-lowest shadow-soft border border-outline-variant/20',
  raised: 'bg-surface-container-lowest shadow-deep border border-outline-variant/20',
  glass: 'glass-panel shadow-soft',
};

/** Generic elevation container (tonal layers + ambient shadows, not borders). */
export function Surface({ level = 'card', className, children, ...props }: SurfaceProps) {
  return (
    <div className={cn('rounded-2xl', LEVEL_CLASSES[level], className)} {...props}>
      {children}
    </div>
  );
}
