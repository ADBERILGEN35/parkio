import { SkeletonBlock } from './Skeleton';

export interface LoadingStateProps {
  label?: string;
}

/** Inline loading placeholder: no spinner, no layout-jarring content swap. */
export function LoadingState({ label = 'Loading…' }: LoadingStateProps) {
  return (
    <div className="flex flex-col gap-sm p-md" role="status" aria-label={label}>
      <span className="sr-only">{label}</span>
      <SkeletonBlock className="h-4 w-40" rounded="full" />
      <SkeletonBlock className="h-3 w-28" rounded="full" />
    </div>
  );
}
