import type { ButtonHTMLAttributes } from 'react';
import { cn } from '../cn';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /**
   * Visual variant per the design-system button recipes:
   * - `primary` — solid Electric Blue pill (default CTA)
   * - `secondary` — tonal surface-container pill
   * - `outline` — bordered surface pill
   * - `ghost` — text-only
   * - `destructive` — solid error
   * - `destructive-soft` — tinted error
   */
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'destructive' | 'destructive-soft';
}

const VARIANT_CLASSES: Record<NonNullable<ButtonProps['variant']>, string> = {
  primary: 'bg-primary text-on-primary shadow-sm hover:bg-primary/90 hover:shadow-md',
  secondary: 'bg-surface-container text-on-surface hover:bg-surface-container-high',
  outline: 'border border-outline-variant bg-surface text-on-surface hover:bg-surface-container',
  ghost: 'text-on-surface-variant hover:bg-surface-container hover:text-on-surface',
  destructive: 'bg-error text-on-error shadow-sm hover:opacity-90',
  'destructive-soft': 'bg-error-container/50 text-error hover:bg-error-container',
};

export function Button({ variant = 'primary', className, children, ...props }: ButtonProps) {
  return (
    <button
      type="button"
      className={cn(
        'inline-flex items-center justify-center gap-sm rounded-full px-lg py-sm text-label-md',
        'transition-all duration-std motion-safe:active:scale-95',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-60 motion-safe:disabled:active:scale-100',
        VARIANT_CLASSES[variant],
        className,
      )}
      {...props}
    >
      {children}
    </button>
  );
}
