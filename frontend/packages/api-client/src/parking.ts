import type { AxiosInstance } from 'axios';
import type {
  CreateSpotRequest,
  NearbySearchParams,
  PublicSpot,
  Spot,
  SpotMediaAccessUrl,
  VerifySpotRequest,
} from '@parkio/types';
import { IDEMPOTENCY_HEADER } from './idempotency';
import type { RequestOptions } from './request-options';

export function createParkingApi(client: AxiosInstance) {
  return {
    getNearbySpots(params: NearbySearchParams, signal?: AbortSignal): Promise<PublicSpot[]> {
      return client
        .get<PublicSpot[]>('/parking/spots/nearby', { params, signal })
        .then((r) => r.data);
    },

    getSpot(spotId: string, options?: RequestOptions): Promise<PublicSpot> {
      return client
        .get<PublicSpot>(`/parking/spots/${spotId}`, { signal: options?.signal })
        .then((r) => r.data);
    },

    /**
     * Short-lived signed URL for a spot photo.
     * Fetch on demand when rendering — URLs expire (~5m); do not cache long.
     */
    getSpotMediaAccessUrl(spotId: string, options?: RequestOptions): Promise<SpotMediaAccessUrl> {
      return client
        .get<SpotMediaAccessUrl>(`/parking/spots/${spotId}/media-access-url`, {
          signal: options?.signal,
        })
        .then((r) => r.data);
    },

    createParkingSpot(body: CreateSpotRequest, idempotencyKey: string): Promise<Spot> {
      return client
        .post<Spot>('/parking/spots', body, {
          headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
        })
        .then((r) => r.data);
    },

    verifySpot(spotId: string, body: VerifySpotRequest, idempotencyKey: string): Promise<PublicSpot> {
      return client
        .post<PublicSpot>(`/parking/spots/${spotId}/verify`, body, {
          headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
        })
        .then((r) => r.data);
    },

    claimSpot(spotId: string, idempotencyKey: string): Promise<PublicSpot> {
      return client
        .post<PublicSpot>(`/parking/spots/${spotId}/claim`, null, {
          headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
        })
        .then((r) => r.data);
    },

    getMySpots(options?: RequestOptions): Promise<Spot[]> {
      return client
        .get<Spot[]>('/parking/my-spots', { signal: options?.signal })
        .then((r) => r.data);
    },

    getMySpot(spotId: string, options?: RequestOptions): Promise<Spot> {
      return client
        .get<Spot>(`/parking/my-spots/${spotId}`, { signal: options?.signal })
        .then((r) => r.data);
    },
  };
}

export type ParkingApi = ReturnType<typeof createParkingApi>;
