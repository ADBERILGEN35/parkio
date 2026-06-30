import { describe, expect, it } from 'vitest';
import type { ParkingStatus, PublicSpot } from '@parkio/types';
import {
  availableSorts,
  availableStatuses,
  defaultSort,
  EMPTY_FILTERS,
  filterSpots,
  hasActiveFilters,
  sortSpots,
  withDistance,
} from './discovery';

function spot(overrides: Partial<PublicSpot> = {}): PublicSpot {
  return {
    id: overrides.id ?? 'id-1',
    mediaId: 'm-1',
    latitude: 38.4237,
    longitude: 27.1428,
    addressText: null,
    description: null,
    manualLocationEdited: false,
    suitableVehicleTypes: ['ANY'],
    parkingContext: 'STREET_PARKING',
    legalStatus: 'LEGAL',
    violationReasons: [],
    status: 'ACTIVE',
    expiresAt: '2026-07-01T00:00:00Z',
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-10T00:00:00Z',
    ...overrides,
  };
}

describe('withDistance', () => {
  it('computes distance from center', () => {
    const [s] = withDistance([spot({ latitude: 38.4327, longitude: 27.1428 })], {
      lat: 38.4237,
      lng: 27.1428,
    });
    expect(s.distanceMeters).not.toBeNull();
    expect(s.distanceMeters as number).toBeGreaterThan(950);
  });

  it('returns null distance with no center', () => {
    const [s] = withDistance([spot()], null);
    expect(s.distanceMeters).toBeNull();
  });
});

describe('filterSpots', () => {
  const spots = [
    spot({ id: 'a', status: 'ACTIVE', legalStatus: 'LEGAL' }),
    spot({ id: 'b', status: 'FILLED', legalStatus: 'UNCERTAIN' }),
    spot({ id: 'c', status: 'VERIFIED', legalStatus: 'LEGAL' }),
  ];

  it('returns all when no filters', () => {
    expect(filterSpots(spots, EMPTY_FILTERS)).toHaveLength(3);
    expect(hasActiveFilters(EMPTY_FILTERS)).toBe(false);
  });

  it('filters by status', () => {
    const out = filterSpots(spots, { statuses: ['VERIFIED'], legalOnly: false });
    expect(out.map((s) => s.id)).toEqual(['c']);
  });

  it('filters legal only', () => {
    const out = filterSpots(spots, { statuses: [], legalOnly: true });
    expect(out.map((s) => s.id)).toEqual(['a', 'c']);
  });
});

describe('sortSpots', () => {
  it('sorts nearest with null distances last', () => {
    const withDist = [
      { ...spot({ id: 'far' }), distanceMeters: 900 },
      { ...spot({ id: 'none' }), distanceMeters: null },
      { ...spot({ id: 'near' }), distanceMeters: 100 },
    ];
    expect(sortSpots(withDist, 'nearest').map((s) => s.id)).toEqual(['near', 'far', 'none']);
  });

  it('sorts newest by createdAt desc', () => {
    const list = [
      { ...spot({ id: 'old', createdAt: '2026-06-01T00:00:00Z' }), distanceMeters: null },
      { ...spot({ id: 'new', createdAt: '2026-06-20T00:00:00Z' }), distanceMeters: null },
    ];
    expect(sortSpots(list, 'newest').map((s) => s.id)).toEqual(['new', 'old']);
  });
});

describe('sort availability + defaults', () => {
  it('offers nearest only with a center', () => {
    expect(availableSorts(true)).toContain('nearest');
    expect(availableSorts(false)).not.toContain('nearest');
    expect(defaultSort(true)).toBe('nearest');
    expect(defaultSort(false)).toBe('newest');
  });
});

describe('availableStatuses', () => {
  it('returns present statuses in canonical order', () => {
    const spots = [spot({ status: 'FILLED' }), spot({ status: 'ACTIVE' }), spot({ status: 'VERIFIED' })];
    expect(availableStatuses(spots)).toEqual<ParkingStatus[]>(['ACTIVE', 'VERIFIED', 'FILLED']);
  });
});
