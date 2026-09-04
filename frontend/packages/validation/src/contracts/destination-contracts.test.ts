import { describe, expect, it } from 'vitest';
import { destinationSchema, placeIdentitySchema } from './destination';

describe('destination contracts', () => {
  it('accepts a full destination with place identity', () => {
    const parsed = destinationSchema.parse({
      label: 'Forum Bornova',
      latitude: 38.4501,
      longitude: 27.2112,
      source: 'GEOCODING',
      placeIdentity: {
        provider: 'osm-nominatim',
        providerPlaceId: '314159',
        canonicalKey: 'osm-nominatim:314159',
      },
      subtitle: 'Bornova, İzmir',
    });

    expect(parsed.label).toBe('Forum Bornova');
    expect(parsed.placeIdentity?.canonicalKey).toBe('osm-nominatim:314159');
  });

  it('accepts destination without place identity', () => {
    const parsed = destinationSchema.parse({
      label: 'Pin',
      latitude: 41,
      longitude: 29,
      source: 'MAP_PIN',
    });

    expect(parsed.placeIdentity).toBeUndefined();
  });

  it('rejects invalid coordinates', () => {
    expect(() =>
      destinationSchema.parse({
        label: 'X',
        latitude: 100,
        longitude: 27,
        source: 'SYSTEM',
      }),
    ).toThrow();
  });

  it('parses place identity alone', () => {
    expect(
      placeIdentitySchema.parse({
        provider: 'osm-nominatim',
        providerPlaceId: '1',
        canonicalKey: 'osm-nominatim:1',
      }),
    ).toMatchObject({ provider: 'osm-nominatim' });
  });
});
