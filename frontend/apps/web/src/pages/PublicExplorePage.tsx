import { SoftBadge, MapSearchSkeleton } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { lazy, Suspense, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useRequireAuth } from '@/components/auth/useRequireAuth';
import { BrandMark } from '@/components/brand/BrandMark';
import { SelectedMunicipalFacilityPreview } from '@/components/map/SelectedMunicipalFacilityPreview';
import { DEFAULT_MAP_CENTER, DEFAULT_MAP_ZOOM } from '@/components/map/mapConfig';
import { frontendConfig } from '@/config/env';
import { toMunicipalFacilityFromPublicExplore } from '@/lib/publicExploreFacilityAdapter';

const NearbySpotsMap = lazy(() =>
  import('@/components/map/NearbySpotsMap').then((m) => ({ default: m.NearbySpotsMap })),
);

/**
 * Anonymous public product surface — same MapLibre product map as authenticated
 * {@link MapPage}, IZUM-only public data, detail/actions via AuthGate.
 */
export function PublicExplorePage() {
  const { publicExploreApi } = useParkioSdk();
  const { t } = useTranslation(['explore', 'navigation', 'map']);
  const { requireAuth, authGate } = useRequireAuth();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const query = useQuery({
    queryKey: ['public-explore', 'facilities'],
    queryFn: ({ signal }) => publicExploreApi.list(signal),
    enabled: frontendConfig.features.publicExplore,
    staleTime: 30_000,
    retry: false,
  });

  const municipalFacilities = useMemo(
    () => (query.data?.facilities ?? []).map(toMunicipalFacilityFromPublicExplore),
    [query.data],
  );
  const selected =
    municipalFacilities.find((facility) => facility.id === selectedId) ?? null;
  const unavailable =
    !frontendConfig.features.publicExplore ||
    query.isError ||
    (!query.isLoading && municipalFacilities.length === 0);

  return (
    <div
      className="relative h-dvh overflow-hidden bg-background text-on-background"
      data-testid="public-explore-product"
    >
      <h1 className="sr-only">{t('explore:title')}</h1>
      {/* Product-family top chrome (AppShell DesktopNav visual language). */}
      <header className="absolute inset-x-0 top-0 z-50 h-16 border-b border-outline-variant/20 bg-surface/70 shadow-sm backdrop-blur-xl">
        <div className="mx-auto flex h-full max-w-7xl items-center justify-between gap-sm px-md">
          <Link
            to="/explore"
            className="flex shrink-0 items-center gap-xs text-headline-md font-bold text-primary no-underline"
            aria-label={t('navigation:homeAria')}
          >
            <BrandMark size={28} className="select-none" />
            {t('navigation:brand')}
          </Link>
          <div className="flex items-center gap-sm">
            <SoftBadge tone="neutral" className="hidden sm:inline-flex">
              {t('explore:readOnly')}
            </SoftBadge>
            <Link
              to="/login"
              className="rounded-full px-md py-sm text-label-md font-semibold text-primary hover:bg-primary/10"
            >
              {t('explore:signIn')}
            </Link>
          </div>
        </div>
      </header>

      <div className="absolute inset-x-0 bottom-0 top-16 z-0 overflow-hidden">
        {query.isLoading && frontendConfig.features.publicExplore ? (
          <div className="flex h-full items-center justify-center p-lg">
            <p role="status" className="rounded-2xl bg-surface-container px-lg py-md">
              {t('explore:loading')}
            </p>
          </div>
        ) : null}

        {unavailable && !query.isLoading ? (
          <div className="flex h-full items-center justify-center p-lg">
            <div
              role="status"
              className="max-w-lg rounded-3xl border border-outline-variant/30 bg-surface-container-low p-lg"
            >
              <h2 className="m-0 text-title-lg text-on-surface">{t('explore:unavailableTitle')}</h2>
              <p className="m-0 mt-xs text-body-md text-on-surface-variant">
                {t('explore:unavailableBody')}
              </p>
            </div>
          </div>
        ) : null}

        {!query.isLoading && !unavailable ? (
          <Suspense fallback={<MapSearchSkeleton />}>
            <NearbySpotsMap
              center={DEFAULT_MAP_CENTER}
              zoom={DEFAULT_MAP_ZOOM}
              spots={[]}
              municipalFacilities={municipalFacilities}
              onPickCenter={() => undefined}
              selectedId={null}
              selectedMunicipalId={selectedId}
              onSelectMunicipalFacility={setSelectedId}
              height="100%"
              showFloatingControls={selectedId === null}
              ariaLabel={t('explore:mapAria')}
              ariaDescription={t('explore:mapDescription')}
            />
          </Suspense>
        ) : null}

        {selected ? (
          <div className="pointer-events-none absolute inset-x-0 bottom-0 z-40 p-md pb-[max(1rem,env(safe-area-inset-bottom))] md:inset-x-auto md:bottom-md md:right-md md:w-[380px] md:p-0">
            <SelectedMunicipalFacilityPreview
              facility={selected}
              parkHereEnabled={false}
              onClose={() => setSelectedId(null)}
              onViewDetails={() => {
                requireAuth(`/facilities/${selected.id}`);
              }}
            />
          </div>
        ) : null}
      </div>

      {authGate}
    </div>
  );
}
