import { describe, expect, it, vi } from 'vitest';
import type { ParkioSdk } from '@/app/sdk';
import { meKeys } from '../keys';
import { myProfileQueryOptions, mySmartReturnQueryOptions } from './me';
import { mySpotsQueryOptions, nearbySpotsQueryOptions, spotDetailQueryOptions } from './parking';
import { myReportsQueryOptions } from './reports';

function createSdkMock(): ParkioSdk {
  return {
    usersApi: {
      getMyProfile: vi.fn(async () => ({ id: 'p1' })),
      getSmartReturn: vi.fn(async () => ({ enabled: true })),
    },
    parkingApi: {
      getMySpots: vi.fn(async () => []),
      getNearbySpots: vi.fn(async () => []),
      getSpot: vi.fn(async () => ({ id: 's1' })),
    },
    moderationApi: {
      getMyReports: vi.fn(async () => []),
    },
  } as unknown as ParkioSdk;
}

describe('me and parking query options', () => {
  it('bind canonical keys and call the injected SDK', async () => {
    const sdk = createSdkMock();
    const profile = myProfileQueryOptions(sdk);
    expect(profile.queryKey).toEqual(meKeys.profile());
    const signal = new AbortController().signal;
    await profile.queryFn!({ signal } as never);
    expect(sdk.usersApi.getMyProfile).toHaveBeenCalledWith({ signal });

    const smartReturn = mySmartReturnQueryOptions(sdk);
    await smartReturn.queryFn!({ signal } as never);
    expect(sdk.usersApi.getSmartReturn).toHaveBeenCalledWith({ signal });

    const mySpots = mySpotsQueryOptions(sdk);
    await mySpots.queryFn!({ signal } as never);
    expect(sdk.parkingApi.getMySpots).toHaveBeenCalledWith({ signal });

    const reports = myReportsQueryOptions(sdk);
    await reports.queryFn!({ signal } as never);
    expect(sdk.moderationApi.getMyReports).toHaveBeenCalledWith({ signal });
  });

  it('passes AbortSignal to nearby parking reads', async () => {
    const sdk = createSdkMock();
    const nearby = nearbySpotsQueryOptions(sdk, { lat: 41, lng: 29, radius: 1000 });
    const signal = new AbortController().signal;
    await nearby.queryFn!({ signal } as never);
    expect(sdk.parkingApi.getNearbySpots).toHaveBeenCalledWith(
      { lat: 41, lng: 29, radius: 1000 },
      signal,
    );
  });

  it('passes AbortSignal to spot detail reads', async () => {
    const sdk = createSdkMock();
    const detail = spotDetailQueryOptions(sdk, 'spot-1');
    const signal = new AbortController().signal;
    await detail.queryFn!({ signal } as never);
    expect(sdk.parkingApi.getSpot).toHaveBeenCalledWith('spot-1', { signal });
  });
});
