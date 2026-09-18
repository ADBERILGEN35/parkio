import {
  PUBLIC_GEOCODING_RESULT_LIMIT,
  publicPlaceSearchQueryOptions,
} from '@/data/query-options/publicGeocoding';
import { publicGeocodingKeys } from '@/data/keys';
import { PUBLIC_PLACE_SEARCH_DEBOUNCE_MS } from '@/features/public-explore/usePublicPlaceSearch';

const mockSearchPlaces = jest.fn();

jest.mock('@/services/api', () => ({
  publicGeocodingApi: {
    searchPlaces: (...args: unknown[]) => mockSearchPlaces(...args),
  },
}));

describe('publicPlaceSearchQueryOptions', () => {
  beforeEach(() => {
    mockSearchPlaces.mockReset();
    mockSearchPlaces.mockResolvedValue([]);
  });

  it('uses public-geocoding keys (not private geocoding)', () => {
    const options = publicPlaceSearchQueryOptions('Alsancak');
    expect(options.queryKey).toEqual(publicGeocodingKeys.places('Alsancak'));
    expect(JSON.stringify(options.queryKey)).toContain('public-geocoding');
    expect(JSON.stringify(options.queryKey)).not.toContain('"geocoding","places"');
  });

  it('disables queries shorter than 3 chars', () => {
    expect(publicPlaceSearchQueryOptions('Al').enabled).toBe(false);
    expect(publicPlaceSearchQueryOptions('Als').enabled).toBe(true);
  });

  it('never retries (no automatic 429 retry)', () => {
    expect(publicPlaceSearchQueryOptions('Alsancak').retry).toBe(false);
  });

  it('requests limit 5', async () => {
    const options = publicPlaceSearchQueryOptions('Alsancak');
    await options.queryFn!({
      queryKey: options.queryKey,
      signal: new AbortController().signal,
      meta: undefined,
      pageParam: undefined,
      direction: undefined,
    } as never);
    expect(mockSearchPlaces).toHaveBeenCalledWith(
      'Alsancak',
      PUBLIC_GEOCODING_RESULT_LIMIT,
      expect.anything(),
    );
  });
});

describe('PUBLIC_PLACE_SEARCH_DEBOUNCE_MS', () => {
  it('is 400ms for public Explore parity', () => {
    expect(PUBLIC_PLACE_SEARCH_DEBOUNCE_MS).toBe(400);
  });
});
