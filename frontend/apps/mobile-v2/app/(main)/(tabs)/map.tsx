import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Linking, StyleSheet, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  DEFAULT_NEARBY_RADIUS_M,
  LOCATED_ZOOM,
  NEARBY_RESULT_LIMIT,
  haversineMeters,
  type LatLng,
} from '@parkio/geo';
import type { GeocodeResult, MunicipalFacility } from '@parkio/types';
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
import { toMapMunicipalMarkers } from '@/features/municipal/municipalMapMarker';
import { MorningPromptModal } from '@/features/smart-return/MorningPromptModal';
import { ActiveParkingSessionBanner } from '@/features/parking/ActiveParkingSessionBanner';
import { ParkHereStartControl } from '@/features/parking/ParkHereStartControl';
import { SmartReturnBanner } from '@/features/smart-return/SmartReturnBanner';
import {
  todayAt,
  todayKey,
  useSmartReturn,
  useSmartReturnMutations,
} from '@/features/smart-return/useSmartReturn';
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

  const location = useLocation();
  const policy = useAccessPolicy();
  const [searchCenter, setSearchCenter] = useState<LatLng | null>(null);
  const [viewCenter, setViewCenter] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [selectedSpotId, setSelectedSpotId] = useState<string | null>(null);
  const [selectedMunicipalId, setSelectedMunicipalId] = useState<string | null>(null);
  const [permissionDismissed, setPermissionDismissed] = useState(false);
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const [promptVisible, setPromptVisible] = useState(false);

  const searchRadiusMeters = policy.data?.searchRadiusMeters ?? DEFAULT_NEARBY_RADIUS_M;
  const resultLimit = policy.data?.resultLimit ?? NEARBY_RESULT_LIMIT;

  const nearby = useNearbySpots(searchCenter, policy.data?.searchRadiusMeters, policy.data?.resultLimit);
  const spots = useMemo(() => nearby.data ?? [], [nearby.data]);

  const municipalNearby = useNearbyMunicipalFacilities(
    municipalDiscovery ? searchCenter : null,
    searchRadiusMeters,
    resultLimit,
  );
  const municipalFacilities = useMemo(
    () => (municipalDiscovery ? (municipalNearby.data ?? []) : []),
    [municipalDiscovery, municipalNearby.data],
  );

  const smartReturn = useSmartReturn();
  const smartReturnMutations = useSmartReturnMutations();

  // First fix: fly to the user and search there. viewCenter follows the
  // programmatic move so "search this area" doesn't appear spuriously.
  const located = useRef(false);
  useEffect(() => {
    if (location.position && !located.current) {
      located.current = true;
      mapRef.current?.flyTo({ ...location.position, zoom: LOCATED_ZOOM, silent: true });
      setSearchCenter(location.position);
      setViewCenter(location.position);
    }
  }, [location.position]);

  useEffect(() => {
    mapRef.current?.setUserLocation(location.position);
  }, [location.position]);

  // Push community markers into the WebView whenever data changes.
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

  // Municipal markers — flag-off: never emit municipal bridge messages.
  useEffect(() => {
    if (!municipalDiscovery) {
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
  }, [municipalDiscovery, municipalFacilities, t]);

  useEffect(() => {
    mapRef.current?.setSelected(selectedSpotId);
  }, [selectedSpotId]);

  // Selected spot may expire out of the result set — drop the stale selection.
  const selectedSpot = useMemo(
    () => spots.find((spot) => spot.id === selectedSpotId) ?? null,
    [spots, selectedSpotId],
  );

  // If the facility leaves the nearby result set, selectedMunicipal becomes null
  // (sheet closes / bridge deselects) without a cascading setState effect.
  const selectedMunicipal: MunicipalFacility | null = useMemo(() => {
    if (!selectedMunicipalId) return null;
    return municipalFacilities.find((facility) => facility.id === selectedMunicipalId) ?? null;
  }, [municipalFacilities, selectedMunicipalId]);

  useEffect(() => {
    if (!municipalDiscovery) {
      return;
    }
    // Prefer resolved facility id so markers deselect when the facility leaves radius.
    mapRef.current?.setSelectedMunicipal(selectedMunicipal?.id ?? null);
  }, [municipalDiscovery, selectedMunicipal?.id]);

  // Morning prompt: once per day when enabled + configured + unanswered.
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
    const position = location.status === 'granted' ? await location.refresh() : await location.request();
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

  const selectSpot = useCallback((id: string) => {
    setSelectedMunicipalId(null);
    setSelectedSpotId(id);
  }, []);

  const selectMunicipal = useCallback((id: string) => {
    setSelectedSpotId(null);
    setSelectedMunicipalId(id);
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedSpotId(null);
    setSelectedMunicipalId(null);
  }, []);

  const movedAway =
    searchCenter !== null && haversineMeters(viewCenter, searchCenter) > SEARCH_AREA_THRESHOLD_M;

  const nearbyErrorCode = nearby.isError ? apiErrorCode(nearby.error) : null;
  const viewLimited = isViewLimitCode(nearbyErrorCode);

  const distanceFrom = location.position ?? searchCenter;
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
    location.status !== 'granted' && !permissionDismissed && searchCenter === null;
  const showEmptyCard =
    !showPermissionCard &&
    !viewLimited &&
    searchCenter !== null &&
    nearby.isSuccess &&
    spots.length === 0 &&
    !selectedSpot &&
    !selectedMunicipal;
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

  return (
    <View style={styles.container}>
      <MapSurface
        ref={mapRef}
        initialCenter={DEFAULT_MAP_CENTER}
        initialZoom={DEFAULT_MAP_ZOOM}
        onSpotTap={selectSpot}
        onMunicipalTap={municipalDiscovery ? selectMunicipal : undefined}
        onMapTap={clearSelection}
        onMoveEnd={(event) => setViewCenter({ lat: event.lat, lng: event.lng })}
        style={styles.map}
      />

      {/* Floating chrome */}
      <View style={[styles.topOverlay, { top: insets.top + 8 }]} pointerEvents="box-none">
        <MapSearchOverlay
          radiusChip={radiusChip}
          onRadiusChipPress={() => router.push('/(main)/impact')}
          showSearchArea={movedAway}
          onSearchArea={searchHere}
          onLocate={locate}
          onPickPlace={pickPlace}
        />
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

      {/* Locate FAB above the tab bar (hidden while the sheet is open). */}
      {!sheetOpen && (
        <View style={[styles.fabColumn, showEmptyCard && styles.fabAboveEmpty]} pointerEvents="box-none">
          <IconButton
            icon="crosshairs-gps"
            size={48}
            variant="surface"
            elevated
            accessibilityLabel={t('share.location.useMyLocation')}
            onPress={() => void locate()}
          />
        </View>
      )}

      <MapAreaStatusSheet
        visible={showEmptyCard}
        radiusLabel={radiusOnlyLabel}
        level={policy.data?.currentLevel ?? null}
        lastRefreshedAt={nearby.dataUpdatedAt ? new Date(nearby.dataUpdatedAt) : null}
        onShare={() => openShareSheet('map-empty-cta')}
      />

      <SpotSheet
        spot={selectedSpot}
        distanceMeters={selectedDistance}
        onClose={() => setSelectedSpotId(null)}
        onOpenDetail={(spotId) => router.push({ pathname: '/(main)/spots/[id]', params: { id: spotId } })}
      />

      {municipalDiscovery ? (
        <MunicipalFacilitySheet
          facility={selectedMunicipal}
          distanceMeters={selectedMunicipalDistance}
          onClose={() => setSelectedMunicipalId(null)}
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
        submitting={smartReturnMutations.leftByCar.isPending || smartReturnMutations.notByCar.isPending}
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
  fabColumn: { position: 'absolute', right: 14, bottom: 24 },
  fabAboveEmpty: { bottom: 128 },
});
