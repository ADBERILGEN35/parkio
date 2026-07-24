import type { CreateSpotRequest, PublicSpot, Spot } from '@parkio/types';
import { describe, expect, it, vi } from 'vitest';
import type { ParkioSdk } from '@/app/sdk';
import { parkingKeys, reportsKeys } from '@/data/keys';
import { createTestQueryClient } from '@/test/utils';
import {
  createClaimSpotMutationOptions,
  createCreateSpotMutationOptions,
  createReportSpotMutationOptions,
  createVerifySpotMutationOptions,
} from './parking';

vi.mock('@/lib/gamificationCache', () => ({
  invalidateGamificationQueries: vi.fn(async () => undefined),
}));

const spotId = '11111111-1111-4111-8111-111111111111';

const publicSpot: PublicSpot = {
  id: spotId,
  mediaId: '22222222-2222-4222-8222-222222222222',
  latitude: 41,
  longitude: 29,
  addressText: null,
  description: null,
  manualLocationEdited: false,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'AVAILABLE',
  expiresAt: '2026-07-24T12:00:00.000Z',
  createdAt: '2026-07-24T10:00:00.000Z',
  updatedAt: '2026-07-24T10:00:00.000Z',
};

function createSdk(): ParkioSdk {
  return {
    parkingApi: {
      verifySpot: vi.fn(async () => ({ ...publicSpot, status: 'AVAILABLE' })),
      claimSpot: vi.fn(async () => ({ ...publicSpot, status: 'FILLED' })),
      createParkingSpot: vi.fn(async () => ({ ...publicSpot, ownerUserId: 'o1' }) as Spot),
    },
    moderationApi: {
      createReport: vi.fn(async () => ({ id: 'r1' })),
    },
  } as unknown as ParkioSdk;
}

describe('parking mutation options', () => {
  it('verify writes the spot into detail/nearby caches and refreshes my-spots', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    const nearbyKey = parkingKeys.nearby({ lat: 41, lng: 29 });
    client.setQueryData(nearbyKey, [publicSpot]);
    const options = createVerifySpotMutationOptions(sdk, client, spotId);
    const updated = await options.mutationFn({ result: 'AVAILABLE' });
    await options.onSuccess(updated);
    expect(sdk.parkingApi.verifySpot).toHaveBeenCalledOnce();
    expect(client.getQueryData(parkingKeys.spot(spotId))).toEqual(updated);
    expect(client.getQueryData<PublicSpot[]>(nearbyKey)?.[0]).toEqual(updated);
  });

  it('claim patches caches with FILLED status', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    client.setQueryData(parkingKeys.spot(spotId), publicSpot);
    const options = createClaimSpotMutationOptions(sdk, client, spotId);
    const updated = await options.mutationFn();
    await options.onSuccess(updated);
    expect(updated.status).toBe('FILLED');
    expect(client.getQueryData<PublicSpot>(parkingKeys.spot(spotId))?.status).toBe('FILLED');
  });

  it('create invalidates my-spots and nearby roots', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const options = createCreateSpotMutationOptions(sdk, client);
    const body = { mediaId: publicSpot.mediaId } as CreateSpotRequest;
    await options.mutationFn(body);
    await options.onSuccess();
    expect(sdk.parkingApi.createParkingSpot).toHaveBeenCalledOnce();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.mySpots() });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.nearbyRoot() });
  });

  it('report invalidates the reports list key', async () => {
    const sdk = createSdk();
    const client = createTestQueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    const options = createReportSpotMutationOptions(sdk, client, spotId);
    await options.mutationFn({ reason: 'SPAM_BEHAVIOR', description: null });
    await options.onSuccess();
    expect(sdk.moderationApi.createReport).toHaveBeenCalledWith(
      expect.objectContaining({ targetType: 'PARKING_SPOT', targetId: spotId }),
    );
    expect(invalidate).toHaveBeenCalledWith({ queryKey: reportsKeys.all });
  });
});