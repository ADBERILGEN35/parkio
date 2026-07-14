import { PageShell, SkeletonBlock, SkeletonText } from '@parkio/ui';
import { useTranslation } from 'react-i18next';

/** Suspense fallback shown while a lazily-loaded route chunk is fetched. */
export function RouteFallback() {
  const { t } = useTranslation('common');
  return (
    <PageShell title={t('actions.loading')}>
      <div className="max-w-xl" role="status" aria-label={t('actions.loading')}>
        <SkeletonBlock className="h-28 w-full" rounded="2xl" />
        <SkeletonText lines={3} className="mt-md" />
      </div>
    </PageShell>
  );
}
