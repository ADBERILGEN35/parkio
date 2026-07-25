import { QueryClient } from '@tanstack/react-query';
import { NetworkError } from '@parkio/api-client';
const activeParkingSessionFixture = {
  id: 'd431ad5a-f8ce-4be2-b4dc-248b47990b39',
  status: 'ACTIVE' as const,
  parkingSource: 'MANUAL' as const,
  startedAt: '2026-07-21T09:00:00Z',
  endedAt: null,
  latitude: 41.0082,
  longitude: 28.9784,
  estimatedFee: '125.50',
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};

const communityParkingSessionFixture = {
  ...activeParkingSessionFixture,
  parkingSource: 'COMMUNITY' as const,
  estimatedFee: null,
  lastConfirmedAt: '2026-07-25T10:00:00.000Z',
  completionType: null,
};
import { parkingKeys } from '../keys';
import { activeParkingSessionQueryOptions } from '../query-options/parking';
import { clearUserSessionQueries } from '../sessionQueryCache';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  parkingApi: {
    getActiveParkingSession: jest.fn(async () => null),
    getNearbySpots: jest.fn(async () => []),
    getSpot: jest.fn(async () => ({ id: 's1' })),
    getMySpots: jest.fn(async () => []),
    claimSpot: jest.fn(async () => ({ id: 's1', status: 'FILLED' })),
  },
}));

describe('S1-P0-02 active ParkingSession query ownership', () => {
  const signal = new AbortController().signal;

  it('exposes a stable user-scoped sessions hierarchy under parkingKeys', () => {
    expect(parkingKeys.sessionsRoot()).toEqual(['parking', 'sessions']);
    expect(parkingKeys.activeSession()).toEqual(['parking', 'sessions', 'active']);
    expect(parkingKeys.sessionHistory(20)).toEqual(['parking', 'sessions', 'history', { size: 20 }]);
    expect(parkingKeys.activeSession()[0]).toBe(parkingKeys.all[0]);
    expect(activeParkingSessionQueryOptions().queryKey).toEqual(parkingKeys.activeSession());
  });

  it('forwards AbortSignal and accepts null empty data', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(null);
    const result = await activeParkingSessionQueryOptions().queryFn!({ signal } as never);
    expect(api.parkingApi.getActiveParkingSession).toHaveBeenCalledWith(signal);
    expect(result).toBeNull();
  });

  it('returns a 200 session payload without fabricating fields', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(activeParkingSessionFixture);
    await expect(activeParkingSessionQueryOptions().queryFn!({ signal } as never)).resolves.toEqual(
      activeParkingSessionFixture,
    );
  });

  it('propagates API errors for existing retry/auth classification', async () => {
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockRejectedValueOnce(
      new NetworkError('offline'),
    );
    await expect(activeParkingSessionQueryOptions().queryFn!({ signal } as never)).rejects.toBeInstanceOf(
      NetworkError,
    );
  });

  it('clears active-session coordinates on logout while preserving nearby', () => {
    const client = new QueryClient();
    client.setQueryData(parkingKeys.activeSession(), {
      ...activeParkingSessionFixture,
      latitude: 41.1,
      longitude: 29.1,
    });
    client.setQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }), [{ id: 'public' }]);

    clearUserSessionQueries(client);

    expect(client.getQueryData(parkingKeys.activeSession())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }))).toEqual([{ id: 'public' }]);
  });

  it('claim-style invalidation can surface a COMMUNITY session', async () => {
    const client = new QueryClient();
    client.setQueryData(parkingKeys.activeSession(), null);
    (api.parkingApi.getActiveParkingSession as jest.Mock).mockResolvedValueOnce(
      communityParkingSessionFixture,
    );

    await client.invalidateQueries({ queryKey: parkingKeys.activeSession() });
    await client.fetchQuery(activeParkingSessionQueryOptions());

    expect(client.getQueryData(parkingKeys.activeSession())).toEqual(communityParkingSessionFixture);
  });
});
