import type { ReactNode } from 'react';
import { colors, spacing } from '../tokens';

export interface PageShellProps {
  title: string;
  children: ReactNode;
}

export function PageShell({ title, children }: PageShellProps) {
  return (
    <main
      style={{
        minHeight: '100vh',
        backgroundColor: colors.background,
        padding: spacing.lg,
        fontFamily: 'system-ui, -apple-system, sans-serif',
        color: colors.text,
      }}
    >
      <header style={{ marginBottom: spacing.lg }}>
        <h1 style={{ margin: 0, fontSize: '1.5rem' }}>{title}</h1>
      </header>
      {children}
    </main>
  );
}
