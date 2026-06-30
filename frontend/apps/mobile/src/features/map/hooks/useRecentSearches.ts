import { useCallback, useEffect, useState } from 'react';
import * as SecureStore from 'expo-secure-store';
import type { GeocodeResult } from '@parkio/types';

const STORAGE_KEY = 'parkio.recentSearches';
const MAX_RECENT = 5;

/** The fields we persist for a recent place (a subset of {@link GeocodeResult}). */
export type RecentSearch = Pick<GeocodeResult, 'id' | 'primary' | 'secondary' | 'lat' | 'lng'>;

function sanitize(raw: unknown): RecentSearch[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .filter(
      (r): r is RecentSearch =>
        !!r &&
        typeof r.id === 'string' &&
        typeof r.primary === 'string' &&
        typeof r.lat === 'number' &&
        typeof r.lng === 'number',
    )
    .slice(0, MAX_RECENT);
}

export interface UseRecentSearchesResult {
  recents: RecentSearch[];
  add: (place: GeocodeResult) => void;
  clear: () => void;
}

/**
 * Recent place searches, persisted locally only (never sent anywhere). Stored in
 * the device keystore via `expo-secure-store` — small, private, and wiped with
 * the app. Capped at {@link MAX_RECENT}, most-recent-first, de-duplicated by id.
 */
export function useRecentSearches(): UseRecentSearchesResult {
  const [recents, setRecents] = useState<RecentSearch[]>([]);

  useEffect(() => {
    let active = true;
    void SecureStore.getItemAsync(STORAGE_KEY)
      .then((json) => {
        if (!active || !json) return;
        try {
          setRecents(sanitize(JSON.parse(json)));
        } catch {
          /* corrupt value — ignore */
        }
      })
      .catch(() => {
        /* keystore locked — treat as empty */
      });
    return () => {
      active = false;
    };
  }, []);

  const persist = useCallback((next: RecentSearch[]) => {
    setRecents(next);
    void SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(next)).catch(() => {});
  }, []);

  const add = useCallback(
    (place: GeocodeResult) => {
      const entry: RecentSearch = {
        id: place.id,
        primary: place.primary,
        secondary: place.secondary,
        lat: place.lat,
        lng: place.lng,
      };
      setRecents((prev) => {
        const next = [entry, ...prev.filter((r) => r.id !== entry.id)].slice(0, MAX_RECENT);
        void SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify(next)).catch(() => {});
        return next;
      });
    },
    [],
  );

  const clear = useCallback(() => persist([]), [persist]);

  return { recents, add, clear };
}
