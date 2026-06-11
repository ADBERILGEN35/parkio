import { colors, spacing } from '../tokens';

export interface LoadingStateProps {
  label?: string;
}

export function LoadingState({ label = 'Loading…' }: LoadingStateProps) {
  return (
    <p style={{ margin: 0, color: colors.textMuted, padding: spacing.md }}>{label}</p>
  );
}
