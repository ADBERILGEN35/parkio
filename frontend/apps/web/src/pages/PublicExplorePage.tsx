import type { PublicExploreFacility } from '@parkio/types';
import { Icon } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { BrandMark } from '@/components/brand/BrandMark';
import { PublicExploreMap } from '@/components/explore/PublicExploreMap';
import { frontendConfig } from '@/config/env';

export function PublicExplorePage() {
  const { publicExploreApi } = useParkioSdk();
  const { t } = useTranslation('explore');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const query = useQuery({
    queryKey: ['public-explore', 'facilities'],
    queryFn: ({ signal }) => publicExploreApi.list(signal),
    enabled: frontendConfig.features.publicExplore,
    staleTime: 30_000,
    retry: false,
  });
  const facilities = useMemo(() => (query.data ?? []).slice(0, 20), [query.data]);
  const selected = facilities.find((facility) => facility.id === selectedId) ?? null;
  const unavailable = !frontendConfig.features.publicExplore || query.isError || (!query.isLoading && facilities.length === 0);

  return (
    <main className="min-h-screen bg-background text-on-background">
      <header className="border-b border-outline-variant/30 bg-surface-container-lowest">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-md py-md md:px-xl">
          <Link to="/explore" className="flex items-center gap-xs text-on-surface no-underline">
            <BrandMark size={32} />
            <span className="text-title-lg font-bold">Parkio</span>
          </Link>
          <Link to="/login" className="rounded-full px-md py-sm text-label-md font-semibold text-primary hover:bg-primary/10">
            {t('signIn')}
          </Link>
        </div>
      </header>

      <section className="mx-auto max-w-7xl px-md py-xl md:px-xl">
        <div className="mb-lg max-w-3xl">
          <div className="mb-sm flex flex-wrap gap-xs text-label-sm font-semibold uppercase tracking-wider">
            <span className="rounded-full bg-primary/10 px-sm py-xs text-primary">{t('liveBeta')}</span>
            <span className="rounded-full bg-secondary/10 px-sm py-xs text-secondary">{t('readOnly')}</span>
            <span className="rounded-full bg-surface-container px-sm py-xs text-on-surface-variant">{t('noAccount')}</span>
          </div>
          <h1 className="m-0 text-headline-lg-mobile text-on-surface md:text-headline-lg">{t('title')}</h1>
          <p className="m-0 mt-sm text-body-lg text-on-surface-variant">{t('description')}</p>
        </div>

        {query.isLoading && frontendConfig.features.publicExplore ? (
          <p role="status" className="rounded-2xl bg-surface-container px-lg py-md">{t('loading')}</p>
        ) : null}

        {unavailable ? (
          <div role="status" className="rounded-3xl border border-outline-variant/30 bg-surface-container-low p-lg">
            <h2 className="m-0 text-title-lg text-on-surface">{t('unavailableTitle')}</h2>
            <p className="m-0 mt-xs text-body-md text-on-surface-variant">{t('unavailableBody')}</p>
          </div>
        ) : null}

        {!query.isLoading && !unavailable ? (
          <div className="grid gap-lg lg:grid-cols-[minmax(0,1fr)_360px]">
            <PublicExploreMap facilities={facilities} selectedId={selectedId} onSelect={setSelectedId} />
            <aside aria-label={t('facilityPanel')} className="rounded-3xl bg-surface-container-lowest p-lg shadow-card">
              {selected ? <FacilityPanel facility={selected} /> : (
                <div className="flex h-full min-h-48 flex-col items-center justify-center text-center text-on-surface-variant">
                  <Icon name="touch_app" className="text-[32px] leading-none" />
                  <p className="m-0 mt-sm text-body-md">{t('selectFacility')}</p>
                </div>
              )}
            </aside>
          </div>
        ) : null}
      </section>
    </main>
  );
}

function FacilityPanel({ facility }: { facility: PublicExploreFacility }) {
  const { t } = useTranslation('explore');
  return (
    <div data-testid="public-explore-facility-panel">
      <p className="m-0 text-label-sm font-semibold uppercase tracking-wider text-secondary">{t('municipalFacility')}</p>
      <h2 className="m-0 mt-xs text-headline-sm text-on-surface">{facility.displayName || t('unnamed')}</h2>
      {facility.operatorName ? <p className="m-0 mt-xs text-body-md text-on-surface-variant">{facility.operatorName}</p> : null}
      {facility.addressText ? <p className="m-0 mt-md text-body-md text-on-surface">{facility.addressText}</p> : null}
      <dl className="mt-lg grid grid-cols-2 gap-md">
        <Metric label={t('available')} value={facility.availableSpaces ?? '—'} />
        <Metric label={t('capacity')} value={facility.capacityTotal ?? '—'} />
        <Metric label={t('freshness')} value={facility.availabilityFreshness} />
        <Metric label={t('type')} value={facility.facilityType.replaceAll('_', ' ')} />
      </dl>
      <div className="mt-lg border-t border-outline-variant/30 pt-md">
        <p className="m-0 text-label-md font-semibold text-on-surface">{facility.sourceLabel}</p>
        <p className="m-0 mt-xs text-label-sm text-on-surface-variant">{facility.attribution}</p>
      </div>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <dt className="text-label-sm text-on-surface-variant">{label}</dt>
      <dd className="m-0 mt-xs text-title-md font-semibold text-on-surface">{value}</dd>
    </div>
  );
}
