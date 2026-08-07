package com.parkio.parking.presentation;

import com.parkio.parking.application.recommendation.RecommendationApplicationService;
import com.parkio.parking.application.recommendation.RecommendationQuery;
import com.parkio.parking.application.recommendation.ranking.evaluation.RankingEvaluationService;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.presentation.dto.RankingEvaluationOutcomeRequest;
import com.parkio.parking.presentation.dto.RankingEvaluationOutcomeResponse;
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
 *
 * <p>WP-SPA-14B adds an optional opaque {@code evaluationId} on recommend responses
 * and a narrowly scoped authenticated outcomes endpoint. Auth protects the endpoint;
 * evaluation rows never store user identity.
 */
@RestController
@RequestMapping("/api/v1/parking/recommendations")
@Tag(name = "Parking recommendations")
public class RecommendationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final RecommendationApplicationService recommendations;
    private final RankingEvaluationService rankingEvaluationService;
    private final boolean recommendationsEnabled;

    public RecommendationController(
            RecommendationApplicationService recommendations,
            RankingEvaluationService rankingEvaluationService,
            @Value("${parkio.spa.recommendations.enabled:false}") boolean recommendationsEnabled) {
        this.recommendations = recommendations;
        this.rankingEvaluationService = rankingEvaluationService;
        this.recommendationsEnabled = recommendationsEnabled;
    }

    @Operation(
            summary = "Recommend parking candidates near a destination",
            description = "Composes municipal facilities and community spots around a Destination. "
                    + "Order is distance-ascending when ranking is disabled; deterministic "
                    + "weighted ranking when parkio.spa.ranking.enabled=true. "
                    + "When ranking evaluation is enabled, response may include an opaque "
                    + "evaluationId for privacy-safe outcome correlation only.")
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

    @Operation(
            summary = "Record a privacy-safe ranking evaluation outcome",
            description = "Correlates an explicit user action (selection / navigation / parking) "
                    + "to an opaque evaluationId + candidate ordinal. Does not accept or store "
                    + "facility/spot/session/user identifiers. Auth is required; identity is not persisted.")
    @SecurityRequirement(name = "bearerAuth")
    @StandardApiResponses
    @PostMapping(
            path = "/evaluation-outcomes",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RankingEvaluationOutcomeResponse recordEvaluationOutcome(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestBody RankingEvaluationOutcomeRequest request) {
        requireEnabled();
        requireUserId(userId); // authenticate only — never copied into evaluation rows
        if (request == null || request.evaluationId() == null) {
            throw new ParkingException(ParkingErrorCode.INVALID_RANKING_EVALUATION_OUTCOME);
        }
        if (request.outcomeType() == null || request.outcomeType().isBlank()) {
            throw new ParkingException(ParkingErrorCode.INVALID_RANKING_EVALUATION_OUTCOME);
        }
        RankingEvaluationService.OutcomeWriteResult result;
        try {
            result = rankingEvaluationService.recordOutcome(
                    request.evaluationId(),
                    request.candidateOrdinal(),
                    request.parsedOutcomeType(),
                    request.parsedPlatform(),
                    request.latencyBucket());
        } catch (IllegalArgumentException ex) {
            throw new ParkingException(ParkingErrorCode.INVALID_RANKING_EVALUATION_OUTCOME, ex.getMessage());
        }
        return switch (result) {
            case RECORDED -> RankingEvaluationOutcomeResponse.recorded();
            case DUPLICATE -> RankingEvaluationOutcomeResponse.duplicate();
            case DISABLED -> RankingEvaluationOutcomeResponse.disabled();
            case PERSISTENCE_FAILED -> RankingEvaluationOutcomeResponse.persistenceFailed();
        };
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
