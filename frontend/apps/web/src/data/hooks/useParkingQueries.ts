import { useQuery } from '@tanstack/react-query';
import type { NearbySearchParams } from '@parkio/types';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import {
  municipalFacilityDetailQueryOptions,
  mySpotsQueryOptions,
  nearbyMunicipalFacilitiesQueryOptions,
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

/** Municipal facilities nearby — enable only when WEB-MUNI discovery flag is on. */
export function useNearbyMunicipalFacilitiesQuery(
  params: NearbySearchParams | null,
  options?: { enabled?: boolean },
) {
  const sdk = useParkioSdk();
  return useQuery({
    ...nearbyMunicipalFacilitiesQueryOptions(sdk, params ?? { lat: 0, lng: 0 }),
    enabled: (options?.enabled ?? true) && params !== null,
  });
}

export function useMunicipalFacilityDetailQuery(
  facilityId: string | null,
  options?: { enabled?: boolean },
) {
  const sdk = useParkioSdk();
  return useQuery({
    ...municipalFacilityDetailQueryOptions(sdk, facilityId ?? ''),
    enabled: (options?.enabled ?? true) && Boolean(facilityId),
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
