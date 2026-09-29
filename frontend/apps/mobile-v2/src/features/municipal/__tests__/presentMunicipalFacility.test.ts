import {
  MUNICIPAL_CANONICAL_LABEL_IZUM,
  MUNICIPAL_CANONICAL_LABEL_OSM,
} from '@parkio/geo';
import type { MunicipalFacility } from '@parkio/types';
import { presentMunicipalFacility } from '../presentMunicipalFacility';

function makeFacility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
    displayName: 'Test Facility',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: 'İzmir',
    latitude: 38.4237,
    longitude: 27.1428,
    capacityTotal: 100,
    availableSpaces: null,
    occupiedSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
    lastUpdatedAt: '2026-08-05T12:00:00Z',
    contributingSourceKeys: ['osm-geofabrik-turkey'],
    selectedFieldProvenanceSummary: {
      ATTRIBUTION: 'osm-geofabrik-turkey',
      COORDINATES: 'osm-geofabrik-turkey',
      FACILITY_TYPE: 'osm-geofabrik-turkey',
    },
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}

function serializedPresentation(facility: MunicipalFacility): string {
  return JSON.stringify(presentMunicipalFacility(facility));
}

describe('presentMunicipalFacility', () => {
  it('presents live İZUM with canonical label and preserved metrics', () => {
    const presented = presentMunicipalFacility(
      makeFacility({
        availableSpaces: 1,
        occupiedSpaces: 58,
        capacityTotal: 59,
        freshness: 'LIVE',
        availabilityFreshness: 'LIVE',
        availabilitySource: 'izmir-izum-otoparklar',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
        sourceLabel: 'IZUM',
      }),
    );

    expect(presented.occupancyKind).toBe('live');
    expect(presented.statusRole).toBe('live');
    expect(presented.iconIntent).toBe('live');
    expect(presented.sourceLabels).toEqual([MUNICIPAL_CANONICAL_LABEL_IZUM]);
    expect(presented.sourceLine).toBe(MUNICIPAL_CANONICAL_LABEL_IZUM);
    expect(presented.availabilityCopyKey).toBe('spacesAvailable');
    expect(presented.availableSpaces).toBe(1);
    expect(presented.occupiedSpaces).toBe(58);
    expect(presented.capacityTotal).toBe(59);
  });

  it('presents stale İZUM as stale_live (not OSM static)', () => {
    const presented = presentMunicipalFacility(
      makeFacility({
        availableSpaces: null,
        occupiedSpaces: null,
        capacityTotal: 59,
        freshness: 'STALE',
        availabilityFreshness: 'STALE',
        availabilitySource: 'izmir-izum-otoparklar',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
        sourceLabel: 'IZUM',
      }),
    );

    expect(presented.occupancyKind).toBe('stale_live');
    expect(presented.statusRole).toBe('warning');
    expect(presented.iconIntent).toBe('stale');
    expect(presented.availabilityCopyKey).toBe('availabilityStaleLive');
    expect(presented.freshnessCopyKey).toBe('staleLive');
    expect(presented.sourceLabels).toEqual([MUNICIPAL_CANONICAL_LABEL_IZUM]);
  });

  it('presents OSM as static with OpenStreetMap label', () => {
    const presented = presentMunicipalFacility(makeFacility());

    expect(presented.occupancyKind).toBe('static');
    expect(presented.statusRole).toBe('inactive');
    expect(presented.iconIntent).toBe('static');
    expect(presented.availabilityCopyKey).toBe('availabilityStatic');
    expect(presented.freshnessCopyKey).toBe('static');
    expect(presented.sourceLabels).toEqual([MUNICIPAL_CANONICAL_LABEL_OSM]);
  });

  it('omits unknown / unsupported sources instead of leaking raw keys', () => {
    const presented = presentMunicipalFacility(
      makeFacility({
        contributingSourceKeys: ['izelman-something'],
        sourceLabel: 'unknown-vendor-key',
        attribution: 'raw-attribution-string',
      }),
    );

    expect(presented.sourceLabels).toEqual([]);
    expect(presented.sourceLine).toBeNull();
  });

  it('never leaks raw source keys, provenance field names, or coordinates', () => {
    const blob = serializedPresentation(
      makeFacility({
        contributingSourceKeys: ['osm-geofabrik-turkey', 'izmir-izum-otoparklar'],
      }),
    );

    expect(blob).not.toContain('osm-geofabrik-turkey');
    expect(blob).not.toContain('izmir-izum-otoparklar');
    expect(blob).not.toContain('ATTRIBUTION');
    expect(blob).not.toContain('COORDINATES');
    expect(blob).not.toContain('FACILITY_TYPE');
    expect(blob).not.toContain('latitude');
    expect(blob).not.toContain('longitude');
    expect(blob).not.toContain('ufid');
    expect(blob).toContain(MUNICIPAL_CANONICAL_LABEL_IZUM);
    expect(blob).toContain(MUNICIPAL_CANONICAL_LABEL_OSM);
  });

  it('keeps occupiedSpaces available for future detail UI when null', () => {
    const presented = presentMunicipalFacility(makeFacility({ occupiedSpaces: null }));
    expect(presented.occupiedSpaces).toBeNull();
  });
});
