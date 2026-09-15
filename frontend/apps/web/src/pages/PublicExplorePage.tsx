import { Icon, MapSearchSkeleton } from '@parkio/ui';
import { haversineMeters, isValidLatLng } from '@parkio/geo';
import { useQuery } from '@tanstack/react-query';
import { lazy, Suspense, useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import { useRequireAuth } from '@/components/auth/useRequireAuth';
import { BrandMark } from '@/components/brand/BrandMark';
import { PlaceSearch } from '@/components/map/PlaceSearch';
import { SelectedMunicipalFacilityPreview } from '@/components/map/SelectedMunicipalFacilityPreview';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  DEFAULT_PICKER_ZOOM,
  LOCATED_ZOOM,
  type LatLng,
} from '@/components/map/mapConfig';
import { acquireBrowserPosition } from '@/components/parking/acquireBrowserPosition';
import { frontendConfig } from '@/config/env';
import { GEOCODING_RESULT_LIMIT, type GeocodeResult } from '@/lib/geocoding';
import { toMunicipalFacilityFromPublicExplore } from '@/lib/publicExploreFacilityAdapter';
import { PUBLIC_AUTOCOMPLETE_DEBOUNCE_MS } from '@/lib/usePlaceAutocomplete';

const NearbySpotsMap = lazy(() =>
  import('@/components/map/NearbySpotsMap').then((m) => ({ default: m.NearbySpotsMap })),
);

/** Certified R6A anonymous caps — never exceed on the client. */
const PUBLIC_EXPLORE_LIMIT = 6;
const PUBLIC_EXPLORE_RADIUS_METERS = 5_000;

/** Destination framing: enough context for nearby municipal markers without over-zoom. */
const DESTINATION_ZOOM = DEFAULT_PICKER_ZOOM;

interface SelectedDestination {
  id: string;
  label: string;
  lat: number;
  lng: number;
}

/**
 * Anonymous public product surface — same MapLibre product map as authenticated
 * {@link MapPage}, IZUM-only public data, detail/actions via AuthGate.
 *
 * Distance ownership: only {@link userLocation} (successful browser geolocation)
 * may drive preview distance. {@link discoveryOrigin} is for API scope/map framing
 * and must never be treated as the visitor's position.
 * {@link selectedDestination} is the searched place and must never become userLocation.
 *
 * Locate rule: pressing locate clears selectedDestination and rediscovers around user.
 *
 * Marker semantics (preserved): municipal = green parking pin; community blue P only
 * when authenticated (public payload is aggregate-only — no anonymous precise pins).
 *
 * Count invariant: displayed visible count == renderable green marker count.
 */
export function PublicExplorePage() {
  const { publicExploreApi, publicGeocodingApi } = useParkioSdk();
  const { t } = useTranslation(['explore', 'navigation', 'map']);
  const navigate = useNavigate();
  const { requireAuth, authGate } = useRequireAuth();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [mapCenter, setMapCenter] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [mapZoom, setMapZoom] = useState(DEFAULT_MAP_ZOOM);
  /** API discovery / map framing origin (defaults to Konak; not user position). */
  const [discoveryOrigin, setDiscoveryOrigin] = useState<LatLng>(DEFAULT_MAP_CENTER);
  /** Real browser geolocation only — null until locate succeeds. */
  const [userLocation, setUserLocation] = useState<LatLng | null>(null);
  /** Searched destination — independent of userLocation. */
  const [selectedDestination, setSelectedDestination] = useState<SelectedDestination | null>(
    null,
  );
  /** Remount search when locate clears destination so the field resets. */
  const [searchResetKey, setSearchResetKey] = useState(0);
  const [locating, setLocating] = useState(false);
  const [locationFeedback, setLocationFeedback] = useState<string | null>(null);
  /** Autocomplete open — hide competing discovery chrome (presentation only). */
  const [searchInteractionActive, setSearchInteractionActive] = useState(false);

  const publicSearchFn = useCallback(
    (query: string, signal: AbortSignal) =>
      publicGeocodingApi.searchPlaces(query, GEOCODING_RESULT_LIMIT, signal),
    [publicGeocodingApi],
  );

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

  /** Only facilities with usable coordinates may count or render as green pins. */
  const municipalFacilities = useMemo(
    () =>
      (query.data?.facilities ?? [])
        .slice(0, PUBLIC_EXPLORE_LIMIT)
        .map(toMunicipalFacilityFromPublicExplore)
        .filter((facility) => isValidLatLng(facility.latitude, facility.longitude)),
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

  const destinationMarker = useMemo(
    () =>
      selectedDestination
        ? {
            latitude: selectedDestination.lat,
            longitude: selectedDestination.lng,
            label: selectedDestination.label,
          }
        : null,
    [selectedDestination],
  );

  const discoveryFramePoints = useMemo(
    () =>
      municipalFacilities.map((facility) => ({
        lat: facility.latitude,
        lng: facility.longitude,
      })),
    [municipalFacilities],
  );

  const discoveryFrameRevision = useMemo(
    () =>
      `${discoveryOrigin.lat.toFixed(6)},${discoveryOrigin.lng.toFixed(6)}:${municipalFacilities
        .map((facility) => facility.id)
        .join(',')}`,
    [discoveryOrigin.lat, discoveryOrigin.lng, municipalFacilities],
  );

  const flagOff = !frontendConfig.features.publicExplore;
  const hardUnavailable = flagOff || query.isError;
  const showMap = frontendConfig.features.publicExplore && !query.isError;
  const discoverySettled = !query.isLoading && !query.isFetching;

  const openContributeGate = useCallback(() => {
    if (requireAuth('/upload', 'contribute')) {
      navigate('/upload');
    }
  }, [navigate, requireAuth]);

  const clearDestination = useCallback(() => {
    setSelectedDestination(null);
    setSelectedId(null);
    if (userLocation) {
      setDiscoveryOrigin(userLocation);
      setMapCenter(userLocation);
      setMapZoom(LOCATED_ZOOM);
    } else {
      setDiscoveryOrigin(DEFAULT_MAP_CENTER);
      setMapCenter(DEFAULT_MAP_CENTER);
      setMapZoom(DEFAULT_MAP_ZOOM);
    }
  }, [userLocation]);

  const selectDestination = useCallback((result: GeocodeResult) => {
    const next: SelectedDestination = {
      id: result.id,
      label: result.primary,
      lat: result.lat,
      lng: result.lng,
    };
    // Never assign destination coordinates to userLocation.
    setSelectedDestination(next);
    setDiscoveryOrigin({ lat: next.lat, lng: next.lng });
    setMapCenter({ lat: next.lat, lng: next.lng });
    setMapZoom(DESTINATION_ZOOM);
    setSelectedId(null);
  }, []);

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
    // Locate means "parking around me" — clear any active destination.
    setSelectedDestination(null);
    setSearchResetKey((key) => key + 1);
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
            <div className="pointer-events-none absolute inset-x-0 top-0 z-30 flex flex-col items-stretch gap-sm px-md pt-md md:max-w-[440px] md:items-start">
              <div
                data-testid="public-explore-destination-search"
                className="pointer-events-auto w-full max-w-full rounded-2xl bg-surface-container-lowest/95 p-sm shadow-sm ring-1 ring-outline-variant/25 backdrop-blur-sm md:w-[400px]"
              >
                <PlaceSearch
                  key={searchResetKey}
                  compact
                  label={t('explore:destinationSearchLabel')}
                  placeholder={t('explore:destinationSearchPlaceholder')}
                  searchFn={publicSearchFn}
                  debounceMs={PUBLIC_AUTOCOMPLETE_DEBOUNCE_MS}
                  retry={false}
                  showClear
                  clearLabel={t('explore:destinationSearchClear')}
                  onSelect={selectDestination}
                  onClear={clearDestination}
                  onInteractionChange={setSearchInteractionActive}
                />
              </div>

              {/* Discovery chrome — suppressed while autocomplete is actively open. */}
              {!searchInteractionActive ? (
                <>
              {/* Coherent discovery stack — visible count + optional membership teasers. */}
              {discoverySettled && municipalFacilities.length > 0 ? (
                <div
                  data-testid="public-explore-discovery-summary"
                  className="pointer-events-auto inline-flex max-w-full flex-col gap-1.5"
                >
                  <div className="inline-flex max-w-full items-center gap-1.5 rounded-2xl bg-surface-container-lowest/95 px-md py-sm text-label-md text-on-surface shadow-sm ring-1 ring-outline-variant/25 backdrop-blur-sm">
                    <Icon
                      name="garage"
                      className="shrink-0 text-[18px] leading-none text-secondary"
                    />
                    <span className="min-w-0 truncate font-medium">
                      {t('explore:municipalVisible', { count: municipalFacilities.length })}
                    </span>
                    {municipalHiddenCount > 0 ? (
                      <>
                        <span
                          className="shrink-0 text-on-surface-variant/70"
                          aria-hidden="true"
                        >
                          •
                        </span>
                        <button
                          type="button"
                          data-testid="municipal-hidden-teaser"
                          onClick={() => {
                            requireAuth('/map', 'municipalMore');
                          }}
                          className="shrink-0 rounded-full px-1.5 py-0.5 font-semibold text-secondary transition-colors hover:bg-secondary/15 focus:outline-none focus-visible:ring-4 focus-visible:ring-secondary/30"
                        >
                          {t('explore:municipalMore', { count: municipalHiddenCount })}
                        </button>
                      </>
                    ) : null}
                  </div>
                  {communitySpotCountInScope != null && communitySpotCountInScope >= 3 ? (
                    <button
                      type="button"
                      data-testid="community-aggregate-teaser"
                      onClick={() => {
                        requireAuth('/map', 'community');
                      }}
                      className="inline-flex max-w-full items-center gap-xs self-start rounded-2xl bg-surface-container-lowest/95 px-md py-sm text-label-md font-medium text-tertiary shadow-sm ring-1 ring-outline-variant/25 backdrop-blur-sm transition-colors hover:bg-tertiary/10 focus:outline-none focus-visible:ring-4 focus-visible:ring-tertiary/30"
                    >
                      <Icon name="groups" className="shrink-0 text-[18px] leading-none" />
                      <span className="min-w-0 truncate">
                        {t('explore:communityCount', { count: communitySpotCountInScope })}
                      </span>
                    </button>
                  ) : null}
                </div>
              ) : null}
              {locationFeedback ? (
                <p
                  role="status"
                  data-testid="public-explore-location-feedback"
                  className="pointer-events-none m-0 max-w-md rounded-2xl bg-surface-container-lowest/95 px-md py-sm text-label-sm text-on-surface-variant shadow-sm"
                >
                  {locationFeedback}
                </p>
              ) : null}
              {discoverySettled && municipalFacilities.length === 0 ? (
                <p
                  role="status"
                  data-testid="public-explore-empty"
                  className="pointer-events-none m-0 max-w-md rounded-2xl bg-surface-container-lowest/95 px-md py-sm text-label-sm text-on-surface-variant shadow-sm"
                >
                  {t('explore:emptyMunicipal')}
                </p>
              ) : null}
              {/* Contribution discoverability — AuthGate only; registration stays CLOSED. */}
              <button
                type="button"
                data-testid="public-explore-contribute-cta"
                aria-label={t('explore:contributeCtaAria')}
                onClick={openContributeGate}
                className="pointer-events-auto inline-flex max-w-full items-center gap-xs self-start rounded-2xl bg-surface-container-lowest/95 px-md py-sm text-left text-primary shadow-sm ring-1 ring-outline-variant/25 backdrop-blur-sm transition-colors hover:bg-primary/10 focus:outline-none focus-visible:ring-4 focus-visible:ring-primary/30"
              >
                <Icon name="add_location_alt" className="shrink-0 text-[18px] leading-none" />
                <span className="min-w-0">
                  <span className="block truncate text-label-md font-semibold">
                    {t('explore:contributeCta')}
                  </span>
                  <span
                    data-testid="public-explore-contribute-support"
                    className="mt-0.5 block truncate text-label-sm font-medium text-on-surface-variant"
                  >
                    {t('explore:contributeSupport')}
                  </span>
                </span>
              </button>
                </>
              ) : null}
            </div>

            <Suspense fallback={<MapSearchSkeleton />}>
              <NearbySpotsMap
                center={mapCenter}
                zoom={mapZoom}
                spots={[]}
                municipalFacilities={municipalFacilities}
                destinationMarker={destinationMarker}
                discoveryFrame={{
                  anchor: discoveryOrigin,
                  points: discoveryFramePoints,
                  revision: discoveryFrameRevision,
                  enabled: discoverySettled && discoveryFramePoints.length > 0,
                }}
                onPickCenter={() => undefined}
                selectedId={null}
                selectedMunicipalId={selectedId}
                onSelectMunicipalFacility={setSelectedId}
                height="100%"
                showFloatingControls={selectedId === null}
                floatingControlsSidebarOpen={false}
                onLocate={() => {
                  void locate();
                }}
                locating={locating}
                ariaLabel={t('explore:mapAria')}
                ariaDescription={t('explore:mapDescription')}
              />
            </Suspense>

            {selected ? (
              <div className="pointer-events-none absolute inset-x-0 bottom-0 z-40 p-md pb-[max(1rem,env(safe-area-inset-bottom))] md:inset-x-auto md:bottom-md md:left-md md:right-auto md:w-[380px] md:p-0">
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
