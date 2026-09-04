import { describe, expect, it } from 'vitest';
import {
  canonicalizeMapDiscoveryUrlState,
  mapDiscoveryUrlStateKey,
  parseMapDiscoveryUrlState,
  serializeMapDiscoveryUrlState,
  type MapDiscoveryUrlState,
} from './mapDiscoveryUrlState';

function buildState(overrides: Partial<MapDiscoveryUrlState> = {}): MapDiscoveryUrlState {
  return {
    communityLayerVisible: true,
    municipalLayerVisible: true,
    municipalFilters: {
      availability: 'all',
      sourceLabels: [],
      facilityTypes: [],
      provenanceOnly: false,
    },
    ...overrides,
  };
}

describe('mapDiscoveryUrlState', () => {
  it('serializes defaults to no managed params', () => {
    expect(serializeMapDiscoveryUrlState(new URLSearchParams(), buildState()).toString()).toBe('');
  });

  it('serializes non-default layers and filters deterministically', () => {
    const params = serializeMapDiscoveryUrlState(
      new URLSearchParams('smartReturn=1&foo=bar'),
      buildState({
        communityLayerVisible: false,
        municipalFilters: {
          availability: 'available',
          sourceLabels: ['B Source', 'A Source', 'A Source'],
          facilityTypes: ['UNKNOWN', 'OFF_STREET'],
          provenanceOnly: true,
        },
      }),
    );

    expect(params.toString()).toBe(
      'smartReturn=1&foo=bar&communityLayer=0&municipalAvailability=available&municipalSources=A+Source%2CB+Source&municipalTypes=OFF_STREET%2CUNKNOWN&municipalProvenance=1',
    );
  });

  it('parses duplicate and blank params deterministically', () => {
    const parsed = parseMapDiscoveryUrlState(
      new URLSearchParams(
        'communityLayer=0&communityLayer=1&municipalSources=&municipalSources=B,A,A&municipalTypes=OFF_STREET&municipalTypes=&municipalTypes=UNKNOWN',
      ),
    );

    expect(parsed.communityLayerVisible).toBe(true);
    expect(parsed.municipalFilters.sourceLabels).toEqual(['A', 'B']);
    expect(parsed.municipalFilters.facilityTypes).toEqual(['OFF_STREET', 'UNKNOWN']);
  });

  it('falls back safely for invalid enum and boolean values', () => {
    const parsed = parseMapDiscoveryUrlState(
      new URLSearchParams(
        'communityLayer=nope&municipalLayer=2&municipalAvailability=bad&municipalProvenance=maybe',
      ),
    );

    expect(parsed).toEqual(buildState());
  });

  it('preserves unknown params and smartReturn during canonicalization', () => {
    const canonical = canonicalizeMapDiscoveryUrlState(
      new URLSearchParams(
        'smartReturn=1&foo=bar&municipalAvailability=nope&municipalSources=&municipalTypes=&municipalProvenance=2',
      ),
    );

    expect(canonical.toString()).toBe('smartReturn=1&foo=bar');
  });

  it('removes stale source and type values when payload options are known', () => {
    const canonical = canonicalizeMapDiscoveryUrlState(
      new URLSearchParams(
        'municipalSources=Stale,Live&municipalTypes=UNKNOWN,OFF_STREET&municipalAvailability=available',
      ),
      {
        availableSourceLabels: ['Live'],
        availableFacilityTypes: ['OFF_STREET'],
      },
    );

    expect(canonical.toString()).toBe(
      'municipalAvailability=available&municipalSources=Live&municipalTypes=OFF_STREET',
    );
  });

  it('does not erase payload-dependent filters before facility options are known', () => {
    const canonical = canonicalizeMapDiscoveryUrlState(
      new URLSearchParams('municipalSources=PotentiallyLive&municipalTypes=OFF_STREET'),
      {
        availableSourceLabels: undefined,
        availableFacilityTypes: undefined,
      },
    );

    expect(canonical.toString()).toBe(
      'municipalSources=PotentiallyLive&municipalTypes=OFF_STREET',
    );
  });

  it('strips municipal discovery params when the feature flag is off', () => {
    const canonical = canonicalizeMapDiscoveryUrlState(
      new URLSearchParams(
        'smartReturn=1&communityLayer=0&municipalLayer=0&municipalAvailability=available',
      ),
      { municipalDiscoveryEnabled: false },
    );

    expect(canonical.toString()).toBe('smartReturn=1');
  });

  it('is idempotent across parse and serialize', () => {
    const state = buildState({
      communityLayerVisible: false,
      municipalFilters: {
        availability: 'unknown',
        sourceLabels: ['Izmir Buyuksehir Belediyesi / IZUM'],
        facilityTypes: ['OFF_STREET'],
        provenanceOnly: true,
      },
    });

    const serialized = serializeMapDiscoveryUrlState(new URLSearchParams('smartReturn=1'), state);
    const roundTripped = serializeMapDiscoveryUrlState(
      new URLSearchParams('smartReturn=1'),
      parseMapDiscoveryUrlState(serialized),
    );

    expect(roundTripped.toString()).toBe(serialized.toString());
    expect(mapDiscoveryUrlStateKey(parseMapDiscoveryUrlState(serialized))).toBe(
      mapDiscoveryUrlStateKey(state),
    );
  });
});
