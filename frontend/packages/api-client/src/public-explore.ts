import type { PublicExploreDiscovery, PublicExploreFacility } from '@parkio/types';
import type { AxiosInstance } from 'axios';

export function createPublicExploreApi(client: AxiosInstance) {
  return {
    list(signal?: AbortSignal): Promise<PublicExploreDiscovery> {
      return client
        .get<PublicExploreDiscovery>('/public/explore/facilities', { signal })
        .then((response) => response.data);
    },

    /**
     * @deprecated Anonymous public detail was removed for R6 membership gating.
     * Prefer authenticated municipal facility APIs after login.
     */
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
