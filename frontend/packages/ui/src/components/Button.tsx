import type { ButtonHTMLAttributes } from 'react';
import { colors, radius, spacing } from '../tokens';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary';
}

export function Button({ variant = 'primary', style, children, ...props }: ButtonProps) {
  const isPrimary = variant === 'primary';
  return (
    <button
      type="button"
      style={{
        padding: `${spacing.sm} ${spacing.md}`,
        borderRadius: radius.md,
        border: isPrimary ? 'none' : `1px solid ${colors.border}`,
        backgroundColor: isPrimary ? colors.primary : colors.surface,
        color: isPrimary ? colors.onPrimary : colors.text,
        fontWeight: 500,
        cursor: props.disabled ? 'not-allowed' : 'pointer',
        opacity: props.disabled ? 0.6 : 1,
        ...style,
      }}
      {...props}
    >
      {children}
    </button>
  );
}
