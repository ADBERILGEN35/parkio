package com.parkio.notification.infrastructure.smartreturn;

import com.parkio.notification.application.NotificationApplicationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Smart Return V1 scheduler. It stores no home location locally: due work is claimed
 * from user-service, parking availability is read from parking-service, and normal
 * notifications are created only when real nearby spots exist.
 */
@Component
public class SmartReturnScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmartReturnScheduler.class);

    private final SmartReturnUserClient userClient;
    private final SmartReturnParkingClient parkingClient;
    private final NotificationApplicationService notifications;
    private final Clock clock;
    private final boolean enabled;
    private final ZoneId promptZone;
    private final int batchSize;
    private final Counter promptsSent;
    private final Counter checksStarted;
    private final Counter notificationsSent;
    private final Counter noSpots;
    private final Counter claimsRetried;
    private final Counter failures;

    public SmartReturnScheduler(
            SmartReturnUserClient userClient,
            SmartReturnParkingClient parkingClient,
            NotificationApplicationService notifications,
            Clock clock,
            MeterRegistry registry,
            @Value("${parkio.smart-return.enabled:false}") boolean featureEnabled,
            @Value("${parkio.smart-return.scheduler.enabled:false}") boolean schedulerEnabled,
            @Value("${parkio.smart-return.scheduler.zone:Europe/Istanbul}") String zone,
            @Value("${parkio.smart-return.scheduler.batch-size:100}") int batchSize) {
        this.userClient = userClient;
        this.parkingClient = parkingClient;
        this.notifications = notifications;
        this.clock = clock;
        this.enabled = featureEnabled && schedulerEnabled;
        this.promptZone = ZoneId.of(zone);
        this.batchSize = batchSize;
        this.promptsSent = Counter.builder("parkio.smart_return.morning_prompts_sent_total").register(registry);
        this.checksStarted = Counter.builder("parkio.smart_return.return_checks_started_total").register(registry);
        this.notificationsSent = Counter.builder("parkio.smart_return.return_notifications_sent_total").register(registry);
        this.noSpots = Counter.builder("parkio.smart_return.return_checks_no_spots_total").register(registry);
        this.claimsRetried = Counter.builder("parkio.smart_return.return_check_claims_retried_total").register(registry);
        this.failures = Counter.builder("parkio.smart_return.scheduler_failures_total").register(registry);
    }

    @Scheduled(cron = "${parkio.smart-return.scheduler.morning-prompt-cron:0 0 8 * * *}",
            zone = "${parkio.smart-return.scheduler.zone:Europe/Istanbul}")
    public SmartReturnSchedulerTickSummary sendMorningPrompts() {
        if (!enabled) {
            SmartReturnSchedulerTickSummary summary = SmartReturnSchedulerTickSummary.disabled();
            log.info("Smart Return morning prompt tick skipped: {}", summary);
            return summary;
        }
        try {
            LocalDate promptDate = LocalDate.now(clock.withZone(promptZone));
            List<SmartReturnUserClient.PromptCandidate> candidates = userClient.claimDuePrompts(promptDate, batchSize);
            int notificationsCreated = 0;
            for (SmartReturnUserClient.PromptCandidate candidate : candidates) {
                notifications.createSmartReturnPrompt(candidate.userId());
                notificationsCreated++;
                promptsSent.increment();
            }
            SmartReturnSchedulerTickSummary summary = new SmartReturnSchedulerTickSummary(
                    true, candidates.size(), candidates.size(), 0, 0, 0, notificationsCreated, 0);
            log.info("Smart Return morning prompt tick completed: {}", summary);
            return summary;
        } catch (RuntimeException ex) {
            failures.increment();
            log.warn("Smart Return morning prompt tick failed", ex);
            return new SmartReturnSchedulerTickSummary(true, 0, 0, 0, 0, 0, 0, 1);
        }
    }

    @Scheduled(fixedDelayString = "${parkio.smart-return.scheduler.return-check-fixed-delay-ms:60000}")
    public SmartReturnSchedulerTickSummary runReturnChecks() {
        if (!enabled) {
            SmartReturnSchedulerTickSummary summary = SmartReturnSchedulerTickSummary.disabled();
            log.info("Smart Return return-check tick skipped: {}", summary);
            return summary;
        }
        Instant now = clock.instant();
        try {
            List<SmartReturnUserClient.ReturnCheckCandidate> due = userClient.claimDueReturnChecks(now, batchSize);
            int claimRetryCount = 0;
            int noSpotCount = 0;
            int notificationCount = 0;
            for (SmartReturnUserClient.ReturnCheckCandidate candidate : due) {
                checksStarted.increment();
                if (candidate.claimRetried()) {
                    claimRetryCount++;
                    claimsRetried.increment();
                }
                ReturnCheckResult result = handleReturnCheck(candidate, now);
                if (result == ReturnCheckResult.NO_SPOTS) {
                    noSpotCount++;
                } else {
                    notificationCount++;
                }
            }
            SmartReturnSchedulerTickSummary summary = new SmartReturnSchedulerTickSummary(
                    true, due.size(), 0, due.size(), claimRetryCount, noSpotCount, notificationCount, 0);
            log.info("Smart Return return-check tick completed: {}", summary);
            return summary;
        } catch (RuntimeException ex) {
            failures.increment();
            log.warn("Smart Return return-check tick failed", ex);
            return new SmartReturnSchedulerTickSummary(true, 0, 0, 0, 0, 0, 0, 1);
        }
    }

    private ReturnCheckResult handleReturnCheck(SmartReturnUserClient.ReturnCheckCandidate candidate, Instant now) {
        List<SmartReturnParkingClient.NearbySpot> spots = parkingClient.searchNearby(
                candidate.userId(), candidate.homeLatitude(), candidate.homeLongitude(), candidate.radiusMeters(), 5);
        if (spots.isEmpty()) {
            userClient.completeReturnCheck(candidate.userId(), false, now);
            noSpots.increment();
            return ReturnCheckResult.NO_SPOTS;
        }
        String label = spots.get(0).addressText();
        notifications.createSmartReturnParkingAvailable(candidate.userId(), label);
        userClient.completeReturnCheck(candidate.userId(), true, now);
        notificationsSent.increment();
        return ReturnCheckResult.NOTIFICATION_CREATED;
    }

    private enum ReturnCheckResult {
        NO_SPOTS,
        NOTIFICATION_CREATED
    }
}
