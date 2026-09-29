import { describe, expect, it } from 'vitest';
import type { PublicExploreFacility } from '@parkio/types';
import { toMunicipalFacilityFromPublicExplore } from './publicExploreFacilityAdapter';

describe('toMunicipalFacilityFromPublicExplore', () => {
  it('maps public explore DTO onto canonical municipal fields without inventing live occupancy', () => {
    const input: PublicExploreFacility = {
      id: 'fac-1',
      displayName: 'Konak',
      operatorName: 'IZUM',
      facilityType: 'OFF_STREET',
      addressText: 'Izmir',
      latitude: 38.4,
      longitude: 27.1,
      capacityTotal: 10,
      availableSpaces: 3,
      availabilityFreshness: 'STALE',
      dataUpdatedAt: '2026-09-01T00:00:00Z',
      sourceLabel: 'IZUM',
      attribution: 'CC BY 4.0',
    };

    const mapped = toMunicipalFacilityFromPublicExplore(input);
    expect(mapped.id).toBe(input.id);
    expect(mapped.availabilityFreshness).toBe('STALE');
    expect(mapped.freshness).toBe('STALE');
    expect(mapped.occupiedSpaces).toBeNull();
    expect(mapped.contributingSourceKeys).toBeNull();
    expect(mapped.lastUpdatedAt).toBe(input.dataUpdatedAt);
  });
});
