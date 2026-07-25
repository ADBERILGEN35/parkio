package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.ParkingApplicationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resolves spots the moderation pipeline never answered for, in bounded,
 * lock-safe batches.
 *
 * <p>This is the safety net that makes "stuck in review forever" impossible: a spot past
 * its AI-gate deadline is re-requested through the outbox up to a bounded number of
 * attempts, and a spot past its human-review deadline (or out of attempts) is moved to the
 * terminal {@code REVIEW_FAILED} state where the owner can see what happened and resubmit.
 *
 * <p>Mirrors {@link ParkingExpiryJob}'s shape; the two are complementary and never overlap
 * — this one only ever touches pending spots, that one never does.
 */
@Component
@ConditionalOnProperty(
        name = "parkio.lifecycle.moderation-timeout.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ModerationTimeoutJob {

    private final ParkingApplicationService parking;
    private final int batchSize;
    private final Counter handledCounter;

    public ModerationTimeoutJob(
            ParkingApplicationService parking,
            MeterRegistry meterRegistry,
            @Value("${parkio.lifecycle.moderation-timeout.batch-size:100}") int batchSize) {
        this.parking = parking;
        this.batchSize = batchSize;
        this.handledCounter = Counter.builder("parkio.parking.moderation.timeout.job.handled.count")
                .description("Overdue spots retried or terminally failed by the moderation timeout job")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.moderation-timeout.fixed-delay-ms:60000}")
    public void resolveOverdueModeration() {
        int handled = parking.processModerationTimeouts(batchSize);
        if (handled > 0) {
            handledCounter.increment(handled);
        }
    }
}
