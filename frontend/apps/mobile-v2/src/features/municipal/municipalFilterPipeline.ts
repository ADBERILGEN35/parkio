import {
  MUNICIPAL_CANONICAL_LABEL_IZUM,
  MUNICIPAL_CANONICAL_LABEL_ISPARK,
  MUNICIPAL_CANONICAL_LABEL_ANPARK,
  MUNICIPAL_CANONICAL_LABEL_KONYA,
  MUNICIPAL_CANONICAL_LABEL_KAYSERI,
  MUNICIPAL_CANONICAL_LABEL_OSM,
  municipalDataSourceLabels,
  municipalOccupancyPresentationKind,
  summarizeMunicipalOccupancy,
  type MunicipalOccupancySummary,
} from '@parkio/geo';
import type { MunicipalFacility } from '@parkio/types';
import type {
  MunicipalMapFilters,
  MunicipalOccupancyFilter,
  MunicipalSourceFilter,
} from './municipalFilterModel';
import { hasActiveMunicipalMapFilters } from './municipalFilterModel';

export type MunicipalFilterEmptyReason = 'none_nearby' | 'filtered';

export interface MunicipalFilterSummaryCounts {
  total: number;
  /** Live + aging (usable live occupancy). */
  live: number;
  /** OSM / non-live static information. */
  staticOnly: number;
  /** Stale-live İZUM — reported separately; not live, not static. */
  staleLive: number;
}

export interface MunicipalFilterPipelineResult {
  facilities: MunicipalFacility[];
  summary: MunicipalFilterSummaryCounts;
  emptyReason: MunicipalFilterEmptyReason | null;
  selectedFacilityValid: boolean;
  /** True when the nearby query returned a full page at the result limit. */
  resultLimitReached: boolean;
}

/** Classify facility into product source filter using canonical labels from @parkio/geo. */
export function municipalFacilitySourceFilter(
  facility: MunicipalFacility,
): Exclude<MunicipalSourceFilter, 'all'> | 'unknown' {
  const labels = municipalDataSourceLabels(facility);
  const hasIzum = labels.includes(MUNICIPAL_CANONICAL_LABEL_IZUM);
  const hasIspark = labels.includes(MUNICIPAL_CANONICAL_LABEL_ISPARK);
  const hasAnpark = labels.includes(MUNICIPAL_CANONICAL_LABEL_ANPARK);
  const hasKonya = labels.includes(MUNICIPAL_CANONICAL_LABEL_KONYA);
  const hasKayseri = labels.includes(MUNICIPAL_CANONICAL_LABEL_KAYSERI);
  const hasOsm = labels.includes(MUNICIPAL_CANONICAL_LABEL_OSM);
  if (hasIzum && !hasOsm && !hasIspark && !hasAnpark && !hasKonya && !hasKayseri) return 'izum';
  if (hasIspark && !hasOsm && !hasIzum && !hasAnpark && !hasKonya && !hasKayseri) return 'ispark';
  if (hasAnpark && !hasOsm && !hasIzum && !hasIspark && !hasKonya && !hasKayseri) return 'anpark';
  if (hasKonya && !hasOsm && !hasIzum && !hasIspark && !hasAnpark && !hasKayseri) return 'konya';
  if (hasKayseri && !hasOsm && !hasIzum && !hasIspark && !hasAnpark && !hasKonya) return 'kayseri';
  if (hasOsm && !hasIzum && !hasIspark && !hasAnpark && !hasKonya && !hasKayseri) return 'osm';
  if (hasIzum) return 'izum';
  if (hasIspark) return 'ispark';
  if (hasAnpark) return 'anpark';
  if (hasKonya) return 'konya';
  if (hasKayseri) return 'kayseri';
  return 'unknown';
}

/**
 * Match source filter. Dual-source facilities match both exclusive controls
 * so users can find them under either control.
 */
export function matchesMunicipalSourceFilter(
  facility: MunicipalFacility,
  source: MunicipalSourceFilter,
): boolean {
  if (source === 'all') return true;
  const labels = municipalDataSourceLabels(facility);
  if (source === 'izum') return labels.includes(MUNICIPAL_CANONICAL_LABEL_IZUM);
  if (source === 'ispark') return labels.includes(MUNICIPAL_CANONICAL_LABEL_ISPARK);
  if (source === 'anpark') return labels.includes(MUNICIPAL_CANONICAL_LABEL_ANPARK);
  if (source === 'konya') return labels.includes(MUNICIPAL_CANONICAL_LABEL_KONYA);
  if (source === 'kayseri') return labels.includes(MUNICIPAL_CANONICAL_LABEL_KAYSERI);
  if (source === 'osm') return labels.includes(MUNICIPAL_CANONICAL_LABEL_OSM);
  return true;
}

/**
 * Occupancy policy (MOBILE-MUNI-V2-04):
 * - live: live | aging only (not stale_live)
 * - static: static only (not stale_live)
 * - all: includes stale_live and invalid
 */
export function matchesMunicipalOccupancyFilter(
  facility: MunicipalFacility,
  occupancy: MunicipalOccupancyFilter,
): boolean {
  if (occupancy === 'all') return true;
  const kind = municipalOccupancyPresentationKind(facility);
  if (occupancy === 'live') return kind === 'live' || kind === 'aging';
  if (occupancy === 'static') return kind === 'static';
  return true;
}

export function facilityMatchesMunicipalFilters(
  facility: MunicipalFacility,
  filters: Pick<MunicipalMapFilters, 'source' | 'occupancy'>,
): boolean {
  return (
    matchesMunicipalSourceFilter(facility, filters.source) &&
    matchesMunicipalOccupancyFilter(facility, filters.occupancy)
  );
}

function toSummaryCounts(occupancy: MunicipalOccupancySummary): MunicipalFilterSummaryCounts {
  return {
    total: occupancy.total,
    live: occupancy.live + occupancy.aging,
    staticOnly: occupancy.staticOnly,
    staleLive: occupancy.staleLive,
  };
}

/**
 * Pure municipal filtering pipeline.
 * Does not mutate input. Leaves TanStack Query cache untouched (caller passes a snapshot).
 */
export function applyMunicipalMapFilters(
  facilities: readonly MunicipalFacility[],
  filters: MunicipalMapFilters,
  options?: {
    selectedId?: string | null;
    resultLimit?: number | null;
  },
): MunicipalFilterPipelineResult {
  const selectedId = options?.selectedId ?? null;
  const resultLimit = options?.resultLimit ?? null;
  const resultLimitReached =
    resultLimit != null && resultLimit > 0 && facilities.length >= resultLimit;

  if (!filters.layerEnabled) {
    return {
      facilities: [],
      summary: { total: 0, live: 0, staticOnly: 0, staleLive: 0 },
      emptyReason: null,
      selectedFacilityValid: false,
      resultLimitReached: false,
    };
  }

  const filtered = hasActiveMunicipalMapFilters(filters)
    ? facilities.filter((facility) => facilityMatchesMunicipalFilters(facility, filters))
    : [...facilities];

  const summary = toSummaryCounts(summarizeMunicipalOccupancy(filtered));

  let emptyReason: MunicipalFilterEmptyReason | null = null;
  if (filtered.length === 0) {
    emptyReason = facilities.length === 0 ? 'none_nearby' : 'filtered';
  }

  const selectedFacilityValid =
    selectedId != null && filtered.some((facility) => facility.id === selectedId);

  return {
    facilities: filtered,
    summary,
    emptyReason,
    selectedFacilityValid,
    resultLimitReached,
  };
}
