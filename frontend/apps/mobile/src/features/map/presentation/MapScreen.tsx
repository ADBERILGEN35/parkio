import { Ionicons } from '@expo/vector-icons';
import { useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, StyleSheet, useWindowDimensions, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import {
  DEFAULT_MAP_CENTER,
  DEFAULT_MAP_ZOOM,
  DEFAULT_NEARBY_RADIUS_M,
  haversineMeters,
  LOCATED_ZOOM,
  type LatLng,
} from '@parkio/geo';
import { AppText } from '@/components/ui';
import { useOnlineStatus } from '@/hooks/useOnlineStatus';
import { useTheme } from '@/theme';
import { LocationPermissionCard } from '../components/LocationPermissionCard';
import { MapControls } from '../components/MapControls';
import { MapSearchBar } from '../components/MapSearchBar';
import { SearchThisAreaButton } from '../components/SearchThisAreaButton';
import { SmartReturnBanner } from '../components/SmartReturnBanner';
import { SpotSheet } from '../components/SpotSheet';
import { useLocation } from '../hooks/useLocation';
import { useNearbySpots } from '../hooks/useNearbySpots';
import { useSmartReturnHome } from '../hooks/useSmartReturnHome';
import { MapSurface } from '../webmap/MapSurface';
import type { MapRegion, MapSurfaceHandle } from '../webmap/types';

/** Min camera move (m) from the committed center before "Search this area" shows. */
const SEARCH_AREA_THRESHOLD_M = Math.max(350, DEFAULT_NEARBY_RADIUS_M * 0.4);

/**
 * Map & Discovery — the primary mobile experience. Composes the isolated map
 * renderer with location, nearby discovery (explicit-center, debounced, cancelable),
 * place search, the spot sheet, and the full set of permission/loading/empty/
 * offline/error states. Smart Return deep links center on the saved home and
 * auto-search. Built mobile-first; no fabricated data.
 */
export function MapScreen() {
  const theme = useTheme();
  const insets = useSafeAreaInsets();
  const { height: screenH } = useWindowDimensions();
  const online = useOnlineStatus();
  const params = useLocalSearchParams<{ smartReturn?: string }>();
  const isSmartReturn = params.smartReturn === '1' || params.smartReturn === 'true';

  const mapRef = useRef<MapSurfaceHandle>(null);
  const location = useLocation();
  const smartHome = useSmartReturnHome(isSmartReturn);

  const [searchCenter, setSearchCenter] = useState<LatLng | null>(null);
  const [region, setRegion] = useState<MapRegion | null>(null);
  const [selectedSpotId, setSelectedSpotId] = useState<string | null>(null);
  const [following, setFollowing] = useState(false);
  const [smartBannerVisible, setSmartBannerVisible] = useState(isSmartReturn);
  const [mapReady, setMapReady] = useState(false);

  const nearby = useNearbySpots({ center: searchCenter, enabled: online !== false });
  const selectedSpot = useMemo(
    () => nearby.spots.find((s) => s.id === selectedSpotId) ?? null,
    [nearby.spots, selectedSpotId],
  );

  const commitCenter = useCallback((center: LatLng, follow: boolean) => {
    setSearchCenter(center);
    setFollowing(follow);
    mapRef.current?.setCamera(center, LOCATED_ZOOM, true);
  }, []);

  // Auto-fetch a position when permission is already granted (no prompt).
  useEffect(() => {
    if (location.permission === 'granted' && !location.location && !location.loading) {
      void location.refresh();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.permission]);

  // Smart Return: center on saved home + auto nearby search. One-shot init —
  // the `searchCenter === null` guard means this commits exactly once when both
  // the map and the async home location are ready, so it can't cascade renders.
  useEffect(() => {
    if (mapReady && isSmartReturn && smartHome.home && searchCenter === null) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot, null-guarded sync to async map/home readiness
      commitCenter(smartHome.home, false);
      setSmartBannerVisible(true);
    }
  }, [mapReady, isSmartReturn, smartHome.home, searchCenter, commitCenter]);

  // Normal launch: once we have the user's location, center + search there.
  // Same one-shot guard — fires once when the first fix arrives after map ready.
  useEffect(() => {
    if (mapReady && !isSmartReturn && location.location && searchCenter === null) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot, null-guarded sync to async location readiness
      commitCenter(location.location, true);
    }
  }, [mapReady, isSmartReturn, location.location, searchCenter, commitCenter]);

  // moveend already fires only once per gesture (never per-frame), but drop
  // updates where center/zoom are effectively unchanged so a settle-back move
  // can't trigger a no-op MapScreen re-render.
  const onRegionChange = useCallback((next: MapRegion) => {
    setRegion((prev) => {
      if (
        prev &&
        Math.abs(prev.center.lat - next.center.lat) < 1e-5 &&
        Math.abs(prev.center.lng - next.center.lng) < 1e-5 &&
        Math.abs(prev.zoom - next.zoom) < 1e-3
      ) {
        return prev;
      }
      return next;
    });
  }, []);

  const onRecenter = useCallback(async () => {
    const loc = await location.request();
    if (loc) {
      setSelectedSpotId(null);
      commitCenter(loc, true);
    }
  }, [location, commitCenter]);

  const onSearchThisArea = useCallback(() => {
    if (!region) return;
    setSelectedSpotId(null);
    setSearchCenter(region.center);
    setFollowing(false);
  }, [region]);

  const onSelectPlace = useCallback(
    (place: { primary: string; lat: number; lng: number }) => {
      setSelectedSpotId(null);
      commitCenter({ lat: place.lat, lng: place.lng }, false);
    },
    [commitCenter],
  );

  const movedDistance = region && searchCenter ? haversineMeters(region.center, searchCenter) : 0;
  const showSearchThisArea =
    mapReady && searchCenter !== null && movedDistance > SEARCH_AREA_THRESHOLD_M;

  // Layout offsets (a native header is shown by the route).
  const topBase = 10;
  const sheetPeek = Math.round(screenH * 0.32);
  const controlsBottom = (selectedSpot ? sheetPeek : insets.bottom) + 20;

  return (
    <View style={styles.fill}>
      <MapSurface
        ref={mapRef}
        initialCenter={DEFAULT_MAP_CENTER}
        initialZoom={DEFAULT_MAP_ZOOM}
        spots={nearby.spots}
        selectedSpotId={selectedSpotId}
        userLocation={location.location}
        onReady={() => setMapReady(true)}
        onRegionChange={onRegionChange}
        onSpotPress={setSelectedSpotId}
        onMapPress={() => setSelectedSpotId(null)}
      />

      {!mapReady ? (
        <View style={[styles.cover, { backgroundColor: theme.colors.background }]}>
          <ActivityIndicator color={theme.colors.primary} />
          <AppText variant="caption" tone="muted">
            Loading map…
          </AppText>
        </View>
      ) : null}

      <MapSearchBar topOffset={topBase} onSelectPlace={onSelectPlace} />

      <SmartReturnBanner
        visible={smartBannerVisible && !selectedSpot}
        topOffset={topBase + 64}
        onDismiss={() => setSmartBannerVisible(false)}
      />

      <SearchThisAreaButton
        visible={showSearchThisArea && !smartBannerVisible}
        loading={nearby.isFetching}
        topOffset={topBase + 64}
        onPress={onSearchThisArea}
      />

      <DiscoveryStatus
        online={online}
        center={searchCenter}
        isFetching={nearby.isFetching}
        isError={nearby.isError}
        isSuccess={nearby.isSuccess}
        count={nearby.spots.length}
        cacheUpdatedAt={nearby.cacheUpdatedAt}
        isShowingCached={nearby.isShowingCached}
        onRetry={nearby.refetch}
        bottomOffset={insets.bottom + 20}
        visible={!selectedSpot && location.permission === 'granted'}
      />

      <MapControls
        bottomOffset={controlsBottom}
        following={following}
        locating={location.loading}
        onRecenter={onRecenter}
      />

      <LocationPermissionCard
        permission={location.permission}
        loading={location.loading || location.permission === 'prompting'}
        bottomOffset={insets.bottom + 20}
        onEnable={onRecenter}
        onOpenSettings={location.openSettings}
      />

      <SpotSheet spot={selectedSpot} onClose={() => setSelectedSpotId(null)} />
    </View>
  );
}

/** Compact discovery status pill: loading / results count / empty / offline / error. */
function DiscoveryStatus({
  online,
  center,
  isFetching,
  isError,
  isSuccess,
  count,
  cacheUpdatedAt,
  isShowingCached,
  onRetry,
  bottomOffset,
  visible,
}: {
  online: boolean | null;
  center: LatLng | null;
  isFetching: boolean;
  isError: boolean;
  isSuccess: boolean;
  count: number;
  cacheUpdatedAt: number | null;
  isShowingCached: boolean;
  onRetry: () => void;
  bottomOffset: number;
  visible: boolean;
}) {
  const theme = useTheme();
  if (!visible || center === null) return null;

  let icon: keyof typeof Ionicons.glyphMap = 'car-outline';
  let text = '';
  let action: (() => void) | null = null;

  if (online === false) {
    icon = 'cloud-offline-outline';
    text =
      count > 0
        ? `Offline — saved spots${cacheUpdatedAt ? ` (${formatCacheAge(cacheUpdatedAt)})` : ''}`
        : 'You’re offline';
    action = isShowingCached ? onRetry : null;
  } else if (isFetching && count === 0) {
    icon = 'sync-outline';
    text = 'Finding parking nearby…';
  } else if (isError) {
    icon = 'warning-outline';
    text = 'Couldn’t load spots';
    action = onRetry;
  } else if (isSuccess && count === 0) {
    icon = 'search-outline';
    text = 'No spots in this area';
  } else if (count > 0) {
    icon = 'car-outline';
    text = `${count} spot${count === 1 ? '' : 's'} nearby`;
  } else {
    return null;
  }

  return (
    <View style={[statusStyles.wrap, { bottom: bottomOffset }]} pointerEvents="box-none">
      <View
        accessibilityRole={action ? 'button' : 'summary'}
        accessibilityLabel={text}
        onTouchEnd={action ?? undefined}
        style={[
          statusStyles.pill,
          {
            backgroundColor: theme.colors.surface,
            borderColor: theme.colors.border,
            borderRadius: theme.radius.full,
            ...theme.elevation.floating,
          },
        ]}
      >
        <Ionicons name={icon} size={16} color={theme.colors.primary} />
        <AppText variant="label">{text}</AppText>
        {action ? (
          <AppText variant="label" tone="primary">
            {'  '}Retry
          </AppText>
        ) : null}
      </View>
    </View>
  );
}

function formatCacheAge(updatedAt: number) {
  const minutes = Math.max(0, Math.round((Date.now() - updatedAt) / 60_000));
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m old`;
  const hours = Math.round(minutes / 60);
  return `${hours}h old`;
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  cover: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
  },
});

const statusStyles = StyleSheet.create({
  wrap: { position: 'absolute', left: 0, right: 0, alignItems: 'center' },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingVertical: 10,
    paddingHorizontal: 18,
    borderWidth: 1,
  },
});
