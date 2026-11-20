import type { MunicipalFacility, MunicipalOccupancyFreshness } from '@parkio/types';

/** User-facing occupancy presentation class — never expose internal freshness enums raw. */
export type MunicipalOccupancyPresentationKind =
  | 'live'
  | 'aging'
  | 'stale_live'
  | 'static'
  | 'invalid';

export interface MunicipalOccupancySummary {
  total: number;
  live: number;
  aging: number;
  staleLive: number;
  staticOnly: number;
  invalid: number;
}

function effectiveFreshness(facility: MunicipalFacility): MunicipalOccupancyFreshness | null {
  return facility.availabilityFreshness ?? facility.freshness;
}

/**
 * Classify how a municipal facility's occupancy should be presented.
 * Freshness takes precedence over retained numeric values, for every provider.
 */
export function municipalOccupancyPresentationKind(
  facility: MunicipalFacility,
): MunicipalOccupancyPresentationKind {
  const freshness = effectiveFreshness(facility);
  if (freshness === 'STALE') {
    return 'stale_live';
  }
  if (freshness === 'INVALID') {
    return 'invalid';
  }
  if (facility.availableSpaces != null) {
    if (freshness === 'LIVE') return 'live';
    if (freshness === 'AGING') return 'aging';
  }
  return 'static';
}

/** i18n key under `map.municipal` for availability body copy. */
export function municipalAvailabilityCopyKey(
  kind: MunicipalOccupancyPresentationKind,
): string {
  switch (kind) {
    case 'live':
    case 'aging':
      return 'spacesAvailable';
    case 'stale_live':
      return 'availabilityStaleLive';
    case 'invalid':
      return 'availabilityInvalid';
    case 'static':
    default:
      return 'availabilityStatic';
  }
}

/** i18n key under `map.municipal.freshness` for badge/status copy. */
export function municipalFreshnessCopyKey(
  kind: MunicipalOccupancyPresentationKind,
): string {
  switch (kind) {
    case 'live':
      return 'live';
    case 'aging':
      return 'aging';
    case 'stale_live':
      return 'staleLive';
    case 'invalid':
      return 'invalid';
    case 'static':
    default:
      return 'static';
  }
}

export function summarizeMunicipalOccupancy(
  facilities: readonly MunicipalFacility[],
): MunicipalOccupancySummary {
  const summary: MunicipalOccupancySummary = {
    total: facilities.length,
    live: 0,
    aging: 0,
    staleLive: 0,
    staticOnly: 0,
    invalid: 0,
  };
  for (const facility of facilities) {
    switch (municipalOccupancyPresentationKind(facility)) {
      case 'live':
        summary.live += 1;
        break;
      case 'aging':
        summary.aging += 1;
        break;
      case 'stale_live':
        summary.staleLive += 1;
        break;
      case 'invalid':
        summary.invalid += 1;
        break;
      case 'static':
      default:
        summary.staticOnly += 1;
        break;
    }
  }
  return summary;
}
