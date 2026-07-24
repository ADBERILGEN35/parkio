import { QueryClient } from '@tanstack/react-query';
import { parkingKeys } from '../keys';
import {
  PARKING_SESSION_HISTORY_PAGE_SIZE,
  parkingSessionHistoryInfiniteQueryOptions,
} from '../query-options/parking';
import { clearUserSessionQueries } from '../sessionQueryCache';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  parkingApi: {
    getParkingSessionHistory: jest.fn(async () => ({ items: [], nextCursor: null })),
    getActiveParkingSession: jest.fn(async () => null),
  },
}));

describe('S1-P0-11 ParkingSession history query options', () => {
  const signal = new AbortController().signal;

  it('scopes history under sessionsRoot with page size', () => {
    expect(parkingKeys.sessionHistory(20)).toEqual(['parking', 'sessions', 'history', { size: 20 }]);
    expect(parkingKeys.sessionHistoryRoot()).toEqual(['parking', 'sessions', 'history']);
    expect(parkingSessionHistoryInfiniteQueryOptions().queryKey).toEqual(
      parkingKeys.sessionHistory(PARKING_SESSION_HISTORY_PAGE_SIZE),
    );
  });

  it('uses different keys for different page sizes (user A vs parameter isolation)', () => {
    expect(parkingKeys.sessionHistory(10)).not.toEqual(parkingKeys.sessionHistory(20));
  });

  it('forwards AbortSignal and omits cursor on the first page', async () => {
    const options = parkingSessionHistoryInfiniteQueryOptions(20);
    await options.queryFn!({
      pageParam: undefined,
      signal,
      queryKey: options.queryKey,
      meta: undefined,
      direction: 'forward',
    } as never);
    expect(api.parkingApi.getParkingSessionHistory).toHaveBeenCalledWith({ size: 20 }, signal);
  });

  it('passes opaque cursor on subsequent pages', async () => {
    const options = parkingSessionHistoryInfiniteQueryOptions(10);
    await options.queryFn!({
      pageParam: 'cursor-abc',
      signal,
      queryKey: options.queryKey,
      meta: undefined,
      direction: 'forward',
    } as never);
    expect(api.parkingApi.getParkingSessionHistory).toHaveBeenCalledWith(
      { size: 10, cursor: 'cursor-abc' },
      signal,
    );
  });

  it('getNextPageParam returns nextCursor or undefined', () => {
    const options = parkingSessionHistoryInfiniteQueryOptions();
    expect(options.getNextPageParam({ items: [], nextCursor: 'next' }, [], undefined, [])).toBe('next');
    expect(options.getNextPageParam({ items: [], nextCursor: null }, [], undefined, [])).toBeUndefined();
  });

  it('clears history with sessionsRoot on logout while preserving nearby', () => {
    const client = new QueryClient();
    client.setQueryData(parkingKeys.sessionHistory(20), {
      pages: [{ items: [{ id: 'h1', latitude: 1, longitude: 2 }], nextCursor: null }],
      pageParams: [undefined],
    });
    client.setQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }), [{ id: 'public' }]);

    clearUserSessionQueries(client);

    expect(client.getQueryData(parkingKeys.sessionHistory(20))).toBeUndefined();
    expect(client.getQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }))).toEqual([{ id: 'public' }]);
  });
});
