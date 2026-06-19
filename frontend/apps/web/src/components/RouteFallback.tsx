import { PageShell, SkeletonBlock, SkeletonText } from '@parkio/ui';

/** Suspense fallback shown while a lazily-loaded route chunk is fetched. */
export function RouteFallback() {
  return (
    <PageShell title="Loading…">
      <div className="max-w-xl" role="status" aria-label="Loading route">
        <SkeletonBlock className="h-28 w-full" rounded="2xl" />
        <SkeletonText lines={3} className="mt-md" />
      </div>
    </PageShell>
  );
}
