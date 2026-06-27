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
            @Value("${parkio.smart-return.scheduler.enabled:true}") boolean enabled,
            @Value("${parkio.smart-return.scheduler.zone:Europe/Istanbul}") String zone,
            @Value("${parkio.smart-return.scheduler.batch-size:100}") int batchSize) {
        this.userClient = userClient;
        this.parkingClient = parkingClient;
        this.notifications = notifications;
        this.clock = clock;
        this.enabled = enabled;
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
    public void sendMorningPrompts() {
        if (!enabled) {
            return;
        }
        try {
            LocalDate promptDate = LocalDate.now(clock.withZone(promptZone));
            for (SmartReturnUserClient.PromptCandidate candidate :
                    userClient.claimDuePrompts(promptDate, batchSize)) {
                notifications.createSmartReturnPrompt(candidate.userId());
                promptsSent.increment();
            }
        } catch (RuntimeException ex) {
            failures.increment();
            log.warn("Smart Return morning prompt tick failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${parkio.smart-return.scheduler.return-check-fixed-delay-ms:60000}")
    public void runReturnChecks() {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        try {
            List<SmartReturnUserClient.ReturnCheckCandidate> due = userClient.claimDueReturnChecks(now, batchSize);
            for (SmartReturnUserClient.ReturnCheckCandidate candidate : due) {
                checksStarted.increment();
                if (candidate.claimRetried()) {
                    claimsRetried.increment();
                }
                handleReturnCheck(candidate, now);
            }
        } catch (RuntimeException ex) {
            failures.increment();
            log.warn("Smart Return return-check tick failed", ex);
        }
    }

    private void handleReturnCheck(SmartReturnUserClient.ReturnCheckCandidate candidate, Instant now) {
        List<SmartReturnParkingClient.NearbySpot> spots = parkingClient.searchNearby(
                candidate.userId(), candidate.homeLatitude(), candidate.homeLongitude(), candidate.radiusMeters(), 5);
        if (spots.isEmpty()) {
            userClient.completeReturnCheck(candidate.userId(), false, now);
            noSpots.increment();
            return;
        }
        String label = spots.get(0).addressText();
        notifications.createSmartReturnParkingAvailable(candidate.userId(), label);
        userClient.completeReturnCheck(candidate.userId(), true, now);
        notificationsSent.increment();
    }
}
