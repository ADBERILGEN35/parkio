import type { Destination } from '@parkio/types';
import { assistantDestinationIdentityKey } from '../assistantDestinationKey';
import { sanitizePersistedDestination } from '../assistantStore';
import {
  ASSISTANT_RECOMMEND_LIMIT,
  ASSISTANT_RECOMMEND_RADIUS_METERS,
  MAX_VISIBLE_REASONS,
  RECOMMENDATION_REASON_I18N,
} from '../recommendationPresentation';

describe('assistantDestinationIdentityKey', () => {
  it('prefers place identity', () => {
    const dest: Destination = {
      label: 'Konak',
      latitude: 38.42,
      longitude: 27.13,
      source: 'GEOCODING',
      placeIdentity: {
        provider: 'osm-nominatim',
        providerPlaceId: 'N123',
        canonicalKey: 'osm-nominatim:N123',
      },
    };
    expect(assistantDestinationIdentityKey(dest)).toBe('identity:osm-nominatim:N123');
  });

  it('falls back to rounded coordinates', () => {
    const dest: Destination = {
      label: 'Alsancak',
      latitude: 38.439123,
      longitude: 27.142987,
      source: 'GEOCODING',
    };
    expect(assistantDestinationIdentityKey(dest)).toBe('coord:38.43912:27.14299');
  });
});

describe('sanitizePersistedDestination', () => {
  it('accepts valid payload', () => {
    const result = sanitizePersistedDestination({
      version: 1,
      destination: {
        label: 'Ev',
        latitude: 38.4,
        longitude: 27.1,
        source: 'SYSTEM',
      },
    });
    expect(result?.label).toBe('Ev');
    expect(result?.source).toBe('SYSTEM');
  });

  it('rejects malformed coords', () => {
    expect(
      sanitizePersistedDestination({
        version: 1,
        destination: { label: 'X', latitude: 999, longitude: 0, source: 'GEOCODING' },
      }),
    ).toBeNull();
  });

  it('rejects wrong schema version', () => {
    expect(
      sanitizePersistedDestination({
        version: 99,
        destination: { label: 'X', latitude: 38, longitude: 27, source: 'GEOCODING' },
      }),
    ).toBeNull();
  });
});

describe('recommendationPresentation', () => {
  it('maps all reason codes', () => {
    expect(RECOMMENDATION_REASON_I18N.CLOSE_TO_DESTINATION).toBe(
      'assistant.reasons.closeToDestination',
    );
    expect(RECOMMENDATION_REASON_I18N.FAVOURITE).toBe('assistant.reasons.favourite');
  });

  it('exposes SPA-05 defaults', () => {
    expect(ASSISTANT_RECOMMEND_RADIUS_METERS).toBe(1500);
    expect(ASSISTANT_RECOMMEND_LIMIT).toBe(10);
    expect(MAX_VISIBLE_REASONS).toBe(3);
  });
});
