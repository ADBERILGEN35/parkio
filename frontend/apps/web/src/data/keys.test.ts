import type { Profile } from '@parkio/types';
import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import {
  meKeys,
  normalizeNearbyFilters,
  parkingKeys,
} from './keys';
import {
  clearUserSessionQueries,
  USER_SESSION_QUERY_ROOTS,
} from './sessionQueryCache';

describe('canonical query keys', () => {
  it('builds stable hierarchical me and parking keys', () => {
    expect(meKeys.profile()).toEqual(['me', 'profile']);
    expect(meKeys.smartReturn()).toEqual(['me', 'smart-return']);
    expect(parkingKeys.mySpots()).toEqual(['parking', 'my-spots']);
    expect(parkingKeys.spot('spot-1')).toEqual(['parking', 'spot', 'spot-1']);
  });

  it('normalizes nearby filters so equivalent filters share identity', () => {
    const a = normalizeNearbyFilters({ lat: 41.0, lng: 29.0 });
    const b = normalizeNearbyFilters({ lat: 41.0, lng: 29.0 });
    expect(a).toEqual(b);
    expect(parkingKeys.nearby({ lat: 41.0, lng: 29.0 })).toEqual(
      parkingKeys.nearby({ lat: 41.0, lng: 29.0 }),
    );
  });

  it('distinguishes nearby filters that change radius or limit', () => {
    expect(parkingKeys.nearby({ lat: 41, lng: 29, radius: 500 })).not.toEqual(
      parkingKeys.nearby({ lat: 41, lng: 29, radius: 1000 }),
    );
    expect(parkingKeys.nearby({ lat: 41, lng: 29, limit: 10 })).not.toEqual(
      parkingKeys.nearby({ lat: 41, lng: 29, limit: 20 }),
    );
  });

  it('omits undefined optionals so accidental undefined does not fork the cache', () => {
    expect(normalizeNearbyFilters({ lat: 1, lng: 2, radius: undefined })).toEqual({
      lat: 1,
      lng: 2,
    });
  });
});

describe('clearUserSessionQueries', () => {
  it('cancels and removes every user-scoped root while preserving nearby discovery', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const cancel = vi.spyOn(client, 'cancelQueries');
    const remove = vi.spyOn(client, 'removeQueries');

    client.setQueryData(meKeys.profile(), { id: 'p1' });
    client.setQueryData(parkingKeys.mySpots(), [{ id: 's1' }]);
    client.setQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }), [{ id: 'n1' }]);

    clearUserSessionQueries(client);

    expect(cancel).toHaveBeenCalledTimes(USER_SESSION_QUERY_ROOTS.length);
    expect(remove).toHaveBeenCalledTimes(USER_SESSION_QUERY_ROOTS.length);
    expect(client.getQueryData(meKeys.profile())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.mySpots())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }))).toEqual([{ id: 'n1' }]);
  });

  it('prevents User A profile from remaining after clear before User B loads', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const userA: Profile = {
      id: 'profile-a',
      authUserId: 'user-a',
      email: 'alice@parkio.dev',
      displayName: 'Alice',
      phoneNumber: null,
      city: null,
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00.000Z',
    };
    client.setQueryData(meKeys.profile(), userA);
    clearUserSessionQueries(client);
    expect(client.getQueryData(meKeys.profile())).toBeUndefined();
  });
});
