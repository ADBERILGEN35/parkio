package com.parkio.parking.presentation.dto;

import java.util.List;

/**
 * Anonymous public discovery envelope. Hidden municipal facilities are never
 * included as rows — only totals/aggregates. {@code communitySpotCountInScope}
 * is always {@code null} on this surface (withheld, not a factual zero): publishing
 * exact community counts under free lat/lng/radius enabled PA-06 differencing.
 */
public record PublicExploreDiscoveryResponse(
        List<PublicExploreFacilityResponse> facilities,
        long municipalTotalInScope,
        long municipalHiddenCount,
        Integer communitySpotCountInScope) {}
