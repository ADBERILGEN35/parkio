import type { AxiosInstance } from 'axios';
import type { GeocodeResult, GeocodeSearchResponse } from '@parkio/types';

/**
 * Forward-geocoding client. Calls the authenticated gateway route
 * `GET /api/v1/geocoding/search` (proxied to parking-service, ADR-014) instead of
 * a third-party provider directly — so it inherits auth, the correlation id, the
 * 401-refresh flow and the standard error envelope from the shared api client.
 */
export function createGeocodingApi(client: AxiosInstance) {
  return {
    /**
     * Resolve free text into candidate places. The backend validates the query
     * (3–256 chars) and limit (1–10); a provider outage degrades to an empty list.
     */
    searchPlaces(query: string, limit?: number, signal?: AbortSignal): Promise<GeocodeResult[]> {
      return client
        .get<GeocodeSearchResponse>('/geocoding/search', {
          params: limit === undefined ? { q: query } : { q: query, limit },
          signal,
        })
        .then((r) => r.data.results);
    },
  };
}

export type GeocodingApi = ReturnType<typeof createGeocodingApi>;
