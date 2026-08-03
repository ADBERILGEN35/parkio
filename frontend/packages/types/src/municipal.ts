/**
 * Municipal parking facility contracts — mirrors parking-service
 * `MunicipalFacilityResponse` (read-only discovery; WEB-MUNI-01).
 *
 * Separate inventory from community {@link PublicSpot}. Do not fuse types.
 */

/** Facility physical class — mirrors `MunicipalFacilityType`. */
export const MUNICIPAL_FACILITY_TYPES = ['ON_STREET', 'OFF_STREET', 'UNKNOWN'] as const;

export type MunicipalFacilityType = (typeof MUNICIPAL_FACILITY_TYPES)[number];

/**
 * Occupancy / availability freshness — mirrors `MunicipalOccupancyFreshness`.
 * OSM inventory typically publishes UNAVAILABLE for live occupancy.
 */
export const MUNICIPAL_OCCUPANCY_FRESHNESS = [
  'LIVE',
  'AGING',
  'STALE',
  'UNAVAILABLE',
  'INVALID',
] as const;

export type MunicipalOccupancyFreshness = (typeof MUNICIPAL_OCCUPANCY_FRESHNESS)[number];

/** `GET /parking/facilities/nearby` query params (same shape as spots nearby). */
export interface MunicipalFacilityNearbyParams {
  lat: number;
  lng: number;
  radius?: number;
  limit?: number;
}

/**
 * Public municipal facility view — mirrors `MunicipalFacilityResponse`.
 * Field names and nullability match the backend JSON contract; do not reshape.
 */
export interface MunicipalFacility {
  id: string;
  displayName: string | null;
  operatorName: string | null;
  facilityType: MunicipalFacilityType;
  addressText: string | null;
  latitude: number;
  longitude: number;
  capacityTotal: number | null;
  availableSpaces: number | null;
  freshness: MunicipalOccupancyFreshness | null;
  attribution: string | null;
  sourceLabel: string | null;
  lastUpdatedAt: string | null;
  contributingSourceKeys: string[] | null;
  selectedFieldProvenanceSummary: Record<string, string> | null;
  /** Always null on the public contract (DATA-WP-09); retained for JSON compatibility. */
  registryConfidenceOrReviewStatus: string | null;
  availabilitySource: string | null;
  availabilityFreshness: MunicipalOccupancyFreshness | null;
  availabilityObservationTimestamp: string | null;
}

export type MunicipalFacilityResponse = MunicipalFacility;
