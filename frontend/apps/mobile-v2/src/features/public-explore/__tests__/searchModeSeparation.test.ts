import fs from 'node:fs';
import path from 'node:path';

const root = path.join(__dirname, '../../../..');

function read(relative: string): string {
  return fs.readFileSync(path.join(root, relative), 'utf8');
}

describe('Wave 2 search mode separation', () => {
  it('authenticated MapSearchOverlay still uses private usePlaceSearch', () => {
    const source = read('src/features/map/MapSearchOverlay.tsx');
    expect(source).toContain('usePlaceSearch');
    expect(source).toContain('useRecentSearches');
    expect(source).not.toContain('usePublicPlaceSearch');
    expect(source).not.toContain('publicGeocodingApi');
  });

  it('authenticated place search hook remains 300ms debounce', () => {
    const source = read('src/features/map/hooks.ts');
    expect(source).toMatch(/setTimeout\(\(\) => setDebounced\(query\), 300\)/);
    expect(source).toContain('placeSearchQueryOptions');
  });

  it('public Explore uses PublicMapSearchOverlay + public place search', () => {
    const screen = read('src/features/public-explore/PublicExploreScreen.tsx');
    expect(screen).toContain('PublicMapSearchOverlay');
    expect(screen).toContain('selectedDestination');
    expect(screen).toContain('setDestinationMarker');
    expect(screen).not.toContain('usePlaceSearch');
    expect(screen).not.toContain('useRecentSearches');
  });

  it('public search overlay uses public hook and rate-limit copy', () => {
    const overlay = read('src/features/public-explore/PublicMapSearchOverlay.tsx');
    expect(overlay).toContain('usePublicPlaceSearch');
    expect(overlay).toContain('publicExplore.search.rateLimited');
    expect(overlay).toContain('publicExplore.search.noResults');
    expect(overlay).not.toContain('useRecentSearches');
    expect(overlay).not.toContain('usePlaceSearch');
  });

  it('authenticated map still avoids public search wiring', () => {
    const map = read('app/(main)/(tabs)/map.tsx');
    expect(map).toContain('MapSearchOverlay');
    expect(map).not.toContain('PublicMapSearchOverlay');
    expect(map).not.toContain('publicPlaceSearchQueryOptions');
  });
});
