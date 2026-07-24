import { useQuery } from '@tanstack/react-query';
import { spotMediaAccessUrlQueryOptions } from '@/data/query-options/parking';

/**
 * Signed photo URL for a spot. URLs expire (~5 min server-side) — cache just
 * under that and refetch on demand; never persist.
 */
export function useSpotPhoto(spotId: string | null | undefined) {
  return useQuery({
    ...spotMediaAccessUrlQueryOptions(spotId ?? ''),
    enabled: Boolean(spotId),
    select: (data) => data.accessUrl,
  });
}
