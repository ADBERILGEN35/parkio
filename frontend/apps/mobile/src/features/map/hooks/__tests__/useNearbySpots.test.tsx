import { renderHook, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { parkingApi } from '@/services/api';
import { useNearbySpots } from '../useNearbySpots';
import { createQueryWrapper } from '../../__tests__/queryWrapper';
import { makeSpot } from '../../__tests__/fixtures';

jest.mock('@/services/api', () => ({
  parkingApi: { getNearbySpots: jest.fn() },
}));

const getNearbySpots = jest.mocked(parkingApi.getNearbySpots);

describe('useNearbySpots', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
  });

  it('does not fetch until a center is committed', () => {
    const { wrapper } = createQueryWrapper();
    const { result } = renderHook(() => useNearbySpots({ center: null }), { wrapper });

    expect(getNearbySpots).not.toHaveBeenCalled();
    expect(result.current.spots).toEqual([]);
  });

  it('fetches for the committed center and enriches results with real distances', async () => {
    getNearbySpots.mockResolvedValue([
      makeSpot({ id: 'near', latitude: 38.4187, longitude: 27.1283 }),
      makeSpot({ id: 'far', latitude: 38.43, longitude: 27.15 }),
    ]);
    const { wrapper } = createQueryWrapper();

    const { result } = renderHook(
      () => useNearbySpots({ center: { lat: 38.4187, lng: 27.1283 } }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.spots).toHaveLength(2));

    const [params, signal] = getNearbySpots.mock.calls[0];
    expect(params).toMatchObject({ lat: 38.4187, lng: 27.1283 });
    // The query passes an AbortSignal so a center change cancels the in-flight call.
    expect(signal).toBeInstanceOf(AbortSignal);

    const near = result.current.spots.find((s) => s.id === 'near');
    expect(near?.distanceMeters).toBeGreaterThanOrEqual(0);
    expect(near?.distanceMeters).toBeLessThan(50);
  });

  it('surfaces an error state without throwing', async () => {
    getNearbySpots.mockRejectedValue(new Error('network'));
    const { wrapper } = createQueryWrapper();

    const { result } = renderHook(
      () => useNearbySpots({ center: { lat: 1, lng: 2 } }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.spots).toEqual([]);
  });

  it('stays gated when explicitly disabled (e.g. offline)', () => {
    const { wrapper } = createQueryWrapper();
    renderHook(() => useNearbySpots({ center: { lat: 1, lng: 2 }, enabled: false }), { wrapper });
    expect(getNearbySpots).not.toHaveBeenCalled();
  });

  it('renders the last successful backend result while offline without refetching', async () => {
    getNearbySpots.mockResolvedValue([makeSpot({ id: 'cached' })]);
    const { wrapper } = createQueryWrapper();
    const center = { lat: 38.4187, lng: 27.1283 };

    const { result, rerender } = renderHook<ReturnType<typeof useNearbySpots>, { enabled: boolean }>(
      ({ enabled }) => useNearbySpots({ center, enabled }),
      { wrapper, initialProps: { enabled: true } },
    );

    await waitFor(() => expect(result.current.spots).toHaveLength(1));
    await waitFor(() => expect(result.current.cacheUpdatedAt).toBeNull());

    getNearbySpots.mockClear();
    rerender({ enabled: false });

    await waitFor(() => expect(result.current.isShowingCached).toBe(true));
    expect(result.current.spots).toHaveLength(1);
    expect(result.current.spots[0].id).toBe('cached');
    expect(result.current.cacheUpdatedAt).toEqual(expect.any(Number));
    expect(getNearbySpots).not.toHaveBeenCalled();
  });
});
