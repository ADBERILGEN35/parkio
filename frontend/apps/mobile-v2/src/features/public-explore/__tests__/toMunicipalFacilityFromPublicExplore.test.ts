import type { PublicExploreFacility } from '@parkio/types';
import { toMunicipalFacilityFromPublicExplore } from '../toMunicipalFacilityFromPublicExplore';

const publicFacility: PublicExploreFacility = {
  id: 'pub-1',
  displayName: 'Alsancak',
  operatorName: 'İZUM',
  facilityType: 'OFF_STREET',
  addressText: 'İzmir',
  latitude: 38.43,
  longitude: 27.14,
  capacityTotal: 80,
  availableSpaces: 5,
  availabilityFreshness: 'LIVE',
  dataUpdatedAt: '2026-09-01T10:00:00Z',
  sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
  attribution: 'İZUM',
};

describe('toMunicipalFacilityFromPublicExplore', () => {
  it('maps public fields without inventing private provenance', () => {
    const mapped = toMunicipalFacilityFromPublicExplore(publicFacility);
    expect(mapped).toMatchObject({
      id: 'pub-1',
      displayName: 'Alsancak',
      availableSpaces: 5,
      capacityTotal: 80,
      availabilityFreshness: 'LIVE',
      lastUpdatedAt: '2026-09-01T10:00:00Z',
      sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
      attribution: 'İZUM',
      occupiedSpaces: null,
      contributingSourceKeys: null,
      selectedFieldProvenanceSummary: null,
    });
  });
});
