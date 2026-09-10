import { describe, expect, it } from 'vitest';
import {
  createCustomSavedPlaceRequestSchema,
  savedPlaceListResponseSchema,
  savedPlaceSchema,
  upsertSavedPlaceRequestSchema,
} from './saved-place';

describe('saved-place contracts', () => {
  it('parses a HOME saved place response', () => {
    const parsed = savedPlaceSchema.parse({
      id: '11111111-1111-4111-8111-111111111111',
      kind: 'HOME',
      label: 'Home',
      latitude: 41.01,
      longitude: 28.97,
      source: 'SYSTEM',
      createdAt: '2026-08-06T12:00:00Z',
      updatedAt: '2026-08-06T12:00:00Z',
    });
    expect(parsed.kind).toBe('HOME');
    expect(parsed.placeIdentity).toBeUndefined();
  });

  it('requires CUSTOM create label', () => {
    expect(() =>
      createCustomSavedPlaceRequestSchema.parse({
        latitude: 41,
        longitude: 29,
      }),
    ).toThrow();
  });

  it('accepts upsert home without label', () => {
    const parsed = upsertSavedPlaceRequestSchema.parse({
      latitude: 41.0082,
      longitude: 28.9784,
      source: 'MAP_PIN',
    });
    expect(parsed.latitude).toBeCloseTo(41.0082);
  });

  it('parses list response', () => {
    const parsed = savedPlaceListResponseSchema.parse({
      items: [
        {
          id: '11111111-1111-4111-8111-111111111111',
          kind: 'WORK',
          label: 'Work',
          latitude: 41.04,
          longitude: 29.0,
          source: 'GEOCODING',
          placeIdentity: {
            provider: 'osm-nominatim',
            providerPlaceId: 'N123',
            canonicalKey: 'osm-nominatim:N123',
          },
          createdAt: '2026-08-06T12:00:00Z',
          updatedAt: '2026-08-06T12:00:00Z',
        },
      ],
    });
    expect(parsed.items).toHaveLength(1);
  });
});
