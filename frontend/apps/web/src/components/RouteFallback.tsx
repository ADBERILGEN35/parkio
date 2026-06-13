import { LoadingState, PageShell } from '@parkio/ui';

/** Suspense fallback shown while a lazily-loaded route chunk is fetched. */
export function RouteFallback() {
  return (
    <PageShell title="Loading…">
      <LoadingState label="Loading…" />
    </PageShell>
  );
}
