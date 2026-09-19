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
 * communitySpotCountInScope is always null on the public surface (withheld —
 * not a factual zero). Exact community counts under free lat/lng/radius were
 * removed for PA-06 / G06 aggregate-privacy remediation.
 */
export interface PublicExploreDiscovery {
  facilities: PublicExploreFacility[];
  municipalTotalInScope: number;
  municipalHiddenCount: number;
  communitySpotCountInScope: number | null;
}
