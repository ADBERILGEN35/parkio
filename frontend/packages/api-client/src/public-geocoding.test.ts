import { describe, expect, it, vi } from 'vitest';
import type { AxiosInstance } from 'axios';
import { createPublicGeocodingApi } from './public-geocoding';

describe('createPublicGeocodingApi', () => {
  it('calls the certified public geocoding path with q and limit', async () => {
    const get = vi.fn().mockResolvedValue({
      data: {
        results: [
          {
            id: '1',
            displayName: 'Alsancak',
            primary: 'Alsancak',
            secondary: 'İzmir',
            lat: 38.45,
            lng: 27.15,
          },
        ],
      },
    });
    const api = createPublicGeocodingApi({ get } as unknown as AxiosInstance);
    const signal = new AbortController().signal;

    const results = await api.searchPlaces('Alsancak', 5, signal);

    expect(get).toHaveBeenCalledWith('/public/geocoding/search', {
      params: { q: 'Alsancak', limit: 5 },
      signal,
    });
    expect(results).toHaveLength(1);
    expect(results[0]?.primary).toBe('Alsancak');
  });

  it('omits limit when undefined', async () => {
    const get = vi.fn().mockResolvedValue({ data: { results: [] } });
    const api = createPublicGeocodingApi({ get } as unknown as AxiosInstance);

    await api.searchPlaces('Konak');

    expect(get).toHaveBeenCalledWith('/public/geocoding/search', {
      params: { q: 'Konak' },
      signal: undefined,
    });
  });
});
