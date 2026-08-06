import type {
  CreateSpotRequest,
  MunicipalFacility,
  MunicipalFacilityNearbyParams,
  NearbySearchParams,
  ParkingSessionHistoryParams,
  ParkingSessionHistoryResponse,
  ParkingSessionLifecycleConfig,
  ParkingSessionResponse,
  PublicSpot,
  RecommendationRequest,
  RecommendationResponse,
  Spot,
  SpotMediaAccessUrl,
  StartParkingSessionRequest,
  VerifySpotRequest,
} from '@parkio/types';
import {
  parkingSessionHistoryResponseSchema,
  parkingSessionLifecycleConfigSchema,
  parkingSessionResponseSchema,
  recommendationResponseSchema,
} from '@parkio/validation';
import { ContractValidationError } from './errors';
import { IDEMPOTENCY_HEADER } from './idempotency';
import type { RequestOptions } from './request-options';
import type { AxiosInstance } from 'axios';
type ContractSchema<T> = {
  safeParse(
    data: unknown,
  ): { success: true; data: T } | { success: false; error: unknown };
};

function parseContract<T>(schema: ContractSchema<T>, data: unknown): T {
  const parsed = schema.safeParse(data);
  if (!parsed.success) {
    throw new ContractValidationError();
  }
  return parsed.data;
}

/** Omit undefined so axios never serializes literal `"undefined"` query values. */
function toDefinedParams(
  params: ParkingSessionHistoryParams,
): Record<string, string | number> | undefined {
  const query: Record<string, string | number> = {};
  if (params.size !== undefined) {
    query.size = params.size;
  }
  if (params.cursor !== undefined) {
    query.cursor = params.cursor;
  }
  return Object.keys(query).length > 0 ? query : undefined;
}

export function createParkingApi(client: AxiosInstance) {
  return {
    getNearbySpots(params: NearbySearchParams, signal?: AbortSignal): Promise<PublicSpot[]> {
      return client
        .get<PublicSpot[]>('/parking/spots/nearby', { params, signal })
        .then((r) => r.data);
    },

    /**
     * Municipal facility nearby discovery (WEB-MUNI-01).
     * Separate inventory from community spots — never fuse responses.
     * Always sends canonical {@code radiusMeters} (maps legacy {@code radius} when needed).
     */
    getNearbyMunicipalFacilities(
      params: MunicipalFacilityNearbyParams,
      signal?: AbortSignal,
    ): Promise<MunicipalFacility[]> {
      const resolvedRadius = params.radiusMeters ?? params.radius;
      const query: {
        lat: number;
        lng: number;
        limit?: number;
        radiusMeters?: number;
      } = {
        lat: params.lat,
        lng: params.lng,
        ...(params.limit !== undefined ? { limit: params.limit } : {}),
        ...(resolvedRadius !== undefined ? { radiusMeters: resolvedRadius } : {}),
      };
      return client
        .get<MunicipalFacility[]>('/parking/facilities/nearby', { params: query, signal })
        .then((r) => r.data);
    },

    /**
     * Destination-scoped parking recommendations (WP-SPA-05 / WP-SPA-06).
     * Distance baseline when ranking disabled; deterministic ranking when enabled.
     */
    recommendParking(
      body: RecommendationRequest,
      options?: RequestOptions,
    ): Promise<RecommendationResponse> {
      return client
        .post<RecommendationResponse>('/parking/recommendations', body, {
          signal: options?.signal,
        })
        .then((r) => parseContract(recommendationResponseSchema, r.data));
    },

    /** Municipal facility detail — same DTO as nearby items. */
    getMunicipalFacility(facilityId: string, options?: RequestOptions): Promise<MunicipalFacility> {
      return client
        .get<MunicipalFacility>(`/parking/facilities/${encodeURIComponent(facilityId)}`, {
          signal: options?.signal,
        })
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

    startParkingSession(
      body: StartParkingSessionRequest,
      idempotencyKey: string,
    ): Promise<ParkingSessionResponse> {
      return client
        .post<unknown>('/parking/sessions', body, {
          headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
        })
        .then((r) => parseContract<ParkingSessionResponse>(parkingSessionResponseSchema, r.data));
    },

    getActiveParkingSession(signal?: AbortSignal): Promise<ParkingSessionResponse | null> {
      return client.get<unknown>('/parking/sessions/active', { signal }).then((r) => {
        if (r.status === 204) {
          return null;
        }
        return parseContract<ParkingSessionResponse>(parkingSessionResponseSchema, r.data);
      });
    },

    /**
     * Effective confirm / reminder / auto-complete thresholds from parking-service.
     * Single source of truth — clients must not hardcode these windows.
     */
    getParkingSessionLifecycleConfig(
      signal?: AbortSignal,
    ): Promise<ParkingSessionLifecycleConfig> {
      return client
        .get<unknown>('/parking/sessions/lifecycle-config', { signal })
        .then((r) =>
          parseContract<ParkingSessionLifecycleConfig>(parkingSessionLifecycleConfigSchema, r.data),
        );
    },

    completeParkingSession(
      sessionId: string,
      idempotencyKey: string,
    ): Promise<ParkingSessionResponse> {
      return client
        .post<unknown>(`/parking/sessions/${encodeURIComponent(sessionId)}/complete`, null, {
          headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
        })
        .then((r) => parseContract<ParkingSessionResponse>(parkingSessionResponseSchema, r.data));
    },

    /**
     * Extends the ACTIVE confirmation window (lastConfirmedAt = now).
     * No Idempotency-Key — safe to retry; overwrites the heartbeat.
     */
    confirmActiveParkingSession(sessionId: string): Promise<ParkingSessionResponse> {
      return client
        .post<unknown>(`/parking/sessions/${encodeURIComponent(sessionId)}/confirm-active`, null)
        .then((r) => parseContract<ParkingSessionResponse>(parkingSessionResponseSchema, r.data));
    },

    cancelParkingSession(
      sessionId: string,
      idempotencyKey: string,
    ): Promise<ParkingSessionResponse> {
      return client
        .post<unknown>(`/parking/sessions/${encodeURIComponent(sessionId)}/cancel`, null, {
          headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
        })
        .then((r) => parseContract<ParkingSessionResponse>(parkingSessionResponseSchema, r.data));
    },

    getParkingSessionHistory(
      params: ParkingSessionHistoryParams,
      signal?: AbortSignal,
    ): Promise<ParkingSessionHistoryResponse> {
      return client
        .get<unknown>('/parking/sessions/history', {
          params: toDefinedParams(params),
          signal,
        })
        .then((r) =>
          parseContract<ParkingSessionHistoryResponse>(parkingSessionHistoryResponseSchema, r.data),
        );
    },

    /**
     * Hard-delete one terminal ParkingSession (S1-P0-07 / S1-P0-11).
     * HTTP 204 is success for deleted, already absent, and foreign ids (opaque).
     * ACTIVE targets return 409 via the typed ConflictError path.
     * No body, no Idempotency-Key, no client-controlled userId.
     */
    deleteParkingSession(sessionId: string): Promise<void> {
      return client
        .delete(`/parking/sessions/${encodeURIComponent(sessionId)}`)
        .then(() => undefined);
    },

    /**
     * Hard-delete all terminal ParkingSession history for the caller.
     * ACTIVE sessions are preserved by the backend. Repeated calls return 204.
     */
    deleteParkingSessionHistory(): Promise<void> {
      return client.delete('/parking/sessions/history').then(() => undefined);
    },
  };
}

export type ParkingApi = ReturnType<typeof createParkingApi>;
