import { useQuery } from '@tanstack/react-query';
import type { GeocodeResult } from '@parkio/types';
import { geocodingApi } from '@/services/api';
import { useDebouncedValue } from './useDebouncedValue';

/** Backend validates 3–256 chars; don't fire below the minimum. */
const MIN_QUERY_LENGTH = 3;
const GEOCODE_LIMIT = 6;
const DEBOUNCE_MS = 320;

export interface UsePlaceSearchResult {
  results: GeocodeResult[];
  /** True while a (debounced) query is resolving. */
  isSearching: boolean;
  isError: boolean;
  /** True when the trimmed query is long enough to search. */
  isActive: boolean;
}

/**
 * Debounced forward-geocoding autocomplete over the shared geocoding API.
 * Each keystroke debounces; react-query forwards an `AbortSignal` so superseded
 * requests are cancelled. Country/language bias lives server-side (ADR-014).
 */
export function usePlaceSearch(query: string): UsePlaceSearchResult {
  const debounced = useDebouncedValue(query.trim(), DEBOUNCE_MS);
  const isActive = debounced.length >= MIN_QUERY_LENGTH;

  const result = useQuery({
    queryKey: ['geocode', debounced],
    enabled: isActive,
    queryFn: ({ signal }) => geocodingApi.searchPlaces(debounced, GEOCODE_LIMIT, signal),
    staleTime: 5 * 60_000,
  });

  return {
    results: isActive && result.data ? result.data : [],
    isSearching: isActive && result.isFetching,
    isError: result.isError,
    isActive,
  };
}
