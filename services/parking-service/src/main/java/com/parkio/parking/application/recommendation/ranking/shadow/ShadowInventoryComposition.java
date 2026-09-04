package com.parkio.parking.application.recommendation.ranking.shadow;

/** Channel mix present in a shadow ranking request (no PII). */
public enum ShadowInventoryComposition {
    MUNICIPAL_ONLY,
    COMMUNITY_ONLY,
    MIXED,
    EMPTY
}
