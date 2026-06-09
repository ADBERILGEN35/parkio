package com.parkio.notification.infrastructure.delivery;

import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushNotificationSender;
import com.parkio.notification.application.port.PushSendResult;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled worker that drains due PENDING push delivery attempts and sends them
 * through the configured {@link PushNotificationSender} (no-op locally; FCM placeholder
 * when explicitly selected).
 *
 * <p><b>Cluster safety:</b> each tick claims its batch with
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} inside the tick's transaction, so multiple
 * notification-service replicas can run the worker concurrently without ever sending
 * the same attempt twice. The transaction spans claim + process + save; a row stays
 * locked until the batch commits.
 *
 * <p><b>Retry/backoff:</b> only attempts with {@code nextAttemptAt <= now} are fetched.
 * On failure the attempt stays PENDING with {@code nextAttemptAt} pushed out by
 * exponential backoff ({@code base-backoff * 2^(attemptCount-1)}) until
 * {@code max-attempts} is reached, then it is marked FAILED (terminal). A failing
 * attempt never throws, so one bad attempt cannot roll back the rest of the batch.
 *
 * <p>Disabled by default in tests via {@code parkio.notification.delivery.push.enabled}.
 * Never logs device tokens or other secrets (ai-context/07).
 */
@Component
public class PushDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(PushDeliveryWorker.class);

    /** Sanitised reason recorded when the sender throws; never the raw exception message. */
    static final String REASON_PROVIDER_ERROR = "PROVIDER_ERROR";

    private final NotificationDeliveryAttemptRepository attempts;
    private final DeviceTokenRepository deviceTokens;
    private final NotificationRepository notifications;
    private final PushNotificationSender sender;
    private final Clock clock;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Counter workerSuccess;
    private final Counter workerFailure;

    public PushDeliveryWorker(
            NotificationDeliveryAttemptRepository attempts,
            DeviceTokenRepository deviceTokens,
            NotificationRepository notifications,
            PushNotificationSender sender,
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${parkio.notification.delivery.push.enabled:true}") boolean enabled,
            @Value("${parkio.notification.delivery.push.batch-size:100}") int batchSize,
            @Value("${parkio.notification.delivery.push.max-attempts:5}") int maxAttempts,
            @Value("${parkio.notification.delivery.push.base-backoff-ms:30000}") long baseBackoffMs) {
        this.attempts = attempts;
        this.deviceTokens = deviceTokens;
        this.notifications = notifications;
        this.sender = sender;
        this.clock = clock;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = Duration.ofMillis(baseBackoffMs);
        this.workerSuccess = Counter.builder("parkio.notification.delivery.worker.success.count")
                .description("Push delivery attempts handed off to the provider by this worker")
                .register(meterRegistry);
        this.workerFailure = Counter.builder("parkio.notification.delivery.worker.failure.count")
                .description("Push delivery attempts that failed a send (retried or terminal)")
                .register(meterRegistry);
    }

    /**
     * Transactional so the SKIP LOCKED claim in {@link #processPendingBatch()} holds its
     * row locks for the whole batch. Unexpected (DB-level) errors roll back the batch and
     * are logged by the scheduler; the claimed rows simply become due again.
     */
    @Scheduled(fixedDelayString = "${parkio.notification.delivery.push.fixed-delay-ms:30000}")
    @Transactional
    public void tick() {
        if (!enabled) {
            return;
        }
        int processed = processPendingBatch();
        if (processed > 0) {
            log.debug("Push delivery worker processed {} attempt(s)", processed);
        }
    }

    /**
     * Claims and processes one batch of due PENDING attempts. Returns how many were
     * handled. Must run inside a transaction (see {@link #tick()}); send failures are
     * recorded as attempt state, never thrown, so a single failed attempt does not roll
     * back the whole batch.
     */
    @Transactional
    public int processPendingBatch() {
        Instant now = clock.instant();
        List<NotificationDeliveryAttempt> claimed = attempts.claimDue(now, batchSize);
        for (NotificationDeliveryAttempt attempt : claimed) {
            deliver(attempt);
        }
        return claimed.size();
    }

    private void deliver(NotificationDeliveryAttempt attempt) {
        if (attempt.deviceTokenId() == null) {
            fail(attempt, "MISSING_DEVICE_TOKEN");
            return;
        }
        Optional<DeviceToken> token = deviceTokens.findById(attempt.deviceTokenId());
        if (token.isEmpty() || !token.get().active()) {
            fail(attempt, "DEVICE_TOKEN_INACTIVE");
            return;
        }
        Optional<Notification> notification = notifications.findById(attempt.notificationId());
        if (notification.isEmpty()) {
            fail(attempt, "NOTIFICATION_NOT_FOUND");
            return;
        }
        DeviceToken target = token.get();
        Notification n = notification.get();
        PushSendResult result;
        try {
            result = sender.send(new PushMessage(target.token(), target.platform(), n.title(), n.body()));
        } catch (RuntimeException ex) {
            log.warn("Push sender threw for attempt {}", attempt.id(), ex);
            fail(attempt, REASON_PROVIDER_ERROR);
            return;
        }
        if (result.delivered()) {
            attempt.markSent(result.providerMessageId(), clock.instant());
            attempts.save(attempt);
            workerSuccess.increment();
            log.debug("Push attempt {} delivered (providerMessageId={})", attempt.id(), result.providerMessageId());
        } else {
            fail(attempt, result.failureReason());
        }
    }

    private void fail(NotificationDeliveryAttempt attempt, String reason) {
        attempt.recordFailure(reason, maxAttempts, baseBackoff, clock.instant());
        attempts.save(attempt);
        workerFailure.increment();
        log.debug("Push attempt {} failed (reason={}, attemptCount={}, status={}, nextAttemptAt={})",
                attempt.id(), reason, attempt.attemptCount(), attempt.status(), attempt.nextAttemptAt());
    }
}
