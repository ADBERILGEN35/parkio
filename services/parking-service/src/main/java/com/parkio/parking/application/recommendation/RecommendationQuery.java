package com.parkio.parking.application.recommendation;

import com.parkio.parking.domain.place.Destination;
import java.util.Objects;
import java.util.UUID;

/**
 * Validated recommendation query. Defaults and bounds are applied by
 * {@link RecommendationApplicationService}.
 */
public record RecommendationQuery(
        UUID requesterUserId,
        Destination destination,
        int radiusMeters,
        int limit,
        boolean includeCommunity,
        boolean includeMunicipal) {

    public static final int DEFAULT_RADIUS_METERS = 1500;
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_RADIUS_METERS = 5000;
    public static final int MAX_LIMIT = 50;
    public static final int MIN_RADIUS_METERS = 1;
    public static final int MIN_LIMIT = 1;

    public RecommendationQuery {
        Objects.requireNonNull(requesterUserId, "requesterUserId");
        Objects.requireNonNull(destination, "destination");
    }
}
