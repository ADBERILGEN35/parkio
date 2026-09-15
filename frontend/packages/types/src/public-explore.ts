import type { MunicipalFacilityType, MunicipalOccupancyFreshness } from './municipal';

/** Exact anonymous public explore facility row (unchanged 13-field allowlist). */
export interface PublicExploreFacility {
  id: string;
  displayName: string | null;
  operatorName: string | null;
  facilityType: MunicipalFacilityType;
  addressText: string | null;
  latitude: number;
  longitude: number;
  capacityTotal: number | null;
  availableSpaces: number | null;
  availabilityFreshness: MunicipalOccupancyFreshness;
  dataUpdatedAt: string | null;
  sourceLabel: string;
  attribution: string;
}

/**
 * Anonymous discovery envelope. Hidden municipal rows are never included.
 * communitySpotCountInScope is null when below the privacy threshold (< 3).
 */
export interface PublicExploreDiscovery {
  facilities: PublicExploreFacility[];
  municipalTotalInScope: number;
  municipalHiddenCount: number;
  communitySpotCountInScope: number | null;
}
