import { createIdempotencyKey } from '@parkio/api-client';
import type { CreateReportRequest, CreateSpotRequest, PublicSpot, Spot, VerificationResult } from '@parkio/types';
import type { QueryClient } from '@tanstack/react-query';
import type { ParkioSdk } from '@/app/sdk';
import { reportsKeys } from '@/data/keys';
import {
  applyParkingSpotUpdate,
  syncAfterSpotCreate,
  syncAfterSpotLifecycleMutation,
} from '@/data/parking/spotCache';
import { activeParkingSessionQueryOptions } from '@/data/query-options/parking';

export type VerifySpotMutationInput = {
  result: VerificationResult;
};

export type ReportSpotMutationInput = Pick<CreateReportRequest, 'reason' | 'description'>;

export function createVerifySpotMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
  spotId: string,
) {
  return {
    mutationFn: (input: VerifySpotMutationInput): Promise<PublicSpot> =>
      sdk.parkingApi.verifySpot(spotId, { result: input.result }, createIdempotencyKey()),
    onSuccess: async (updated: PublicSpot) => {
      applyParkingSpotUpdate(queryClient, updated);
      await syncAfterSpotLifecycleMutation(queryClient);
    },
  };
}

export function createClaimSpotMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
  spotId: string,
) {
  return {
    mutationFn: (): Promise<PublicSpot> =>
      sdk.parkingApi.claimSpot(spotId, createIdempotencyKey()),
    onSuccess: async (updated: PublicSpot) => {
      applyParkingSpotUpdate(queryClient, updated);
      await syncAfterSpotLifecycleMutation(queryClient);
      // Backend claim atomically creates an ACTIVE COMMUNITY ParkingSession; the claim
      // response is still PublicSpot-only, so restore via the canonical active query
      // (same intent as mobile-v2 SpotActions — never synthesize a session from the spot).
      await queryClient.fetchQuery(activeParkingSessionQueryOptions(sdk));
    },
  };
}

export function createCreateSpotMutationOptions(sdk: ParkioSdk, queryClient: QueryClient) {
  return {
    mutationFn: (body: CreateSpotRequest): Promise<Spot> =>
      sdk.parkingApi.createParkingSpot(body, createIdempotencyKey()),
    onSuccess: async () => {
      await syncAfterSpotCreate(queryClient);
    },
  };
}

export function createReportSpotMutationOptions(
  sdk: ParkioSdk,
  queryClient: QueryClient,
  spotId: string,
) {
  return {
    mutationFn: (input: ReportSpotMutationInput) =>
      sdk.moderationApi.createReport({
        targetType: 'PARKING_SPOT',
        targetId: spotId,
        reason: input.reason,
        description: input.description,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: reportsKeys.all });
    },
  };
}