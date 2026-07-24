import type { PublicSpot, Spot } from '@parkio/types';
import { describe, expect, it, vi } from 'vitest';
import { meKeys, parkingKeys } from '@/data/keys';
import { createTestQueryClient } from '@/test/utils';
import {
  applyParkingSpotUpdate,
  syncAfterSpotCreate,
  syncAfterSpotLifecycleMutation,
} from './spotCache';

vi.mock('@/lib/gamificationCache', () => ({
  invalidateGamificationQueries: vi.fn(async () => undefined),
}));

import { invalidateGamificationQueries } from '@/lib/gamificationCache';

const baseSpot: PublicSpot = {
  id: '11111111-1111-4111-8111-111111111111',
  mediaId: '22222222-2222-4222-8222-222222222222',
  latitude: 41.0,
  longitude: 29.0,
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

describe('parking spot cache ownership', () => {
  it('patches detail, nearby lists, and overlapping my-spots fields', () => {
    const client = createTestQueryClient();
    const nearbyKey = parkingKeys.nearby({ lat: 41, lng: 29, radius: 1000 });
    const owned: Spot = {
      ...baseSpot,
      ownerUserId: 'owner-1',
      confidenceScore: 1,
      verificationCount: 0,
      filledReportCount: 0,
    };
    client.setQueryData(parkingKeys.spot(baseSpot.id), baseSpot);
    client.setQueryData(nearbyKey, [baseSpot]);
    client.setQueryData(parkingKeys.mySpots(), [owned]);
    client.setQueryData(meKeys.stats(), { trustScore: 9 });

    const updated: PublicSpot = { ...baseSpot, status: 'FILLED' };
    applyParkingSpotUpdate(client, updated);

    expect(client.getQueryData(parkingKeys.spot(baseSpot.id))).toEqual(updated);
    expect(client.getQueryData<PublicSpot[]>(nearbyKey)?.[0]?.status).toBe('FILLED');
    expect(client.getQueryData<Spot[]>(parkingKeys.mySpots())?.[0]).toMatchObject({
      id: baseSpot.id,
      status: 'FILLED',
      ownerUserId: 'owner-1',
    });
    expect(client.getQueryData(meKeys.stats())).toEqual({ trustScore: 9 });
  });

  it('invalidates my-spots, nearby root, and gamification after create', async () => {
    const client = createTestQueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    await syncAfterSpotCreate(client);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.mySpots() });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.nearbyRoot() });
    expect(invalidateGamificationQueries).toHaveBeenCalledWith(client);
  });

  it('invalidates my-spots and gamification after verify/claim lifecycle sync', async () => {
    const client = createTestQueryClient();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    await syncAfterSpotLifecycleMutation(client);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: parkingKeys.mySpots() });
    expect(invalidate).not.toHaveBeenCalledWith({ queryKey: parkingKeys.nearbyRoot() });
    expect(invalidateGamificationQueries).toHaveBeenCalledWith(client);
  });
});