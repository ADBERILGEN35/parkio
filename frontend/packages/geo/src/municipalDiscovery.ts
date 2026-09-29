import type { MunicipalFacility, MunicipalFacilityType } from '@parkio/types';
import { MUNICIPAL_FACILITY_TYPES } from '@parkio/types';

/**
 * Client-side municipal discovery filters (WEB-MUNI-03).
 *
 * `GET /parking/facilities/nearby` accepts only lat/lng/radius/limit — these
 * filters narrow the already-fetched result set on the client. Do not invent
 * categories, source groups, or availability states beyond published fields.
 */

/** Availability buckets derived only from published `availableSpaces`. */
export type MunicipalAvailabilityFilter = 'all' | 'available' | 'unavailable' | 'unknown';

export const MUNICIPAL_AVAILABILITY_FILTERS: MunicipalAvailabilityFilter[] = [
  'all',
  'available',
  'unavailable',
  'unknown',
];

/**
 * Presentation filters for municipal facilities.
 * Empty `sourceLabels` / `facilityTypes` means "all".
 */
export interface MunicipalFacilityFilters {
  availability: MunicipalAvailabilityFilter;
  /** Exact `sourceLabel` values present on facilities; never fabricated groups. */
  sourceLabels: string[];
  facilityTypes: MunicipalFacilityType[];
  /**
   * When true, keep only facilities with a non-empty
   * `selectedFieldProvenanceSummary` (field already exposed on the DTO).
   */
  provenanceOnly: boolean;
}

export const EMPTY_MUNICIPAL_FILTERS: MunicipalFacilityFilters = {
  availability: 'all',
  sourceLabels: [],
  facilityTypes: [],
  provenanceOnly: false,
};

/** True when any municipal presentation filter is narrowing the list. */
export function hasActiveMunicipalFilters(filters: MunicipalFacilityFilters): boolean {
  return (
    filters.availability !== 'all' ||
    filters.sourceLabels.length > 0 ||
    filters.facilityTypes.length > 0 ||
    filters.provenanceOnly
  );
}

/**
 * Map a facility to an availability bucket using only `availableSpaces`.
 * - spaces &gt; 0 → available
 * - spaces === 0 → unavailable
 * - spaces null → unknown (live availability not published)
 *
 * Does not infer from freshness, capacity, or source.
 */
export function municipalAvailabilityBucket(
  facility: MunicipalFacility,
): Exclude<MunicipalAvailabilityFilter, 'all'> {
  if (facility.availableSpaces == null) return 'unknown';
  return facility.availableSpaces > 0 ? 'available' : 'unavailable';
}

export function hasMunicipalProvenance(facility: MunicipalFacility): boolean {
  const summary = facility.selectedFieldProvenanceSummary;
  return summary != null && Object.keys(summary).length > 0;
}

/** Distinct non-empty `sourceLabel` values in the result set (sorted). */
export function availableMunicipalSourceLabels(facilities: MunicipalFacility[]): string[] {
  const present = new Set<string>();
  for (const facility of facilities) {
    const label = facility.sourceLabel?.trim();
    if (label) present.add(label);
  }
  return [...present].sort((a, b) => a.localeCompare(b));
}

/** Facility types present in the result set, in canonical type order. */
export function availableMunicipalFacilityTypes(
  facilities: MunicipalFacility[],
): MunicipalFacilityType[] {
  const present = new Set(facilities.map((facility) => facility.facilityType));
  return MUNICIPAL_FACILITY_TYPES.filter((type) => present.has(type));
}

/** Apply presentation filters; returns a new array. */
export function filterMunicipalFacilities(
  facilities: MunicipalFacility[],
  filters: MunicipalFacilityFilters,
): MunicipalFacility[] {
  if (!hasActiveMunicipalFilters(filters)) return facilities;
  return facilities.filter((facility) => {
    if (
      filters.availability !== 'all' &&
      municipalAvailabilityBucket(facility) !== filters.availability
    ) {
      return false;
    }
    if (
      filters.sourceLabels.length > 0 &&
      (facility.sourceLabel == null || !filters.sourceLabels.includes(facility.sourceLabel))
    ) {
      return false;
    }
    if (
      filters.facilityTypes.length > 0 &&
      !filters.facilityTypes.includes(facility.facilityType)
    ) {
      return false;
    }
    if (filters.provenanceOnly && !hasMunicipalProvenance(facility)) {
      return false;
    }
    return true;
  });
}
