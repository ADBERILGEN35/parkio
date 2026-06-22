import type { PublicSpot } from '@parkio/types';
import { describe, expect, it } from 'vitest';
import {
  EMPTY_FILTERS,
  availableSorts,
  availableStatuses,
  defaultSort,
  filterSpots,
  formatDistance,
  haversineMeters,
  hasActiveFilters,
  sortSpots,
  withDistance,
} from './spotDiscovery';

function makeSpot(overrides: Partial<PublicSpot> & Pick<PublicSpot, 'id'>): PublicSpot {
  return {
    mediaId: 'm',
    latitude: 38.42,
    longitude: 27.14,
    addressText: 'Somewhere',
    description: null,
    manualLocationEdited: false,
    suitableVehicleTypes: ['SEDAN'],
    parkingContext: 'STREET_PARKING',
    legalStatus: 'LEGAL',
    violationReasons: [],
    status: 'ACTIVE',
    expiresAt: '2026-06-20T12:00:00Z',
    createdAt: '2026-06-20T10:00:00Z',
    updatedAt: '2026-06-20T10:00:00Z',
    ...overrides,
  };
}

describe('haversineMeters', () => {
  it('is ~0 for the same point', () => {
    expect(haversineMeters({ lat: 38.42, lng: 27.14 }, { lat: 38.42, lng: 27.14 })).toBeLessThan(1);
  });

  it('approximates a known short distance (~1.11km per 0.01° latitude)', () => {
    const meters = haversineMeters({ lat: 38.42, lng: 27.14 }, { lat: 38.43, lng: 27.14 });
    expect(meters).toBeGreaterThan(1050);
    expect(meters).toBeLessThan(1150);
  });
});

describe('formatDistance', () => {
  it('uses meters below 1km and km above', () => {
    expect(formatDistance(120)).toBe('120 m');
    expect(formatDistance(1400)).toBe('1.4 km');
    expect(formatDistance(12000)).toBe('12 km');
  });
});

describe('withDistance', () => {
  it('attaches distance from the center, ordered closest-first when sorted', () => {
    const near = makeSpot({ id: 'near', latitude: 38.42, longitude: 27.14 });
    const far = makeSpot({ id: 'far', latitude: 38.5, longitude: 27.3 });
    const enriched = withDistance([far, near], { lat: 38.42, lng: 27.14 });
    const byId = Object.fromEntries(enriched.map((s) => [s.id, s.distanceMeters]));
    expect(byId.near).toBeLessThan(byId.far as number);
  });

  it('sets distance null when there is no center', () => {
    const enriched = withDistance([makeSpot({ id: 'a' })], null);
    expect(enriched[0].distanceMeters).toBeNull();
  });
});

describe('filterSpots', () => {
  const spots = [
    makeSpot({ id: 'a', status: 'ACTIVE', legalStatus: 'LEGAL' }),
    makeSpot({ id: 'b', status: 'FILLED', legalStatus: 'LEGAL' }),
    makeSpot({ id: 'c', status: 'ACTIVE', legalStatus: 'UNCERTAIN' }),
  ];

  it('returns all when no filters are active', () => {
    expect(filterSpots(spots, EMPTY_FILTERS)).toHaveLength(3);
  });

  it('filters by status', () => {
    const result = filterSpots(spots, { statuses: ['ACTIVE'], legalOnly: false });
    expect(result.map((s) => s.id)).toEqual(['a', 'c']);
  });

  it('filters by legal only', () => {
    const result = filterSpots(spots, { statuses: [], legalOnly: true });
    expect(result.map((s) => s.id)).toEqual(['a', 'b']);
  });

  it('combines status and legal filters', () => {
    const result = filterSpots(spots, { statuses: ['ACTIVE'], legalOnly: true });
    expect(result.map((s) => s.id)).toEqual(['a']);
  });
});

describe('sortSpots', () => {
  const center = { lat: 38.42, lng: 27.14 };
  const spots = withDistance(
    [
      makeSpot({
        id: 'far-old',
        latitude: 38.5,
        longitude: 27.3,
        createdAt: '2026-06-19T10:00:00Z',
        updatedAt: '2026-06-19T10:00:00Z',
      }),
      makeSpot({
        id: 'near-new',
        latitude: 38.421,
        longitude: 27.141,
        createdAt: '2026-06-20T11:00:00Z',
        updatedAt: '2026-06-20T08:00:00Z',
      }),
      makeSpot({
        id: 'mid-recent',
        latitude: 38.45,
        longitude: 27.2,
        createdAt: '2026-06-18T10:00:00Z',
        updatedAt: '2026-06-20T12:00:00Z',
      }),
    ],
    center,
  );

  it('sorts nearest by distance', () => {
    expect(sortSpots(spots, 'nearest').map((s) => s.id)).toEqual([
      'near-new',
      'mid-recent',
      'far-old',
    ]);
  });

  it('sorts newest by createdAt', () => {
    expect(sortSpots(spots, 'newest')[0].id).toBe('near-new');
  });

  it('sorts recent by updatedAt', () => {
    expect(sortSpots(spots, 'recent')[0].id).toBe('mid-recent');
  });

  it('sinks null distances to the bottom for nearest', () => {
    const noCenter = withDistance([makeSpot({ id: 'x' })], null);
    expect(sortSpots(noCenter, 'nearest')[0].id).toBe('x');
  });
});

describe('sort availability + defaults', () => {
  it('offers nearest only when a center exists', () => {
    expect(availableSorts(true)).toContain('nearest');
    expect(availableSorts(false)).not.toContain('nearest');
  });

  it('defaults to nearest with a center, newest without', () => {
    expect(defaultSort(true)).toBe('nearest');
    expect(defaultSort(false)).toBe('newest');
  });
});

describe('availableStatuses', () => {
  it('returns present statuses in canonical order', () => {
    const spots = [
      makeSpot({ id: 'a', status: 'FILLED' }),
      makeSpot({ id: 'b', status: 'ACTIVE' }),
      makeSpot({ id: 'c', status: 'ACTIVE' }),
    ];
    expect(availableStatuses(spots)).toEqual(['ACTIVE', 'FILLED']);
  });
});

describe('hasActiveFilters', () => {
  it('detects active filters', () => {
    expect(hasActiveFilters(EMPTY_FILTERS)).toBe(false);
    expect(hasActiveFilters({ statuses: ['ACTIVE'], legalOnly: false })).toBe(true);
    expect(hasActiveFilters({ statuses: [], legalOnly: true })).toBe(true);
  });
});
