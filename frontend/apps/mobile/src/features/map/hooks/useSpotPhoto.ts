import { useQuery } from '@tanstack/react-query';
import { parkingApi } from '@/services/api';

/**
 * Fetch a short-lived signed photo URL for a spot, on demand. URLs expire (~5m),
 * so this is cached only briefly and refetched when stale. Returns `null` URL
 * until resolved; failures degrade silently (the sheet just hides the photo).
 */
export function useSpotPhoto(spotId: string | null) {
  const query = useQuery({
    queryKey: ['parking', 'spot-photo', spotId],
    enabled: spotId !== null,
    queryFn: () => parkingApi.getSpotMediaAccessUrl(spotId!),
    staleTime: 4 * 60_000,
    retry: 1,
  });
  return {
    url: query.data?.accessUrl ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
  };
}
