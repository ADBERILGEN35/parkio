import { QueryClient } from '@tanstack/react-query';
import { clearUserSessionQueries } from '../sessionQueryCache';
import {
  meKeys,
  parkingKeys,
  placesKeys,
  publicExploreKeys,
  publicGeocodingKeys,
  reportsKeys,
} from '../keys';

describe('PA-02 session query cache isolation (mobile-v2)', () => {
  function seed(client: QueryClient) {
    client.setQueryData(meKeys.profile(), { id: 'user-a' });
    client.setQueryData(parkingKeys.mySpots(), [{ id: 'spot-a' }]);
    client.setQueryData(parkingKeys.activeSession(), {
      id: 'sess-a',
      latitude: 41.01,
      longitude: 29.01,
    });
    client.setQueryData(parkingKeys.nearby({ lat: 41, lng: 29 }), [
      { id: 'community-a', latitude: 41, longitude: 29 },
    ]);
    client.setQueryData(parkingKeys.spot('spot-a'), {
      id: 'spot-a',
      mediaUrl: 'https://signed.example/a',
    });
    client.setQueryData(parkingKeys.spotMediaAccessUrl('spot-a'), {
      url: 'https://signed.example/private-a',
    });
    client.setQueryData(parkingKeys.municipalFacility('fac-a'), { id: 'fac-a', private: true });
    client.setQueryData(placesKeys.saved(), [{ id: 'saved-a' }]);
    client.setQueryData(reportsKeys.all, [{ id: 'rep-a' }]);
    client.setQueryData(publicExploreKeys.facilities({ lat: 41, lng: 29, radiusMeters: 800, limit: 40 }), [
      { id: 'public-fac' },
    ]);
    client.setQueryData(publicGeocodingKeys.places('izmir'), [{ id: 'geo-public' }]);
  }

  it('MOBILE-A/E: clears every SESSION_PRIVATE root including nearby/spot/media', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    seed(client);

    await clearUserSessionQueries(client);

    expect(client.getQueryData(meKeys.profile())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.mySpots())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.activeSession())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.nearby({ lat: 41, lng: 29 }))).toBeUndefined();
    expect(client.getQueryData(parkingKeys.spot('spot-a'))).toBeUndefined();
    expect(client.getQueryData(parkingKeys.spotMediaAccessUrl('spot-a'))).toBeUndefined();
    expect(client.getQueryData(parkingKeys.municipalFacility('fac-a'))).toBeUndefined();
    expect(client.getQueryData(placesKeys.saved())).toBeUndefined();
    expect(client.getQueryData(reportsKeys.all)).toBeUndefined();
  });

  it('MOBILE-B: preserves PUBLIC_SAFE public-explore and public-geocoding', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    seed(client);

    await clearUserSessionQueries(client);

    expect(
      client.getQueryData(
        publicExploreKeys.facilities({ lat: 41, lng: 29, radiusMeters: 800, limit: 40 }),
      ),
    ).toEqual([{ id: 'public-fac' }]);
    expect(client.getQueryData(publicGeocodingKeys.places('izmir'))).toEqual([{ id: 'geo-public' }]);
  });

  it('MOBILE-C: late in-flight private query does not repopulate after logout clear', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let resolveFetch: (value: unknown) => void = () => undefined;
    const pending = new Promise((resolve) => {
      resolveFetch = resolve;
    });

    const fetchPromise = client
      .fetchQuery({
        queryKey: parkingKeys.nearby({ lat: 1, lng: 2 }),
        queryFn: () => pending,
      })
      .then(
        () => 'resolved' as const,
        (error: unknown) => error,
      );

    await clearUserSessionQueries(client);
    resolveFetch([{ id: 'late-community' }]);

    const outcome = await fetchPromise;
    expect(outcome).not.toBe('resolved');
    expect(client.getQueryData(parkingKeys.nearby({ lat: 1, lng: 2 }))).toBeUndefined();
  });

  it('MOBILE-D: User A → clear → User B does not see A private cache', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(meKeys.profile(), { id: 'user-a', email: 'a@parkio.dev' });
    client.setQueryData(parkingKeys.spotMediaAccessUrl('spot-a'), {
      url: 'https://signed.example/a',
    });

    await clearUserSessionQueries(client);
    expect(client.getQueryData(meKeys.profile())).toBeUndefined();
    expect(client.getQueryData(parkingKeys.spotMediaAccessUrl('spot-a'))).toBeUndefined();

    client.setQueryData(meKeys.profile(), { id: 'user-b', email: 'b@parkio.dev' });
    expect(client.getQueryData(meKeys.profile())).toEqual({
      id: 'user-b',
      email: 'b@parkio.dev',
    });
  });
});
