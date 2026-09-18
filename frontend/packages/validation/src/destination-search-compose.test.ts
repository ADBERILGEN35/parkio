import { describe, expect, it } from 'vitest';
import {
  composeDestinationSearch,
  destinationDuplicateKey,
  normalizeSearchText,
} from './destination-search-compose';

describe('destination search composition', () => {
  it('orders blank-query sections HOME/WORK before CUSTOM then favourites then recents', () => {
    const result = composeDestinationSearch({
      query: '',
      savedPlaces: [
        {
          id: 'c1',
          kind: 'CUSTOM',
          label: 'Cafe',
          latitude: 38.4,
          longitude: 27.1,
          source: 'MAP_PIN',
        },
        {
          id: 'h1',
          kind: 'HOME',
          label: '',
          latitude: 38.41,
          longitude: 27.11,
          source: 'MAP_PIN',
        },
        {
          id: 'w1',
          kind: 'WORK',
          label: '',
          latitude: 38.42,
          longitude: 27.12,
          source: 'MAP_PIN',
        },
      ],
      favouriteDestinations: [
        {
          id: 'f1',
          label: 'Kordon',
          latitude: 38.43,
          longitude: 27.14,
          source: 'GEOCODING',
        },
      ],
      recentDestinations: [
        {
          id: 'r1',
          label: 'Konak',
          latitude: 38.42,
          longitude: 27.13,
          source: 'MAP_PIN',
        },
      ],
      geocodingResults: [
        {
          label: 'Should be ignored',
          latitude: 38.5,
          longitude: 27.2,
        },
      ],
    });

    expect(result.sections.map((s) => s.group)).toEqual([
      'SAVED_PLACE',
      'FAVOURITE_DESTINATION',
      'RECENT_DESTINATION',
    ]);
    expect(result.sections[0].items.map((i) => i.savedPlaceKind)).toEqual([
      'HOME',
      'WORK',
      'CUSTOM',
    ]);
    expect(result.items.some((i) => i.source === 'GEOCODING')).toBe(false);
  });

  it('dedupes by PlaceIdentity preferring SavedPlace over favourite/recent/geocode', () => {
    const identity = {
      provider: 'osm-nominatim',
      providerPlaceId: 'N1',
      canonicalKey: 'osm-nominatim:N1',
    };
    const result = composeDestinationSearch({
      query: 'kor',
      savedPlaces: [
        {
          id: 's1',
          kind: 'CUSTOM',
          label: 'Kordon',
          latitude: 38.43,
          longitude: 27.14,
          source: 'GEOCODING',
          placeIdentity: identity,
        },
      ],
      favouriteDestinations: [
        {
          id: 'f1',
          label: 'Kordon Fav',
          latitude: 38.43,
          longitude: 27.14,
          source: 'GEOCODING',
          placeIdentity: identity,
        },
      ],
      recentDestinations: [
        {
          id: 'r1',
          label: 'Kordon Recent',
          latitude: 38.43,
          longitude: 27.14,
          source: 'GEOCODING',
          placeIdentity: identity,
        },
      ],
      geocodingResults: [
        {
          label: 'Kordon Geo',
          latitude: 38.43,
          longitude: 27.14,
          placeIdentity: identity,
        },
      ],
    });

    const matches = result.items.filter((i) =>
      i.destination.placeIdentity?.canonicalKey === 'osm-nominatim:N1',
    );
    expect(matches).toHaveLength(1);
    expect(matches[0].source).toBe('SAVED_PLACE');
    expect(matches[0].alsoFavourite).toBe(true);
    expect(matches[0].alsoRecent).toBe(true);
  });

  it('does not merge label-only matches at different coordinates', () => {
    const result = composeDestinationSearch({
      query: 'park',
      favouriteDestinations: [
        {
          id: 'f1',
          label: 'Park',
          latitude: 38.4,
          longitude: 27.1,
          source: 'MAP_PIN',
        },
      ],
      recentDestinations: [
        {
          id: 'r1',
          label: 'Park',
          latitude: 38.5,
          longitude: 27.2,
          source: 'MAP_PIN',
        },
      ],
    });
    expect(result.items).toHaveLength(2);
  });

  it('matches Turkish casing deterministically', () => {
    expect(normalizeSearchText('İZMİR')).toBe(normalizeSearchText('izmir'));
    const result = composeDestinationSearch({
      query: 'izmir',
      recentDestinations: [
        {
          id: 'r1',
          label: 'İzmir Saat Kulesi',
          latitude: 38.41,
          longitude: 27.12,
          source: 'GEOCODING',
        },
      ],
    });
    expect(result.items).toHaveLength(1);
  });

  it('uses coordinate duplicate keys at 5 decimal places', () => {
    expect(destinationDuplicateKey(38.430_123_4, 27.140_987_6, null)).toBe(
      'coord:38.43012:27.14099',
    );
  });
});
