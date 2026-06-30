import { useQuery } from '@tanstack/react-query';
import { isValidLatLng, type LatLng } from '@parkio/geo';
import { usersApi } from '@/services/api';

export interface SmartReturnHome {
  home: LatLng | null;
  label: string | null;
  isLoading: boolean;
}

/**
 * The user's saved Smart Return home location, used to center the map when
 * opened from a Smart Return entry point. Only fetched when `enabled` (i.e. the
 * `smartReturn` deep-link param is present), reusing the existing endpoint.
 */
export function useSmartReturnHome(enabled: boolean): SmartReturnHome {
  const query = useQuery({
    queryKey: ['users', 'smart-return'],
    enabled,
    queryFn: () => usersApi.getSmartReturn(),
    staleTime: 5 * 60_000,
  });

  const home =
    query.data && isValidLatLng(query.data.homeLatitude, query.data.homeLongitude)
      ? { lat: query.data.homeLatitude as number, lng: query.data.homeLongitude as number }
      : null;

  return { home, label: query.data?.homeLabel ?? null, isLoading: query.isLoading };
}
