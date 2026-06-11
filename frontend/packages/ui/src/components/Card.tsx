import type { HTMLAttributes, ReactNode } from 'react';
import { colors, radius, spacing } from '../tokens';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  title?: string;
  children: ReactNode;
}

export function Card({ title, children, style, ...props }: CardProps) {
  return (
    <div
      style={{
        backgroundColor: colors.surface,
        border: `1px solid ${colors.border}`,
        borderRadius: radius.lg,
        padding: spacing.lg,
        ...style,
      }}
      {...props}
    >
      {title ? (
        <h2 style={{ margin: `0 0 ${spacing.md}`, fontSize: '1.125rem', color: colors.text }}>
          {title}
        </h2>
      ) : null}
      {children}
    </div>
  );
}
