import { useQuery } from '@tanstack/react-query';
import type { NearbySearchParams } from '@parkio/types';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import {
  mySpotsQueryOptions,
  nearbySpotsQueryOptions,
  spotDetailQueryOptions,
  spotMediaAccessUrlQueryOptions,
} from '../query-options/parking';

export function useMySpotsQuery() {
  const sdk = useParkioSdk();
  return useQuery(mySpotsQueryOptions(sdk));
}

export function useNearbySpotsQuery(params: NearbySearchParams | null) {
  const sdk = useParkioSdk();
  return useQuery({
    ...nearbySpotsQueryOptions(
      sdk,
      params ?? { lat: 0, lng: 0 },
    ),
    enabled: params !== null,
  });
}

export function useSpotDetailQuery(spotId: string) {
  const sdk = useParkioSdk();
  return useQuery({
    ...spotDetailQueryOptions(sdk, spotId),
    enabled: Boolean(spotId),
  });
}

export function useSpotMediaAccessUrlQuery(spotId: string, options?: { enabled?: boolean }) {
  const sdk = useParkioSdk();
  return useQuery({
    ...spotMediaAccessUrlQueryOptions(sdk, spotId),
    enabled: (options?.enabled ?? true) && Boolean(spotId),
  });
}
