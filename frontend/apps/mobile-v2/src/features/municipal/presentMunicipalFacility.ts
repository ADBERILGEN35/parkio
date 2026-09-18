import {
  formatMunicipalDataSourcesLine,
  municipalAvailabilityCopyKey,
  municipalDataSourceLabels,
  municipalFreshnessCopyKey,
  municipalOccupancyPresentationKind,
  type MunicipalOccupancyPresentationKind,
} from '@parkio/geo';
import type { MunicipalFacility, MunicipalFacilityType } from '@parkio/types';

/**
 * Semantic status role for future Badge / Chip coloring.
 * Not a color token — map to theme in a later UI package.
 */
export type MunicipalStatusRole = 'live' | 'warning' | 'inactive' | 'error';

/** Icon intent for MaterialCommunityIcons — concrete glyph chosen in UI packages. */
export type MunicipalIconIntent = 'live' | 'stale' | 'static' | 'invalid';

/**
 * Thin mobile view-model over shared `@parkio/geo` municipal helpers.
 * No React Native components. Never exposes raw source keys, coordinates,
 * provenance maps, or ETL identifiers.
 */
export interface MunicipalFacilityPresentation {
  sourceLabels: string[];
  sourceLine: string | null;
  occupancyKind: MunicipalOccupancyPresentationKind;
  statusRole: MunicipalStatusRole;
  iconIntent: MunicipalIconIntent;
  /** Shared geo i18n key under `map.municipal` (web namespace) — wire in a later package. */
  availabilityCopyKey: string;
  freshnessCopyKey: string;
  displayName: string | null;
  operatorName: string | null;
  facilityType: MunicipalFacilityType;
  addressText: string | null;
  availableSpaces: number | null;
  occupiedSpaces: number | null;
  capacityTotal: number | null;
  lastUpdatedAt: string | null;
}

function statusRoleFor(
  kind: MunicipalOccupancyPresentationKind,
): MunicipalStatusRole {
  switch (kind) {
    case 'live':
    case 'aging':
      return 'live';
    case 'stale_live':
      return 'warning';
    case 'invalid':
      return 'error';
    case 'static':
    default:
      return 'inactive';
  }
}

function iconIntentFor(
  kind: MunicipalOccupancyPresentationKind,
): MunicipalIconIntent {
  switch (kind) {
    case 'live':
    case 'aging':
      return 'live';
    case 'stale_live':
      return 'stale';
    case 'invalid':
      return 'invalid';
    case 'static':
    default:
      return 'static';
  }
}

/**
 * Present a municipal facility for future mobile UI without leaking raw metadata.
 * Occupancy metrics are preserved (including nulls) for detail screens.
 */
export function presentMunicipalFacility(
  facility: MunicipalFacility,
): MunicipalFacilityPresentation {
  const occupancyKind = municipalOccupancyPresentationKind(facility);
  return {
    sourceLabels: municipalDataSourceLabels(facility),
    sourceLine: formatMunicipalDataSourcesLine(facility),
    occupancyKind,
    statusRole: statusRoleFor(occupancyKind),
    iconIntent: iconIntentFor(occupancyKind),
    availabilityCopyKey: municipalAvailabilityCopyKey(occupancyKind),
    freshnessCopyKey: municipalFreshnessCopyKey(occupancyKind),
    displayName: facility.displayName,
    operatorName: facility.operatorName,
    facilityType: facility.facilityType,
    addressText: facility.addressText,
    availableSpaces: facility.availableSpaces,
    occupiedSpaces: facility.occupiedSpaces,
    capacityTotal: facility.capacityTotal,
    lastUpdatedAt: facility.lastUpdatedAt,
  };
}
