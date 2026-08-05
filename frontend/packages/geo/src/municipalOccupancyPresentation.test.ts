import type { MunicipalFacility } from '@parkio/types';
import { describe, expect, it } from 'vitest';
import {
  municipalAvailabilityCopyKey,
  municipalFreshnessCopyKey,
  municipalOccupancyPresentationKind,
  summarizeMunicipalOccupancy,
} from './municipalOccupancyPresentation';

function facility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: 'f1',
    displayName: 'Test',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: null,
    latitude: 38.4,
    longitude: 27.1,
    capacityTotal: 100,
    availableSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: null,
    sourceLabel: null,
    lastUpdatedAt: null,
    contributingSourceKeys: ['osm-geofabrik-turkey'],
    selectedFieldProvenanceSummary: null,
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}

describe('municipalOccupancyPresentation', () => {
  it('classifies OSM as static, not broken live feed', () => {
    const kind = municipalOccupancyPresentationKind(facility());
    expect(kind).toBe('static');
    expect(municipalAvailabilityCopyKey(kind)).toBe('availabilityStatic');
    expect(municipalFreshnessCopyKey(kind)).toBe('static');
  });

  it('classifies live İZUM with available spaces as live', () => {
    const kind = municipalOccupancyPresentationKind(
      facility({
        contributingSourceKeys: ['izmir-izum-otoparklar'],
        availableSpaces: 12,
        freshness: 'LIVE',
        availabilityFreshness: 'LIVE',
        availabilitySource: 'izmir-izum-otoparklar',
      }),
    );
    expect(kind).toBe('live');
    expect(municipalAvailabilityCopyKey(kind)).toBe('spacesAvailable');
  });

  it('classifies stale İZUM as stale_live distinct from static', () => {
    const kind = municipalOccupancyPresentationKind(
      facility({
        contributingSourceKeys: ['izmir-izum-otoparklar'],
        availableSpaces: null,
        freshness: 'STALE',
        availabilityFreshness: null,
        capacityTotal: 50,
      }),
    );
    expect(kind).toBe('stale_live');
    expect(municipalAvailabilityCopyKey(kind)).toBe('availabilityStaleLive');
    expect(municipalFreshnessCopyKey(kind)).toBe('staleLive');
  });

  it('summarizes live vs static counts', () => {
    const summary = summarizeMunicipalOccupancy([
      facility(),
      facility({
        id: 'f2',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
        availableSpaces: 3,
        freshness: 'LIVE',
        availabilityFreshness: 'LIVE',
      }),
      facility({
        id: 'f3',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
        freshness: 'STALE',
        availabilityFreshness: null,
      }),
    ]);
    expect(summary).toEqual({
      total: 3,
      live: 1,
      aging: 0,
      staleLive: 1,
      staticOnly: 1,
      invalid: 0,
    });
  });
});
