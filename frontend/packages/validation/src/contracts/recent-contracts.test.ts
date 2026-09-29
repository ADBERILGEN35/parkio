import { describe, expect, it } from 'vitest';
import {
  confirmRecentDestinationRequestSchema,
  recentDestinationSchema,
  recentParkingSchema,
  recordRecentParkingRequestSchema,
} from './recent';

describe('recent contracts', () => {
  it('parses recent destination and confirm request', () => {
    const recent = recentDestinationSchema.parse({
      id: '0b8f6c3a-0000-0000-0000-000000000071',
      label: 'Kordon',
      latitude: 38.43,
      longitude: 27.14,
      source: 'MAP_PIN',
      firstUsedAt: '2026-08-06T12:00:00Z',
      lastUsedAt: '2026-08-06T13:00:00Z',
      useCount: 2,
    });
    expect(recent.useCount).toBe(2);

    expect(
      confirmRecentDestinationRequestSchema.parse({
        label: 'Kordon',
        latitude: 38.43,
        longitude: 27.14,
        source: 'GEOCODING',
      }),
    ).toMatchObject({ label: 'Kordon' });
  });

  it('parses recent parking payloads', () => {
    expect(
      recentParkingSchema.parse({
        id: '0b8f6c3a-0000-0000-0000-000000000072',
        targetKind: 'MUNICIPAL_FACILITY',
        targetId: '0b8f6c3a-0000-0000-0000-000000000073',
        firstUsedAt: '2026-08-06T12:00:00Z',
        lastUsedAt: '2026-08-06T13:00:00Z',
        useCount: 1,
      }).targetKind,
    ).toBe('MUNICIPAL_FACILITY');

    expect(
      recordRecentParkingRequestSchema.parse({
        targetId: '0b8f6c3a-0000-0000-0000-000000000073',
      }).targetId,
    ).toBe('0b8f6c3a-0000-0000-0000-000000000073');
  });
});
