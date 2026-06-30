import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import * as SecureStore from 'expo-secure-store';
import {
  DEFAULT_NEARBY_RADIUS_M,
  NEARBY_RESULT_LIMIT,
  withDistance,
  type LatLng,
  type SpotWithDistance,
} from '@parkio/geo';
import { parkingApi } from '@/services/api';

const CACHE_KEY = 'parkio.map.lastNearbySearch';

export interface UseNearbySpotsArgs {
  /** The committed search center (user location / searched place / "search this area"). */
  center: LatLng | null;
  radiusMeters?: number;
  /** Gate the query (e.g. until a center is known). */
  enabled?: boolean;
}

export interface UseNearbySpotsResult {
  spots: SpotWithDistance[];
  cacheUpdatedAt: number | null;
  isShowingCached: boolean;
  isLoading: boolean;
  isFetching: boolean;
  isError: boolean;
  /** True once a fetch has resolved at least once for the current center. */
  isSuccess: boolean;
  refetch: () => void;
}

interface NearbyCache {
  center: LatLng;
  radiusMeters: number;
  spots: SpotWithDistance[];
  updatedAt: number;
}

/** Stable, low-cardinality query key — coordinates are rounded to ~11m. */
function nearbyKey(center: LatLng, radius: number) {
  return ['parking', 'nearby', center.lat.toFixed(4), center.lng.toFixed(4), radius] as const;
}

function sanitizeCache(raw: unknown): NearbyCache | null {
  if (!raw || typeof raw !== 'object') return null;
  const cache = raw as Partial<NearbyCache>;
  if (
    !cache.center ||
    typeof cache.center.lat !== 'number' ||
    typeof cache.center.lng !== 'number' ||
    typeof cache.radiusMeters !== 'number' ||
    typeof cache.updatedAt !== 'number' ||
    !Array.isArray(cache.spots)
  ) {
    return null;
  }
  return {
    center: cache.center,
    radiusMeters: cache.radiusMeters,
    spots: cache.spots.filter(
      (spot): spot is SpotWithDistance =>
        !!spot &&
        typeof spot.id === 'string' &&
        typeof spot.latitude === 'number' &&
        typeof spot.longitude === 'number',
    ),
    updatedAt: cache.updatedAt,
  };
}

/**
 * Nearby spots for an explicit search center.
 *
 * - Cancellation: the react-query `signal` is forwarded to axios, so a center
 *   change aborts the in-flight request (no stale results, no backend waste).
 * - Spam control: only a *committed* center triggers a fetch — panning the map
 *   never auto-fetches (the "Search this area" affordance commits a new center).
 * - Offline/loading polish: `keepPreviousData` retains the last results while a
 *   new center loads, so the map never flashes empty.
 * - Distances are enriched from the real search center via shared `withDistance`.
 */
export function useNearbySpots({
  center,
  radiusMeters = DEFAULT_NEARBY_RADIUS_M,
  enabled = true,
}: UseNearbySpotsArgs): UseNearbySpotsResult {
  const [cache, setCache] = useState<NearbyCache | null>(null);
  const persistedSignatureRef = useRef<string | null>(null);

  useEffect(() => {
    let active = true;
    void SecureStore.getItemAsync(CACHE_KEY)
      .then((json) => {
        if (!active || !json) return;
        try {
          setCache(sanitizeCache(JSON.parse(json)));
        } catch {
          setCache(null);
        }
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

  const query = useQuery({
    queryKey: center ? nearbyKey(center, radiusMeters) : ['parking', 'nearby', 'none'],
    enabled: enabled && center !== null,
    queryFn: ({ signal }) =>
      parkingApi.getNearbySpots(
        { lat: center!.lat, lng: center!.lng, radius: radiusMeters, limit: NEARBY_RESULT_LIMIT },
        signal,
      ),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
  });

  useEffect(() => {
    if (!center || !query.data || !query.isSuccess) return;
    const signature = JSON.stringify({
      center: nearbyKey(center, radiusMeters),
      spots: query.data.map((spot) => [spot.id, spot.status, spot.updatedAt]),
    });
    if (persistedSignatureRef.current === signature) return;
    persistedSignatureRef.current = signature;
    const next: NearbyCache = {
      center,
      radiusMeters,
      spots: withDistance(query.data, center),
      updatedAt: Date.now(),
    };
    setCache(next);
    void SecureStore.setItemAsync(CACHE_KEY, JSON.stringify(next)).catch(() => {});
  }, [center, query.data, query.isSuccess, radiusMeters]);

  const showingCache = enabled === false && cache !== null;
  const spots = useMemo(() => {
    if (query.data && center) return withDistance(query.data, center);
    if (showingCache) return cache.spots;
    return [];
  }, [cache, center, query.data, showingCache]);

  return {
    spots,
    cacheUpdatedAt: showingCache ? cache?.updatedAt ?? null : null,
    isShowingCached: showingCache,
    isLoading: query.isLoading,
    isFetching: query.isFetching,
    isError: query.isError,
    isSuccess: query.isSuccess,
    refetch: () => void query.refetch(),
  };
}
