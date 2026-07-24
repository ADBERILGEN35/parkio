import { queryOptions } from '@tanstack/react-query';
import { geocodingApi } from '@/services/api';
import { geocodingKeys } from '../keys';

export function placeSearchQueryOptions(query: string) {
  const trimmed = query.trim();
  return queryOptions({
    queryKey: geocodingKeys.places(trimmed),
    queryFn: ({ signal }) => geocodingApi.searchPlaces(trimmed, 8, signal),
    enabled: trimmed.length >= 3,
    staleTime: 5 * 60_000,
  });
}
