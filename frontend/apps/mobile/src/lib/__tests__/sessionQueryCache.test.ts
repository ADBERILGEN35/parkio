import { QueryClient } from '@tanstack/react-query';
import type { Profile, UserStats } from '@parkio/types';
import { clearUserSessionQueries, USER_SESSION_QUERY_ROOTS } from '../sessionQueryCache';

describe('clearUserSessionQueries', () => {
  it('removes every user-scoped query root', () => {
    const client = new QueryClient();
    const removeQueries = jest.spyOn(client, 'removeQueries');
    for (const queryKey of USER_SESSION_QUERY_ROOTS) {
      client.setQueryData(queryKey, { seeded: true });
    }
    clearUserSessionQueries(client);
    expect(removeQueries).toHaveBeenCalledTimes(USER_SESSION_QUERY_ROOTS.length);
    for (const queryKey of USER_SESSION_QUERY_ROOTS) {
      expect(removeQueries).toHaveBeenCalledWith({ queryKey });
      expect(client.getQueryData(queryKey)).toBeUndefined();
    }
  });
});

describe('account-switch cache consistency', () => {
  it('does not keep mismatched profile and stats after clear', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const userAProfile: Profile = {
      id: 'profile-a',
      authUserId: 'user-a',
      email: 'alice@parkio.dev',
      displayName: 'Alice',
      phoneNumber: null,
      city: null,
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00.000Z',
    };
    const userBStats: UserStats = {
      trustScore: 90,
      trustBand: 'HIGH_TRUST',
      totalPoints: 500,
      currentLevel: 4,
    };
    client.setQueryData(['me', 'profile'], userAProfile);
    client.setQueryData(['me', 'stats'], userBStats);
    clearUserSessionQueries(client);
    expect(client.getQueryData(['me', 'profile'])).toBeUndefined();
    expect(client.getQueryData(['me', 'stats'])).toBeUndefined();
  });
});