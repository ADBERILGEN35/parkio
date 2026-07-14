import { SkeletonBlock } from './Skeleton';

export interface LoadingStateProps {
  /** Localized accessible label. Prefer always passing from the app i18n layer. */
  label?: string;
}

/** Inline loading placeholder without an English default label. */
export function LoadingState({ label }: LoadingStateProps) {
  return (
    <div
      className="flex flex-col gap-sm p-md"
      role="status"
      aria-busy="true"
      aria-label={label}
    >
      {label ? <span className="sr-only">{label}</span> : null}
      <SkeletonBlock className="h-4 w-40" rounded="full" />
      <SkeletonBlock className="h-3 w-28" rounded="full" />
    </div>
  );
}
