import { describe, expect, it } from 'vitest';
import {
  EMPTY_ASSISTANT_URL_STATE,
  assistantDestinationIdentityKey,
  parseAssistantUrlState,
  serializeAssistantUrlState,
  stripAssistantUrlParams,
} from './assistantUrlState';

describe('assistantUrlState', () => {
  it('parses a valid destination and candidate', () => {
    const params = new URLSearchParams({
      destLat: '38.4192',
      destLng: '27.1287',
      destLabel: 'Konak',
      destSource: 'GEOCODING',
      candidate: 'cand-1',
    });
    const state = parseAssistantUrlState(params);
    expect(state.destination?.label).toBe('Konak');
    expect(state.destination?.latitude).toBe(38.4192);
    expect(state.candidateId).toBe('cand-1');
  });

  it('ignores malformed coordinates', () => {
    const params = new URLSearchParams({
      destLat: 'not-a-number',
      destLng: '27.1',
      destLabel: 'X',
    });
    expect(parseAssistantUrlState(params).destination).toBeNull();
  });

  it('returns empty when assistant disabled', () => {
    const params = new URLSearchParams({
      destLat: '38.4',
      destLng: '27.1',
      destLabel: 'Konak',
    });
    expect(parseAssistantUrlState(params, { assistantEnabled: false })).toEqual(
      EMPTY_ASSISTANT_URL_STATE,
    );
  });

  it('round-trips serialize/parse and preserves unmanaged keys', () => {
    const base = new URLSearchParams({ communityLayer: '0', smartReturn: '1' });
    const serialized = serializeAssistantUrlState(base, {
      destination: {
        label: 'Alsancak',
        latitude: 38.43,
        longitude: 27.14,
        source: 'GEOCODING',
        placeIdentity: {
          provider: 'osm-nominatim',
          providerPlaceId: 'N1',
          canonicalKey: 'osm-nominatim:N1',
        },
        subtitle: 'İzmir',
      },
      candidateId: 'c1',
    });
    expect(serialized.get('communityLayer')).toBe('0');
    expect(serialized.get('smartReturn')).toBe('1');
    const parsed = parseAssistantUrlState(serialized);
    expect(parsed.destination?.label).toBe('Alsancak');
    expect(parsed.destination?.placeIdentity?.providerPlaceId).toBe('N1');
    expect(parsed.candidateId).toBe('c1');
  });

  it('strips assistant params only', () => {
    const params = new URLSearchParams({
      destLat: '1',
      destLng: '2',
      destLabel: 'A',
      communityLayer: '0',
    });
    const stripped = stripAssistantUrlParams(params);
    expect(stripped.get('destLat')).toBeNull();
    expect(stripped.get('communityLayer')).toBe('0');
  });

  it('builds stable destination identity keys', () => {
    expect(
      assistantDestinationIdentityKey({
        label: 'A',
        latitude: 38.41921,
        longitude: 27.12871,
        source: 'GEOCODING',
      }),
    ).toBe('coord:38.41921:27.12871');
  });
});
