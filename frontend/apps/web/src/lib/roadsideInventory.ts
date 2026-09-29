import type { MunicipalFacility, RoadsideSegment } from '@parkio/types';
import { IZELMAN_ROADSIDE_SOURCE_LABEL } from '@parkio/types';

/** True when a municipal-shaped row is an İZELMAN roadside projection. */
export function isIzelmanRoadsideFacility(
  facility: Pick<MunicipalFacility, 'sourceLabel'>,
): boolean {
  return facility.sourceLabel === IZELMAN_ROADSIDE_SOURCE_LABEL;
}

/**
 * Projects authenticated roadside nearby rows onto the municipal facility shape
 * for map markers / list reuse. Occupancy stays unavailable; access UNKNOWN.
 */
export function toMunicipalFacilityFromRoadside(segment: RoadsideSegment): MunicipalFacility {
  const address =
    segment.address?.trim()
    || [segment.neighborhood, segment.district].filter(Boolean).join(', ')
    || null;
  return {
    id: segment.id,
    displayName: segment.displayName,
    operatorName: 'İZELMAN A.Ş.',
    facilityType: 'ON_STREET',
    addressText: address,
    latitude: segment.latitude,
    longitude: segment.longitude,
    capacityTotal: segment.capacityTotal,
    availableSpaces: null,
    occupiedSpaces: null,
    freshness: 'UNAVAILABLE',
    attribution:
      segment.attribution?.trim()
      || 'İzmir Metropolitan Municipality / İZELMAN A.Ş.',
    sourceLabel: IZELMAN_ROADSIDE_SOURCE_LABEL,
    lastUpdatedAt: segment.updatedAt,
    contributingSourceKeys: ['izelman-roadside-parking'],
    selectedFieldProvenanceSummary: null,
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: 'UNAVAILABLE',
    availabilityObservationTimestamp: segment.updatedAt,
    accessClassification: 'UNKNOWN',
  };
}
