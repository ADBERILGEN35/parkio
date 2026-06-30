import { renderHook, waitFor } from '@testing-library/react-native';
import type { GeocodeResult } from '@parkio/types';
import { geocodingApi } from '@/services/api';
import { usePlaceSearch } from '../usePlaceSearch';
import { createQueryWrapper } from '../../__tests__/queryWrapper';

jest.mock('@/services/api', () => ({
  geocodingApi: { searchPlaces: jest.fn() },
}));

const searchPlaces = jest.mocked(geocodingApi.searchPlaces);

const result: GeocodeResult = {
  id: 'g1',
  displayName: 'Konak Meydanı, İzmir',
  primary: 'Konak Meydanı',
  secondary: 'Konak, İzmir',
  lat: 38.4187,
  lng: 27.1283,
};

describe('usePlaceSearch', () => {
  beforeEach(() => jest.clearAllMocks());

  it('does not search below the 3-character minimum', async () => {
    searchPlaces.mockResolvedValue([result]);
    const { wrapper } = createQueryWrapper();
    const { result: hook } = renderHook(() => usePlaceSearch('ko'), { wrapper });

    // Give the debounce window time to (not) fire.
    await new Promise((r) => setTimeout(r, 400));
    expect(hook.current.isActive).toBe(false);
    expect(searchPlaces).not.toHaveBeenCalled();
  });

  it('debounces, then queries the shared geocoding API with a cancel signal', async () => {
    searchPlaces.mockResolvedValue([result]);
    const { wrapper } = createQueryWrapper();
    const { result: hook } = renderHook(() => usePlaceSearch('konak'), { wrapper });

    await waitFor(() => expect(hook.current.results).toHaveLength(1));

    const [query, limit, signal] = searchPlaces.mock.calls[0];
    expect(query).toBe('konak');
    expect(typeof limit).toBe('number');
    expect(signal).toBeInstanceOf(AbortSignal);
  });

  it('exposes an error state without throwing', async () => {
    searchPlaces.mockRejectedValue(new Error('geocode failed'));
    const { wrapper } = createQueryWrapper();
    const { result: hook } = renderHook(() => usePlaceSearch('konak'), { wrapper });

    await waitFor(() => expect(hook.current.isError).toBe(true));
    expect(hook.current.results).toEqual([]);
  });
});
