package com.parkio.parking.application.port;

import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Duration;

/**
 * Observability port for the moderation lifecycle. Keeps the application service free of
 * Micrometer types (ai-context/01); the adapter in {@code infrastructure.metrics} exports
 * these to {@code /actuator/prometheus}.
 */
public interface ModerationMetricsPort {

    /**
     * How long a spot waited between submission and the verdict being applied — the
     * end-to-end moderation queue latency the user actually experiences.
     */
    void recordQueueLatency(Duration latency, ParkingSpotStatus outcome);

    /** Wall-clock time spent applying one moderation verdict inside this service. */
    void recordProcessingDuration(Duration duration, String outcome);

    /** A bounded AI publication-gate retry was requested for a spot. */
    void recordRetry(int attempt);

    /** A spot's moderation deadline elapsed (whether or not a retry remained). */
    void recordTimeout(ParkingSpotStatus pendingStatus);

    /** A spot reached the terminal {@code REVIEW_FAILED} state. */
    void recordModerationFailure(String reason);

    /** Human review SLA elapsed without rejecting the submission. */
    void recordReviewSlaBreach(Duration queueLatencyBeforeBreach);

    /**
     * A spot was expired without ever having been published. This must never happen — it
     * is the exact defect this lifecycle exists to prevent — so the counter is an
     * invariant alarm, not a routine measurement.
     */
    void recordExpiredBeforeApproved();
}
