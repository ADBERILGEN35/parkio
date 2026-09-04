import type { MunicipalFacilityType, MunicipalOccupancyFreshness } from './municipal';

/** Exact anonymous public explore v1 response contract. */
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
