import type { ButtonHTMLAttributes } from 'react';
import { cn } from '../cn';
import { Icon } from './Icon';

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Material Symbols Outlined icon name. */
  icon: string;
  /** Accessible name — required, the icon alone is not announced. */
  'aria-label': string;
  /**
   * - `ghost` — transparent, tinted hover (default)
   * - `tonal` — surface-container disc
   * - `overlay` — translucent white disc for use over imagery
   */
  variant?: 'ghost' | 'tonal' | 'overlay';
  filled?: boolean;
}

const VARIANT_CLASSES: Record<NonNullable<IconButtonProps['variant']>, string> = {
  ghost: 'text-on-surface-variant hover:bg-surface-container hover:text-on-surface',
  tonal: 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high',
  overlay: 'bg-surface-container-lowest/90 text-on-surface shadow-sm backdrop-blur-md hover:bg-surface-container',
};

/** 40px circular icon button. */
export function IconButton({ icon, variant = 'ghost', filled, className, ...props }: IconButtonProps) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex h-10 w-10 items-center justify-center rounded-full',
        'transition-colors duration-std motion-safe:active:scale-95',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        'disabled:cursor-not-allowed disabled:opacity-60',
        VARIANT_CLASSES[variant],
        className,
      )}
      {...props}
    >
      <Icon name={icon} filled={filled} className="text-[20px] leading-none" />
    </button>
  );
}
