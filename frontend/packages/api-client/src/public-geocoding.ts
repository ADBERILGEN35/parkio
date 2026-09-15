import type { GeocodeResult, GeocodeSearchResponse } from '@parkio/types';
import type { AxiosInstance } from 'axios';

/**
 * Anonymous destination geocoding for Public Explore (R6G-A/B).
 * Calls {@code GET /api/v1/public/geocoding/search} — no auth, no saved/favourites/recents.
 * Shape matches {@link GeocodingApi.searchPlaces} so shared autocomplete helpers can reuse it.
 */
export function createPublicGeocodingApi(client: AxiosInstance) {
  return {
    /**
     * Resolve free text into candidate places. Prefer {@code limit = 5}.
     * Server validates q (3–256) and limit (1–10); provider outage → empty list (200).
     */
    searchPlaces(query: string, limit?: number, signal?: AbortSignal): Promise<GeocodeResult[]> {
      return client
        .get<GeocodeSearchResponse>('/public/geocoding/search', {
          params: limit === undefined ? { q: query } : { q: query, limit },
          signal,
        })
        .then((r) => r.data.results);
    },
  };
}

export type PublicGeocodingApi = ReturnType<typeof createPublicGeocodingApi>;
