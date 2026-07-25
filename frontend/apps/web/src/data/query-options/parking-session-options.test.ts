import type { ParkingSessionHistoryResponse, ParkingSessionResponse } from '@parkio/types';
import { describe, expect, it, vi } from 'vitest';
import type { ParkioSdk } from '@/app/sdk';
import { parkingKeys } from '../keys';
import {
  PARKING_SESSION_HISTORY_PAGE_SIZE,
  activeParkingSessionQueryOptions,
  parkingSessionHistoryInfiniteQueryOptions,
} from './parking';

const activeSession: ParkingSessionResponse = {
  id: 'sess-1',
  status: 'ACTIVE',
  parkingSource: 'MANUAL',
  startedAt: '2026-07-25T10:00:00.000Z',
  endedAt: null,
  latitude: 41,
  longitude: 29,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

function createSdkMock(historyNextCursor: string | null = null): ParkioSdk {
  const historyPage: ParkingSessionHistoryResponse = {
    items: [],
    nextCursor: historyNextCursor,
  };
  return {
    parkingApi: {
      getActiveParkingSession: vi.fn(async () => activeSession),
      getParkingSessionHistory: vi.fn(async () => historyPage),
    },
  } as unknown as ParkioSdk;
}

describe('ParkingSession query options', () => {
  it('binds the active-session key and forwards the AbortSignal to the SDK', async () => {
    const sdk = createSdkMock();
    const options = activeParkingSessionQueryOptions(sdk);
    expect(options.queryKey).toEqual(parkingKeys.activeSession());

    const signal = new AbortController().signal;
    const result = await options.queryFn!({ signal } as never);
    expect(sdk.parkingApi.getActiveParkingSession).toHaveBeenCalledWith(signal);
    expect(result).toEqual(activeSession);
  });

  it('binds a size-scoped history key and requests the first page without a cursor', async () => {
    const sdk = createSdkMock();
    const options = parkingSessionHistoryInfiniteQueryOptions(sdk);
    expect(options.queryKey).toEqual(
      parkingKeys.sessionHistory(PARKING_SESSION_HISTORY_PAGE_SIZE),
    );

    const signal = new AbortController().signal;
    await options.queryFn!({ pageParam: undefined, signal } as never);
    expect(sdk.parkingApi.getParkingSessionHistory).toHaveBeenCalledWith(
      { size: PARKING_SESSION_HISTORY_PAGE_SIZE },
      signal,
    );
  });

  it('passes the cursor for subsequent pages and derives the next page param', async () => {
    const sdk = createSdkMock('cursor-2');
    const options = parkingSessionHistoryInfiniteQueryOptions(sdk, 50);
    expect(options.queryKey).toEqual(parkingKeys.sessionHistory(50));

    const signal = new AbortController().signal;
    await options.queryFn!({ pageParam: 'cursor-1', signal } as never);
    expect(sdk.parkingApi.getParkingSessionHistory).toHaveBeenCalledWith(
      { size: 50, cursor: 'cursor-1' },
      signal,
    );

    expect(options.getNextPageParam({ items: [], nextCursor: 'cursor-2' }, [], 'cursor-1', [])).toBe(
      'cursor-2',
    );
    expect(options.getNextPageParam({ items: [], nextCursor: null }, [], 'cursor-1', [])).toBeUndefined();
  });
});
