import { describe, expect, it } from 'vitest';
import type { MunicipalFacility } from '@parkio/types';
import {
  MUNICIPAL_CANONICAL_LABEL_IZUM,
  MUNICIPAL_CANONICAL_LABEL_ISPARK,
  MUNICIPAL_CANONICAL_LABEL_OSM,
  MUNICIPAL_SOURCE_KEY_IZUM,
  MUNICIPAL_SOURCE_KEY_ISPARK,
  MUNICIPAL_SOURCE_KEY_OSM,
  canonicalLabelForSourceKey,
  canonicalLabelForSourceLabel,
  displaySourceLabelForFilter,
  formatMunicipalDataSourcesLine,
  municipalDataSourceLabels,
} from './municipalSourcePresentation';

function facility(overrides: Partial<MunicipalFacility> = {}): MunicipalFacility {
  return {
    id: 'id-1',
    displayName: 'Lot',
    operatorName: null,
    facilityType: 'OFF_STREET',
    addressText: null,
    latitude: 38.42,
    longitude: 27.14,
    capacityTotal: 100,
    availableSpaces: null,
    occupiedSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution: 'OpenStreetMap contributors',
    sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
    lastUpdatedAt: null,
    contributingSourceKeys: null,
    selectedFieldProvenanceSummary: null,
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: null,
    ...overrides,
  };
}

describe('canonicalLabelForSourceKey', () => {
  it('maps known keys', () => {
    expect(canonicalLabelForSourceKey(MUNICIPAL_SOURCE_KEY_OSM)).toBe(MUNICIPAL_CANONICAL_LABEL_OSM);
    expect(canonicalLabelForSourceKey(MUNICIPAL_SOURCE_KEY_IZUM)).toBe(MUNICIPAL_CANONICAL_LABEL_IZUM);
    expect(canonicalLabelForSourceKey(MUNICIPAL_SOURCE_KEY_ISPARK)).toBe(MUNICIPAL_CANONICAL_LABEL_ISPARK);
  });

  it('omits unknown keys', () => {
    expect(canonicalLabelForSourceKey('osm-geofabrik-turkey')).toBe(MUNICIPAL_CANONICAL_LABEL_OSM);
    expect(canonicalLabelForSourceKey('unknown-source')).toBeNull();
    expect(canonicalLabelForSourceKey('izelman-open-parking-facilities')).toBeNull();
  });
});

describe('canonicalLabelForSourceLabel', () => {
  it('recognizes OSM backend copy without exposing Geofabrik', () => {
    expect(canonicalLabelForSourceLabel('OpenStreetMap contributors / Geofabrik GmbH')).toBe(
      MUNICIPAL_CANONICAL_LABEL_OSM,
    );
    expect(canonicalLabelForSourceLabel('OSM')).toBeNull();
  });

  it('recognizes IZUM backend copy', () => {
    expect(canonicalLabelForSourceLabel('Izmir Buyuksehir Belediyesi / IZUM')).toBe(
      MUNICIPAL_CANONICAL_LABEL_IZUM,
    );
  });

  it('recognizes ISPARK backend copy', () => {
    expect(canonicalLabelForSourceLabel('Istanbul Buyuksehir Belediyesi / ISPARK')).toBe(
      MUNICIPAL_CANONICAL_LABEL_ISPARK,
    );
  });
});

describe('municipalDataSourceLabels', () => {
  it('prefers contributingSourceKeys with IZUM before OSM', () => {
    expect(
      municipalDataSourceLabels(
        facility({
          contributingSourceKeys: [MUNICIPAL_SOURCE_KEY_OSM, MUNICIPAL_SOURCE_KEY_IZUM],
        }),
      ),
    ).toEqual([MUNICIPAL_CANONICAL_LABEL_IZUM, MUNICIPAL_CANONICAL_LABEL_OSM]);
  });

  it('deduplicates repeated keys', () => {
    expect(
      municipalDataSourceLabels(
        facility({
          contributingSourceKeys: [MUNICIPAL_SOURCE_KEY_OSM, MUNICIPAL_SOURCE_KEY_OSM],
        }),
      ),
    ).toEqual([MUNICIPAL_CANONICAL_LABEL_OSM]);
  });

  it('falls back to sourceLabel when keys are absent', () => {
    expect(
      municipalDataSourceLabels(
        facility({
          contributingSourceKeys: null,
          sourceLabel: 'OpenStreetMap contributors / Geofabrik GmbH',
        }),
      ),
    ).toEqual([MUNICIPAL_CANONICAL_LABEL_OSM]);
  });

  it('returns IZUM-only label from contributingSourceKeys', () => {
    expect(
      municipalDataSourceLabels(
        facility({
          contributingSourceKeys: [MUNICIPAL_SOURCE_KEY_IZUM],
          sourceLabel: 'Izmir Buyuksehir Belediyesi / IZUM',
        }),
      ),
    ).toEqual([MUNICIPAL_CANONICAL_LABEL_IZUM]);
  });

  it('never returns raw source keys', () => {
    const labels = municipalDataSourceLabels(
      facility({
        contributingSourceKeys: [MUNICIPAL_SOURCE_KEY_OSM],
        selectedFieldProvenanceSummary: {
          ATTRIBUTION: MUNICIPAL_SOURCE_KEY_OSM,
          COORDINATES: MUNICIPAL_SOURCE_KEY_OSM,
        },
      }),
    );
    expect(labels).toEqual([MUNICIPAL_CANONICAL_LABEL_OSM]);
    expect(labels.some((l) => l.includes('geofabrik'))).toBe(false);
    expect(labels.some((l) => l.includes('ATTRIBUTION'))).toBe(false);
  });

  it('returns no labels when keys and sourceLabel are unmapped', () => {
    expect(
      municipalDataSourceLabels(
        facility({
          contributingSourceKeys: ['unknown-ingest-key'],
          sourceLabel: 'OSM',
        }),
      ),
    ).toEqual([]);
  });
});

describe('formatMunicipalDataSourcesLine', () => {
  it('joins multi-source labels once each', () => {
    expect(
      formatMunicipalDataSourcesLine(
        facility({
          contributingSourceKeys: [MUNICIPAL_SOURCE_KEY_IZUM, MUNICIPAL_SOURCE_KEY_OSM],
        }),
      ),
    ).toBe(`${MUNICIPAL_CANONICAL_LABEL_IZUM} · ${MUNICIPAL_CANONICAL_LABEL_OSM}`);
  });

  it('returns null when no canonical labels resolve', () => {
    expect(
      formatMunicipalDataSourcesLine(
        facility({
          contributingSourceKeys: null,
          sourceLabel: 'OSM',
        }),
      ),
    ).toBeNull();
  });
});

describe('displaySourceLabelForFilter', () => {
  it('maps raw filter chip labels to canonical copy', () => {
    expect(displaySourceLabelForFilter('OpenStreetMap contributors / Geofabrik GmbH')).toBe(
      MUNICIPAL_CANONICAL_LABEL_OSM,
    );
    expect(displaySourceLabelForFilter('OSM')).toBeNull();
  });
});
