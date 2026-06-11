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

export function createParkingApi(client: AxiosInstance) {
  return {
    getNearbySpots(params: NearbySearchParams): Promise<PublicSpot[]> {
      return client
        .get<PublicSpot[]>('/parking/spots/nearby', { params })
        .then((r) => r.data);
    },

    getSpot(spotId: string): Promise<PublicSpot> {
      return client.get<PublicSpot>(`/parking/spots/${spotId}`).then((r) => r.data);
    },

    /**
     * Short-lived signed URL for a spot photo.
     * Fetch on demand when rendering — URLs expire (~5m); do not cache long.
     */
    getSpotMediaAccessUrl(spotId: string): Promise<SpotMediaAccessUrl> {
      return client
        .get<SpotMediaAccessUrl>(`/parking/spots/${spotId}/media-access-url`)
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

    getMySpots(): Promise<Spot[]> {
      return client.get<Spot[]>('/parking/my-spots').then((r) => r.data);
    },

    getMySpot(spotId: string): Promise<Spot> {
      return client.get<Spot>(`/parking/my-spots/${spotId}`).then((r) => r.data);
    },
  };
}

export type ParkingApi = ReturnType<typeof createParkingApi>;
