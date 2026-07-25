package com.parkio.parking.infrastructure.lifecycle;

import com.parkio.parking.application.ParkingSessionService;
import com.parkio.parking.application.ParkingSessionStaleBatchResult;
import com.parkio.parking.domain.ParkingSessionReminderStage;
import com.parkio.parking.infrastructure.config.ParkingProperties;
import com.parkio.parking.infrastructure.metrics.ParkingSessionLifecycleMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Paginated stale-session job: reminders (24h/48h), auto-complete (72h), optional retention.
 * Candidate mutations run per-row (REQUIRES_NEW). Drain stops when a page makes no progress
 * to avoid spinning on contested rows. Concurrent nodes remain idempotent via optimistic
 * locking and persisted reminder stages.
 *
 * <p>Schedule delay: {@code parkio.lifecycle.parking-session-stale.fixed-delay-ms}
 * (keep aligned with {@code parkio.parking.session.scheduler-rate}, default PT1H = 3600000).
 */
@Component
@ConditionalOnProperty(
        name = "parkio.lifecycle.parking-session-stale.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ParkingSessionStaleCompletionJob {

    private static final Logger log = LoggerFactory.getLogger(ParkingSessionStaleCompletionJob.class);
    private static final int MAX_PAGES_PER_TICK = 10_000;

    private final ParkingSessionService sessions;
    private final ParkingSessionLifecycleMetrics metrics;
    private final int batchSize;

    public ParkingSessionStaleCompletionJob(
            ParkingSessionService sessions,
            ParkingSessionLifecycleMetrics metrics,
            ParkingProperties parkingProperties,
            @Value("${parkio.lifecycle.parking-session-stale.batch-size:0}") int legacyBatchSize) {
        this.sessions = sessions;
        this.metrics = metrics;
        int configured = parkingProperties.getSession().getSchedulerBatchSize();
        this.batchSize = legacyBatchSize > 0 ? legacyBatchSize : Math.max(1, configured);
    }

    @Scheduled(fixedDelayString = "${parkio.lifecycle.parking-session-stale.fixed-delay-ms:3600000}")
    public void processStaleSessions() {
        Timer.Sample sample = metrics.startSchedulerTimer();
        try {
            drainReminders(ParkingSessionReminderStage.FIRST);
            drainReminders(ParkingSessionReminderStage.SECOND);
            drainAutoComplete();
            drainRetention();
            sessions.refreshActiveSessionGauge();
        } catch (RuntimeException exception) {
            metrics.recordSchedulerFailed();
            log.error("Stale parking-session scheduler tick failed", exception);
            throw exception;
        } finally {
            metrics.stopSchedulerTimer(sample);
        }
    }

    private void drainReminders(ParkingSessionReminderStage stage) {
        for (int page = 0; page < MAX_PAGES_PER_TICK; page++) {
            ParkingSessionStaleBatchResult result = sessions.sendDueRemindersPage(stage, batchSize);
            if (result.exhausted()) {
                return;
            }
        }
        log.warn("Reminder drain hit page cap stage={} batchSize={}", stage, batchSize);
    }

    private void drainAutoComplete() {
        for (int page = 0; page < MAX_PAGES_PER_TICK; page++) {
            ParkingSessionStaleBatchResult result = sessions.autoCompleteStaleSessionsPage(batchSize);
            if (result.exhausted()) {
                return;
            }
        }
        log.warn("Auto-complete drain hit page cap batchSize={}", batchSize);
    }

    private void drainRetention() {
        for (int page = 0; page < MAX_PAGES_PER_TICK; page++) {
            ParkingSessionStaleBatchResult result = sessions.purgeExpiredHistoryPage(batchSize);
            if (result.exhausted()) {
                return;
            }
        }
        log.warn("Retention drain hit page cap batchSize={}", batchSize);
    }
}