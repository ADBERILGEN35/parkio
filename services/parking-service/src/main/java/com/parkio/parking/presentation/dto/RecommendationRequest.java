package com.parkio.parking.presentation.dto;

import com.parkio.parking.application.recommendation.RecommendationQuery;

/**
 * POST /api/v1/parking/recommendations body. Defaults applied in the controller
 * when fields are absent.
 */
public record RecommendationRequest(
        RecommendationDestinationRequest destination,
        Integer radiusMeters,
        Integer limit,
        Boolean includeCommunity,
        Boolean includeMunicipal) {

    public int resolvedRadiusMeters() {
        return radiusMeters != null ? radiusMeters : RecommendationQuery.DEFAULT_RADIUS_METERS;
    }

    public int resolvedLimit() {
        return limit != null ? limit : RecommendationQuery.DEFAULT_LIMIT;
    }

    public boolean resolvedIncludeCommunity() {
        return includeCommunity == null || includeCommunity;
    }

    public boolean resolvedIncludeMunicipal() {
        return includeMunicipal == null || includeMunicipal;
    }
}
