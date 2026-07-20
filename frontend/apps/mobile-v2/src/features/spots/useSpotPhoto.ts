import { useQuery } from '@tanstack/react-query';
import { parkingApi } from '@/services/api';

/**
 * Signed photo URL for a spot. URLs expire (~5 min server-side) — cache just
 * under that and refetch on demand; never persist.
 */
export function useSpotPhoto(spotId: string | null | undefined) {
  return useQuery({
    queryKey: ['spot-photo', spotId],
    queryFn: () => parkingApi.getSpotMediaAccessUrl(spotId as string),
    enabled: Boolean(spotId),
    staleTime: 3 * 60_000,
    gcTime: 4 * 60_000,
    select: (data) => data.accessUrl,
  });
}
