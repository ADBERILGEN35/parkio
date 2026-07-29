package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.exposure.ExposureShadowFailureStage;
import com.parkio.parking.application.exposure.ExposureShadowProcessingResult;
import com.parkio.parking.application.port.ExposureShadowObserverPort;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.exposure.ExposureEligibility;
import com.parkio.parking.exposure.ExposureEvaluation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for exposure shadow evaluate/replay.
 *
 * <p>Success-path uses {@link Long#MAX_VALUE} as the time budget so the assertion is
 * functional correctness (candidates evaluated + replay identical), not a wall-clock
 * microbenchmark. Production request-path budget remains {@code ParkingProperties}
 * default {@code timeBudgetMillis=25}. Exceeded-budget behavior is covered separately
 * with budget {@code 0}.
 */
class ExposureShadowApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

    /** Disables wall-clock trip for functional success-path coverage. */
    private static final long BUDGET_DISABLED_FOR_FUNCTIONAL_TEST = Long.MAX_VALUE;

    @Test
    void evaluatesBoundedNearbyCandidatesAndReplays() {
        RecordingObserver observer = new RecordingObserver();
        ExposureShadowApplicationService service = new ExposureShadowApplicationService(
                observer, Clock.fixed(NOW, ZoneOffset.UTC));
        ParkingSpot near = spot(UUID.randomUUID(), 41.0, 29.0);
        ParkingSpot far = spot(UUID.randomUUID(), 41.01, 29.01);

        ExposureShadowProcessingResult result = service.evaluateNearbySearch(
                List.of(near, far), 41.0, 29.0, 1500, 10, true, BUDGET_DISABLED_FOR_FUNCTIONAL_TEST);

        assertThat(result.status())
                .as("failureStage=%s", result.failureStage())
                .isEqualTo(ExposureShadowProcessingResult.Status.SUCCESS);
        assertThat(observer.candidateCount).isEqualTo(2);
        assertThat(observer.evaluations).allMatch(e -> e.eligibility() == ExposureEligibility.ELIGIBLE);
        assertThat(observer.replaySuccess).isTrue();
        assertThat(observer.comparison).isNotNull();
    }

    @Test
    void timeBudgetExceededReturnsFailedWithoutThrowing() {
        RecordingObserver observer = new RecordingObserver();
        ExposureShadowApplicationService service = new ExposureShadowApplicationService(
                observer, Clock.fixed(NOW, ZoneOffset.UTC));
        ParkingSpot spot = spot(UUID.randomUUID(), 41.0, 29.0);

        ExposureShadowProcessingResult result = service.evaluateNearbySearch(
                List.of(spot), 41.0, 29.0, 1500, 10, true, 0);

        assertThat(result.status()).isEqualTo(ExposureShadowProcessingResult.Status.FAILED);
        assertThat(result.failureStage()).contains(ExposureShadowFailureStage.TIME_BUDGET_EXCEEDED);
    }

    private static ParkingSpot spot(UUID id, double lat, double lng) {
        return new ParkingSpot(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                lat,
                lng,
                "Street",
                "Desc",
                false,
                Set.of(VehicleType.SEDAN),
                ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL,
                Set.of(),
                ParkingSpotStatus.ACTIVE,
                1.0,
                0,
                0,
                NOW.plusSeconds(600),
                NOW.minusSeconds(120),
                NOW.minusSeconds(60),
                1L,
                NOW.minusSeconds(120),
                null,
                0,
                null,
                null,
                null);
    }

    private static final class RecordingObserver implements ExposureShadowObserverPort {
        private int candidateCount;
        private boolean replaySuccess;
        private com.parkio.parking.exposure.ExposureComparison comparison;
        private final List<ExposureEvaluation> evaluations = new ArrayList<>();

        @Override
        public void recordCandidateEvaluated(ExposureEvaluation evaluation) {
            candidateCount++;
            evaluations.add(evaluation);
        }

        @Override
        public void recordEvaluationSuccess(
                com.parkio.parking.exposure.ExposureComparison comparison,
                java.time.Duration duration) {
            this.comparison = comparison;
        }

        @Override
        public void recordReplaySuccess(com.parkio.parking.exposure.ExposureReplayComparison replay) {
            replaySuccess = true;
        }
    }
}