import { queryOptions } from '@tanstack/react-query';
import { publicGeocodingKeys } from '@/data/keys';
import { publicGeocodingApi } from '@/services/api';

/** Certified public autocomplete limit (R6G / web Public Explore). */
export const PUBLIC_GEOCODING_RESULT_LIMIT = 5;

/**
 * Anonymous destination search — GET /public/geocoding/search.
 * Never shares keys or queryFns with private {@link placeSearchQueryOptions}.
 */
export function publicPlaceSearchQueryOptions(query: string) {
  const trimmed = query.trim();
  return queryOptions({
    queryKey: publicGeocodingKeys.places(trimmed),
    queryFn: ({ signal }) =>
      publicGeocodingApi.searchPlaces(trimmed, PUBLIC_GEOCODING_RESULT_LIMIT, signal),
    enabled: trimmed.length >= 3,
    staleTime: 5 * 60_000,
    retry: false,
  });
}
