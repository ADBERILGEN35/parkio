import { renderHook, waitFor } from '@testing-library/react-native';
import type { GeocodeResult } from '@parkio/types';
import { geocodingApi } from '@/services/api';
import { createQueryWrapper } from '@/features/map/__tests__/queryWrapper';
import { useSelectedPlace } from '../useSelectedPlace';

jest.mock('@/services/api', () => ({
  geocodingApi: { searchPlaces: jest.fn() },
}));

const searchPlaces = jest.mocked(geocodingApi.searchPlaces);

const nearest: GeocodeResult = {
  id: 'g1',
  displayName: 'Alemdar Caddesi, Cankurtaran Mahallesi, İstanbul',
  primary: 'Alemdar Caddesi',
  secondary: 'Cankurtaran Mahallesi, İstanbul',
  lat: 41.0082,
  lng: 28.9784,
};

describe('useSelectedPlace', () => {
  beforeEach(() => jest.clearAllMocks());

  it('resolves the pin coordinate to a place label via the existing search endpoint', async () => {
    searchPlaces.mockResolvedValue([nearest]);
    const { wrapper } = createQueryWrapper();
    const { result } = renderHook(() => useSelectedPlace({ lat: 41.0082, lng: 28.9784 }), { wrapper });

    expect(result.current.isResolving).toBe(true);
    await waitFor(() =>
      expect(result.current.place).toEqual({ primary: 'Alemdar Caddesi', secondary: 'Cankurtaran Mahallesi, İstanbul' }),
    );
    // Coordinate query ("lat,lng") — the provider resolves it to the nearest address.
    const [query, limit] = searchPlaces.mock.calls[0];
    expect(query).toBe('41.00820,28.97840');
    expect(limit).toBe(1);
    expect(result.current.isResolving).toBe(false);
    expect(result.current.isUnresolved).toBe(false);
  });

  it('does nothing without a pin', async () => {
    const { wrapper } = createQueryWrapper();
    const { result } = renderHook(() => useSelectedPlace(null), { wrapper });

    await new Promise((r) => setTimeout(r, 500));
    expect(searchPlaces).not.toHaveBeenCalled();
    expect(result.current.place).toBeNull();
    expect(result.current.isResolving).toBe(false);
    expect(result.current.isUnresolved).toBe(false);
  });

  it('reports unresolved when the lookup returns nothing', async () => {
    searchPlaces.mockResolvedValue([]);
    const { wrapper } = createQueryWrapper();
    const { result } = renderHook(() => useSelectedPlace({ lat: 0.001, lng: 0.001 }), { wrapper });

    await waitFor(() => expect(result.current.isUnresolved).toBe(true));
    expect(result.current.place).toBeNull();
  });
});
