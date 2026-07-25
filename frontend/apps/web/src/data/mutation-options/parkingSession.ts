import { createIdempotencyKey } from '@parkio/api-client';
import type { ParkingSessionResponse, StartParkingSessionRequest } from '@parkio/types';
import type { QueryClient } from '@tanstack/react-query';
import type { ParkioSdk } from '@/app/sdk';
import {
  invalidateParkingSessionHistory,
  setActiveParkingSession,
  syncAfterParkingSessionTerminal,
} from '@/data/parking/sessionCache';

/**
 * ParkingSession mutation options (data layer). Each factory owns its own
 * caller-scoped idempotency key and reconciles the active-session / history cache
 * on success. Higher-level flow state (geolocation, ambiguous retry) is layered
 * on top in the feature hooks — this module never bypasses the SDK.
 */

export function createStartParkingSessionMutationOptions(sdk: ParkioSdk, queryClient: QueryClient) {
  return {
    mutationFn: (body: StartParkingSessionRequest): Promise<ParkingSessionResponse> =>
      sdk.parkingApi.startParkingSession(body, createIdempotencyKey()),
    onSuccess: (session: ParkingSessionResponse) => {
      setActiveParkingSession(queryClient, session);
    },
  };
}

export function createCompleteParkingSessionMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
) {
  return {
    mutationFn: (sessionId: string): Promise<ParkingSessionResponse> =>
      sdk.parkingApi.completeParkingSession(sessionId, createIdempotencyKey()),
    onSuccess: async (result: ParkingSessionResponse) => {
      await syncAfterParkingSessionTerminal(queryClient, result);
    },
  };
}

export function createCancelParkingSessionMutationOptions(sdk: ParkioSdk, queryClient: QueryClient) {
  return {
    mutationFn: (sessionId: string): Promise<ParkingSessionResponse> =>
      sdk.parkingApi.cancelParkingSession(sessionId, createIdempotencyKey()),
    onSuccess: async (result: ParkingSessionResponse) => {
      await syncAfterParkingSessionTerminal(queryClient, result);
    },
  };
}

export function createDeleteParkingSessionMutationOptions(sdk: ParkioSdk, queryClient: QueryClient) {
  return {
    mutationFn: (sessionId: string): Promise<void> =>
      sdk.parkingApi.deleteParkingSession(sessionId),
    onSuccess: async () => {
      await invalidateParkingSessionHistory(queryClient);
    },
  };
}

export function createDeleteParkingSessionHistoryMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
) {
  return {
    mutationFn: (): Promise<void> => sdk.parkingApi.deleteParkingSessionHistory(),
    onSuccess: async () => {
      await invalidateParkingSessionHistory(queryClient);
    },
  };
}
