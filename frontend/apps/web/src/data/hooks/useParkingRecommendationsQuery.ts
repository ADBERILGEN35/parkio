import { useQuery } from '@tanstack/react-query';
import type { Destination } from '@parkio/types';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import {
  recommendationsQueryOptions,
  type RecommendParkingQueryInput,
} from '../query-options/recommendations';

export function useParkingRecommendationsQuery(
  destination: Destination | null,
  options?: {
    enabled?: boolean;
    includeMunicipal?: boolean;
    includeCommunity?: boolean;
    radiusMeters?: number;
    limit?: number;
  },
) {
  const sdk = useParkioSdk();
  const enabled = (options?.enabled ?? true) && destination != null;

  const input: RecommendParkingQueryInput = {
    destination: destination ?? {
      label: '_',
      latitude: 0,
      longitude: 0,
      source: 'GEOCODING',
    },
    includeMunicipal: options?.includeMunicipal,
    includeCommunity: options?.includeCommunity,
    radiusMeters: options?.radiusMeters,
    limit: options?.limit,
  };

  return useQuery({
    ...recommendationsQueryOptions(sdk.parkingApi, input),
    enabled,
  });
}
