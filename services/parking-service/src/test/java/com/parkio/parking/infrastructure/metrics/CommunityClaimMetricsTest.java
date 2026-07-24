package com.parkio.parking.infrastructure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.idempotency.IdempotencyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class CommunityClaimMetricsTest {

    @Test
    void recordsOnlyBoundedOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommunityClaimMetrics metrics = new CommunityClaimMetrics(registry);
        UUID spotId = UUID.randomUUID();

        metrics.recordSuccess(spotId, false);
        metrics.recordSuccess(spotId, true);
        metrics.recordFailure(spotId, new ParkingException(
                ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS));
        metrics.recordFailure(spotId, new IdempotencyException(
                "IDEMPOTENCY_REQUEST_IN_PROGRESS", "Request is in progress."));
        metrics.recordFailure(spotId, new ObjectOptimisticLockingFailureException(
                "ParkingSpot", spotId));

        assertThat(registry.find(CommunityClaimMetrics.METRIC_NAME).counters())
                .hasSize(14);
        assertThat(counter(registry, "committed")).isEqualTo(1.0);
        assertThat(counter(registry, "replayed")).isEqualTo(1.0);
        assertThat(counter(registry, "active_session_conflict")).isEqualTo(1.0);
        assertThat(counter(registry, "idempotency_in_progress")).isEqualTo(1.0);
        assertThat(counter(registry, "optimistic_conflict")).isEqualTo(1.0);
    }

    private static double counter(SimpleMeterRegistry registry, String outcome) {
        return registry.get(CommunityClaimMetrics.METRIC_NAME)
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
