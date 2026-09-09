import { Icon, MapSearchSkeleton } from '@parkio/ui';
import { haversineMeters } from '@parkio/geo';
import { useQuery } from '@tanstack/react-query';
import { lazy, Suspense, useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useRequireAuth } from '@/components/auth/useRequireAuth';
import { BrandMark } from '@/components/brand/BrandMark';
import { SelectedMunicipalFacilityPreview } from '@/components/map/SelectedMunicipalFacilityPreview';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  LOCATED_ZOOM,
  type LatLng,
} from '@/components/map/mapConfig';
import { acquireBrowserPosition } from '@/components/parking/acquireBrowserPosition';
import { frontendConfig } from '@/config/env';
import { toMunicipalFacilityFromPublicExplore } from '@/lib/publicExploreFacilityAdapter';

const NearbySpotsMap = lazy(() =>
  import('@/components/map/NearbySpotsMap').then((m) => ({ default: m.NearbySpotsMap })),
);

/** Certified R6A anonymous caps — never exceed on the client. */
const PUBLIC_EXPLORE_LIMIT = 6;
const PUBLIC_EXPLORE_RADIUS_METERS = 5_000;

/**
 * Anonymous public product surface — same MapLibre product map as authenticated
 * {@link MapPage}, IZUM-only public data, detail/actions via AuthGate.
 *
 * Distance ownership: only {@link userLocation} (successful browser geolocation)
 * may drive preview distance. {@link discoveryOrigin} is for API scope/map framing
 * and must never be treated as the visitor's position.
 */
export function PublicExplorePage() {
  const { publicExploreApi } = useParkioSdk();
  const { t } = useTranslation(['explore', 'navigation', 'map']);
  const { requireAuth, authGate } = useRequireAuth();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [mapCenter, setMapCenter] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [mapZoom, setMapZoom] = useState(DEFAULT_MAP_ZOOM);
  /** API discovery / map framing origin (defaults to Konak; not user position). */
  const [discoveryOrigin, setDiscoveryOrigin] = useState<LatLng>(DEFAULT_MAP_CENTER);
  /** Real browser geolocation only — null until locate succeeds. */
  const [userLocation, setUserLocation] = useState<LatLng | null>(null);
  const [locating, setLocating] = useState(false);
  const [locationFeedback, setLocationFeedback] = useState<string | null>(null);

  const query = useQuery({
    queryKey: [
      'public-explore',
      'facilities',
      discoveryOrigin.lat,
      discoveryOrigin.lng,
      PUBLIC_EXPLORE_RADIUS_METERS,
      PUBLIC_EXPLORE_LIMIT,
    ],
    queryFn: ({ signal }) =>
      publicExploreApi.list({
        lat: discoveryOrigin.lat,
        lng: discoveryOrigin.lng,
        radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
        limit: PUBLIC_EXPLORE_LIMIT,
        signal,
      }),
    enabled: frontendConfig.features.publicExplore,
    staleTime: 30_000,
    retry: false,
  });

  const municipalFacilities = useMemo(
    () => (query.data?.facilities ?? []).slice(0, PUBLIC_EXPLORE_LIMIT).map(
      toMunicipalFacilityFromPublicExplore,
    ),
    [query.data],
  );
  const municipalHiddenCount = query.data?.municipalHiddenCount ?? 0;
  const communitySpotCountInScope = query.data?.communitySpotCountInScope ?? null;
  const selected =
    municipalFacilities.find((facility) => facility.id === selectedId) ?? null;
  const selectedDistanceMeters =
    selected && userLocation
      ? haversineMeters(userLocation, {
          lat: selected.latitude,
          lng: selected.longitude,
        })
      : null;

  const flagOff = !frontendConfig.features.publicExplore;
  const hardUnavailable = flagOff || query.isError;
  const showMap = frontendConfig.features.publicExplore && !query.isError;

  const locate = useCallback(async () => {
    setLocating(true);
    setLocationFeedback(null);
    const result = await acquireBrowserPosition({
      enableHighAccuracy: false,
      timeout: 10_000,
      maximumAge: 0,
    });
    setLocating(false);
    if (!result.ok) {
      if (result.reason === 'denied') {
        setLocationFeedback(t('map:geoDenied'));
      } else {
        setLocationFeedback(t('map:geoUnavailable'));
      }
      return;
    }
    const next: LatLng = {
      lat: Number(result.latitude.toFixed(6)),
      lng: Number(result.longitude.toFixed(6)),
    };
    setUserLocation(next);
    setMapCenter(next);
    setMapZoom(LOCATED_ZOOM);
    setDiscoveryOrigin(next);
    setSelectedId(null);
  }, [t]);

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
        {hardUnavailable ? (
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

        {showMap ? (
          <>
            <div className="pointer-events-none absolute inset-x-0 top-0 z-30 flex flex-col items-stretch gap-sm p-md md:items-start">
              <div className="pointer-events-auto flex flex-wrap items-center gap-sm">
                {municipalHiddenCount > 0 ? (
                  <button
                    type="button"
                    data-testid="municipal-hidden-teaser"
                    onClick={() => {
                      requireAuth('/map', 'municipalMore');
                    }}
                    className="inline-flex items-center gap-xs rounded-full bg-secondary/15 px-md py-sm text-label-md font-semibold text-secondary shadow-sm ring-1 ring-secondary/20 transition-colors hover:bg-secondary/25 focus:outline-none focus-visible:ring-4 focus-visible:ring-secondary/30"
                  >
                    <Icon name="garage" className="text-[18px] leading-none" />
                    {t('explore:municipalMore', { count: municipalHiddenCount })}
                  </button>
                ) : null}
                {communitySpotCountInScope != null && communitySpotCountInScope >= 3 ? (
                  <button
                    type="button"
                    data-testid="community-aggregate-teaser"
                    onClick={() => {
                      requireAuth('/map', 'community');
                    }}
                    className="inline-flex items-center gap-xs rounded-full bg-tertiary/15 px-md py-sm text-label-md font-semibold text-tertiary shadow-sm ring-1 ring-tertiary/20 transition-colors hover:bg-tertiary/25 focus:outline-none focus-visible:ring-4 focus-visible:ring-tertiary/30"
                  >
                    <Icon name="groups" className="text-[18px] leading-none" />
                    {t('explore:communityCount', { count: communitySpotCountInScope })}
                  </button>
                ) : null}
              </div>
              {locationFeedback ? (
                <p
                  role="status"
                  data-testid="public-explore-location-feedback"
                  className="pointer-events-none m-0 max-w-md rounded-2xl bg-surface-container-lowest/95 px-md py-sm text-label-sm text-on-surface-variant shadow-sm"
                >
                  {locationFeedback}
                </p>
              ) : null}
              {!query.isLoading && municipalFacilities.length === 0 ? (
                <p
                  role="status"
                  data-testid="public-explore-empty"
                  className="pointer-events-none m-0 max-w-md rounded-2xl bg-surface-container-lowest/95 px-md py-sm text-label-sm text-on-surface-variant shadow-sm"
                >
                  {t('explore:emptyMunicipal')}
                </p>
              ) : null}
            </div>

            <Suspense fallback={<MapSearchSkeleton />}>
              <NearbySpotsMap
                center={mapCenter}
                zoom={mapZoom}
                spots={[]}
                municipalFacilities={municipalFacilities}
                onPickCenter={() => undefined}
                selectedId={null}
                selectedMunicipalId={selectedId}
                onSelectMunicipalFacility={setSelectedId}
                height="100%"
                showFloatingControls={selectedId === null}
                onLocate={() => {
                  void locate();
                }}
                locating={locating}
                ariaLabel={t('explore:mapAria')}
                ariaDescription={t('explore:mapDescription')}
              />
            </Suspense>

            {selected ? (
              <div className="pointer-events-none absolute inset-x-0 bottom-0 z-40 p-md pb-[max(1rem,env(safe-area-inset-bottom))] md:inset-x-auto md:bottom-md md:right-md md:w-[380px] md:p-0">
                <SelectedMunicipalFacilityPreview
                  facility={selected}
                  distanceMeters={selectedDistanceMeters}
                  parkHereEnabled={false}
                  onClose={() => setSelectedId(null)}
                  onViewDetails={() => {
                    requireAuth(`/facilities/${selected.id}`, 'facilityDetail');
                  }}
                />
              </div>
            ) : null}
          </>
        ) : null}
      </div>

      {authGate}
    </div>
  );
}
