import { DEFAULT_NEARBY_RADIUS_M } from '@parkio/geo';

/**
 * Municipal discovery map filters (MOBILE-MUNI-V2-04).
 * Semantic values only — never store labels, i18n strings, or DTOs.
 */

export const MUNICIPAL_FILTER_SCHEMA_VERSION = 1 as const;

/** Product-approved discrete municipal search radii (meters). */
export const MUNICIPAL_RADIUS_OPTIONS_M = [500, 1000, 1500, 3000, 5000] as const;

export type MunicipalRadiusMeters = (typeof MUNICIPAL_RADIUS_OPTIONS_M)[number];

export type MunicipalSourceFilter = 'all' | 'izum' | 'ispark' | 'anpark' | 'konya' | 'kayseri' | 'osm';

export type MunicipalOccupancyFilter = 'all' | 'live' | 'static';

export interface MunicipalMapFilters {
  version: typeof MUNICIPAL_FILTER_SCHEMA_VERSION;
  /** User-facing municipal layer visibility (separate from build-time feature flag). */
  layerEnabled: boolean;
  source: MunicipalSourceFilter;
  occupancy: MunicipalOccupancyFilter;
  radiusMeters: MunicipalRadiusMeters;
}

export const DEFAULT_MUNICIPAL_MAP_FILTERS: MunicipalMapFilters = {
  version: MUNICIPAL_FILTER_SCHEMA_VERSION,
  layerEnabled: true,
  source: 'all',
  occupancy: 'all',
  radiusMeters: DEFAULT_NEARBY_RADIUS_M as MunicipalRadiusMeters,
};

const SOURCE_VALUES = new Set<MunicipalSourceFilter>([
  'all',
  'izum',
  'ispark',
  'anpark',
  'konya',
  'kayseri',
  'osm',
]);
const OCCUPANCY_VALUES = new Set<MunicipalOccupancyFilter>(['all', 'live', 'static']);
const RADIUS_VALUES = new Set<number>(MUNICIPAL_RADIUS_OPTIONS_M);

/** True when source, occupancy, or radius differs from product defaults (layer excluded). */
export function countActiveMunicipalFilters(filters: MunicipalMapFilters): number {
  let count = 0;
  if (filters.source !== DEFAULT_MUNICIPAL_MAP_FILTERS.source) count += 1;
  if (filters.occupancy !== DEFAULT_MUNICIPAL_MAP_FILTERS.occupancy) count += 1;
  if (filters.radiusMeters !== DEFAULT_MUNICIPAL_MAP_FILTERS.radiusMeters) count += 1;
  return count;
}

export function hasActiveMunicipalMapFilters(filters: MunicipalMapFilters): boolean {
  return countActiveMunicipalFilters(filters) > 0;
}

/**
 * Coerce persisted / untrusted values to a safe filter snapshot.
 * Invalid fields fall back individually; unknown schema → full defaults.
 */
export function sanitizeMunicipalMapFilters(raw: unknown): MunicipalMapFilters {
  if (raw == null || typeof raw !== 'object') {
    return { ...DEFAULT_MUNICIPAL_MAP_FILTERS };
  }
  const record = raw as Record<string, unknown>;
  const version = record.version;
  if (version !== MUNICIPAL_FILTER_SCHEMA_VERSION && version != null) {
    // Future versions: migrate when added; unknown → defaults.
    if (typeof version !== 'number' || version < 1) {
      return { ...DEFAULT_MUNICIPAL_MAP_FILTERS };
    }
  }

  const source =
    typeof record.source === 'string' && SOURCE_VALUES.has(record.source as MunicipalSourceFilter)
      ? (record.source as MunicipalSourceFilter)
      : DEFAULT_MUNICIPAL_MAP_FILTERS.source;

  const occupancy =
    typeof record.occupancy === 'string' &&
    OCCUPANCY_VALUES.has(record.occupancy as MunicipalOccupancyFilter)
      ? (record.occupancy as MunicipalOccupancyFilter)
      : DEFAULT_MUNICIPAL_MAP_FILTERS.occupancy;

  const radiusMeters =
    typeof record.radiusMeters === 'number' && RADIUS_VALUES.has(record.radiusMeters)
      ? (record.radiusMeters as MunicipalRadiusMeters)
      : DEFAULT_MUNICIPAL_MAP_FILTERS.radiusMeters;

  const layerEnabled =
    typeof record.layerEnabled === 'boolean'
      ? record.layerEnabled
      : DEFAULT_MUNICIPAL_MAP_FILTERS.layerEnabled;

  return {
    version: MUNICIPAL_FILTER_SCHEMA_VERSION,
    layerEnabled,
    source,
    occupancy,
    radiusMeters,
  };
}

/** Reset source / occupancy / radius; keep layer visibility. */
export function resetMunicipalMapFilters(current: MunicipalMapFilters): MunicipalMapFilters {
  return {
    ...DEFAULT_MUNICIPAL_MAP_FILTERS,
    layerEnabled: current.layerEnabled,
  };
}

export function formatMunicipalRadiusLabel(meters: number): string {
  if (meters >= 1000) {
    const km = meters / 1000;
    return Number.isInteger(km) ? `${km} km` : `${km.toFixed(1).replace(/\.0$/, '')} km`;
  }
  return `${meters} m`;
}
