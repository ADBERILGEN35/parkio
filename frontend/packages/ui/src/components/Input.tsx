import type { InputHTMLAttributes } from 'react';
import { colors, radius, spacing } from '../tokens';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
}

export function Input({ label, error, id, style, ...props }: InputProps) {
  const inputId = id ?? label?.toLowerCase().replace(/\s+/g, '-');
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: spacing.xs }}>
      {label ? (
        <span style={{ fontSize: '0.875rem', fontWeight: 500, color: colors.text }}>{label}</span>
      ) : null}
      <input
        id={inputId}
        style={{
          padding: spacing.sm,
          borderRadius: radius.md,
          border: `1px solid ${error ? colors.error : colors.border}`,
          fontSize: '1rem',
          ...style,
        }}
        {...props}
      />
      {error ? (
        <span style={{ fontSize: '0.75rem', color: colors.error }}>{error}</span>
      ) : null}
    </label>
  );
}
