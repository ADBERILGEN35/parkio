import { parkingKeys } from '../keys';

describe('parkingKeys municipal hierarchy', () => {
  const filters = { lat: 38.42, lng: 27.14, radiusMeters: 1500 };

  it('produces stable nearby keys for identical inputs', () => {
    expect(parkingKeys.municipalNearby(filters)).toEqual(
      parkingKeys.municipalNearby({ ...filters }),
    );
  });

  it('differentiates nearby keys by radiusMeters', () => {
    expect(parkingKeys.municipalNearby(filters)).not.toEqual(
      parkingKeys.municipalNearby({ ...filters, radiusMeters: 3000 }),
    );
  });

  it('includes limit when provided', () => {
    const withLimit = parkingKeys.municipalNearby({ ...filters, limit: 40 });
    const withoutLimit = parkingKeys.municipalNearby(filters);
    expect(withLimit).not.toEqual(withoutLimit);
    expect(JSON.stringify(withLimit)).toContain('"limit":40');
  });

  it('differentiates detail keys by facility id', () => {
    expect(parkingKeys.municipalFacility('a')).not.toEqual(
      parkingKeys.municipalFacility('b'),
    );
  });

  it('does not collide with community spot nearby keys', () => {
    const municipal = parkingKeys.municipalNearby(filters);
    const spots = parkingKeys.nearby({
      lat: filters.lat,
      lng: filters.lng,
      radius: filters.radiusMeters,
    });
    expect(municipal).not.toEqual(spots);
    expect(municipal[1]).toBe('municipal');
    expect(spots[1]).toBe('nearby');
  });

  it('scopes municipal facility detail under municipalRoot', () => {
    const key = parkingKeys.municipalFacility('70db58f2-4cca-4010-9315-fa46b30fba1e');
    expect(key).toEqual([
      'parking',
      'municipal',
      'facility',
      '70db58f2-4cca-4010-9315-fa46b30fba1e',
    ]);
    expect(key).not.toEqual(
      parkingKeys.spot('70db58f2-4cca-4010-9315-fa46b30fba1e'),
    );
  });
});
