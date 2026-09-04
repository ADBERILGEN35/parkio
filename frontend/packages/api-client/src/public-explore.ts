import type { PublicExploreFacility } from '@parkio/types';
import type { AxiosInstance } from 'axios';

export function createPublicExploreApi(client: AxiosInstance) {
  return {
    list(signal?: AbortSignal): Promise<PublicExploreFacility[]> {
      return client
        .get<PublicExploreFacility[]>('/public/explore/facilities', { signal })
        .then((response) => response.data);
    },

    detail(facilityId: string, signal?: AbortSignal): Promise<PublicExploreFacility> {
      return client
        .get<PublicExploreFacility>(
          `/public/explore/facilities/${encodeURIComponent(facilityId)}`,
          { signal },
        )
        .then((response) => response.data);
    },
  };
}

export type PublicExploreApi = ReturnType<typeof createPublicExploreApi>;
