import type { MunicipalFacility, PublicExploreFacility } from '@parkio/types';

/**
 * Maps the anonymous public-explore DTO onto the canonical municipal facility
 * shape so product map/preview components can be reused without a parallel UI.
 * Does not invent fields the public contract omits.
 */
export function toMunicipalFacilityFromPublicExplore(
  facility: PublicExploreFacility,
): MunicipalFacility {
  return {
    id: facility.id,
    displayName: facility.displayName,
    operatorName: facility.operatorName,
    facilityType: facility.facilityType,
    addressText: facility.addressText,
    latitude: facility.latitude,
    longitude: facility.longitude,
    capacityTotal: facility.capacityTotal,
    availableSpaces: facility.availableSpaces,
    occupiedSpaces: null,
    freshness: facility.availabilityFreshness,
    attribution: facility.attribution,
    sourceLabel: facility.sourceLabel,
    lastUpdatedAt: facility.dataUpdatedAt,
    contributingSourceKeys: null,
    selectedFieldProvenanceSummary: null,
    registryConfidenceOrReviewStatus: null,
    availabilitySource: null,
    availabilityFreshness: facility.availabilityFreshness,
    availabilityObservationTimestamp: facility.dataUpdatedAt,
  };
}
