import { act, renderHook, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import type { GeocodeResult } from '@parkio/types';
import { useRecentSearches } from '../useRecentSearches';

const STORAGE_KEY = 'parkio.recentSearches';

function place(n: number): GeocodeResult {
  return {
    id: `place-${n}`,
    displayName: `Place ${n}, İzmir`,
    primary: `Place ${n}`,
    secondary: 'Konak, İzmir',
    lat: 38.4 + n / 1000,
    lng: 27.1 + n / 1000,
  };
}

describe('useRecentSearches', () => {
  beforeEach(() => {
    (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
    jest.clearAllMocks();
  });

  it('hydrates persisted recents on mount', async () => {
    await SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify([place(1)]));
    const { result } = renderHook(() => useRecentSearches());

    await waitFor(() => expect(result.current.recents).toHaveLength(1));
    expect(result.current.recents[0].id).toBe('place-1');
  });

  it('adds most-recent-first and persists to the keystore', async () => {
    const { result } = renderHook(() => useRecentSearches());

    act(() => result.current.add(place(1)));
    act(() => result.current.add(place(2)));

    expect(result.current.recents.map((r) => r.id)).toEqual(['place-2', 'place-1']);
    await waitFor(() =>
      expect(SecureStore.setItemAsync).toHaveBeenCalledWith(STORAGE_KEY, expect.any(String)),
    );
  });

  it('de-duplicates by id, moving a re-selected place to the front', () => {
    const { result } = renderHook(() => useRecentSearches());

    act(() => result.current.add(place(1)));
    act(() => result.current.add(place(2)));
    act(() => result.current.add(place(1)));

    expect(result.current.recents.map((r) => r.id)).toEqual(['place-1', 'place-2']);
  });

  it('caps the list at five entries', () => {
    const { result } = renderHook(() => useRecentSearches());

    act(() => {
      for (let n = 1; n <= 7; n += 1) result.current.add(place(n));
    });

    expect(result.current.recents).toHaveLength(5);
    expect(result.current.recents[0].id).toBe('place-7');
  });

  it('clears all recents', () => {
    const { result } = renderHook(() => useRecentSearches());
    act(() => result.current.add(place(1)));
    act(() => result.current.clear());
    expect(result.current.recents).toEqual([]);
  });
});
