import { myProfileQueryOptions, mySmartReturnQueryOptions } from '../query-options/me';
import { nearbySpotsQueryOptions, spotDetailQueryOptions, mySpotsQueryOptions } from '../query-options/parking';
import { myReportsQueryOptions } from '../query-options/reports';
import { meKeys, parkingKeys, reportsKeys } from '../keys';
import * as api from '@/services/api';

jest.mock('@/services/api', () => ({
  usersApi: {
    getMyProfile: jest.fn(async () => ({ id: 'u1' })),
    getSmartReturn: jest.fn(async () => ({ enabled: false })),
  },
  parkingApi: {
    getNearbySpots: jest.fn(async () => []),
    getSpot: jest.fn(async () => ({ id: 's1' })),
    getMySpots: jest.fn(async () => []),
  },
  moderationApi: {
    getMyReports: jest.fn(async () => []),
  },
}));

describe('WP-07 mobile query-options signal forwarding', () => {
  const signal = new AbortController().signal;

  it('uses canonical keys', () => {
    expect(myProfileQueryOptions().queryKey).toEqual(meKeys.profile());
    expect(mySpotsQueryOptions().queryKey).toEqual(parkingKeys.mySpots());
    expect(spotDetailQueryOptions('s1').queryKey).toEqual(parkingKeys.spot('s1'));
    expect(myReportsQueryOptions().queryKey).toEqual(reportsKeys.all);
    expect(mySmartReturnQueryOptions().queryKey).toEqual(meKeys.smartReturn());
  });

  it('forwards AbortSignal to representative SDK reads', async () => {
    await myProfileQueryOptions().queryFn!({ signal } as never);
    expect(api.usersApi.getMyProfile).toHaveBeenCalledWith({ signal });

    await mySmartReturnQueryOptions().queryFn!({ signal } as never);
    expect(api.usersApi.getSmartReturn).toHaveBeenCalledWith({ signal });

    await mySpotsQueryOptions().queryFn!({ signal } as never);
    expect(api.parkingApi.getMySpots).toHaveBeenCalledWith({ signal });

    await spotDetailQueryOptions('s1').queryFn!({ signal } as never);
    expect(api.parkingApi.getSpot).toHaveBeenCalledWith('s1', { signal });

    await myReportsQueryOptions().queryFn!({ signal } as never);
    expect(api.moderationApi.getMyReports).toHaveBeenCalledWith({ signal });

    await nearbySpotsQueryOptions({ lat: 38.4, lng: 27.1 }).queryFn!({ signal } as never);
    expect(api.parkingApi.getNearbySpots).toHaveBeenCalledWith(
      expect.objectContaining({ lat: 38.4, lng: 27.1 }),
      signal,
    );
  });
});