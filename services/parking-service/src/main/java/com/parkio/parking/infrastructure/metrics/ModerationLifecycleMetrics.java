package com.parkio.parking.infrastructure.metrics;

import com.parkio.parking.application.port.ModerationMetricsPort;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.infrastructure.persistence.jpa.ParkingSpotJpaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Micrometer adapter for {@link ModerationMetricsPort} plus the moderation-backlog gauges,
 * exported at {@code /actuator/prometheus}.
 *
 * <p>The alerting-relevant series:
 *
 * <ul>
 *   <li>{@code parkio.parking.moderation.queue.latency} — submission to verdict.</li>
 *   <li>{@code parkio.parking.moderation.processing.duration} — verdict handling cost.</li>
 *   <li>{@code parkio.parking.moderation.retry.count} / {@code ...timeout.count} /
 *       {@code ...failed.count} — pipeline health.</li>
 *   <li>{@code parkio.parking.moderation.pending.count} and
 *       {@code parkio.parking.moderation.pending.oldest.seconds} — backlog depth and the
 *       worst time-spent-in-review, the leading indicator of a stalled pipeline.</li>
 *   <li>{@code parkio.parking.expired_before_approved.count} — <strong>must stay zero</strong>.
 *       Exported both as a live counter (something just went wrong) and as a gauge over
 *       stored rows (something went wrong at some point), so a regression is visible even
 *       if it happened before the current process started.</li>
 * </ul>
 */
@Component
public class ModerationLifecycleMetrics implements ModerationMetricsPort {

    private static final Set<ParkingSpotStatus> PENDING = EnumSet.of(
            ParkingSpotStatus.PENDING_VALIDATION, ParkingSpotStatus.PENDING_REVIEW);

    private final MeterRegistry registry;
    private final ParkingSpotJpaRepository spots;
    private final Clock clock;
    private final Counter expiredBeforeApprovedCounter;

    public ModerationLifecycleMetrics(MeterRegistry registry, ParkingSpotJpaRepository spots, Clock clock) {
        this.registry = registry;
        this.spots = spots;
        this.clock = clock;
        this.expiredBeforeApprovedCounter = Counter.builder("parkio.parking.expired_before_approved.count")
                .description("Spots expired without ever being published — invariant violation, must stay 0")
                .register(registry);

        Gauge.builder("parkio.parking.moderation.pending.count", this,
                        m -> m.spots.countByStatus(ParkingSpotStatus.PENDING_VALIDATION)
                                + m.spots.countByStatus(ParkingSpotStatus.PENDING_REVIEW))
                .description("Spots currently awaiting a moderation verdict")
                .register(registry);
        Gauge.builder("parkio.parking.moderation.pending_review.count", this,
                        m -> m.spots.countByStatus(ParkingSpotStatus.PENDING_REVIEW))
                .description("Spots currently awaiting a human moderation decision")
                .register(registry);
        Gauge.builder("parkio.parking.moderation.pending.oldest.seconds", this,
                        ModerationLifecycleMetrics::oldestPendingAgeSeconds)
                .description("Age of the longest-waiting spot still in moderation")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("parkio.parking.moderation.review_failed.count", this,
                        m -> m.spots.countByStatus(ParkingSpotStatus.REVIEW_FAILED))
                .description("Spots in the terminal REVIEW_FAILED state")
                .register(registry);
        Gauge.builder("parkio.parking.expired_before_approved.total", this,
                        m -> m.spots.countByStatusAndActivatedAtIsNull(ParkingSpotStatus.EXPIRED))
                .description("Stored spots expired without ever being published — must stay 0")
                .register(registry);
    }

    @Override
    public void recordQueueLatency(Duration latency, ParkingSpotStatus outcome) {
        Timer.builder("parkio.parking.moderation.queue.latency")
                .description("Time from spot submission to the moderation verdict being applied")
                .tag("outcome", outcome.name())
                .register(registry)
                .record(latency.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordProcessingDuration(Duration duration, String outcome) {
        Timer.builder("parkio.parking.moderation.processing.duration")
                .description("Time spent applying one moderation verdict")
                .tag("outcome", outcome)
                .register(registry)
                .record(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordRetry(int attempt) {
        Counter.builder("parkio.parking.moderation.retry.count")
                .description("Bounded AI publication-gate retries requested for overdue spots")
                .tag("attempt", Integer.toString(attempt))
                .register(registry)
                .increment();
    }

    @Override
    public void recordTimeout(ParkingSpotStatus pendingStatus) {
        Counter.builder("parkio.parking.moderation.timeout.count")
                .description("Moderation deadlines elapsed while a spot was still pending")
                .tag("status", pendingStatus.name())
                .register(registry)
                .increment();
    }

    @Override
    public void recordModerationFailure(String reason) {
        Counter.builder("parkio.parking.moderation.failed.count")
                .description("Spots moved to the terminal REVIEW_FAILED state")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    @Override
    public void recordReviewSlaBreach(Duration queueLatencyBeforeBreach) {
        Counter.builder("parkio.parking.moderation.review_sla_breach.count")
                .description("Human review SLA elapsed without rejecting the submission")
                .register(registry)
                .increment();
        Timer.builder("parkio.parking.moderation.review_sla_breach.queue_latency")
                .description("Queue latency when the human review SLA first breached")
                .register(registry)
                .record(queueLatencyBeforeBreach.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordExpiredBeforeApproved() {
        expiredBeforeApprovedCounter.increment();
    }

    private double oldestPendingAgeSeconds() {
        Instant oldest = spots.findOldestPendingCreatedAt(PENDING);
        return oldest == null ? 0.0 : Math.max(0.0, Duration.between(oldest, clock.instant()).toSeconds());
    }
}
