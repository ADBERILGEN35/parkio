import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { ParkingSessionResponse } from '@parkio/types';
import { municipalParkTarget } from '@parkio/validation';
import {
  recentParkingAttemptKey,
  recordRecentParkingAfterPark,
} from './recordRecentParkingAfterPark';

const session = {
  id: '11111111-1111-4111-8111-111111111111',
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-08-07T08:00:00.000Z',
  endedAt: null,
  latitude: 38.4,
  longitude: 27.1,
  estimatedFee: null,
  lastConfirmedAt: '2026-08-07T08:00:00.000Z',
  completionType: null,
} satisfies ParkingSessionResponse;

describe('recordRecentParkingAfterPark', () => {
  const invalidateQueries = vi.fn();
  const queryClient = { invalidateQueries } as never;
  const recordRecentParking = vi.fn();

  beforeEach(() => {
    invalidateQueries.mockReset();
    recordRecentParking.mockReset();
  });

  it('skips when no municipal target', async () => {
    const sdk = { placesApi: { recordRecentParking } } as never;
    await expect(recordRecentParkingAfterPark(sdk, queryClient, null)).resolves.toBe(
      'skipped',
    );
    expect(recordRecentParking).not.toHaveBeenCalled();
  });

  it('records municipal target and invalidates cache', async () => {
    recordRecentParking.mockResolvedValue({});
    const sdk = { placesApi: { recordRecentParking } } as never;
    const target = municipalParkTarget('fac-1', 'Test');
    await expect(recordRecentParkingAfterPark(sdk, queryClient, target)).resolves.toBe(
      'recorded',
    );
    expect(recordRecentParking).toHaveBeenCalledWith({
      targetKind: 'MUNICIPAL_FACILITY',
      targetId: 'fac-1',
    });
    expect(invalidateQueries).toHaveBeenCalled();
  });

  it('fails open when RecentParking throws', async () => {
    recordRecentParking.mockRejectedValue(new Error('down'));
    const sdk = { placesApi: { recordRecentParking } } as never;
    await expect(
      recordRecentParkingAfterPark(sdk, queryClient, municipalParkTarget('fac-1')),
    ).resolves.toBe('failed');
  });

  it('builds stable attempt keys', () => {
    expect(recentParkingAttemptKey(session, municipalParkTarget('fac-1'))).toBe(
      `${session.id}:MUNICIPAL_FACILITY:fac-1`,
    );
  });
});
