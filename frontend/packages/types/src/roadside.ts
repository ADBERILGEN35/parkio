/** Authenticated roadside nearby inventory (`GET /parking/roadside/nearby`). */
export interface RoadsideSegmentNearbyParams {
  lat: number;
  lng: number;
  radiusMeters?: number;
  limit?: number;
}

/**
 * Roadside segment row — mirrors parking-service `RoadsideParkingController.RoadsideView`.
 * Separate inventory from {@link MunicipalFacility}; map UX may project into facility shape.
 */
export interface RoadsideSegment {
  id: string;
  displayName: string | null;
  district: string | null;
  neighborhood: string | null;
  address: string | null;
  latitude: number;
  longitude: number;
  capacityTotal: number | null;
  availableSpaces: number | null;
  sourceAgeClassification: string | null;
  attribution: string | null;
  legalStatusUnknown: boolean;
  updatedAt: string | null;
}

/** Stable public/authenticated source label for İZELMAN roadside projections. */
export const IZELMAN_ROADSIDE_SOURCE_LABEL = 'İZELMAN roadside';
