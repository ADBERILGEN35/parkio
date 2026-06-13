import { forwardRef, type InputHTMLAttributes } from 'react';
import { cn } from '../cn';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

/**
 * V2 design-system text input: near-borderless field on a `surface` background
 * with a soft shadow; focus swaps the hairline ring for a 2px primary ring.
 */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, error, id, className, ...props },
  ref,
) {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-');
  return (
    <label className="flex flex-col gap-xs">
      {label ? (
        <span className="text-label-sm font-medium text-on-surface-variant">{label}</span>
      ) : null}
      <input
        ref={ref}
        id={inputId}
        className={cn(
          'w-full rounded-lg border-0 bg-surface px-md py-sm text-body-md text-on-surface shadow-sm',
          'placeholder:text-outline transition-shadow duration-std',
          'focus:outline-none focus:ring-2',
          error ? 'ring-1 ring-error focus:ring-error' : 'ring-1 ring-outline-variant/40 focus:ring-primary',
          className,
        )}
        {...props}
      />
      {error ? <span className="text-label-sm text-error">{error}</span> : null}
    </label>
  );
});
