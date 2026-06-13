export interface LoadingStateProps {
  label?: string;
}

/** Inline loading row: spinner + muted label. */
export function LoadingState({ label = 'Loading…' }: LoadingStateProps) {
  return (
    <p className="m-0 flex items-center gap-sm p-md text-body-md text-on-surface-variant">
      <span
        aria-hidden
        className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-outline-variant border-t-primary"
      />
      {label}
    </p>
  );
}
