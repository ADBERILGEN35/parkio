import type { MunicipalFacility } from '@parkio/types';
import {
  buildMunicipalFacilityDetailFields,
  parseFacilityRouteId,
  parseOptionalDistanceMeters,
} from '../municipalFacilityDetailFields';

function makeFacility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: '70db58f2-4cca-4010-9315-fa46b30fba1e',
    displayName: 'Konak Otopark',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: 'Konak, İzmir',
    latitude: 38.4237,
    longitude: 27.1428,
    capacityTotal: 120,
    availableSpaces: null,
    occupiedSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OpenStreetMap',
    lastUpdatedAt: '2026-08-05T12:00:00Z',
    contributingSourceKeys: ['osm-geofabrik-turkey'],
    selectedFieldProvenanceSummary: {
      ATTRIBUTION: 'osm-geofabrik-turkey',
      COORDINATES: 'osm-geofabrik-turkey',
    },
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}

describe('municipalFacilityDetailFields', () => {
  it('parses route id and distance safely', () => {
    expect(parseFacilityRouteId('  abc  ')).toBe('abc');
    expect(parseFacilityRouteId('')).toBe('');
    expect(parseFacilityRouteId(['x'])).toBe('');
    expect(parseOptionalDistanceMeters('250')).toBe(250);
    expect(parseOptionalDistanceMeters('-1')).toBeNull();
    expect(parseOptionalDistanceMeters('nope')).toBeNull();
  });

  it('builds live İZUM metrics including valid zero available', () => {
    const fields = buildMunicipalFacilityDetailFields(
      makeFacility({
        availableSpaces: 0,
        occupiedSpaces: 59,
        capacityTotal: 59,
        freshness: 'LIVE',
        availabilityFreshness: 'LIVE',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
      }),
      { unnamedLabel: 'Belediye otoparkı', distanceMeters: 120 },
    );
    expect(fields.showLiveMetrics).toBe(true);
    expect(fields.availableSpaces).toBe(0);
    expect(fields.occupiedSpaces).toBe(59);
    expect(fields.capacityTotal).toBe(59);
    expect(fields.sourceLine).toContain('İZUM');
    expect(fields.distanceMeters).toBe(120);
    expect(JSON.stringify(fields)).not.toContain('izmir-izum');
    expect(JSON.stringify(fields)).not.toContain('osm-geofabrik');
  });

  it('hides stale numeric occupancy and keeps stale_live distinct from static', () => {
    const stale = buildMunicipalFacilityDetailFields(
      makeFacility({
        availableSpaces: null,
        capacityTotal: 59,
        freshness: 'STALE',
        availabilityFreshness: 'STALE',
        contributingSourceKeys: ['izmir-izum-otoparklar'],
      }),
      { unnamedLabel: 'Belediye otoparkı' },
    );
    expect(stale.occupancyKind).toBe('stale_live');
    expect(stale.showLiveMetrics).toBe(false);
    expect(stale.availableSpaces).toBeNull();

    const osm = buildMunicipalFacilityDetailFields(makeFacility(), {
      unnamedLabel: 'Belediye otoparkı',
    });
    expect(osm.occupancyKind).toBe('static');
    expect(osm.showLiveMetrics).toBe(false);
  });

  it('hides unknown type, blank operator/address, and duplicate operator', () => {
    const hidden = buildMunicipalFacilityDetailFields(
      makeFacility({
        facilityType: 'UNKNOWN',
        operatorName: '  ',
        addressText: null,
        displayName: '  ',
      }),
      { unnamedLabel: 'Belediye otoparkı' },
    );
    expect(hidden.facilityTypeKey).toBeNull();
    expect(hidden.operatorName).toBeNull();
    expect(hidden.addressText).toBeNull();
    expect(hidden.title).toBe('Belediye otoparkı');
    expect(hidden.showFacilityInfoSection).toBe(false);

    const duplicateOperator = buildMunicipalFacilityDetailFields(
      makeFacility({
        operatorName: 'OpenStreetMap',
        facilityType: 'UNKNOWN',
      }),
      { unnamedLabel: 'Belediye otoparkı' },
    );
    expect(duplicateOperator.operatorName).toBeNull();
    expect(duplicateOperator.showFacilityInfoSection).toBe(false);
  });

  it('does not expose coordinates as display text fields', () => {
    const fields = buildMunicipalFacilityDetailFields(makeFacility(), {
      unnamedLabel: 'Belediye otoparkı',
    });
    expect(fields.canOpenInMaps).toBe(true);
    expect(fields.latitude).toBe(38.4237);
    // Coordinates are bridge-only internals — never rendered as labels in UI tests.
    expect(fields.addressText).not.toContain('38.4237');
  });
});
