import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  LOCATED_ZOOM,
  haversineMeters,
  type LatLng,
} from '@parkio/geo';
import type { GeocodeResult, MunicipalFacility } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { publicExploreQueryOptions } from '@/data/query-options/publicExplore';
import { AuthGate } from '@/features/auth/AuthGate';
import type { AuthGateIntent } from '@/features/auth/authGateIntents';
import { MapSurface, type MapSurfaceHandle } from '@/features/map/MapSurface';
import { MunicipalFacilitySheet } from '@/features/map/MunicipalFacilitySheet';
import { useLocation } from '@/features/map/hooks';
import {
  toMapMunicipalMarkers,
  type MunicipalMarkerBuildOptions,
} from '@/features/municipal/municipalMapMarker';
import {
  PUBLIC_EXPLORE_LIMIT,
  PUBLIC_EXPLORE_RADIUS_METERS,
} from '@/features/public-explore/constants';
import {
  buildFitDiscoveryFrame,
  discoveryFrameRevision,
} from '@/features/public-explore/fitDiscoveryFrame';
import { PublicExploreContributeCta } from '@/features/public-explore/PublicExploreContributeCta';
import { PublicExploreSummary } from '@/features/public-explore/PublicExploreSummary';
import { PublicExploreTeasers } from '@/features/public-explore/PublicExploreTeasers';
import { PublicMapSearchOverlay } from '@/features/public-explore/PublicMapSearchOverlay';
import { toRenderablePublicFacilities } from '@/features/public-explore/renderablePublicFacilities';
import { toMunicipalFacilityFromPublicExplore } from '@/features/public-explore/toMunicipalFacilityFromPublicExplore';
import { selectPublicFacilityForPreview } from '@/features/public-explore/selectPublicFacilityForPreview';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Anonymous public Explore — municipal facilities from public explore +
 * destination search via public geocoding. Never calls private parking/geocoding.
 */
export function PublicExploreScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const location = useLocation();
  const mapRef = useRef<MapSurfaceHandle>(null);
  const framedRevisionRef = useRef<string | null>(null);

  const [mapCenter, setMapCenter] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [discoveryOrigin, setDiscoveryOrigin] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [selectedDestination, setSelectedDestination] = useState<GeocodeResult | null>(null);
  const [gpsAdopted, setGpsAdopted] = useState(false);
  const [searchActive, setSearchActive] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [mapReady, setMapReady] = useState(false);
  const [authGate, setAuthGate] = useState<{
    intent: AuthGateIntent;
    facilityId?: string;
  } | null>(null);

  const userLocation = location.position;
  const [prevUserLocation, setPrevUserLocation] = useState(userLocation);

  // Promote granted GPS into discovery origin once — never overwrite an active destination.
  // Adjust during render when location changes (React-recommended alternative to syncing effects).
  if (userLocation !== prevUserLocation) {
    setPrevUserLocation(userLocation);
    if (userLocation && !gpsAdopted && !selectedDestination) {
      setGpsAdopted(true);
      setDiscoveryOrigin(userLocation);
      setMapCenter(userLocation);
    }
  }

  const exploreQuery = useQuery(
    publicExploreQueryOptions({
      lat: discoveryOrigin.lat,
      lng: discoveryOrigin.lng,
      radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
      limit: PUBLIC_EXPLORE_LIMIT,
    }),
  );

  const envelope = exploreQuery.data;
  const municipalHiddenCount = envelope?.municipalHiddenCount ?? 0;
  const communitySpotCountInScope = envelope?.communitySpotCountInScope ?? null;
  void envelope?.municipalTotalInScope;

  const renderable = useMemo(
    () => toRenderablePublicFacilities(envelope?.facilities ?? []),
    [envelope?.facilities],
  );

  const municipalFacilities = useMemo(
    () => renderable.map(toMunicipalFacilityFromPublicExplore),
    [renderable],
  );

  const markerOptions = useMemo<MunicipalMarkerBuildOptions>(
    () => ({
      unnamedLabel: t('map.municipal.unnamed'),
      occupancyLabels: {
        live: t('map.municipal.occupancy.live'),
        aging: t('map.municipal.occupancy.aging'),
        stale_live: t('map.municipal.occupancy.staleLive'),
        static: t('map.municipal.occupancy.static'),
        invalid: t('map.municipal.occupancy.invalid'),
      },
    }),
    [t],
  );

  const markers = useMemo(
    () => toMapMunicipalMarkers(municipalFacilities, markerOptions),
    [municipalFacilities, markerOptions],
  );

  const selectedFacility: MunicipalFacility | null = useMemo(
    () => selectPublicFacilityForPreview(renderable, selectedId),
    [renderable, selectedId],
  );

  const selectedDistance = useMemo(() => {
    if (!selectedFacility || !userLocation) return null;
    return haversineMeters(userLocation, {
      lat: selectedFacility.latitude,
      lng: selectedFacility.longitude,
    });
  }, [selectedFacility, userLocation]);

  // Push markers + clear community spots (hard anonymity).
  useEffect(() => {
    if (!mapReady) return;
    mapRef.current?.setSpots([]);
    mapRef.current?.setMunicipalFacilities(markers);
    mapRef.current?.setSelectedMunicipal(selectedId);
  }, [mapReady, markers, selectedId]);

  useEffect(() => {
    if (!mapReady) return;
    mapRef.current?.setUserLocation(userLocation);
  }, [mapReady, userLocation]);

  useEffect(() => {
    if (!mapReady) return;
    if (selectedDestination) {
      mapRef.current?.setDestinationMarker({
        lat: selectedDestination.lat,
        lng: selectedDestination.lng,
        label: selectedDestination.primary,
      });
    } else {
      mapRef.current?.setDestinationMarker(null);
    }
  }, [mapReady, selectedDestination]);

  // Fit discoveryOrigin (user or destination) + renderable municipal pins.
  useEffect(() => {
    if (!mapReady || exploreQuery.isFetching) return;
    if (markers.length === 0) return;
    const revision = discoveryFrameRevision(
      discoveryOrigin,
      markers.map((m) => m.id),
    );
    if (framedRevisionRef.current === revision) return;
    const frame = buildFitDiscoveryFrame(
      discoveryOrigin,
      markers.map((m) => ({ lat: m.lat, lng: m.lng })),
    );
    if (!frame) return;
    framedRevisionRef.current = revision;
    mapRef.current?.fitBounds({ ...frame, silent: true });
  }, [mapReady, exploreQuery.isFetching, discoveryOrigin, markers]);

  const applyDiscoveryOrigin = useCallback((origin: LatLng) => {
    setDiscoveryOrigin(origin);
    setMapCenter(origin);
    framedRevisionRef.current = null;
  }, []);

  const onPickPlace = useCallback(
    (place: GeocodeResult) => {
      setSelectedDestination(place);
      setSelectedId(null);
      applyDiscoveryOrigin({ lat: place.lat, lng: place.lng });
    },
    [applyDiscoveryOrigin],
  );

  const onClearDestination = useCallback(() => {
    setSelectedDestination(null);
    const origin = userLocation ?? DEFAULT_MAP_CENTER;
    applyDiscoveryOrigin(origin);
    if (userLocation) {
      mapRef.current?.flyTo({ ...userLocation, zoom: LOCATED_ZOOM, silent: true });
    } else {
      mapRef.current?.flyTo({ ...DEFAULT_MAP_CENTER, zoom: DEFAULT_MAP_ZOOM, silent: true });
    }
  }, [applyDiscoveryOrigin, userLocation]);

  const locate = useCallback(async () => {
    const position = (await location.request()) ?? (await location.refresh());
    if (!position) {
      // Denial / unavailable — do not silently clear a valid destination.
      if (selectedDestination) return;
      applyDiscoveryOrigin(DEFAULT_MAP_CENTER);
      mapRef.current?.flyTo({ ...DEFAULT_MAP_CENTER, zoom: DEFAULT_MAP_ZOOM, silent: true });
      return;
    }
    // Successful locate clears destination and rediscovers around user.
    setSelectedDestination(null);
    setGpsAdopted(true);
    applyDiscoveryOrigin(position);
    mapRef.current?.setUserLocation(position);
    mapRef.current?.flyTo({ ...position, zoom: LOCATED_ZOOM, silent: true });
  }, [applyDiscoveryOrigin, location, selectedDestination]);

  const onMunicipalTap = useCallback((id: string) => {
    setSelectedId(id);
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedId(null);
  }, []);

  const openAuthGate = useCallback((intent: AuthGateIntent, facilityId?: string) => {
    setAuthGate(facilityId ? { intent, facilityId } : { intent });
  }, []);

  const onOpenFacilityDetailGate = useCallback(
    (facilityId: string) => {
      // AuthGate only — never call private facility detail anonymously.
      openAuthGate('facility-detail', facilityId);
    },
    [openAuthGate],
  );

  const sheetOpen = selectedFacility != null;
  const showDiscoveryChrome = !sheetOpen && !searchActive;
  const showLocationHint =
    !searchActive && (location.status === 'denied' || location.status === 'unknown');

  return (
    <View style={[styles.container, { backgroundColor: theme.colors.background }]}>
      <MapSurface
        ref={mapRef}
        initialCenter={mapCenter}
        initialZoom={DEFAULT_MAP_ZOOM}
        interactiveSpots
        onReady={() => setMapReady(true)}
        onMunicipalTap={onMunicipalTap}
        onMapTap={clearSelection}
        onMoveEnd={(event) => setMapCenter({ lat: event.lat, lng: event.lng })}
        style={styles.map}
      />

      <View style={[styles.topOverlay, { top: insets.top + 8 }]} pointerEvents="box-none">
        {!searchActive ? (
          <Glass radius={20} style={styles.headerGlass} contentStyle={styles.headerContent}>
            <View style={styles.headerText}>
              <AppText variant="titleMd" accessibilityRole="header">
                {t('publicExplore.title')}
              </AppText>
              <AppText variant="bodySm" color={theme.colors.onSurfaceVariant} numberOfLines={2}>
                {t('publicExplore.subtitle')}
              </AppText>
            </View>
            <Button
              label={t('publicExplore.signIn')}
              variant="tonal"
              size="sm"
              onPress={() => router.push('/(auth)/login')}
            />
          </Glass>
        ) : null}

        <PublicMapSearchOverlay
          selectedDestination={selectedDestination}
          onPickPlace={onPickPlace}
          onClearDestination={onClearDestination}
          onLocate={() => void locate()}
          onSearchActiveChange={setSearchActive}
        />

        <PublicExploreSummary
          visible={showDiscoveryChrome}
          loading={exploreQuery.isFetching}
          error={exploreQuery.isError}
          visibleCount={markers.length}
          municipalHiddenCount={municipalHiddenCount}
          onMunicipalHiddenPress={() => openAuthGate('municipal-hidden')}
          onRetry={() => void exploreQuery.refetch()}
        />

        <PublicExploreTeasers
          visible={showDiscoveryChrome}
          communitySpotCountInScope={communitySpotCountInScope}
          onCommunityPress={() => openAuthGate('community-teaser')}
        />

        <PublicExploreContributeCta
          visible={showDiscoveryChrome}
          onPress={() => openAuthGate('contribute')}
        />

        {showLocationHint ? (
          <Glass radius={16} style={styles.locationHint} contentStyle={styles.locationHintContent}>
            <AppText variant="bodySm" color={theme.colors.onSurfaceVariant}>
              {location.status === 'denied'
                ? t('publicExplore.location.denied')
                : t('publicExplore.location.prompt')}
            </AppText>
            {location.status !== 'denied' || location.canAskAgain ? (
              <Button
                label={t('publicExplore.location.allow')}
                variant="ghost"
                size="sm"
                onPress={() => void locate()}
              />
            ) : null}
          </Glass>
        ) : null}
      </View>

      {!sheetOpen && !searchActive ? (
        <View
          style={[styles.fabColumn, { bottom: insets.bottom + 24 }]}
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

      <MunicipalFacilitySheet
        facility={selectedFacility}
        distanceMeters={selectedDistance}
        onClose={clearSelection}
        parkHereEnabled={false}
        openDetailLabel={t('publicExplore.preview.viewDetails')}
        onOpenDetail={onOpenFacilityDetailGate}
      />

      <AuthGate
        visible={authGate != null}
        intent={authGate?.intent ?? 'facility-detail'}
        resume={authGate?.facilityId ? { facilityId: authGate.facilityId } : undefined}
        onDismiss={() => setAuthGate(null)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  map: { ...StyleSheet.absoluteFill },
  topOverlay: {
    position: 'absolute',
    left: 12,
    right: 12,
    gap: 8,
    zIndex: 2,
  },
  headerGlass: { alignSelf: 'stretch' },
  headerContent: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  headerText: { flex: 1, gap: 2 },
  locationHint: { alignSelf: 'stretch' },
  locationHintContent: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  fabColumn: {
    position: 'absolute',
    right: 16,
    zIndex: 2,
  },
});
