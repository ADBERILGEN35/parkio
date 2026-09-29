import { queryOptions } from '@tanstack/react-query';
import type { ParkingApi } from '@parkio/api-client';
import type { Destination, RecommendationRequest } from '@parkio/types';
import { assistantDestinationIdentityKey } from '@/lib/assistantUrlState';
import {
  ASSISTANT_RECOMMEND_LIMIT,
  ASSISTANT_RECOMMEND_RADIUS_METERS,
} from '@/lib/recommendationPresentation';
import { recommendationKeys } from '../keys';

export type RecommendParkingQueryInput = {
  destination: Destination;
  radiusMeters?: number;
  limit?: number;
  includeCommunity?: boolean;
  includeMunicipal?: boolean;
};

export function buildRecommendationRequest(
  input: RecommendParkingQueryInput,
): RecommendationRequest {
  return {
    destination: {
      label: input.destination.label,
      latitude: input.destination.latitude,
      longitude: input.destination.longitude,
      source: input.destination.source,
      placeIdentity: input.destination.placeIdentity
        ? {
            provider: input.destination.placeIdentity.provider,
            providerPlaceId: input.destination.placeIdentity.providerPlaceId,
          }
        : null,
      subtitle: input.destination.subtitle ?? null,
    },
    radiusMeters: input.radiusMeters ?? ASSISTANT_RECOMMEND_RADIUS_METERS,
    limit: input.limit ?? ASSISTANT_RECOMMEND_LIMIT,
    includeCommunity: input.includeCommunity ?? true,
    includeMunicipal: input.includeMunicipal ?? true,
  };
}

export function recommendationsQueryOptions(
  parkingApi: ParkingApi,
  input: RecommendParkingQueryInput,
) {
  const request = buildRecommendationRequest(input);
  const filters = {
    destKey: assistantDestinationIdentityKey(input.destination),
    radiusMeters: request.radiusMeters ?? ASSISTANT_RECOMMEND_RADIUS_METERS,
    limit: request.limit ?? ASSISTANT_RECOMMEND_LIMIT,
    includeCommunity: request.includeCommunity ?? true,
    includeMunicipal: request.includeMunicipal ?? true,
  };

  return queryOptions({
    queryKey: recommendationKeys.list(filters),
    queryFn: ({ signal }) => parkingApi.recommendParking(request, { signal }),
    staleTime: 30_000,
  });
}
