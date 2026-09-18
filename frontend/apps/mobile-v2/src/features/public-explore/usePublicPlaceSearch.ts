import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { publicPlaceSearchQueryOptions } from '@/data/query-options/publicGeocoding';

/** Match certified web/public Explore debounce. Authenticated search stays at 300ms. */
export const PUBLIC_PLACE_SEARCH_DEBOUNCE_MS = 400;

/**
 * Debounced public destination typeahead (≥3 chars).
 * Uses AbortSignal via React Query (latest query wins). Never retries 429.
 */
export function usePublicPlaceSearch(query: string) {
  const [debounced, setDebounced] = useState(query);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(query), PUBLIC_PLACE_SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(id);
  }, [query]);

  const trimmed = debounced.trim();
  return useQuery({
    ...publicPlaceSearchQueryOptions(trimmed),
    enabled: trimmed.length >= 3,
    retry: false,
  });
}
