import { useCallback, useEffect, useRef, useState } from 'react';
import * as Location from 'expo-location';
import { useQuery } from '@tanstack/react-query';
import type { LatLng } from '@parkio/geo';
import type { GeocodeResult } from '@parkio/types';
import { accessPolicyQueryOptions } from '@/data/query-options/gamification';
import { placeSearchQueryOptions } from '@/data/query-options/geocoding';
import { nearbySpotsQueryOptions } from '@/data/query-options/parking';
import { readJson, writeJson } from '@/services/jsonStore';

/** Foreground location permission + one-shot position. */
export interface LocationState {
  status: 'unknown' | 'granted' | 'denied';
  position: LatLng | null;
  accuracy: number | null;
  request: () => Promise<LatLng | null>;
  refresh: () => Promise<LatLng | null>;
}

export function useLocation(): LocationState {
  const [status, setStatus] = useState<LocationState['status']>('unknown');
  const [position, setPosition] = useState<LatLng | null>(null);
  const [accuracy, setAccuracy] = useState<number | null>(null);

  const readPosition = useCallback(async (): Promise<LatLng | null> => {
    try {
      const location = await Location.getCurrentPositionAsync({
        accuracy: Location.Accuracy.Balanced,
      });
      const next = { lat: location.coords.latitude, lng: location.coords.longitude };
      setPosition(next);
      setAccuracy(location.coords.accuracy ?? null);
      return next;
    } catch {
      return null;
    }
  }, []);

  useEffect(() => {
    void (async () => {
      const current = await Location.getForegroundPermissionsAsync();
      if (current.granted) {
        setStatus('granted');
        void readPosition();
      } else {
        setStatus(current.canAskAgain ? 'unknown' : 'denied');
      }
    })();
  }, [readPosition]);

  const request = useCallback(async (): Promise<LatLng | null> => {
    const result = await Location.requestForegroundPermissionsAsync();
    if (result.granted) {
      setStatus('granted');
      return readPosition();
    }
    setStatus('denied');
    return null;
  }, [readPosition]);

  return { status, position, accuracy, request, refresh: readPosition };
}

/** Level-driven access policy (radius, result limit, daily views). */
export function useAccessPolicy() {
  return useQuery(accessPolicyQueryOptions());
}

/**
 * Nearby spots around a search center. Spots die in minutes — poll every 30s
 * while the screen is focused, and drop non-discoverable statuses client-side.
 */
export function useNearbySpots(center: LatLng | null, radius: number | undefined, limit: number | undefined) {
  return useQuery({
    ...nearbySpotsQueryOptions({
      lat: center?.lat ?? 0,
      lng: center?.lng ?? 0,
      ...(radius !== undefined ? { radius } : {}),
      ...(limit !== undefined ? { limit } : {}),
    }),
    enabled: center !== null,
    refetchInterval: 30_000,
  });
}

/** Debounced forward-geocoding typeahead (≥3 chars per backend contract). */
export function usePlaceSearch(query: string) {
  const [debounced, setDebounced] = useState(query);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(query), 300);
    return () => clearTimeout(id);
  }, [query]);

  const trimmed = debounced.trim();
  return useQuery({
    ...placeSearchQueryOptions(trimmed),
    enabled: trimmed.length >= 3,
    retry: false,
  });
}

/** Recent place searches, persisted (max 6). */
export function useRecentSearches() {
  const [recent, setRecent] = useState<GeocodeResult[]>([]);
  const loaded = useRef(false);

  useEffect(() => {
    void readJson<GeocodeResult[]>('recent-searches').then((stored) => {
      loaded.current = true;
      if (Array.isArray(stored)) {
        setRecent(stored);
      }
    });
  }, []);

  const push = useCallback((result: GeocodeResult) => {
    setRecent((current) => {
      const next = [result, ...current.filter((item) => item.id !== result.id)].slice(0, 6);
      void writeJson('recent-searches', next);
      return next;
    });
  }, []);

  return { recent, push };
}
