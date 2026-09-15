import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { clearUserSessionQueries } from './sessionQueryCache';
import { meKeys, parkingKeys, placesKeys } from './keys';

describe('PA-02 web QueryClient terminal cleanup', () => {
  it('WEB-A/E: clears private roots including nearby, spot, signed media', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(meKeys.profile(), { id: 'user-a' });
    client.setQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }), [{ id: 'community-a' }]);
    client.setQueryData(parkingKeys.spot('spot-a'), { id: 'spot-a' });
    client.setQueryData(parkingKeys.spotMediaAccessUrl('spot-a'), {
      url: 'https://signed.example/a',
    });
    client.setQueryData(parkingKeys.municipalFacility('fac-a'), { id: 'fac-a' });
    client.setQueryData(placesKeys.favouriteDestinations(), [{ id: 'fav-a' }]);
    client.setQueryData(['public-explore', 'x'], [{ id: 'public' }]);

    await clearUserSessionQueries(client);

    expect(client.getQueryData(meKeys.profile())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }))).toBeUndefined();
    expect(client.getQueryData(parkingKeys.spot('spot-a'))).toBeUndefined();
    expect(client.getQueryData(parkingKeys.spotMediaAccessUrl('spot-a'))).toBeUndefined();
    expect(client.getQueryData(parkingKeys.municipalFacility('fac-a'))).toBeUndefined();
    expect(client.getQueryData(placesKeys.favouriteDestinations())).toBeUndefined();
    expect(client.getQueryData(['public-explore', 'x'])).toEqual([{ id: 'public' }]);
  });

  it('WEB-C: late private fetch after clear does not repopulate', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let resolveFetch: (value: unknown) => void = () => undefined;
    const pending = new Promise((resolve) => {
      resolveFetch = resolve;
    });

    const fetchPromise = client
      .fetchQuery({
        queryKey: parkingKeys.spot('spot-late'),
        queryFn: () => pending,
      })
      .then(
        () => 'resolved' as const,
        (error: unknown) => error,
      );

    await clearUserSessionQueries(client);
    resolveFetch({ id: 'spot-late', secret: true });

    const outcome = await fetchPromise;
    expect(outcome).not.toBe('resolved');
    expect(client.getQueryData(parkingKeys.spot('spot-late'))).toBeUndefined();
  });

  it('WEB-D: User A → clear → User B starts empty for identical keys', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(meKeys.profile(), { id: 'a' });
    await clearUserSessionQueries(client);
    expect(client.getQueryData(meKeys.profile())).toBeUndefined();
    client.setQueryData(meKeys.profile(), { id: 'b' });
    expect(client.getQueryData(meKeys.profile())).toEqual({ id: 'b' });
  });
});
