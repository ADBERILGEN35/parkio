import { describe, expect, it } from 'vitest';
import {
  createFavouriteDestinationRequestSchema,
  createFavouriteParkingRequestSchema,
  favouriteDestinationSchema,
  favouriteParkingSchema,
} from './favourite';

describe('favourite contracts', () => {
  it('parses parking favourite', () => {
    const parsed = favouriteParkingSchema.parse({
      id: '11111111-1111-4111-8111-111111111111',
      targetKind: 'MUNICIPAL_FACILITY',
      targetId: '22222222-2222-4222-8222-222222222222',
      createdAt: '2026-08-06T12:00:00Z',
    });
    expect(parsed.targetKind).toBe('MUNICIPAL_FACILITY');
  });

  it('accepts create parking without targetKind', () => {
    const parsed = createFavouriteParkingRequestSchema.parse({
      targetId: '22222222-2222-4222-8222-222222222222',
    });
    expect(parsed.targetId).toBe('22222222-2222-4222-8222-222222222222');
  });

  it('parses destination favourite with Turkish label', () => {
    const parsed = favouriteDestinationSchema.parse({
      id: '11111111-1111-4111-8111-111111111111',
      label: 'Forum Bornova',
      latitude: 38.45,
      longitude: 27.21,
      source: 'GEOCODING',
      createdAt: '2026-08-06T12:00:00Z',
      updatedAt: '2026-08-06T12:00:00Z',
    });
    expect(parsed.label).toBe('Forum Bornova');
  });

  it('requires destination label', () => {
    expect(() =>
      createFavouriteDestinationRequestSchema.parse({
        latitude: 38.4,
        longitude: 27.1,
      }),
    ).toThrow();
  });
});
