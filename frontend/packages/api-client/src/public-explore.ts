import type { PublicExploreDiscovery, PublicExploreFacility } from '@parkio/types';
import type { AxiosInstance } from 'axios';

/** R6A-bounded anonymous list query (server caps: limit ≤ 6, radius ≤ 5000). */
interface PublicExploreListQuery {
  lat?: number;
  lng?: number;
  radiusMeters?: number;
  limit?: number;
  signal?: AbortSignal;
}

const MAX_PUBLIC_LIMIT = 6;
const MAX_PUBLIC_RADIUS_METERS = 5_000;

function clampPublicLimit(limit: number | undefined): number | undefined {
  if (limit == null || !Number.isFinite(limit)) return undefined;
  return Math.min(Math.max(Math.trunc(limit), 1), MAX_PUBLIC_LIMIT);
}

function clampPublicRadius(radiusMeters: number | undefined): number | undefined {
  if (radiusMeters == null || !Number.isFinite(radiusMeters)) return undefined;
  return Math.min(Math.max(Math.trunc(radiusMeters), 1), MAX_PUBLIC_RADIUS_METERS);
}

export function createPublicExploreApi(client: AxiosInstance) {
  return {
    list(query: PublicExploreListQuery = {}): Promise<PublicExploreDiscovery> {
      const params: Record<string, number> = {};
      if (query.lat != null) params.lat = query.lat;
      if (query.lng != null) params.lng = query.lng;
      const radiusMeters = clampPublicRadius(query.radiusMeters);
      if (radiusMeters != null) params.radiusMeters = radiusMeters;
      const limit = clampPublicLimit(query.limit);
      if (limit != null) params.limit = limit;

      return client
        .get<PublicExploreDiscovery>('/public/explore/facilities', {
          params,
          signal: query.signal,
        })
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
