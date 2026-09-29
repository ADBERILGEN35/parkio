import { IZELMAN_ROADSIDE_SOURCE_LABEL, type RoadsideSegment } from '@parkio/types';
import { describe, expect, it } from 'vitest';
import {
  isIzelmanRoadsideFacility,
  toMunicipalFacilityFromRoadside,
} from './roadsideInventory';

const sample: RoadsideSegment = {
  id: '11111111-1111-1111-1111-111111111111',
  displayName: 'Konak roadside sample',
  district: 'Konak',
  neighborhood: 'Alsancak',
  address: 'Atatürk Cad.',
  latitude: 38.438,
  longitude: 27.142,
  capacityTotal: 18,
  availableSpaces: 4,
  sourceAgeClassification: 'HISTORICAL',
  attribution: 'Izmir Metropolitan Municipality / IZELMAN A.S.',
  legalStatusUnknown: true,
  updatedAt: '2022-11-25T00:00:00Z',
};

describe('roadsideInventory', () => {
  it('projects roadside onto municipal shape with unknown access and unavailable occupancy', () => {
    const facility = toMunicipalFacilityFromRoadside(sample);
    expect(facility.id).toBe(sample.id);
    expect(facility.facilityType).toBe('ON_STREET');
    expect(facility.sourceLabel).toBe(IZELMAN_ROADSIDE_SOURCE_LABEL);
    expect(facility.accessClassification).toBe('UNKNOWN');
    expect(facility.availabilityFreshness).toBe('UNAVAILABLE');
    expect(facility.availableSpaces).toBeNull();
    expect(facility.freshness).toBe('UNAVAILABLE');
    expect(isIzelmanRoadsideFacility(facility)).toBe(true);
  });
});
