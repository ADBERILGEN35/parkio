import { useQuery } from '@tanstack/react-query';
import type { GeocodeResult } from '@parkio/types';
import type { LatLng } from '@parkio/geo';
import { geocodingApi } from '@/services/api';
import { useDebouncedValue } from '@/features/map/hooks/useDebouncedValue';

/** ~1m grid: dedupes cache keys while the pin settles on the same street. */
const COORD_PRECISION = 5;
const DEBOUNCE_MS = 400;

export interface SelectedPlace {
  /** Short label, e.g. "Alemdar Caddesi". */
  primary: string;
  /** Context line, e.g. "Cankurtaran Mahallesi, İstanbul". */
  secondary: string;
}

export interface UseSelectedPlaceResult {
  place: SelectedPlace | null;
  /** True while the current pin position is still resolving. */
  isResolving: boolean;
  /** True when resolution failed or returned nothing — show a neutral fallback. */
  isUnresolved: boolean;
}

/**
 * Resolves the pinned coordinate to a human place label using the existing
 * forward-geocoding endpoint: the provider resolves `"lat,lng"` queries to the
 * nearest address, so no reverse-geocoding backend is needed. Debounced so
 * dragging the map doesn't spam the API; react-query caches per ~1m grid cell.
 * Display-only — the submitted request is untouched.
 */
export function useSelectedPlace(center: LatLng | null): UseSelectedPlaceResult {
  const key = center ? `${center.lat.toFixed(COORD_PRECISION)},${center.lng.toFixed(COORD_PRECISION)}` : null;
  const debouncedKey = useDebouncedValue(key, DEBOUNCE_MS);

  const query = useQuery({
    queryKey: ['spot-create', 'place-label', debouncedKey],
    enabled: debouncedKey !== null,
    queryFn: async ({ signal }): Promise<SelectedPlace | null> => {
      const results: GeocodeResult[] = await geocodingApi.searchPlaces(debouncedKey as string, 1, signal);
      const first = results[0];
      return first ? { primary: first.primary, secondary: first.secondary } : null;
    },
    staleTime: 5 * 60_000,
    retry: 1,
  });

  const waitingForDebounce = key !== null && key !== debouncedKey;
  return {
    place: query.data ?? null,
    isResolving: key !== null && (waitingForDebounce || query.isFetching),
    isUnresolved: !query.isFetching && !waitingForDebounce && key !== null && (query.isError || query.data === null),
  };
}
