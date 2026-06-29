import { zodResolver } from '@hookform/resolvers/zod';
import type { NearbySearchParams } from '@parkio/types';
import {
  Button,
  ErrorMessage,
  Icon,
  Input,
  MapSearchSkeleton,
} from '@parkio/ui';
import { nearbySearchSchema, type NearbySearchFormValues } from '@parkio/validation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useSearchParams } from 'react-router-dom';
import { parkingApi, usersApi } from '@/api';
import { useAuthStore } from '@/auth/store';
import { BottomSheet, COLLAPSED_PEEK, type SheetState } from '@/components/map/BottomSheet';
import { DiscoveryResults } from '@/components/map/DiscoveryResults';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  LOCATED_ZOOM,
  isValidLatLng,
} from '@/components/map/mapConfig';
import { PlaceSearch } from '@/components/map/PlaceSearch';
import { SelectedSpotPreview } from '@/components/map/SelectedSpotPreview';
import { type GeocodeResult } from '@/lib/geocoding';
import { DESKTOP_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import {
  EMPTY_FILTERS,
  availableSorts,
  availableStatuses as deriveStatuses,
  defaultSort,
  filterSpots,
  sortSpots,
  withDistance,
  type SpotFilters,
  type SpotSort,
} from '@/lib/spotDiscovery';

const NearbySpotsMap = lazy(() =>
  import('@/components/map/NearbySpotsMap').then((m) => ({ default: m.NearbySpotsMap })),
);

type GeoStatus = 'idle' | 'locating' | 'error';

/** Parse a watched coordinate field; blank/non-finite values yield NaN (no center). */
function parseCoord(value: unknown): number {
  if (value === '' || value === null || value === undefined) return Number.NaN;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

function optionalNumber(value: unknown): number | undefined {
  if (value === '' || value === null || value === undefined) return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/**
 * Map Experience V4 (`/map`): a map-first product. Full-bleed map canvas with a
 * floating glass search overlay, floating map controls, and a discovery surface
 * that adapts by viewport — a slide-in results sidebar on desktop (`md+`) and a
 * draggable, snap-pointed {@link BottomSheet} on mobile/tablet.
 *
 * Selecting a marker (or a result's "Show on map") raises a Google-Maps-style
 * {@link SelectedSpotPreview}; selection is shared state across the map, the
 * preview, and the list. Discovery adds a result count, real-distance chips,
 * status/vehicle chips, presentation filters, and sort — all derived only from
 * fields the backend already returns (see `lib/spotDiscovery`); nothing about
 * ETA/popularity/confidence is fabricated.
 *
 * Primary search is address/place text → coordinates (forward geocoding via
 * Nominatim); the resolved coordinates feed the unchanged
 * `GET /parking/spots/nearby` call. Manual lat/lng (+ radius/limit),
 * click-to-set-center, and "Use my location" remain as an advanced fallback.
 */
export function MapPage() {
  const [searchParams] = useSearchParams();
  const smartReturnMode = searchParams.get('smartReturn') === '1';
  const [params, setParams] = useState<NearbySearchParams | null>(null);
  const [geoStatus, setGeoStatus] = useState<GeoStatus>('idle');
  const [geoError, setGeoError] = useState<string | null>(null);
  const [mapZoom, setMapZoom] = useState(DEFAULT_MAP_ZOOM);
  const [centerLabel, setCenterLabel] = useState<string | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [smartReturnBannerOpen, setSmartReturnBannerOpen] = useState(true);

  // Discovery state (selection is shared by map markers, the preview card, and
  // the result list; filters/sort are client-side presentation only).
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [filters, setFilters] = useState<SpotFilters>(EMPTY_FILTERS);
  const [sort, setSort] = useState<SpotSort | null>(null);
  const [sheetState, setSheetState] = useState<SheetState>('collapsed');

  const isDesktop = useMediaQuery(DESKTOP_QUERY);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  const search = useQuery({
    queryKey: ['parking', 'nearby', params],
    queryFn: () => parkingApi.getNearbySpots(params as NearbySearchParams),
    enabled: params !== null,
    // Keep the prior results and markers on screen while a re-search (new center,
    // radius, or "use my location") loads, instead of flashing back to the
    // skeleton — the map stays populated and feels instant. The first-ever search
    // still shows the skeleton (no previous data to hold).
    placeholderData: keepPreviousData,
    // Results are spatial snapshots; treat them as fresh for 30s so remounting the
    // route or refocusing the tab doesn't refetch an identical query.
    staleTime: 30_000,
  });

  const vehicleQuery = useQuery({
    queryKey: ['me', 'vehicle'],
    queryFn: usersApi.getMyVehicle,
    enabled: isAuthenticated,
    // The signed-in user's vehicle changes rarely; cache it across the session to
    // avoid refetching on every map mount (it only gates the "Fits your X" hint).
    staleTime: 5 * 60_000,
  });

  const smartReturnQuery = useQuery({
    queryKey: ['me', 'smart-return'],
    queryFn: usersApi.getSmartReturn,
    enabled: isAuthenticated && smartReturnMode,
    staleTime: 30_000,
  });

  // Distance is computed from the *real* searched center; no center ⇒ no distance.
  // Memoized so distance/sort recompute only when the data or center truly change.
  const searchCenter = useMemo(
    () => (params ? { lat: params.lat, lng: params.lng } : null),
    [params],
  );
  const spotsWithDistance = useMemo(
    () => withDistance(search.data ?? [], searchCenter),
    [search.data, searchCenter],
  );

  const statuses = useMemo(() => deriveStatuses(spotsWithDistance), [spotsWithDistance]);
  const hasSearchCenter = searchCenter !== null;
  const sortOptions = useMemo(() => availableSorts(hasSearchCenter), [hasSearchCenter]);
  const effectiveSort: SpotSort =
    sort && sortOptions.includes(sort) ? sort : defaultSort(hasSearchCenter);

  const visibleSpots = useMemo(
    () => sortSpots(filterSpots(spotsWithDistance, filters), effectiveSort),
    [spotsWithDistance, filters, effectiveSort],
  );

  // Selection is resolved from the *unfiltered* set so a selected spot survives
  // filter/sort changes even if it is filtered out of the visible list.
  const selectedSpot = useMemo(
    () => spotsWithDistance.find((spot) => spot.id === selectedId) ?? null,
    [spotsWithDistance, selectedId],
  );

  const selectSpot = useCallback(
    (id: string | null) => {
      setSelectedId(id);
      // On mobile the preview owns the bottom band; drop the sheet to its peek so
      // the two never fight for the same space (and the sheet handle stays visible
      // just below the preview). Desktop has dedicated space for both.
      if (id !== null && !isDesktop) setSheetState('collapsed');
    },
    [isDesktop],
  );

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    getValues,
    formState: { errors },
  } = useForm<NearbySearchFormValues>({ resolver: zodResolver(nearbySearchSchema) });

  const runSearch = useCallback(
    (values: NearbySearchParams) => {
      setParams(values);
      if (!isDesktop) setSheetState('half');
      setAdvancedOpen(false);
    },
    [isDesktop],
  );

  const onSubmit = handleSubmit((values) => runSearch(values));

  // Empty inputs must not read as 0,0 (Number('') === 0) — that opened the map
  // on empty ocean off the African coast.
  const latValue = parseCoord(watch('lat'));
  const lngValue = parseCoord(watch('lng'));
  const hasCenter = isValidLatLng(latValue, lngValue);
  // Never open on empty ocean: fall back to the İzmir beta center until the user
  // locates/picks coordinates (this fallback is shown only — not auto-searched).
  const center = hasCenter ? { lat: latValue, lng: lngValue } : DEFAULT_MAP_CENTER;

  const applyCoords = useCallback((lat: number, lng: number) => {
    setValue('lat', Number(lat.toFixed(6)), { shouldValidate: true });
    setValue('lng', Number(lng.toFixed(6)), { shouldValidate: true });
  }, [setValue]);

  const currentOptionalSearchFields = useCallback(() => {
    const values = getValues();
    return {
      radius: optionalNumber(values.radius),
      limit: optionalNumber(values.limit),
    };
  }, [getValues]);

  /** Center on a geocoded place and run the existing nearby search there. */
  const selectPlace = (result: GeocodeResult) => {
    applyCoords(result.lat, result.lng);
    setMapZoom(LOCATED_ZOOM);
    setCenterLabel(result.secondary || result.primary);
    runSearch({ lat: result.lat, lng: result.lng, ...currentOptionalSearchFields() });
  };

  /** Map click / manual coordinate edits update the same center source of truth. */
  const handlePickCenter = (lat: number, lng: number) => {
    applyCoords(lat, lng);
    setCenterLabel('selected map point');
  };

  /**
   * Resolve the browser location. `autoSearch` is used by the on-mount attempt
   * to immediately run a nearby search; the manual "Use my location" button
   * only fills coordinates and lets the user press Search.
   */
  const runGeolocation = useCallback(
    ({ autoSearch }: { autoSearch: boolean }) => {
      const geolocation =
        typeof navigator !== 'undefined' ? navigator.geolocation : undefined;
      if (!geolocation) {
        setGeoStatus('error');
        setGeoError("Geolocation isn't available in this browser. You can search manually.");
        return;
      }
      setGeoStatus('locating');
      setGeoError(null);
      geolocation.getCurrentPosition(
        (position) => {
          const lat = Number(position.coords.latitude.toFixed(6));
          const lng = Number(position.coords.longitude.toFixed(6));
          setValue('lat', lat, { shouldValidate: true });
          setValue('lng', lng, { shouldValidate: true });
          setMapZoom(LOCATED_ZOOM);
          setCenterLabel('your current location');
          setGeoStatus('idle');
          if (autoSearch) {
            runSearch({ lat, lng, ...currentOptionalSearchFields() });
          }
        },
        (error) => {
          setGeoStatus('error');
          setGeoError(
            error.code === error.PERMISSION_DENIED
              ? 'Location permission was not granted. You can search manually.'
              : "We couldn't determine your location. You can search manually.",
          );
        },
        { enableHighAccuracy: false, timeout: 10_000 },
      );
    },
    [currentOptionalSearchFields, runSearch, setValue],
  );

  // Attempt geolocation exactly once per mount so the map opens on a useful view.
  const autoLocatedRef = useRef(false);
  useEffect(() => {
    if (smartReturnMode) return;
    if (autoLocatedRef.current) return;
    autoLocatedRef.current = true;
    runGeolocation({ autoSearch: true });
  }, [runGeolocation, smartReturnMode]);

  useEffect(() => {
    if (!smartReturnMode || !smartReturnQuery.data) return;
    const settings = smartReturnQuery.data;
    if (settings.homeLatitude === null || settings.homeLongitude === null) return;
    const lat = settings.homeLatitude;
    const lng = settings.homeLongitude;
    applyCoords(lat, lng);
    setMapZoom(LOCATED_ZOOM);
    setCenterLabel('your saved home area');
    runSearch({ lat, lng, radius: 1000 });
  }, [applyCoords, runSearch, smartReturnMode, smartReturnQuery.data]);

  const locate = () => runGeolocation({ autoSearch: false });

  const discovery = (
    <DiscoveryResults
      search={search}
      params={params}
      spots={visibleSpots}
      totalCount={spotsWithDistance.length}
      filters={filters}
      onFiltersChange={setFilters}
      availableStatuses={statuses}
      sort={effectiveSort}
      onSortChange={setSort}
      sortOptions={sortOptions}
      selectedId={selectedId}
      onSelect={selectSpot}
      userVehicleType={vehicleQuery.data?.vehicleType ?? null}
    />
  );

  const summaryText = resolveSummary(params, search, visibleSpots.length, spotsWithDistance.length);
  const advancedForm = (
    <form onSubmit={onSubmit}>
      <fieldset
        disabled={search.isFetching}
        className="m-0 flex flex-col gap-sm border-0 p-0"
      >
        <div className="grid grid-cols-2 gap-sm">
          <Input
            label="Latitude"
            inputMode="decimal"
            error={errors.lat?.message}
            {...register('lat')}
          />
          <Input
            label="Longitude"
            inputMode="decimal"
            error={errors.lng?.message}
            {...register('lng')}
          />
        </div>
        <div className="grid grid-cols-2 gap-sm">
          <Input
            label="Radius (m, default 1000)"
            inputMode="numeric"
            error={errors.radius?.message}
            {...register('radius')}
          />
          <Input
            label="Limit (default 10)"
            inputMode="numeric"
            error={errors.limit?.message}
            {...register('limit')}
          />
        </div>
        <Button type="submit" disabled={search.isFetching} className="w-full">
          <Icon name="travel_explore" className="text-[16px] leading-none" />
          {search.isFetching ? 'Searching...' : 'Search nearby'}
        </Button>
      </fieldset>
    </form>
  );

  return (
    <div className="fixed inset-x-0 bottom-16 top-16 z-0 overflow-hidden bg-background md:bottom-0">
      {/* Full-bleed map canvas */}
      <div className="absolute inset-0 z-0">
        <Suspense fallback={<MapSearchSkeleton />}>
          <NearbySpotsMap
            center={center}
            zoom={mapZoom}
            spots={search.data ?? []}
            onPickCenter={handlePickCenter}
            selectedId={selectedId}
            onSelectSpot={selectSpot}
            height="100%"
            onLocate={locate}
            locating={geoStatus === 'locating'}
            showFloatingControls={isDesktop || selectedId === null}
          />
        </Suspense>
      </div>

      {/* Floating search overlay */}
      {isDesktop ? (
        <div className="pointer-events-none absolute inset-x-0 top-md z-[1100] flex justify-start px-md pl-lg">
          <div className="pointer-events-auto w-full max-w-[min(28rem,calc(100vw-1rem))] animate-fade-in-up glass-panel rounded-2xl p-md shadow-deep">
            <h2 className="m-0 text-title-lg text-on-surface">Find parking</h2>
            <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
              Search by address or place, or click the map to set the center.
            </p>

            {centerLabel ? (
              <p className="mt-sm flex items-center gap-xs text-label-sm font-medium text-on-surface">
                <Icon name="location_on" className="text-[16px] leading-none text-primary" />
                Searching near {centerLabel}
              </p>
            ) : null}

            {smartReturnMode && smartReturnBannerOpen ? (
              <div className="m-0 mt-sm flex items-center gap-xs rounded-2xl bg-primary/10 px-md py-sm text-label-sm font-medium text-primary">
                <Icon name="home_pin" className="text-[16px] leading-none" />
                <span className="flex-1">Showing parking near your saved home.</span>
                <button
                  type="button"
                  aria-label="Dismiss Smart Return notice"
                  onClick={() => setSmartReturnBannerOpen(false)}
                  className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-primary hover:bg-primary/10 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <Icon name="close" className="text-[16px] leading-none" />
                </button>
              </div>
            ) : null}

            {geoStatus === 'error' && geoError ? (
              <div className="mt-sm">
                <ErrorMessage message={geoError} />
              </div>
            ) : null}

            <div className="mt-md">
              <PlaceSearch onSelect={selectPlace} />
            </div>

            <button
              type="button"
              onClick={locate}
              disabled={geoStatus === 'locating'}
              className="mt-sm inline-flex items-center gap-xs rounded-full bg-surface-container px-md py-xs text-label-sm font-medium text-on-surface transition-colors hover:bg-surface-container-high disabled:opacity-60"
            >
              <Icon name="my_location" className="text-[16px] leading-none" />
              {geoStatus === 'locating' ? 'Locating...' : 'Use my location'}
            </button>

            <details className="mt-sm border-t border-outline-variant/30 pt-sm">
              <summary className="cursor-pointer list-none text-label-sm font-semibold text-on-surface-variant marker:content-none">
                <span className="inline-flex items-center gap-xs">
                  <Icon name="tune" className="text-[16px] leading-none" />
                  Advanced coordinates
                </span>
              </summary>
              <div className="mt-sm">{advancedForm}</div>
            </details>
          </div>
        </div>
      ) : (
        <div className="pointer-events-none absolute inset-x-0 top-sm z-[1100] px-sm">
          <div className="pointer-events-auto mx-auto flex max-w-[430px] items-center gap-xs rounded-full border border-outline-variant/30 bg-surface/90 p-xs shadow-deep backdrop-blur-xl">
            <div className="min-w-0 flex-1">
              <PlaceSearch
                compact
                placeholder="Search Parkio"
                onSelect={selectPlace}
              />
            </div>
            <button
              type="button"
              aria-label="Use my location"
              onClick={locate}
              disabled={geoStatus === 'locating'}
              className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-surface-container text-primary transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60"
            >
              <Icon name={geoStatus === 'locating' ? 'progress_activity' : 'my_location'} className="text-[20px] leading-none" />
            </button>
            <button
              type="button"
              aria-label="Filters and search options"
              aria-expanded={advancedOpen}
              onClick={() => {
                setAdvancedOpen((open) => !open);
                if (params) setSheetState('half');
              }}
              className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-surface-container text-on-surface transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            >
              <Icon name="tune" className="text-[20px] leading-none" />
            </button>
          </div>

          {centerLabel ? (
            <p className="pointer-events-auto mx-auto mt-xs flex max-w-[430px] items-center gap-xs rounded-full bg-surface/85 px-md py-xs text-label-sm font-medium text-on-surface shadow-soft backdrop-blur-xl">
              <Icon name="location_on" className="text-[14px] leading-none text-primary" />
              <span className="truncate">Near {centerLabel}</span>
            </p>
          ) : null}

          {smartReturnMode && smartReturnBannerOpen ? (
            <div className="pointer-events-auto mx-auto mt-xs flex max-w-[430px] items-center gap-xs rounded-full bg-primary/10 px-md py-xs text-label-sm font-medium text-primary shadow-soft backdrop-blur-xl">
              <Icon name="home_pin" className="text-[14px] leading-none" />
              <span className="flex-1 truncate">Showing parking near your saved home.</span>
              <button
                type="button"
                aria-label="Dismiss Smart Return notice"
                onClick={() => setSmartReturnBannerOpen(false)}
                className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-primary hover:bg-primary/15 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                <Icon name="close" className="text-[14px] leading-none" />
              </button>
            </div>
          ) : null}

          {geoStatus === 'error' && geoError ? (
            <div className="pointer-events-auto mx-auto mt-xs max-w-[430px]">
              <ErrorMessage message={geoError} />
            </div>
          ) : null}

          {advancedOpen ? (
            <div className="pointer-events-auto mx-auto mt-xs max-w-[430px] animate-fade-in-up rounded-2xl glass-panel p-md shadow-deep">
              <div className="mb-sm flex items-start justify-between gap-sm">
                <div>
                  <h2 className="m-0 text-title-lg text-on-surface">Search options</h2>
                  <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
                    Coordinate search and result filters use only current backend fields.
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="Close search options"
                  onClick={() => setAdvancedOpen(false)}
                  className="-mr-xs -mt-xs flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <Icon name="close" className="text-[18px] leading-none" />
                </button>
              </div>
              <details open>
                <summary className="cursor-pointer list-none text-label-sm font-semibold text-on-surface-variant marker:content-none">
                  <span className="inline-flex items-center gap-xs">
                    <Icon name="travel_explore" className="text-[16px] leading-none" />
                    Advanced coordinates
                  </span>
                </summary>
                <div className="mt-sm">{advancedForm}</div>
              </details>
              {spotsWithDistance.length > 0 ? (
                <button
                  type="button"
                  onClick={() => {
                    setSheetState('half');
                    setAdvancedOpen(false);
                  }}
                  className="mt-sm inline-flex w-full items-center justify-center gap-xs rounded-full bg-surface-container px-md py-sm text-label-md font-semibold text-on-surface transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <Icon name="filter_alt" className="text-[16px] leading-none" />
                  Show {spotsWithDistance.length} result
                  {spotsWithDistance.length === 1 ? '' : 's'} &amp; filters
                </button>
              ) : (
                // No results yet ⇒ filters would be a dead-end. Point back at search
                // instead of offering a control that opens an empty filter list.
                <p className="mt-sm flex items-center gap-xs rounded-2xl bg-surface-container px-md py-sm text-label-sm text-on-surface-variant">
                  <Icon name="search" className="text-[16px] leading-none text-primary" />
                  {params
                    ? 'No spots matched here yet — try a wider radius or a new place above.'
                    : 'Search a place above to see parking and result filters.'}
                </p>
              )}
            </div>
          ) : null}
        </div>
      )}

      {/* Selected-spot preview (Google Maps / Uber style).
          Desktop: floats bottom-left over the map, clear of the right-hand results
          sidebar. Mobile: anchored just above the bottom-sheet peek — and only while
          the sheet is collapsed, so an expanded/half sheet (which the user opened to
          browse results) is never obscured by the preview. selectSpot() collapses the
          sheet on selection, so the preview is visible by default. The offset is
          derived from the exported COLLAPSED_PEEK + the device safe-area inset rather
          than a hardcoded magic number, so it tracks the real peek height. */}
      {selectedSpot ? (
        isDesktop ? (
          <div className="pointer-events-none absolute bottom-md left-md z-[1060] w-[360px]">
            <SelectedSpotPreview spot={selectedSpot} onClose={() => selectSpot(null)} />
          </div>
        ) : sheetState === 'collapsed' ? (
          <div
            className="pointer-events-none absolute inset-x-0 z-[1060] px-sm"
            style={{ bottom: `calc(${COLLAPSED_PEEK}px + env(safe-area-inset-bottom) + 0.5rem)` }}
          >
            <SelectedSpotPreview spot={selectedSpot} onClose={() => selectSpot(null)} />
          </div>
        ) : null
      ) : null}

      {/* Results — desktop sidebar vs mobile draggable bottom sheet. Exactly one
          mounts (media-query driven) so the discovery panel renders once. */}
      {isDesktop ? (
        <aside
          aria-label="Search results"
          className="pointer-events-none absolute bottom-0 right-0 top-0 z-[1050] flex w-[400px] flex-col"
        >
          <div className="pointer-events-auto flex h-full min-h-0 flex-col gap-sm overflow-y-auto glass-panel p-md shadow-sheet-left animate-slide-in-right rounded-l-[2rem] hide-scrollbar">
            {discovery}
          </div>
        </aside>
      ) : (
        <BottomSheet
          state={sheetState}
          onStateChange={setSheetState}
          ariaLabel="Search results"
          summary={
            <span className="block truncate text-label-md font-semibold text-on-surface">
              {summaryText}
            </span>
          }
        >
          {discovery}
        </BottomSheet>
      )}
    </div>
  );
}

/** One-line summary for the collapsed bottom-sheet peek. */
function resolveSummary(
  params: NearbySearchParams | null,
  search: { isPending: boolean; isError: boolean },
  visible: number,
  total: number,
): string {
  if (params === null) return 'Search for parking';
  if (search.isPending) return 'Searching nearby…';
  if (search.isError) return "Couldn't load results";
  if (total === 0) return 'No spots nearby';
  if (visible !== total) return `${visible} of ${total} spots`;
  return `${total} ${total === 1 ? 'spot' : 'spots'} nearby`;
}
