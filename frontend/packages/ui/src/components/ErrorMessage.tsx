import { colors, radius, spacing } from '../tokens';

export interface ErrorMessageProps {
  message: string;
  traceId?: string;
  code?: string;
}

export function ErrorMessage({ message, traceId, code }: ErrorMessageProps) {
  return (
    <div
      role="alert"
      style={{
        padding: spacing.md,
        borderRadius: radius.md,
        backgroundColor: colors.errorBg,
        border: `1px solid ${colors.error}`,
        color: colors.error,
        fontSize: '0.875rem',
      }}
    >
      <p style={{ margin: 0 }}>{message}</p>
      {code ? (
        <p style={{ margin: `${spacing.xs} 0 0`, fontSize: '0.75rem', opacity: 0.85 }}>
          Code: {code}
        </p>
      ) : null}
      {traceId ? (
        <p style={{ margin: `${spacing.xs} 0 0`, fontSize: '0.75rem', opacity: 0.85 }}>
          Trace: {traceId}
        </p>
      ) : null}
    </div>
  );
}
