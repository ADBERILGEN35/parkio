import { zodResolver } from '@hookform/resolvers/zod';
import type {
  NearbySearchParams,
  Destination,
  DestinationSearchItem,
  ParkingCandidate,
  AssistantDestinationOrigin,
} from '@parkio/types';
import {
  Button,
  EmptyState,
  ErrorMessage,
  Icon,
  Input,
  MapSearchSkeleton,
} from '@parkio/ui';
import { nearbySearchSchema, type NearbySearchFormValues } from '@parkio/validation';
import { Suspense, lazy, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '@/auth/store';
import { BottomSheet, COLLAPSED_PEEK, type SheetState } from '@/components/map/BottomSheet';
import { DiscoveryResults } from '@/components/map/DiscoveryResults';
import { MapLayerVisibilityControls } from '@/components/map/MapLayerVisibilityControls';
import { MunicipalFacilityResults } from '@/components/map/MunicipalFacilityResults';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  LOCATED_ZOOM,
  isValidLatLng,
} from '@/components/map/mapConfig';
import { PlaceSearch } from '@/components/map/PlaceSearch';
import { SelectedMunicipalFacilityPreview } from '@/components/map/SelectedMunicipalFacilityPreview';
import { SelectedSpotPreview } from '@/components/map/SelectedSpotPreview';
import type { ParkedCarFocusRequest } from '@/components/map/parkedCarCoords';
import { isUsableParkedCoordinate } from '@/components/map/parkedCarCoords';
import {
  ActiveParkingSessionCard,
  ActiveParkingSessionErrorCard,
} from '@/components/parking/ActiveParkingSessionCard';
import { ParkHereStartControl } from '@/components/parking/ParkHereStartControl';
import { frontendConfig } from '@/config/env';
import { useMySmartReturnQuery, useMyVehicleQuery } from '@/data/hooks/useMeQueries';
import {
  useNearbyMunicipalFacilitiesQuery,
  useNearbySpotsQuery,
} from '@/data/hooks/useParkingQueries';
import { useActiveParkingSessionQuery, useParkingSessionLifecycleConfigQuery } from '@/data/hooks/useParkingSessionQueries';
import { type GeocodeResult } from '@/lib/geocoding';
import { needsActiveConfirmation } from '@/lib/parkingSessionStale';
import { DESKTOP_QUERY, useMediaQuery } from '@/lib/useMediaQuery';
import {
  EMPTY_FILTERS,
  availableMunicipalFacilityTypes,
  availableMunicipalSourceLabels,
  availableSorts,
  availableStatuses as deriveStatuses,
  defaultSort,
  filterMunicipalFacilities,
  filterSpots,
  haversineMeters,
  sortSpots,
  withDistance,
  type MunicipalFacilityFilters,
  type SpotFilters,
  type SpotSort,
} from '@/lib/spotDiscovery';
import {
  formatDiscoveryChromeCtaLabel,
  formatDiscoveryChromeSummary,
  resolveMapDiscoveryChrome,
} from '@/lib/mapDiscoveryChrome';
import {
  canonicalizeMapDiscoveryUrlState,
  mapDiscoveryUrlStateKey,
  parseMapDiscoveryUrlState,
  serializeMapDiscoveryUrlState,
  type MapDiscoveryUrlState,
} from '@/lib/mapDiscoveryUrlState';
import {
  parseAssistantUrlState,
  serializeAssistantUrlState,
  stripAssistantUrlParams,
  type AssistantUrlState,
} from '@/lib/assistantUrlState';
import { ASSISTANT_RECOMMEND_RADIUS_METERS } from '@/lib/recommendationPresentation';
import {
  AssistantEntryControl,
  DestinationSearchPanel,
  QuickActionsBar,
  RecommendationsPanel,
  useSmartParkingAssistant,
} from '@/features/smart-parking-assistant';

const NearbySpotsMap = lazy(() =>
  import('@/components/map/NearbySpotsMap').then((m) => ({ default: m.NearbySpotsMap })),
);

type GeoStatus = 'idle' | 'locating' | 'error';
type DiscoverySelectionOrigin = 'map' | 'list' | 'control' | 'system';

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
export function MapPage({
  municipalDiscoveryEnabled = frontendConfig.features.municipalDiscovery,
  smartParkingAssistantEnabled = frontendConfig.features.smartParkingAssistant,
}: {
  /** Test override for WEB_MUNICIPAL_DISCOVERY_ENABLED. */
  municipalDiscoveryEnabled?: boolean;
  /** Test override for VITE_SMART_PARKING_ASSISTANT_ENABLED. */
  smartParkingAssistantEnabled?: boolean;
} = {}) {
  const { t } = useTranslation('map');
  const [searchParams, setSearchParams] = useSearchParams();
  const persistedMapUiState = useMemo(
    () => parseMapDiscoveryUrlState(searchParams, { municipalDiscoveryEnabled }),
    [municipalDiscoveryEnabled, searchParams],
  );
  const persistedMapUiStateKey = useMemo(
    () => mapDiscoveryUrlStateKey(persistedMapUiState, { municipalDiscoveryEnabled }),
    [municipalDiscoveryEnabled, persistedMapUiState],
  );
  const assistantUrlState = useMemo(
    () =>
      parseAssistantUrlState(searchParams, {
        assistantEnabled: smartParkingAssistantEnabled,
      }),
    [searchParams, smartParkingAssistantEnabled],
  );
  const setAssistantUrlState = useCallback(
    (next: AssistantUrlState) => {
      if (!smartParkingAssistantEnabled) return;
      const serialized = serializeAssistantUrlState(searchParams, next, {
        assistantEnabled: true,
      });
      if (serialized.toString() !== searchParams.toString()) {
        setSearchParams(serialized, { replace: false });
      }
    },
    [searchParams, setSearchParams, smartParkingAssistantEnabled],
  );
  const assistant = useSmartParkingAssistant({
    enabled: smartParkingAssistantEnabled,
    municipalDiscoveryEnabled,
    urlState: assistantUrlState,
    onUrlStateChange: setAssistantUrlState,
  });
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
  const [selectedMunicipalId, setSelectedMunicipalId] = useState<string | null>(null);
  const [selectionOrigin, setSelectionOrigin] = useState<DiscoverySelectionOrigin | null>(null);
  const [filters, setFilters] = useState<SpotFilters>(EMPTY_FILTERS);
  const [municipalFilters, setMunicipalFilters] = useState<MunicipalFacilityFilters>(
    persistedMapUiState.municipalFilters,
  );
  const [sort, setSort] = useState<SpotSort | null>(null);
  /** Dual-inventory layer visibility (WEB-MUNI-05) — presentation only. */
  const [communityLayerVisible, setCommunityLayerVisible] = useState(
    persistedMapUiState.communityLayerVisible,
  );
  const [municipalLayerVisible, setMunicipalLayerVisible] = useState(
    persistedMapUiState.municipalLayerVisible,
  );
  const [sheetState, setSheetState] = useState<SheetState>('collapsed');
  /** Visual emphasis for the parked-car marker (card stays non-dismissible). */
  const [parkedCarSelected, setParkedCarSelected] = useState(false);
  const [parkedCarFocusRequest, setParkedCarFocusRequest] =
    useState<ParkedCarFocusRequest | null>(null);
  const parkedFocusTokenRef = useRef(0);
  const isApplyingUrlStateRef = useRef(false);
  const initialUrlWriteDoneRef = useRef(false);
  const lastSeenPersistedMapUiStateKeyRef = useRef(persistedMapUiStateKey);

  const isDesktop = useMediaQuery(DESKTOP_QUERY);
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);

  // Nearby hook keeps prior results via placeholderData while a re-search loads
  // (new center/radius/"use my location") instead of flashing the skeleton.
  const search = useNearbySpotsQuery(params);
  const municipalSearch = useNearbyMunicipalFacilitiesQuery(params, {
    enabled: municipalDiscoveryEnabled,
  });

  const activeSessionQuery = useActiveParkingSessionQuery({ enabled: isAuthenticated });
  const lifecycleConfigQuery = useParkingSessionLifecycleConfigQuery({ enabled: isAuthenticated });
  const confirmAfterMs = lifecycleConfigQuery.data?.confirmAfterMs;
  const activeSession =
    isAuthenticated && activeSessionQuery.data?.status === 'ACTIVE'
      ? activeSessionQuery.data
      : null;
  const parkedCarCoords = useMemo(() => {
    if (!activeSession) return null;
    if (!isUsableParkedCoordinate(activeSession.latitude, activeSession.longitude)) return null;
    return { latitude: activeSession.latitude, longitude: activeSession.longitude };
  }, [activeSession]);
  const activeSessionNeedsConfirmation = Boolean(
    activeSession &&
      confirmAfterMs != null &&
      needsActiveConfirmation(activeSession, confirmAfterMs),
  );

  /** Shared map-focus path: card CTA, marker click, and floating recenter. */
  const focusParkedCar = useCallback(() => {
    if (!parkedCarCoords || !activeSession) return;
    if (confirmAfterMs == null || needsActiveConfirmation(activeSession, confirmAfterMs)) return;
    parkedFocusTokenRef.current += 1;
    setParkedCarSelected(true);
    setParkedCarFocusRequest({
      latitude: parkedCarCoords.latitude,
      longitude: parkedCarCoords.longitude,
      token: parkedFocusTokenRef.current,
    });
  }, [activeSession, confirmAfterMs, parkedCarCoords]);

  // Drop parked-car emphasis when the ACTIVE session disappears (complete/logout).
  useEffect(() => {
    if (!parkedCarCoords) {
      setParkedCarSelected(false);
      setParkedCarFocusRequest(null);
    }
  }, [parkedCarCoords]);

  const vehicleQuery = useMyVehicleQuery({
    enabled: isAuthenticated,
    staleTime: 5 * 60_000,
  });

  const smartReturnQuery = useMySmartReturnQuery({
    enabled: isAuthenticated && smartReturnMode,
    staleTime: 30_000,
  });

  const smartReturnHomeConfigured =
    smartReturnQuery.data?.homeLatitude != null && smartReturnQuery.data?.homeLongitude != null;
  const showSmartReturnReadyBanner =
    smartReturnMode && smartReturnBannerOpen && smartReturnHomeConfigured;
  const showSmartReturnSetupNotice =
    smartReturnMode &&
    smartReturnBannerOpen &&
    smartReturnQuery.isSuccess &&
    !smartReturnHomeConfigured;

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

  const municipalFacilities = useMemo(
    () => (municipalDiscoveryEnabled ? (municipalSearch.data ?? []) : []),
    [municipalDiscoveryEnabled, municipalSearch.data],
  );
  const municipalSourceLabels = useMemo(
    () => availableMunicipalSourceLabels(municipalFacilities),
    [municipalFacilities],
  );
  const municipalFacilityTypes = useMemo(
    () => availableMunicipalFacilityTypes(municipalFacilities),
    [municipalFacilities],
  );
  const urlStateOptions = useMemo(
    () => ({
      municipalDiscoveryEnabled,
      availableSourceLabels: municipalSearch.isSuccess ? municipalSourceLabels : undefined,
      availableFacilityTypes: municipalSearch.isSuccess ? municipalFacilityTypes : undefined,
    }),
    [
      municipalDiscoveryEnabled,
      municipalFacilityTypes,
      municipalSearch.isSuccess,
      municipalSourceLabels,
    ],
  );
  const canonicalSearchParams = useMemo(
    () => canonicalizeMapDiscoveryUrlState(searchParams, urlStateOptions),
    [searchParams, urlStateOptions],
  );
  const canonicalSearchParamsKey = canonicalSearchParams.toString();
  const persistedStateFromComponent = useMemo<MapDiscoveryUrlState>(
    () => ({
      communityLayerVisible,
      municipalLayerVisible,
      municipalFilters,
    }),
    [communityLayerVisible, municipalLayerVisible, municipalFilters],
  );
  const persistedStateFromComponentKey = useMemo(
    () => mapDiscoveryUrlStateKey(persistedStateFromComponent, urlStateOptions),
    [persistedStateFromComponent, urlStateOptions],
  );
  const visibleMunicipalFacilities = useMemo(
    () => filterMunicipalFacilities(municipalFacilities, municipalFilters),
    [municipalFacilities, municipalFilters],
  );
  // Selection resolves from the unfiltered set so a selected facility survives
  // filter changes (and /facilities/:id deep links stay independent of filters).
  const selectedMunicipalFacility = useMemo(
    () => municipalFacilities.find((facility) => facility.id === selectedMunicipalId) ?? null,
    [municipalFacilities, selectedMunicipalId],
  );
  const selectedMunicipalDistance = useMemo(() => {
    if (!selectedMunicipalFacility || !searchCenter) return null;
    return haversineMeters(
      { lat: searchCenter.lat, lng: searchCenter.lng },
      { lat: selectedMunicipalFacility.latitude, lng: selectedMunicipalFacility.longitude },
    );
  }, [selectedMunicipalFacility, searchCenter]);

  const selectSpot = useCallback(
    (id: string | null, origin: DiscoverySelectionOrigin = 'system') => {
      setSelectedId(id);
      setSelectionOrigin(id === null ? null : origin);
      if (id !== null) {
        setSelectedMunicipalId(null);
        setParkedCarSelected(false);
      }
      // On mobile the preview owns the bottom band; drop the sheet to its peek so
      // the two never fight for the same space (and the sheet handle stays visible
      // just below the preview). Desktop has dedicated space for both.
      if (id !== null && !isDesktop) setSheetState('collapsed');
    },
    [isDesktop],
  );

  const selectMunicipalFacility = useCallback(
    (id: string | null, origin: DiscoverySelectionOrigin = 'system') => {
      setSelectedMunicipalId(id);
      setSelectionOrigin(id === null ? null : origin);
      if (id !== null) {
        setSelectedId(null);
        setParkedCarSelected(false);
      }
      if (id !== null && !isDesktop) setSheetState('collapsed');
    },
    [isDesktop],
  );

  // Strip assistant URL params when the feature flag is off.
  useEffect(() => {
    if (smartParkingAssistantEnabled) return;
    const stripped = stripAssistantUrlParams(searchParams);
    if (stripped.toString() !== searchParams.toString()) {
      setSearchParams(stripped, { replace: true });
    }
  }, [searchParams, setSearchParams, smartParkingAssistantEnabled]);

  const handleAssistantConfirm = useCallback(
    (item: DestinationSearchItem) => {
      assistant.confirmDestination(item);
    },
    [assistant],
  );

  const handleQuickSelectDestination = useCallback(
    (destination: Destination, origin: AssistantDestinationOrigin) => {
      assistant.selectAssistantDestination(destination, origin);
    },
    [assistant],
  );

  const handleQuickFavouriteParking = useCallback(
    (facilityId: string) => {
      selectMunicipalFacility(facilityId, 'list');
    },
    [selectMunicipalFacility],
  );

  const handleQuickParkedCar = useCallback(() => {
    if (!parkedCarCoords || !activeSession) return;
    setParkedCarSelected(true);
    focusParkedCar();
    if (!isDesktop) setSheetState('half');
  }, [activeSession, focusParkedCar, isDesktop, parkedCarCoords]);

  const handleAssistantSelectCandidate = useCallback(
    (candidate: ParkingCandidate) => {
      assistant.selectCandidate(candidate);
      if (candidate.channel === 'MUNICIPAL_FACILITY') {
        selectMunicipalFacility(candidate.refId, 'list');
      } else {
        selectSpot(candidate.refId, 'list');
      }
    },
    [assistant, selectMunicipalFacility, selectSpot],
  );

  const handleSelectSpotWithAssistant = useCallback(
    (id: string | null, origin: DiscoverySelectionOrigin = 'system') => {
      selectSpot(id, origin);
      if (!assistant.enabled || !assistant.destination) return;
      if (id == null) {
        assistant.selectCandidateById(null);
        return;
      }
      const match = assistant.recommendations.data?.candidates.find(
        (c) => c.channel === 'COMMUNITY_SPOT' && c.refId === id,
      );
      assistant.selectCandidateById(match?.id ?? null);
    },
    [assistant, selectSpot],
  );

  const handleSelectMunicipalWithAssistant = useCallback(
    (id: string | null, origin: DiscoverySelectionOrigin = 'system') => {
      selectMunicipalFacility(id, origin);
      if (!assistant.enabled || !assistant.destination) return;
      if (id == null) {
        assistant.selectCandidateById(null);
        return;
      }
      const match = assistant.recommendations.data?.candidates.find(
        (c) => c.channel === 'MUNICIPAL_FACILITY' && c.refId === id,
      );
      assistant.selectCandidateById(match?.id ?? null);
    },
    [assistant, selectMunicipalFacility],
  );

  const handleCommunityLayerVisibleChange = useCallback((visible: boolean) => {
    setCommunityLayerVisible(visible);
    if (!visible) {
      setSelectedId(null);
      setSelectionOrigin(null);
    }
  }, []);

  const handleMunicipalLayerVisibleChange = useCallback((visible: boolean) => {
    setMunicipalLayerVisible(visible);
    if (!visible) {
      setSelectedMunicipalId(null);
      setSelectionOrigin(null);
    }
  }, []);

  useEffect(() => {
    if (canonicalSearchParamsKey !== searchParams.toString()) {
      setSearchParams(canonicalSearchParams, { replace: true });
    }
  }, [canonicalSearchParams, canonicalSearchParamsKey, searchParams, setSearchParams]);

  useEffect(() => {
    if (lastSeenPersistedMapUiStateKeyRef.current === persistedMapUiStateKey) {
      return;
    }
    lastSeenPersistedMapUiStateKeyRef.current = persistedMapUiStateKey;

    isApplyingUrlStateRef.current = true;
    setCommunityLayerVisible(persistedMapUiState.communityLayerVisible);
    if (!persistedMapUiState.communityLayerVisible) {
      setSelectedId(null);
      setSelectionOrigin(null);
    }
    setMunicipalLayerVisible(persistedMapUiState.municipalLayerVisible);
    if (!persistedMapUiState.municipalLayerVisible) {
      setSelectedMunicipalId(null);
      setSelectionOrigin(null);
    }
    setMunicipalFilters(persistedMapUiState.municipalFilters);
  }, [persistedMapUiState, persistedMapUiStateKey]);

  useEffect(() => {
    if (isApplyingUrlStateRef.current) {
      if (persistedStateFromComponentKey === persistedMapUiStateKey) {
        isApplyingUrlStateRef.current = false;
      }
      return;
    }

    const nextSearchParams = serializeMapDiscoveryUrlState(
      searchParams,
      persistedStateFromComponent,
      urlStateOptions,
    );
    const currentKey = searchParams.toString();
    const nextKey = nextSearchParams.toString();
    if (currentKey === nextKey) {
      initialUrlWriteDoneRef.current = true;
      return;
    }

    setSearchParams(nextSearchParams, { replace: !initialUrlWriteDoneRef.current });
    initialUrlWriteDoneRef.current = true;
  }, [
    persistedMapUiStateKey,
    persistedStateFromComponent,
    persistedStateFromComponentKey,
    searchParams,
    setSearchParams,
    urlStateOptions,
  ]);

  useEffect(() => {
    if (selectedMunicipalId === null) {
      return;
    }
    const stillVisible = visibleMunicipalFacilities.some((facility) => facility.id === selectedMunicipalId);
    if (!stillVisible) {
      setSelectedMunicipalId(null);
      setSelectionOrigin(null);
    }
  }, [selectedMunicipalId, visibleMunicipalFacilities]);

  // Spot filters/sort reset when the search center/params change (existing behaviour).
  // Layer visibility is independent and intentionally preserved across re-searches.

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

  // When a destination is confirmed (or restored from URL), center discovery nearby.
  const lastAssistantDestKeyRef = useRef<string | null>(null);
  useEffect(() => {
    if (!assistant.destination) {
      lastAssistantDestKeyRef.current = null;
      return;
    }
    const key = `${assistant.destination.latitude},${assistant.destination.longitude},${assistant.destination.label}`;
    if (lastAssistantDestKeyRef.current === key) return;
    lastAssistantDestKeyRef.current = key;
    applyCoords(assistant.destination.latitude, assistant.destination.longitude);
    setMapZoom(LOCATED_ZOOM);
    setCenterLabel(assistant.destination.label);
    runSearch({
      lat: assistant.destination.latitude,
      lng: assistant.destination.longitude,
      radius: ASSISTANT_RECOMMEND_RADIUS_METERS,
    });
    if (!isDesktop) setSheetState('half');
  }, [applyCoords, assistant.destination, isDesktop, runSearch]);

  /** Map click / manual coordinate edits update the same center source of truth. */
  const handlePickCenter = (lat: number, lng: number) => {
    applyCoords(lat, lng);
    setCenterLabel(t('selectedMapPoint'));
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
        setGeoError(t('geoUnavailable'));
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
          setCenterLabel(t('currentLocation'));
          setGeoStatus('idle');
          if (autoSearch) {
            runSearch({ lat, lng, ...currentOptionalSearchFields() });
          }
        },
        (error) => {
          setGeoStatus('error');
          setGeoError(
            error.code === error.PERMISSION_DENIED ? t('geoDenied') : t('geoFailed'),
          );
        },
        { enableHighAccuracy: false, timeout: 10_000 },
      );
    },
    [currentOptionalSearchFields, runSearch, setValue, t],
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
    setCenterLabel(t('savedHome'));
    runSearch({ lat, lng, radius: 1000 });
  }, [applyCoords, runSearch, smartReturnMode, smartReturnQuery.data, t]);

  const locate = () => runGeolocation({ autoSearch: false });

  const mapCommunitySpots = communityLayerVisible ? visibleSpots : [];
  const mapMunicipalFacilities =
    municipalDiscoveryEnabled && municipalLayerVisible ? visibleMunicipalFacilities : [];
  const bothLayersHidden =
    municipalDiscoveryEnabled && !communityLayerVisible && !municipalLayerVisible;

  const discoveryChrome = useMemo(
    () =>
      resolveMapDiscoveryChrome({
        municipalDiscoveryEnabled,
        communityLayerVisible,
        municipalLayerVisible,
        hasSearchParams: params !== null,
        communityPending: search.isPending,
        communityError: search.isError,
        communityVisibleCount: visibleSpots.length,
        communityTotalCount: spotsWithDistance.length,
        municipalPending: municipalSearch.isPending,
        municipalError: municipalSearch.isError,
        municipalVisibleCount: visibleMunicipalFacilities.length,
        municipalTotalCount: municipalFacilities.length,
      }),
    [
      municipalDiscoveryEnabled,
      communityLayerVisible,
      municipalLayerVisible,
      params,
      search.isPending,
      search.isError,
      visibleSpots.length,
      spotsWithDistance.length,
      municipalSearch.isPending,
      municipalSearch.isError,
      visibleMunicipalFacilities.length,
      municipalFacilities.length,
    ],
  );

  const summaryText = useMemo(
    () => formatDiscoveryChromeSummary(t, discoveryChrome),
    [t, discoveryChrome],
  );
  const selectedMapAnnouncement = useMemo(() => {
    if (selectionOrigin === 'map' && selectedMunicipalFacility) {
      const name =
        selectedMunicipalFacility.displayName?.trim() ||
        selectedMunicipalFacility.addressText?.trim() ||
        t('municipal.unnamedFacility');
      return t('mapSelection.municipal', { name });
    }
    if (selectionOrigin === 'map' && selectedSpot) {
      const address = selectedSpot.addressText?.trim() || t('currentLocation');
      return t('mapSelection.community', { address });
    }
    if (selectionOrigin === 'map' && parkedCarSelected) {
      return t('mapSelection.parkedCar');
    }
    return null;
  }, [parkedCarSelected, selectedMunicipalFacility, selectedSpot, selectionOrigin, t]);

  const discovery = (
    <>
      {smartParkingAssistantEnabled && assistant.destination ? (
        <div className="mb-md">
          <RecommendationsPanel
            destination={assistant.destination}
            recommendations={assistant.recommendations}
            selectedCandidateId={assistant.candidateId}
            onSelectCandidate={handleAssistantSelectCandidate}
            onClearDestination={() => {
              assistant.clearDestination();
              selectSpot(null);
              selectMunicipalFacility(null);
            }}
            onChangeDestination={() => {
              assistant.openSearch();
            }}
          />
        </div>
      ) : null}

      {municipalDiscoveryEnabled ? (
        <div className="mb-md">
          <MapLayerVisibilityControls
            communityVisible={communityLayerVisible}
            municipalVisible={municipalLayerVisible}
            onCommunityVisibleChange={handleCommunityLayerVisibleChange}
            onMunicipalVisibleChange={handleMunicipalLayerVisibleChange}
          />
        </div>
      ) : null}

      {bothLayersHidden ? (
        <div
          role="status"
          data-testid="map-layers-both-hidden"
          className="mb-md rounded-3xl bg-surface-container px-md py-md"
        >
          <EmptyState
            icon="layers"
            title={t('layers.bothHiddenTitle')}
            description={t('layers.bothHiddenDescription')}
          />
        </div>
      ) : null}

      {municipalDiscoveryEnabled && municipalLayerVisible ? (
        <MunicipalFacilityResults
          search={municipalSearch}
          params={params}
          facilities={visibleMunicipalFacilities}
          totalCount={municipalFacilities.length}
          filters={municipalFilters}
          onFiltersChange={setMunicipalFilters}
          availableSourceLabels={municipalSourceLabels}
          availableFacilityTypes={municipalFacilityTypes}
          selectedId={selectedMunicipalId}
          onSelect={(id) => handleSelectMunicipalWithAssistant(id, 'list')}
          selectionFromMap={selectionOrigin === 'map'}
        />
      ) : null}
      {communityLayerVisible ? (
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
          onSelect={(id) => handleSelectSpotWithAssistant(id, 'list')}
          selectionFromMap={selectionOrigin === 'map'}
          userVehicleType={vehicleQuery.data?.vehicleType ?? null}
          siblingInventoryHasResults={discoveryChrome.communityEmptySubordinate}
        />
      ) : null}
    </>
  );
  const advancedForm = (
    <form onSubmit={onSubmit}>
      <fieldset
        disabled={search.isFetching}
        className="m-0 flex flex-col gap-sm border-0 p-0"
      >
        <div className="grid grid-cols-2 gap-sm">
          <Input
            label={t('latitude')}
            inputMode="decimal"
            error={errors.lat?.message}
            {...register('lat')}
          />
          <Input
            label={t('longitude')}
            inputMode="decimal"
            error={errors.lng?.message}
            {...register('lng')}
          />
        </div>
        <div className="grid grid-cols-2 gap-sm">
          <Input
            label={t('radius')}
            inputMode="numeric"
            error={errors.radius?.message}
            {...register('radius')}
          />
          <Input
            label={t('limit')}
            inputMode="numeric"
            error={errors.limit?.message}
            {...register('limit')}
          />
        </div>
        <Button type="submit" disabled={search.isFetching} className="w-full">
          <Icon name="travel_explore" className="text-[16px] leading-none" />
          {search.isFetching ? t('searching') : t('searchNearby')}
        </Button>
      </fieldset>
    </form>
  );

  return (
    <div className="fixed inset-x-0 bottom-[var(--parkio-mobile-nav-offset)] top-0 z-0 overflow-hidden bg-background md:bottom-0 md:top-[var(--parkio-desktop-nav-height)]">
      {/* Full-bleed map canvas */}
      <div className="absolute inset-0 z-0">
        <Suspense fallback={<MapSearchSkeleton />}>
          <NearbySpotsMap
            center={center}
            zoom={mapZoom}
            spots={mapCommunitySpots}
            municipalFacilities={mapMunicipalFacilities}
            onPickCenter={handlePickCenter}
            selectedId={communityLayerVisible ? selectedId : null}
            selectedMunicipalId={
              municipalDiscoveryEnabled && municipalLayerVisible ? selectedMunicipalId : null
            }
            onSelectSpot={
              communityLayerVisible
                ? (id) => handleSelectSpotWithAssistant(id, id === null ? 'system' : 'map')
                : undefined
            }
            onSelectMunicipalFacility={
              municipalDiscoveryEnabled && municipalLayerVisible
                ? (id) => handleSelectMunicipalWithAssistant(id, id === null ? 'system' : 'map')
                : undefined
            }
            height="100%"
            onLocate={locate}
            locating={geoStatus === 'locating'}
            // Keep recenter reachable when ACTIVE even if a spot preview is selected.
            showFloatingControls={
              isDesktop ||
              (selectedId === null && selectedMunicipalId === null) ||
              Boolean(parkedCarCoords)
            }
            parkedCar={parkedCarCoords}
            parkedCarSelected={parkedCarSelected}
            onSelectParkedCar={() => {
              setSelectionOrigin('map');
              focusParkedCar();
            }}
            parkedCarFocusRequest={parkedCarFocusRequest}
            onFocusParkedCar={
              activeSessionNeedsConfirmation
                ? undefined
                : () => {
                    setSelectionOrigin('control');
                    focusParkedCar();
                  }
            }
            ariaLabel={t('mapRegionAria')}
            ariaDescription={
              municipalDiscoveryEnabled ? t('mapRegionHelpMunicipal') : t('mapRegionHelp')
            }
            selectionSummary={selectedMapAnnouncement}
            destinationMarker={
              assistant.destination
                ? {
                    latitude: assistant.destination.latitude,
                    longitude: assistant.destination.longitude,
                    label: assistant.destination.label,
                  }
                : null
            }
            recommendedRefIds={
              assistant.enabled && assistant.destination ? assistant.recommendedRefIds : undefined
            }
          />
        </Suspense>
      </div>

      {/* Floating search overlay */}
      {isDesktop ? (
        <div className="pointer-events-none absolute inset-x-0 top-md z-[1100] flex justify-start px-md pl-lg">
          <div className="pointer-events-auto w-full max-w-[min(28rem,calc(100vw-1rem))] animate-fade-in-up glass-panel rounded-2xl p-md shadow-deep">
            <h2 className="m-0 text-title-lg text-on-surface">{t('title')}</h2>
            <p className="m-0 mt-xs text-label-sm text-on-surface-variant">{t('subtitle')}</p>

            {centerLabel ? (
              <p className="mt-sm flex items-center gap-xs text-label-sm font-medium text-on-surface">
                <Icon name="location_on" className="text-[16px] leading-none text-primary" />
                {t('searchingNear', { label: centerLabel })}
              </p>
            ) : null}

            {showSmartReturnReadyBanner ? (
              <div className="m-0 mt-sm flex items-center gap-xs rounded-2xl bg-primary/10 px-md py-sm text-label-sm font-medium text-primary">
                <Icon name="home_pin" className="text-[16px] leading-none" />
                <span className="flex-1">{t('smartReturnReady')}</span>
                <button
                  type="button"
                  aria-label={t('dismissSmartReturn')}
                  onClick={() => setSmartReturnBannerOpen(false)}
                  className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-primary hover:bg-primary/10 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <Icon name="close" className="text-[16px] leading-none" />
                </button>
              </div>
            ) : null}

            {showSmartReturnSetupNotice ? (
              <div className="m-0 mt-sm flex flex-col gap-xs rounded-2xl bg-surface-container px-md py-sm text-label-sm text-on-surface">
                <span>{t('smartReturnSetup')}</span>
                <Link to="/profile?section=smart-return" className="font-semibold text-primary hover:underline">
                  {t('smartReturnOpen')}
                </Link>
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

            {smartParkingAssistantEnabled ? (
              assistant.searchOpen ? (
                <DestinationSearchPanel
                  open
                  onClose={assistant.closeSearch}
                  onSelect={handleAssistantConfirm}
                />
              ) : !assistant.destination ? (
                <>
                  <AssistantEntryControl onOpen={assistant.openSearch} />
                  <QuickActionsBar
                    enabled={smartParkingAssistantEnabled}
                    authenticated={isAuthenticated}
                    visible
                    onSelectDestination={handleQuickSelectDestination}
                    onOpenSearch={assistant.openSearch}
                    onParkedCar={handleQuickParkedCar}
                    onSelectFavouriteParking={handleQuickFavouriteParking}
                  />
                </>
              ) : null
            ) : null}

            <button
              type="button"
              onClick={locate}
              disabled={geoStatus === 'locating'}
              className="mt-sm inline-flex items-center gap-xs rounded-full bg-surface-container px-md py-xs text-label-sm font-medium text-on-surface transition-colors hover:bg-surface-container-high disabled:opacity-60"
            >
              <Icon name="my_location" className="text-[16px] leading-none" />
              {geoStatus === 'locating' ? t('locating') : t('useMyLocation')}
            </button>

            <details className="mt-sm border-t border-outline-variant/30 pt-sm">
              <summary className="cursor-pointer list-none text-label-sm font-semibold text-on-surface-variant marker:content-none">
                <span className="inline-flex items-center gap-xs">
                  <Icon name="tune" className="text-[16px] leading-none" />
                  {t('advanced')}
                </span>
              </summary>
              <div className="mt-sm">{advancedForm}</div>
            </details>
          </div>
        </div>
      ) : (
        <div className="pointer-events-none absolute inset-x-0 top-sm z-[1100] px-sm">
          <div className="pointer-events-auto mx-auto flex max-w-[430px] flex-col gap-xs">
            <div className="flex items-center gap-xs rounded-full border border-outline-variant/30 bg-surface/90 p-xs shadow-deep backdrop-blur-xl">
              {smartParkingAssistantEnabled && !assistant.destination && !assistant.searchOpen ? (
                <AssistantEntryControl compact onOpen={assistant.openSearch} />
              ) : (
                <div className="min-w-0 flex-1">
                  <PlaceSearch
                    compact
                    placeholder={t('searchPlaceholder')}
                    onSelect={selectPlace}
                  />
                </div>
              )}
              <button
                type="button"
                aria-label={t('locateAria')}
                onClick={locate}
                disabled={geoStatus === 'locating'}
                className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-surface-container text-primary transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60"
              >
                <Icon name={geoStatus === 'locating' ? 'progress_activity' : 'my_location'} className="text-[20px] leading-none" />
              </button>
              <button
                type="button"
                aria-label={t('filtersAria')}
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

            {smartParkingAssistantEnabled && assistant.searchOpen ? (
              <div className="rounded-2xl border border-outline-variant/30 bg-surface/95 p-xs shadow-deep backdrop-blur-xl">
                <DestinationSearchPanel
                  open
                  onClose={assistant.closeSearch}
                  onSelect={handleAssistantConfirm}
                />
              </div>
            ) : null}

            {smartParkingAssistantEnabled && !assistant.destination && !assistant.searchOpen ? (
              <div className="rounded-2xl border border-outline-variant/30 bg-surface/95 p-sm shadow-deep backdrop-blur-xl">
                <QuickActionsBar
                  enabled={smartParkingAssistantEnabled}
                  authenticated={isAuthenticated}
                  visible
                  onSelectDestination={handleQuickSelectDestination}
                  onOpenSearch={assistant.openSearch}
                  onParkedCar={handleQuickParkedCar}
                  onSelectFavouriteParking={handleQuickFavouriteParking}
                />
              </div>
            ) : null}

          {centerLabel ? (
            <p className="flex items-center gap-xs rounded-full bg-surface/85 px-md py-xs text-label-sm font-medium text-on-surface shadow-soft backdrop-blur-xl">
              <Icon name="location_on" className="text-[14px] leading-none text-primary" />
              <span className="truncate">{t('near', { label: centerLabel })}</span>
            </p>
          ) : null}

          {showSmartReturnReadyBanner ? (
            <div className="pointer-events-auto mx-auto mt-xs flex max-w-[430px] items-center gap-xs rounded-full bg-primary/10 px-md py-xs text-label-sm font-medium text-primary shadow-soft backdrop-blur-xl">
              <Icon name="home_pin" className="text-[14px] leading-none" />
              <span className="flex-1 truncate">{t('smartReturnReady')}</span>
              <button
                type="button"
                aria-label={t('dismissSmartReturn')}
                onClick={() => setSmartReturnBannerOpen(false)}
                className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-primary hover:bg-primary/15 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                <Icon name="close" className="text-[14px] leading-none" />
              </button>
            </div>
          ) : null}

          {showSmartReturnSetupNotice ? (
            <div className="pointer-events-auto mx-auto mt-xs max-w-[430px] rounded-2xl bg-surface/90 px-md py-sm text-label-sm text-on-surface shadow-soft backdrop-blur-xl">
              <p className="m-0">{t('smartReturnSetup')}</p>
              <Link to="/profile?section=smart-return" className="mt-xs inline-block font-semibold text-primary">
                {t('smartReturnOpen')}
              </Link>
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
                  <h2 className="m-0 text-title-lg text-on-surface">{t('advanced')}</h2>
                  <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
                    {t('subtitle')}
                  </p>
                </div>
                <button
                  type="button"
                  aria-label={t('actions.close', { ns: 'common' })}
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
                    {t('advanced')}
                  </span>
                </summary>
                <div className="mt-sm">{advancedForm}</div>
              </details>
              {discoveryChrome.ctaMode === 'open_results' ? (
                <button
                  type="button"
                  data-testid="map-sheet-show-results"
                  onClick={() => {
                    setSheetState('half');
                    setAdvancedOpen(false);
                  }}
                  className="mt-sm inline-flex w-full items-center justify-center gap-xs rounded-full bg-surface-container px-md py-sm text-label-md font-semibold text-on-surface transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <Icon name="filter_alt" className="text-[16px] leading-none" />
                  {formatDiscoveryChromeCtaLabel(t, discoveryChrome)}
                </button>
              ) : (
                <p
                  className="mt-sm flex items-center gap-xs rounded-2xl bg-surface-container px-md py-sm text-label-sm text-on-surface-variant"
                  data-testid="map-sheet-results-hint"
                >
                  <Icon
                    name={discoveryChrome.ctaMode === 'both_hidden' ? 'layers' : 'search'}
                    className="text-[16px] leading-none text-primary"
                  />
                  {discoveryChrome.ctaMode === 'both_hidden'
                    ? t('sheet.bothHiddenHint')
                    : discoveryChrome.ctaMode === 'idle'
                      ? t('sheet.searchPlaceHint')
                      : discoveryChrome.ctaMode === 'error'
                        ? t('sheet.summaryError')
                        : municipalDiscoveryEnabled
                          ? t('sheet.noVisibleResultsYet')
                          : t('sheet.noMatchYet')}
                </p>
              )}
            </div>
          ) : null}
          </div>
        </div>
      )}

      {/* Parking Session + selected-spot preview stack.
          Park Here (no ACTIVE) and Active card (ACTIVE) share this band.
          SelectedSpotPreview is selection-scoped and stacks below them.
          Desktop: bottom-left over the map. Mobile: above the bottom-sheet peek
          while the sheet is collapsed (same rule as SelectedSpotPreview). */}
      {(() => {
        // Show the Active card whenever ACTIVE exists — invalid coords still need leave/cancel.
        const showActiveCard = Boolean(activeSession);
        const showActiveError =
          isAuthenticated && activeSessionQuery.isError && !activeSessionQuery.isPending;
        // Authenticated + settled + no ACTIVE: offer Park Here (never flash while pending).
        const showParkHere =
          isAuthenticated &&
          !activeSessionQuery.isPending &&
          !activeSession &&
          !showActiveError;
        const showSpotPreview = Boolean(selectedSpot);
        const showMunicipalPreview = Boolean(selectedMunicipalFacility);
        // Mobile: yield the bottom band to an expanded discovery sheet (same rule as
        // SelectedSpotPreview). Recenter FAB still focuses the car when controls show.
        const showBottomStack = isDesktop || sheetState === 'collapsed';
        if (
          !showBottomStack ||
          (!showActiveCard &&
            !showActiveError &&
            !showParkHere &&
            !showSpotPreview &&
            !showMunicipalPreview)
        ) {
          return null;
        }

        const stack = (
          <div className="flex flex-col gap-sm">
            {showActiveError ? (
              <ActiveParkingSessionErrorCard onRetry={() => void activeSessionQuery.refetch()} />
            ) : null}
            {showActiveCard && activeSession ? (
              <ActiveParkingSessionCard session={activeSession} onFocusCar={focusParkedCar} />
            ) : null}
            {showParkHere ? <ParkHereStartControl /> : null}
            {showSpotPreview && selectedSpot ? (
              <SelectedSpotPreview spot={selectedSpot} onClose={() => selectSpot(null)} />
            ) : null}
            {showMunicipalPreview && selectedMunicipalFacility ? (
              <SelectedMunicipalFacilityPreview
                facility={selectedMunicipalFacility}
                distanceMeters={selectedMunicipalDistance}
                onClose={() => selectMunicipalFacility(null)}
              />
            ) : null}
          </div>
        );

        if (isDesktop) {
          return (
            <div className="pointer-events-none absolute bottom-md left-md z-[1060] w-[360px]">
              {stack}
            </div>
          );
        }

        return (
          <div
            className="pointer-events-none absolute inset-x-0 z-[1060] px-sm"
            style={{ bottom: `calc(${COLLAPSED_PEEK}px + 0.5rem)` }}
          >
            {stack}
          </div>
        );
      })()}

      {/* Results — desktop sidebar vs mobile draggable bottom sheet. Exactly one
          mounts (media-query driven) so the discovery panel renders once. */}
      {isDesktop ? (
        <aside
          aria-label={t('sheet.searchResultsAria')}
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
          ariaLabel={t('sheet.searchResultsAria')}
          handleAriaLabel={t('sheet.handleAria', { state: t(`sheet.state.${sheetState}`) })}
          summary={
            <span
              className="block truncate text-label-md font-semibold text-on-surface"
              data-testid="map-sheet-summary"
              aria-live="polite"
              aria-atomic="true"
              aria-label={t('sheet.summaryLiveRegionAria')}
            >
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
