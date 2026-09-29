import { queryOptions, keepPreviousData } from '@tanstack/react-query';
import type { PublicExploreDiscovery } from '@parkio/types';
import {
  publicExploreKeys,
  type PublicExploreFilters,
} from '@/data/keys';
import {
  PUBLIC_EXPLORE_LIMIT,
  PUBLIC_EXPLORE_RADIUS_METERS,
} from '@/features/public-explore/constants';
import { publicExploreApi } from '@/services/api';

/** Clamp client request to certified public contract (server also clamps). */
export function clampPublicExploreFilters(
  filters: PublicExploreFilters,
): PublicExploreFilters {
  return {
    lat: filters.lat,
    lng: filters.lng,
    radiusMeters: Math.min(
      Math.max(Math.trunc(filters.radiusMeters), 1),
      PUBLIC_EXPLORE_RADIUS_METERS,
    ),
    limit: Math.min(Math.max(Math.trunc(filters.limit), 1), PUBLIC_EXPLORE_LIMIT),
  };
}

/**
 * Anonymous public Explore discovery.
 * Never shares keys or queryFns with {@link nearbyMunicipalFacilitiesQueryOptions}.
 */
export function publicExploreQueryOptions(filters: PublicExploreFilters) {
  const clamped = clampPublicExploreFilters(filters);
  return queryOptions({
    queryKey: publicExploreKeys.facilities(clamped),
    queryFn: ({ signal }): Promise<PublicExploreDiscovery> =>
      publicExploreApi.list({
        lat: clamped.lat,
        lng: clamped.lng,
        radiusMeters: clamped.radiusMeters,
        limit: clamped.limit,
        signal,
      }),
    placeholderData: keepPreviousData,
    staleTime: 15_000,
    retry: false,
  });
}
