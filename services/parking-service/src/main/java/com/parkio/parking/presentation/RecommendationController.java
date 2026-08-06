package com.parkio.parking.presentation;

import com.parkio.parking.application.recommendation.RecommendationApplicationService;
import com.parkio.parking.application.recommendation.RecommendationQuery;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.presentation.dto.RecommendationRequest;
import com.parkio.parking.presentation.dto.RecommendationResponse;
import com.parkio.parking.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Destination-scoped parking recommendations (WP-SPA-05).
 *
 * <p>Authenticated (Option A): personalization (favourite boost) uses X-User-Id
 * for a fail-open batch favourite lookup when ranking is enabled.
 *
 * <p>Gated by {@code parkio.spa.recommendations.enabled} (default false).
 * Ranking is separately gated by {@code parkio.spa.ranking.enabled} (default false).
 */
@RestController
@RequestMapping("/api/v1/parking/recommendations")
@Tag(name = "Parking recommendations")
public class RecommendationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final RecommendationApplicationService recommendations;
    private final boolean recommendationsEnabled;

    public RecommendationController(
            RecommendationApplicationService recommendations,
            @Value("${parkio.spa.recommendations.enabled:false}") boolean recommendationsEnabled) {
        this.recommendations = recommendations;
        this.recommendationsEnabled = recommendationsEnabled;
    }

    @Operation(
            summary = "Recommend parking candidates near a destination",
            description = "Composes municipal facilities and community spots around a Destination. "
                    + "Order is distance-ascending when ranking is disabled; deterministic "
                    + "weighted ranking when parkio.spa.ranking.enabled=true.")
    @SecurityRequirement(name = "bearerAuth")
    @StandardApiResponses
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RecommendationResponse recommend(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody RecommendationRequest request) {
        requireEnabled();
        UUID requester = requireUserId(userId);
        if (request == null || request.destination() == null) {
            throw new ParkingException(ParkingErrorCode.INVALID_DESTINATION, "Destination is required.");
        }
        Destination destination = request.destination().toDomain();
        RecommendationQuery query = new RecommendationQuery(
                requester,
                destination,
                request.resolvedRadiusMeters(),
                request.resolvedLimit(),
                request.resolvedIncludeCommunity(),
                request.resolvedIncludeMunicipal());
        return RecommendationResponse.from(recommendations.recommend(query));
    }

    private void requireEnabled() {
        if (!recommendationsEnabled) {
            throw new ParkingException(ParkingErrorCode.RECOMMENDATIONS_DISABLED);
        }
    }

    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID);
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID);
        }
    }
}
