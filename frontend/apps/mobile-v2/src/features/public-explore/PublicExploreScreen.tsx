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
import type { MunicipalFacility } from '@parkio/types';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';
import { Glass } from '@/components/ui/Glass';
import { IconButton } from '@/components/ui/IconButton';
import { publicExploreQueryOptions } from '@/data/query-options/publicExplore';
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
import { PublicExploreSummary } from '@/features/public-explore/PublicExploreSummary';
import { toRenderablePublicFacilities } from '@/features/public-explore/renderablePublicFacilities';
import { toMunicipalFacilityFromPublicExplore } from '@/features/public-explore/toMunicipalFacilityFromPublicExplore';
import { selectPublicFacilityForPreview } from '@/features/public-explore/selectPublicFacilityForPreview';
import { useT } from '@/i18n/LocaleProvider';
import { useTheme } from '@/theme/ThemeProvider';

/**
 * Anonymous public Explore map — municipal facilities from
 * GET /public/explore/facilities only. Never calls private parking nearby/detail.
 */
export function PublicExploreScreen() {
  const theme = useTheme();
  const t = useT();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const location = useLocation();
  const mapRef = useRef<MapSurfaceHandle>(null);
  const framedRevisionRef = useRef<string | null>(null);
  const locatedOnceRef = useRef(false);

  const [mapCenter, setMapCenter] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [discoveryOrigin, setDiscoveryOrigin] = useState<LatLng>(DEFAULT_MAP_CENTER);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [mapReady, setMapReady] = useState(false);

  const userLocation = location.position;

  // Promote granted GPS into discovery origin once (does not track live GPS continuously).
  useEffect(() => {
    if (!userLocation || locatedOnceRef.current) return;
    locatedOnceRef.current = true;
    setDiscoveryOrigin(userLocation);
    setMapCenter(userLocation);
  }, [userLocation]);

  const exploreQuery = useQuery(
    publicExploreQueryOptions({
      lat: discoveryOrigin.lat,
      lng: discoveryOrigin.lng,
      radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
      limit: PUBLIC_EXPLORE_LIMIT,
    }),
  );

  // Envelope fields retained for Wave 3 (+N / community aggregate) — not shown yet.
  const envelope = exploreQuery.data;
  void envelope?.municipalTotalInScope;
  void envelope?.municipalHiddenCount;
  void envelope?.communitySpotCountInScope;

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

  // Fit anchor + renderable municipal pins after each discovery response.
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

  const locate = useCallback(async () => {
    const position = (await location.request()) ?? (await location.refresh());
    if (!position) {
      // Denial / unavailable — keep Explore usable on fallback origin.
      setDiscoveryOrigin(DEFAULT_MAP_CENTER);
      setMapCenter(DEFAULT_MAP_CENTER);
      framedRevisionRef.current = null;
      mapRef.current?.flyTo({ ...DEFAULT_MAP_CENTER, zoom: DEFAULT_MAP_ZOOM, silent: true });
      return;
    }
    setDiscoveryOrigin(position);
    setMapCenter(position);
    framedRevisionRef.current = null;
    mapRef.current?.setUserLocation(position);
    mapRef.current?.flyTo({ ...position, zoom: LOCATED_ZOOM, silent: true });
  }, [location]);

  const onMunicipalTap = useCallback((id: string) => {
    setSelectedId(id);
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedId(null);
  }, []);

  const sheetOpen = selectedFacility != null;
  const showSummary = !sheetOpen;

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

        <PublicExploreSummary
          visible={showSummary}
          loading={exploreQuery.isFetching}
          error={exploreQuery.isError}
          visibleCount={markers.length}
          onRetry={() => void exploreQuery.refetch()}
        />

        {location.status === 'denied' || location.status === 'unknown' ? (
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

      {!sheetOpen ? (
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
        // Wave 1: no private detail / park-here — preview only from public DTO.
        parkHereEnabled={false}
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
