package com.parkio.parking.presentation.dto;

import java.util.List;

/**
 * Anonymous public discovery envelope. Hidden municipal facilities are never
 * included as rows — only totals/aggregates.
 */
public record PublicExploreDiscoveryResponse(
        List<PublicExploreFacilityResponse> facilities,
        long municipalTotalInScope,
        long municipalHiddenCount,
        Integer communitySpotCountInScope) {}
