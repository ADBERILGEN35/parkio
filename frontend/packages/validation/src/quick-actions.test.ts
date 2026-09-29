import { describe, expect, it } from 'vitest';
import type { FavouriteDestination, RecentDestination, SavedPlace } from '@parkio/types';
import {
  asAssistantSearchItem,
  buildQuickActionDescriptors,
  destinationFromFavouriteDestination,
  destinationFromRecentDestination,
  destinationFromSavedPlace,
  resolveHomePlace,
  resolveWorkPlace,
  type QuickActionSourceSnapshot,
} from './quick-actions';

const home: SavedPlace = {
  id: 'h1',
  kind: 'HOME',
  label: 'Evim',
  latitude: 38.42,
  longitude: 27.13,
  source: 'SYSTEM',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const work: SavedPlace = {
  id: 'w1',
  kind: 'WORK',
  label: 'İş',
  latitude: 38.45,
  longitude: 27.15,
  source: 'GEOCODING',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const fav: FavouriteDestination = {
  id: 'f1',
  label: 'Alsancak',
  latitude: 38.43,
  longitude: 27.14,
  source: 'GEOCODING',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const recent: RecentDestination = {
  id: 'r1',
  label: 'Konak',
  latitude: 38.41,
  longitude: 27.12,
  source: 'GEOCODING',
  useCount: 2,
  firstUsedAt: '2026-01-01T00:00:00Z',
  lastUsedAt: '2026-01-02T00:00:00Z',
};

function baseSnapshot(
  overrides: Partial<QuickActionSourceSnapshot> = {},
): QuickActionSourceSnapshot {
  return {
    savedPlaces: [],
    savedPlacesStatus: 'success',
    favouriteDestinations: [],
    favouriteDestinationsStatus: 'success',
    favouriteParkingCount: 0,
    favouriteParkingStatus: 'success',
    recentDestinations: [],
    recentDestinationsStatus: 'success',
    parkedCarAvailable: false,
    parkedCarStatus: 'success',
    ...overrides,
  };
}

describe('destination converters', () => {
  it('maps saved HOME to Destination', () => {
    const dest = destinationFromSavedPlace(home);
    expect(dest.label).toBe('Evim');
    expect(dest.latitude).toBe(38.42);
    expect(dest.source).toBe('SYSTEM');
  });

  it('maps favourite and recent destinations', () => {
    expect(destinationFromFavouriteDestination(fav).label).toBe('Alsancak');
    expect(destinationFromRecentDestination(recent).label).toBe('Konak');
  });

  it('wraps Destination as search item for confirm path', () => {
    const item = asAssistantSearchItem(destinationFromSavedPlace(home), 'SAVED_PLACE', {
      savedPlaceKind: 'HOME',
    });
    expect(item.source).toBe('SAVED_PLACE');
    expect(item.savedPlaceKind).toBe('HOME');
    expect(item.destination.label).toBe('Evim');
  });
});

describe('buildQuickActionDescriptors', () => {
  it('marks HOME/WORK unconfigured when absent', () => {
    const list = buildQuickActionDescriptors(baseSnapshot());
    expect(list.find((d) => d.kind === 'HOME')?.availability).toBe('UNCONFIGURED');
    expect(list.find((d) => d.kind === 'WORK')?.availability).toBe('UNCONFIGURED');
    expect(list.find((d) => d.kind === 'PARKED_CAR')).toBeUndefined();
  });

  it('includes parked car only when available', () => {
    const withCar = buildQuickActionDescriptors(
      baseSnapshot({ parkedCarAvailable: true, parkedCarStatus: 'success' }),
    );
    expect(withCar.map((d) => d.kind)).toEqual([
      'HOME',
      'WORK',
      'PARKED_CAR',
      'FAVOURITE_DESTINATIONS',
      'FAVOURITE_PARKING',
      'RECENT_DESTINATIONS',
    ]);
    expect(withCar.find((d) => d.kind === 'PARKED_CAR')?.availability).toBe('AVAILABLE');
  });

  it('keeps order Home → Work → Parked → fav dest → fav parking → recent', () => {
    const list = buildQuickActionDescriptors(
      baseSnapshot({
        savedPlaces: [home, work],
        favouriteDestinations: [fav],
        favouriteParkingCount: 2,
        recentDestinations: [recent],
        parkedCarAvailable: true,
      }),
    );
    expect(list.map((d) => d.kind)).toEqual([
      'HOME',
      'WORK',
      'PARKED_CAR',
      'FAVOURITE_DESTINATIONS',
      'FAVOURITE_PARKING',
      'RECENT_DESTINATIONS',
    ]);
    expect(list.find((d) => d.kind === 'HOME')?.availability).toBe('AVAILABLE');
    expect(list.find((d) => d.kind === 'FAVOURITE_DESTINATIONS')?.count).toBe(1);
  });

  it('isolates saved-places error from favourites', () => {
    const list = buildQuickActionDescriptors(
      baseSnapshot({
        savedPlacesStatus: 'error',
        favouriteDestinations: [fav],
        favouriteDestinationsStatus: 'success',
      }),
    );
    expect(list.find((d) => d.kind === 'HOME')?.availability).toBe('ERROR');
    expect(list.find((d) => d.kind === 'FAVOURITE_DESTINATIONS')?.availability).toBe('AVAILABLE');
  });
});

describe('resolveHomePlace / resolveWorkPlace', () => {
  it('finds HOME and WORK', () => {
    expect(resolveHomePlace([home, work])?.id).toBe('h1');
    expect(resolveWorkPlace([home, work])?.id).toBe('w1');
    expect(resolveHomePlace([])).toBeNull();
  });
});
