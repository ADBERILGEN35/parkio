import type { MunicipalFacility } from '@parkio/types';

/** Stable ingestion keys — presentation only; never shown to users. */
export const MUNICIPAL_SOURCE_KEY_IZUM = 'izmir-izum-otoparklar';
export const MUNICIPAL_SOURCE_KEY_OSM = 'osm-geofabrik-turkey';

/** Canonical end-user labels (Turkish municipality copy for IZUM). */
export const MUNICIPAL_CANONICAL_LABEL_IZUM = 'İzmir Büyükşehir Belediyesi / İZUM';
export const MUNICIPAL_CANONICAL_LABEL_OSM = 'OpenStreetMap';

const SOURCE_KEY_SORT_ORDER: Record<string, number> = {
  [MUNICIPAL_SOURCE_KEY_IZUM]: 0,
  [MUNICIPAL_SOURCE_KEY_OSM]: 1,
};

export type MunicipalSourceFamily = 'izum' | 'osm' | 'izelman' | 'unknown';

/** Map a raw ingestion key to a source family. */
export function municipalSourceFamily(sourceKey: string): MunicipalSourceFamily {
  const key = sourceKey.trim();
  if (key === MUNICIPAL_SOURCE_KEY_IZUM) return 'izum';
  if (key === MUNICIPAL_SOURCE_KEY_OSM) return 'osm';
  if (key.startsWith('izelman-')) return 'izelman';
  return 'unknown';
}

/** Canonical user-facing label for a source key, or null when unknown/unpublished. */
export function canonicalLabelForSourceKey(sourceKey: string): string | null {
  switch (municipalSourceFamily(sourceKey)) {
    case 'izum':
      return MUNICIPAL_CANONICAL_LABEL_IZUM;
    case 'osm':
      return MUNICIPAL_CANONICAL_LABEL_OSM;
    default:
      return null;
  }
}

/**
 * Fallback when `contributingSourceKeys` is absent (e.g. provenance publication off).
 * Uses only published `sourceLabel` heuristics — never returns raw backend strings.
 */
export function canonicalLabelForSourceLabel(sourceLabel: string | null | undefined): string | null {
  if (sourceLabel == null || !sourceLabel.trim()) return null;
  const folded = sourceLabel.trim().toUpperCase();
  if (folded.includes('IZUM') || (folded.includes('IZMIR') && folded.includes('BELEDIY'))) {
    return MUNICIPAL_CANONICAL_LABEL_IZUM;
  }
  if (folded.includes('OPENSTREETMAP') || folded.includes('GEOFABRIK')) {
    return MUNICIPAL_CANONICAL_LABEL_OSM;
  }
  return null;
}

/**
 * Unique canonical data-source labels for a facility, deterministic order: IZUM → OSM → other.
 * Never exposes source keys, registry field names, or distributor strings.
 */
export function municipalDataSourceLabels(facility: MunicipalFacility): string[] {
  const keys = facility.contributingSourceKeys?.filter((k) => k != null && k.trim() !== '') ?? [];
  const orderedKeys = [...keys].sort((a, b) => {
    const orderA = SOURCE_KEY_SORT_ORDER[a] ?? 99;
    const orderB = SOURCE_KEY_SORT_ORDER[b] ?? 99;
    if (orderA !== orderB) return orderA - orderB;
    return a.localeCompare(b);
  });

  const labels: string[] = [];
  const seen = new Set<string>();
  for (const key of orderedKeys) {
    const label = canonicalLabelForSourceKey(key);
    if (label != null && !seen.has(label)) {
      seen.add(label);
      labels.push(label);
    }
  }

  if (labels.length > 0) return labels;

  const fallback = canonicalLabelForSourceLabel(facility.sourceLabel);
  return fallback != null ? [fallback] : [];
}

/** Single-line summary for compact surfaces (list cards). */
export function formatMunicipalDataSourcesLine(facility: MunicipalFacility): string | null {
  const labels = municipalDataSourceLabels(facility);
  if (labels.length === 0) return null;
  return labels.join(' · ');
}

/** Display text for filter chips derived from raw backend `sourceLabel` (filter value unchanged). */
export function displaySourceLabelForFilter(rawSourceLabel: string): string | null {
  return canonicalLabelForSourceLabel(rawSourceLabel);
}
