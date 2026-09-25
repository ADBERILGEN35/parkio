import {
  clampPublicExploreFilters,
  publicExploreQueryOptions,
} from '@/data/query-options/publicExplore';
import {
  PUBLIC_EXPLORE_LIMIT,
  PUBLIC_EXPLORE_RADIUS_METERS,
} from '@/features/public-explore/constants';
import { publicExploreKeys } from '@/data/keys';

describe('publicExploreQueryOptions', () => {
  it('clamps limit to <= 6 and radius to <= 5000', () => {
    expect(
      clampPublicExploreFilters({
        lat: 38.42,
        lng: 27.14,
        radiusMeters: 50_000,
        limit: 99,
      }),
    ).toEqual({
      lat: 38.42,
      lng: 27.14,
      radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
      limit: PUBLIC_EXPLORE_LIMIT,
    });
  });

  it('uses public-explore query keys (not private municipal nearby)', () => {
    const filters = {
      lat: 38.42,
      lng: 27.14,
      radiusMeters: PUBLIC_EXPLORE_RADIUS_METERS,
      limit: PUBLIC_EXPLORE_LIMIT,
    };
    const options = publicExploreQueryOptions(filters);
    expect(options.queryKey).toEqual(publicExploreKeys.facilities(filters));
    expect(JSON.stringify(options.queryKey)).toContain('public-explore');
    expect(JSON.stringify(options.queryKey)).not.toContain('nearby');
  });
});
