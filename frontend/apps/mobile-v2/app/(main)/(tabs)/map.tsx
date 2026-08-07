import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Linking, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  LOCATED_ZOOM,
  NEARBY_RESULT_LIMIT,
  haversineMeters,
  type LatLng,
} from '@parkio/geo';
import type {
  AssistantDestinationOrigin,
  Destination,
  GeocodeResult,
  MunicipalFacility,
  ParkingCandidate,
} from '@parkio/types';
import { isLiveStatus } from '@/components/spots/statusVisuals';
import { appConfig } from '@/config/env';
import { MapSurface, type MapSurfaceHandle } from '@/features/map/MapSurface';
import { MapSearchOverlay } from '@/features/map/MapSearchOverlay';
import { LocationPermissionCard, ViewLimitCard } from '@/features/map/MapCards';
import { MapAreaStatusSheet } from '@/features/map/MapAreaStatusSheet';
import { MunicipalFacilitySheet } from '@/features/map/MunicipalFacilitySheet';
import { SpotSheet } from '@/features/map/SpotSheet';
import {
  useAccessPolicy,
  useLocation,
  useNearbyMunicipalFacilities,
  useNearbySpots,
} from '@/features/map/hooks';
import type { MapSpotMarker } from '@/features/map/mapHtml';
import {
  MunicipalFilterEntry,
  MunicipalFilterSheet,
} from '@/features/municipal/MunicipalFilterSheet';
import { MunicipalSummaryBanner } from '@/features/municipal/MunicipalSummaryBanner';
import { applyMunicipalMapFilters } from '@/features/municipal/municipalFilterPipeline';
import {
  useMunicipalFilterStore,
  useMunicipalMapFilters,
} from '@/features/municipal/municipalFilterStore';
import { hasActiveMunicipalMapFilters } from '@/features/municipal/municipalFilterModel';
import { toMapMunicipalMarkers } from '@/features/municipal/municipalMapMarker';
import { MorningPromptModal } from '@/features/smart-return/MorningPromptModal';
import { ActiveParkingSessionBanner } from '@/features/parking/ActiveParkingSessionBanner';
import { ParkHereStartControl } from '@/features/parking/ParkHereStartControl';
import { useActiveParkingSession } from '@/features/parking/useActiveParkingSession';
import { useParkingLocationActions } from '@/features/parking/useParkingLocationActions';
import { SmartReturnBanner } from '@/features/smart-return/SmartReturnBanner';
import {
  todayAt,
  todayKey,
  useSmartReturn,
  useSmartReturnMutations,
} from '@/features/smart-return/useSmartReturn';
import {
  AssistantEntryControl,
  ASSISTANT_RECOMMEND_RADIUS_METERS,
  DestinationSearchSheet,
  QuickActionsRow,
  RecommendationsSheet,
  useSmartParkingAssistant,
} from '@/features/smart-parking-assistant';
import { IconButton } from '@/components/ui/IconButton';
import { useShareSheetStore } from '@/features/share/shareSheetStore';
import { useT } from '@/i18n/LocaleProvider';
import { apiErrorCode } from '@/lib/apiErrors';
import { formatClock } from '@/lib/time';
import { readJson, writeJson } from '@/services/jsonStore';
import { useAuthStore } from '@/state/authStore';

const SEARCH_AREA_THRESHOLD_M = 250;

function isViewLimitCode(code: string | null): boolean {
  return Boolean(code && /VIEW/.test(code) && /LIMIT/.test(code));
}

export default function MapScreen() {
  const t = useT();
  const parkHereUserId = useAuthStore((state) => state.user?.id ?? 'anon');
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const mapRef = useRef<MapSurfaceHandle>(null);
  const openShareSheet = useShareSheetStore((s) => s.open);
  const municipalDiscovery = appConfig.features.municipalDiscovery;
  const spaEnabled = appConfig.features.smartParkingAssistant;
  const municipalFilters = useMunicipalMapFilters();
  const setMunicipalLayerEnabled = useMunicipalFilterStore((s) => s.setLayerEnabled);
  const setMunicipalSource = useMunicipalFilterStore((s) => s.setSource);
  const setMunicipalOccupancy = useMunicipalFilterStore((s) => s.setOccupancy);
  const setMunicipalRadiusMeters = useMunicipalFilterStore((s) => s.setRadiusMeters);
  const resetMunicipalFilters = useMunicipalFilterStore((s) => s.resetFilters);

  const location = useLocation();
  const policy = useAccessPolicy();
  const [searchCenter, setSearchCenter] = useState<LatLng | null>(null);
  const [viewCenter, setViewCenter] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [selectedSpotId, setSelectedSpotId] = useState<string | null>(null);
  const [selectedMunicipalId, setSelectedMunicipalId] = useState<string | null>(null);
  const [permissionDismissed, setPermissionDismissed] = useState(false);
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const [promptVisible, setPromptVisible] = useState(false);
  const [municipalFilterSheetOpen, setMunicipalFilterSheetOpen] = useState(false);

  const assistant = useSmartParkingAssistant({
    enabled: spaEnabled,
    municipalDiscoveryEnabled: municipalDiscovery,
  });

  const activeParkingSession = useActiveParkingSession();
  const parkedCarActions = useParkingLocationActions({
    sessionId: activeParkingSession.data?.id ?? null,
    latitude: activeParkingSession.data?.latitude,
    longitude: activeParkingSession.data?.longitude,
    terminalBusy: false,
  });

  const onQuickSelectDestination = useCallback(
    (destination: Destination, origin: AssistantDestinationOrigin) => {
      assistant.selectAssistantDestination(destination, origin);
    },
    [assistant],
  );

  const onQuickFavouriteParking = useCallback(
    (facilityId: string) => {
      setSelectedSpotId(null);
      if (municipalDiscovery && municipalFilters.layerEnabled) {
        setSelectedMunicipalId(facilityId);
      } else {
        setSelectedMunicipalId(null);
        router.push({
          pathname: '/(main)/facilities/[id]',
          params: { id: facilityId },
        });
      }
    },
    [municipalDiscovery, municipalFilters.layerEnabled, router],
  );

  const onQuickParkedCar = useCallback(() => {
    void parkedCarActions.navigate();
  }, [parkedCarActions]);

  const resultLimit = policy.data?.resultLimit ?? NEARBY_RESULT_LIMIT;
  const municipalRadiusMeters = municipalFilters.radiusMeters;
  const municipalLayerActive = municipalDiscovery && municipalFilters.layerEnabled;

  // When assistant has a destination, nearby search recenters on it (SPA radius).
  const effectiveSearchCenter = assistant.destination
    ? { lat: assistant.destination.latitude, lng: assistant.destination.longitude }
    : searchCenter;
  const communityRadius = assistant.destination
    ? ASSISTANT_RECOMMEND_RADIUS_METERS
    : policy.data?.searchRadiusMeters;

  const nearby = useNearbySpots(effectiveSearchCenter, communityRadius, policy.data?.resultLimit);
  const spots = useMemo(() => nearby.data ?? [], [nearby.data]);

  const municipalNearby = useNearbyMunicipalFacilities(
    municipalLayerActive ? effectiveSearchCenter : null,
    assistant.destination ? ASSISTANT_RECOMMEND_RADIUS_METERS : municipalRadiusMeters,
    resultLimit,
  );
  const municipalFacilitiesRaw = useMemo(
    () => (municipalDiscovery ? (municipalNearby.data ?? []) : []),
    [municipalDiscovery, municipalNearby.data],
  );

  const municipalPipeline = useMemo(
    () =>
      applyMunicipalMapFilters(municipalFacilitiesRaw, municipalFilters, {
        selectedId: selectedMunicipalId,
        resultLimit,
      }),
    [municipalFacilitiesRaw, municipalFilters, selectedMunicipalId, resultLimit],
  );
  const municipalFacilities = municipalPipeline.facilities;

  const smartReturn = useSmartReturn();
  const smartReturnMutations = useSmartReturnMutations();

  const located = useRef(false);
  useEffect(() => {
    if (location.position && !located.current && !assistant.destination) {
      located.current = true;
      mapRef.current?.flyTo({ ...location.position, zoom: LOCATED_ZOOM, silent: true });
      setSearchCenter(location.position);
      setViewCenter(location.position);
    }
  }, [assistant.destination, location.position]);

  useEffect(() => {
    mapRef.current?.setUserLocation(location.position);
  }, [location.position]);

  useEffect(() => {
    const markers: MapSpotMarker[] = spots.map((spot) => ({
      id: spot.id,
      lat: spot.latitude,
      lng: spot.longitude,
      createdAt: spot.createdAt,
      expiresAt: spot.expiresAt,
      live: isLiveStatus(spot.status),
      warning: spot.status === 'SUSPICIOUS',
    }));
    mapRef.current?.setSpots(markers);
  }, [spots]);

  useEffect(() => {
    if (!municipalDiscovery) {
      return;
    }
    if (!municipalFilters.layerEnabled) {
      mapRef.current?.setMunicipalFacilities([]);
      mapRef.current?.setSelectedMunicipal(null);
      return;
    }
    const markers = toMapMunicipalMarkers(municipalFacilities, {
      unnamedLabel: t('map.municipal.unnamed'),
      occupancyLabels: {
        live: t('map.municipal.occupancy.live'),
        aging: t('map.municipal.occupancy.aging'),
        stale_live: t('map.municipal.occupancy.staleLive'),
        static: t('map.municipal.occupancy.static'),
        invalid: t('map.municipal.occupancy.invalid'),
      },
    });
    mapRef.current?.setMunicipalFacilities(markers);
  }, [municipalDiscovery, municipalFilters.layerEnabled, municipalFacilities, t]);

  useEffect(() => {
    mapRef.current?.setSelected(selectedSpotId);
  }, [selectedSpotId]);

  const selectedSpot = useMemo(
    () => spots.find((spot) => spot.id === selectedSpotId) ?? null,
    [spots, selectedSpotId],
  );

  const selectedMunicipal: MunicipalFacility | null = useMemo(() => {
    if (!selectedMunicipalId || !municipalFilters.layerEnabled) return null;
    return municipalFacilities.find((facility) => facility.id === selectedMunicipalId) ?? null;
  }, [municipalFacilities, municipalFilters.layerEnabled, selectedMunicipalId]);

  useEffect(() => {
    if (!municipalDiscovery) {
      return;
    }
    mapRef.current?.setSelectedMunicipal(selectedMunicipal?.id ?? null);
  }, [municipalDiscovery, selectedMunicipal?.id]);

  // Assistant destination marker + recommendation highlights (flag-off: clear).
  useEffect(() => {
    if (!spaEnabled || !assistant.destination) {
      mapRef.current?.setDestinationMarker(null);
      mapRef.current?.setRecommendedHighlights(null);
      return;
    }
    mapRef.current?.setDestinationMarker({
      lat: assistant.destination.latitude,
      lng: assistant.destination.longitude,
      label: t('assistant.destinationMarkerA11y', { label: assistant.destination.label }),
    });
    const top = assistant.topCandidate;
    mapRef.current?.setRecommendedHighlights({
      communityIds: assistant.recommendedCommunityIds,
      municipalIds: assistant.recommendedMunicipalIds,
      topCommunityId: top?.channel === 'COMMUNITY_SPOT' ? top.refId : null,
      topMunicipalId: top?.channel === 'MUNICIPAL_FACILITY' ? top.refId : null,
    });
  }, [
    assistant.destination,
    assistant.recommendedCommunityIds,
    assistant.recommendedMunicipalIds,
    assistant.topCandidate,
    spaEnabled,
    t,
  ]);

  // Reframe map when destination is confirmed / hydrated.
  const lastFlownDestKey = useRef<string | null>(null);
  useEffect(() => {
    if (!spaEnabled || !assistant.destination) {
      lastFlownDestKey.current = null;
      return;
    }
    const key = `${assistant.destination.latitude}:${assistant.destination.longitude}`;
    if (lastFlownDestKey.current === key) return;
    lastFlownDestKey.current = key;
    const target = {
      lat: assistant.destination.latitude,
      lng: assistant.destination.longitude,
    };
    mapRef.current?.flyTo({ ...target, zoom: LOCATED_ZOOM, silent: true });
    setSearchCenter(target);
    setViewCenter(target);
  }, [assistant.destination, spaEnabled]);

  useEffect(() => {
    const settings = smartReturn.data;
    if (
      settings?.enabled &&
      settings.homeLatitude !== null &&
      settings.todayStatus === 'UNKNOWN'
    ) {
      void readJson<string>('sr-prompt-shown').then((shown) => {
        if (shown !== todayKey()) {
          setPromptVisible(true);
        }
      });
    }
  }, [smartReturn.data]);

  const dismissPrompt = useCallback(() => {
    setPromptVisible(false);
    void writeJson('sr-prompt-shown', todayKey());
  }, []);

  const searchHere = useCallback(() => {
    setSearchCenter(viewCenter);
  }, [viewCenter]);

  const locate = useCallback(async () => {
    const position =
      location.status === 'granted' ? await location.refresh() : await location.request();
    if (position) {
      mapRef.current?.flyTo({ ...position, zoom: LOCATED_ZOOM, silent: true });
      setSearchCenter(position);
      setViewCenter(position);
    }
  }, [location]);

  const pickPlace = useCallback((place: GeocodeResult) => {
    const target = { lat: place.lat, lng: place.lng };
    mapRef.current?.flyTo({ ...target, zoom: LOCATED_ZOOM, silent: true });
    setSearchCenter(target);
    setViewCenter(target);
  }, []);

  const selectSpot = useCallback(
    (id: string) => {
      setSelectedMunicipalId(null);
      setSelectedSpotId(id);
      if (spaEnabled && assistant.destination) {
        const match = assistant.recommendations.data?.candidates.find(
          (c) => c.channel === 'COMMUNITY_SPOT' && c.refId === id,
        );
        if (match) assistant.selectCandidate(match);
      }
    },
    [assistant, spaEnabled],
  );

  const selectMunicipal = useCallback(
    (id: string) => {
      setSelectedSpotId(null);
      setSelectedMunicipalId(id);
      if (spaEnabled && assistant.destination) {
        const match = assistant.recommendations.data?.candidates.find(
          (c) => c.channel === 'MUNICIPAL_FACILITY' && c.refId === id,
        );
        if (match) assistant.selectCandidate(match);
      }
    },
    [assistant, spaEnabled],
  );

  const clearSelection = useCallback(() => {
    setSelectedSpotId(null);
    setSelectedMunicipalId(null);
    if (spaEnabled) assistant.selectCandidate(null);
  }, [assistant, spaEnabled]);

  const onSelectRecommendation = useCallback(
    (candidate: ParkingCandidate) => {
      assistant.selectCandidate(candidate);
      if (candidate.channel === 'COMMUNITY_SPOT') {
        setSelectedMunicipalId(null);
        setSelectedSpotId(candidate.refId);
        mapRef.current?.flyTo({
          lat: candidate.latitude,
          lng: candidate.longitude,
          zoom: LOCATED_ZOOM,
          silent: true,
        });
      } else {
        setSelectedSpotId(null);
        if (municipalDiscovery && municipalFilters.layerEnabled) {
          setSelectedMunicipalId(candidate.refId);
        } else if (municipalDiscovery) {
          setSelectedMunicipalId(null);
          router.push({
            pathname: '/(main)/facilities/[id]',
            params: { id: candidate.refId },
          });
        }
        mapRef.current?.flyTo({
          lat: candidate.latitude,
          lng: candidate.longitude,
          zoom: LOCATED_ZOOM,
          silent: true,
        });
      }
    },
    [assistant, municipalDiscovery, municipalFilters.layerEnabled, router],
  );

  const movedAway =
    !assistant.destination &&
    searchCenter !== null &&
    haversineMeters(viewCenter, searchCenter) > SEARCH_AREA_THRESHOLD_M;

  const nearbyErrorCode = nearby.isError ? apiErrorCode(nearby.error) : null;
  const viewLimited = isViewLimitCode(nearbyErrorCode);

  const distanceFrom = location.position ?? effectiveSearchCenter;
  const selectedDistance =
    selectedSpot && distanceFrom
      ? haversineMeters(distanceFrom, { lat: selectedSpot.latitude, lng: selectedSpot.longitude })
      : null;
  const selectedMunicipalDistance =
    selectedMunicipal && distanceFrom
      ? haversineMeters(distanceFrom, {
          lat: selectedMunicipal.latitude,
          lng: selectedMunicipal.longitude,
        })
      : null;

  const showPermissionCard =
    location.status !== 'granted' &&
    !permissionDismissed &&
    searchCenter === null &&
    !assistant.destination;
  const showEmptyCard =
    !showPermissionCard &&
    !viewLimited &&
    effectiveSearchCenter !== null &&
    nearby.isSuccess &&
    spots.length === 0 &&
    !selectedSpot &&
    !selectedMunicipal &&
    !assistant.destination;
  const showBanner =
    Boolean(smartReturn.data?.enabled) &&
    (smartReturn.data?.todayStatus === 'LEFT_BY_CAR' ||
      smartReturn.data?.todayStatus === 'RETURN_CHECK_IN_PROGRESS') &&
    !bannerDismissed;

  const radiusChip = policy.data
    ? t('map.radiusChip', {
        radius:
          policy.data.searchRadiusMeters >= 1000
            ? `${(policy.data.searchRadiusMeters / 1000).toFixed(1).replace(/\.0$/, '')} km`
            : `${policy.data.searchRadiusMeters} m`,
        level: policy.data.currentLevel,
      })
    : null;

  const radiusOnlyLabel = policy.data
    ? policy.data.searchRadiusMeters >= 1000
      ? `${(policy.data.searchRadiusMeters / 1000).toFixed(1).replace(/\.0$/, '')} km`
      : `${policy.data.searchRadiusMeters} m`
    : null;

  const sheetOpen = Boolean(selectedSpot || selectedMunicipal);
  const showMunicipalSummary =
    municipalLayerActive && effectiveSearchCenter != null && !showPermissionCard && !sheetOpen;
  const showRecommendations =
    spaEnabled && assistant.destination != null && !assistant.searchOpen;

  return (
    <View style={styles.container}>
      <MapSurface
        ref={mapRef}
        initialCenter={DEFAULT_MAP_CENTER}
        initialZoom={DEFAULT_MAP_ZOOM}
        onSpotTap={selectSpot}
        onMunicipalTap={municipalLayerActive ? selectMunicipal : undefined}
        onMapTap={clearSelection}
        onMoveEnd={(event) => setViewCenter({ lat: event.lat, lng: event.lng })}
        style={styles.map}
      />

      <View style={[styles.topOverlay, { top: insets.top + 8 }]} pointerEvents="box-none">
        <MapSearchOverlay
          radiusChip={radiusChip}
          onRadiusChipPress={() => router.push('/(main)/impact')}
          showSearchArea={movedAway}
          onSearchArea={searchHere}
          onLocate={locate}
          onPickPlace={pickPlace}
        />
        {spaEnabled ? <AssistantEntryControl onPress={() => assistant.openSearch()} /> : null}
        {spaEnabled && !assistant.destination ? (
          <QuickActionsRow
            enabled={spaEnabled}
            visible
            onSelectDestination={onQuickSelectDestination}
            onOpenSearch={() => assistant.openSearch()}
            onParkedCar={onQuickParkedCar}
            onSelectFavouriteParking={onQuickFavouriteParking}
          />
        ) : null}
        {municipalDiscovery ? (
          <View style={styles.municipalChrome} pointerEvents="box-none">
            <MunicipalFilterEntry
              filters={municipalFilters}
              onPress={() => setMunicipalFilterSheetOpen(true)}
            />
            <MunicipalSummaryBanner
              visible={showMunicipalSummary}
              summary={municipalPipeline.summary}
              emptyReason={municipalPipeline.emptyReason}
              resultLimitReached={municipalPipeline.resultLimitReached}
              resultLimit={resultLimit}
              loading={municipalNearby.isFetching && !municipalNearby.data}
              showReset={hasActiveMunicipalMapFilters(municipalFilters)}
              onResetFilters={resetMunicipalFilters}
            />
          </View>
        ) : null}
        {showBanner && smartReturn.data ? (
          <SmartReturnBanner
            settings={smartReturn.data}
            onPress={() => router.push('/(main)/smart-return')}
            onDismiss={() => setBannerDismissed(true)}
          />
        ) : null}
        <ActiveParkingSessionBanner />
        <ParkHereStartControl key={parkHereUserId} location={location} />
        {showPermissionCard ? (
          <LocationPermissionCard
            canAskAgain={location.canAskAgain}
            onAllow={() => void locate()}
            onOpenSettings={() => void Linking.openSettings()}
            onDismiss={() => setPermissionDismissed(true)}
          />
        ) : null}
        {viewLimited && policy.data ? (
          <ViewLimitCard
            level={policy.data.currentLevel}
            limit={policy.data.dailyViewLimit}
            onLevelUp={() => router.push('/(main)/impact')}
          />
        ) : null}
      </View>

      {!sheetOpen && !showRecommendations ? (
        <View
          style={[styles.fabColumn, showEmptyCard && styles.fabAboveEmpty]}
          pointerEvents="box-none"
        >
          <IconButton
            icon="crosshairs-gps"
            size={48}
            variant="surface"
            elevated
            accessibilityLabel={t('share.location.useMyLocation')}
            onPress={() => void locate()}
          />
        </View>
      ) : null}

      <MapAreaStatusSheet
        visible={showEmptyCard}
        radiusLabel={radiusOnlyLabel}
        level={policy.data?.currentLevel ?? null}
        lastRefreshedAt={nearby.dataUpdatedAt ? new Date(nearby.dataUpdatedAt) : null}
        onShare={() => openShareSheet('map-empty-cta')}
      />

      {showRecommendations && assistant.destination ? (
        <RecommendationsSheet
          destination={assistant.destination}
          recommendations={assistant.recommendations}
          selectedCandidateId={assistant.candidateId}
          onSelectCandidate={onSelectRecommendation}
          onChangeDestination={() => assistant.openSearch()}
          onClearDestination={() => {
            clearSelection();
            assistant.clearDestination();
          }}
          suppressed={sheetOpen}
        />
      ) : null}

      <SpotSheet
        spot={selectedSpot}
        distanceMeters={selectedDistance}
        onClose={() => setSelectedSpotId(null)}
        onOpenDetail={(spotId) =>
          router.push({ pathname: '/(main)/spots/[id]', params: { id: spotId } })
        }
      />

      {municipalDiscovery ? (
        <MunicipalFacilitySheet
          facility={selectedMunicipal}
          distanceMeters={selectedMunicipalDistance}
          onClose={() => setSelectedMunicipalId(null)}
          onOpenDetail={(facilityId) => {
            setSelectedMunicipalId(null);
            router.push({
              pathname: '/(main)/facilities/[id]',
              params: {
                id: facilityId,
                ...(selectedMunicipalDistance != null
                  ? { distanceMeters: String(Math.round(selectedMunicipalDistance)) }
                  : {}),
              },
            });
          }}
        />
      ) : null}

      {municipalDiscovery ? (
        <MunicipalFilterSheet
          visible={municipalFilterSheetOpen}
          onClose={() => setMunicipalFilterSheetOpen(false)}
          filters={municipalFilters}
          onLayerEnabledChange={setMunicipalLayerEnabled}
          onSourceChange={setMunicipalSource}
          onOccupancyChange={setMunicipalOccupancy}
          onRadiusChange={setMunicipalRadiusMeters}
          onReset={resetMunicipalFilters}
        />
      ) : null}

      {spaEnabled ? (
        <DestinationSearchSheet
          open={assistant.searchOpen}
          onClose={() => assistant.closeSearch()}
          onSelect={(item) => assistant.confirmDestination(item)}
        />
      ) : null}

      <MorningPromptModal
        visible={promptVisible}
        defaultTime={
          smartReturn.data?.defaultReturnTime ??
          (smartReturn.data?.todayExpectedReturnAt
            ? formatClock(smartReturn.data.todayExpectedReturnAt)
            : '18:00')
        }
        submitting={
          smartReturnMutations.leftByCar.isPending || smartReturnMutations.notByCar.isPending
        }
        onYes={(hours, minutes) => {
          smartReturnMutations.leftByCar.mutate(todayAt(hours, minutes), {
            onSettled: dismissPrompt,
          });
        }}
        onNo={() => smartReturnMutations.notByCar.mutate(undefined, { onSettled: dismissPrompt })}
        onDismiss={dismissPrompt}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  map: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  topOverlay: { position: 'absolute', left: 12, right: 12, gap: 8 },
  municipalChrome: { gap: 8 },
  fabColumn: { position: 'absolute', right: 14, bottom: 24 },
  fabAboveEmpty: { bottom: 128 },
});
