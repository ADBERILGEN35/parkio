import { describe, expect, it } from 'vitest';
import {
  recommendationRequestSchema,
  recommendationResponseSchema,
} from './recommendation';

describe('recommendation contracts', () => {
  it('accepts a municipal live candidate payload', () => {
    const parsed = recommendationResponseSchema.safeParse({
      destination: {
        label: 'Forum Bornova',
        latitude: 38.45,
        longitude: 27.2,
        source: 'GEOCODING',
      },
      generatedAt: '2026-08-06T12:00:00Z',
      partial: false,
      inventoryStatus: { community: 'EMPTY', municipal: 'AVAILABLE' },
      candidates: [
        {
          id: 'municipal:cccccccc-cccc-cccc-cccc-cccccccccccc',
          channel: 'MUNICIPAL_FACILITY',
          refId: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
          title: 'Katlı Otopark',
          latitude: 38.4505,
          longitude: 27.2005,
          distanceMeters: 80,
          availability: {
            kind: 'MUNICIPAL',
            freshness: 'LIVE',
            availableSpaces: 12,
            occupiedSpaces: 68,
            capacityTotal: 80,
            sourceLabel: 'IZUM',
            observationTimestamp: '2026-08-06T11:59:00Z',
          },
          sourceLabel: 'IZUM',
          baselineOrder: 0,
          reasons: [{ code: 'CLOSE_TO_DESTINATION' }, { code: 'LIVE_AVAILABILITY' }],
        },
      ],
    });
    expect(parsed.success).toBe(true);
  });

  it('accepts a community candidate without municipal occupancy', () => {
    const parsed = recommendationResponseSchema.safeParse({
      destination: {
        label: 'Kordon',
        latitude: 38.43,
        longitude: 27.14,
        source: 'MAP_PIN',
      },
      generatedAt: '2026-08-06T12:00:00Z',
      partial: true,
      inventoryStatus: { community: 'AVAILABLE', municipal: 'DEGRADED' },
      candidates: [
        {
          id: 'community:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          channel: 'COMMUNITY_SPOT',
          refId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          title: 'Alsancak Cad.',
          latitude: 38.4302,
          longitude: 27.1402,
          distanceMeters: 40,
          availability: {
            kind: 'COMMUNITY',
            communityStatus: 'VERIFIED',
            expiresAt: '2026-08-06T12:10:00Z',
          },
          baselineOrder: 0,
          reasons: [{ code: 'CLOSE_TO_DESTINATION' }, { code: 'COMMUNITY_FRESH' }],
        },
      ],
      warnings: [{ code: 'INVENTORY_DEGRADED' }],
    });
    expect(parsed.success).toBe(true);
  });

  it('rejects oversized radius on request', () => {
    const parsed = recommendationRequestSchema.safeParse({
      destination: {
        label: 'Forum',
        latitude: 38.45,
        longitude: 27.2,
        source: 'GEOCODING',
      },
      radiusMeters: 9000,
    });
    expect(parsed.success).toBe(false);
  });

  it('accepts request defaults with optional identity', () => {
    const parsed = recommendationRequestSchema.safeParse({
      destination: {
        label: 'İzmir Adnan Menderes Havalimanı',
        latitude: 38.2924,
        longitude: 27.157,
        source: 'GEOCODING',
        placeIdentity: {
          provider: 'osm-nominatim',
          providerPlaceId: 'R123',
        },
      },
    });
    expect(parsed.success).toBe(true);
  });
});
