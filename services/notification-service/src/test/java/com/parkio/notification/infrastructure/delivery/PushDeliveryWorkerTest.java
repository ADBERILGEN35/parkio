package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushNotificationSender;
import com.parkio.notification.application.port.PushSendResult;
import com.parkio.notification.domain.DeliveryStatus;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PushDeliveryWorker} using in-memory fakes and stub senders —
 * no Spring, no DB, no scheduler. The fake attempt repository mirrors the claim
 * contract of the production adapter ({@code FOR UPDATE SKIP LOCKED}): a claimed row
 * is invisible to other claimers until it is saved.
 */
class PushDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");
    private static final String SECRET_TOKEN = "super-secret-device-token-value";
    private static final long BASE_BACKOFF_MS = 30_000L;

    private FakeAttemptRepository attempts;
    private FakeDeviceTokenRepository deviceTokens;
    private FakeNotificationRepository notifications;
    private SimpleMeterRegistry meterRegistry;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        attempts = new FakeAttemptRepository();
        deviceTokens = new FakeDeviceTokenRepository();
        notifications = new FakeNotificationRepository();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void noopSenderMarksAttemptSent() {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        PushDeliveryWorker worker = worker(new NoopPushNotificationSender(), 5);

        int processed = worker.processPendingBatch();

        assertThat(processed).isEqualTo(1);
        NotificationDeliveryAttempt saved = attempts.byId.get(attempt.id());
        assertThat(saved.status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(saved.providerMessageId()).startsWith("noop-");
        assertThat(saved.nextAttemptAt()).isNull();
    }

    @Test
    void workerCountersTrackSuccessAndFailure() {
        persistPendingAttempt();
        worker(new NoopPushNotificationSender(), 5).processPendingBatch();

        persistPendingAttempt();
        worker(message -> PushSendResult.failed("PROVIDER_TIMEOUT"), 5).processPendingBatch();

        assertThat(meterRegistry.counter("parkio.notification.delivery.worker.success.count").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("parkio.notification.delivery.worker.failure.count").count())
                .isEqualTo(1.0);
    }

    @Test
    void failureAtMaxAttemptsBecomesFailedWithoutLeakingSecret() {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        PushDeliveryWorker worker = worker(message -> PushSendResult.failed("PROVIDER_TIMEOUT"), 1);

        worker.processPendingBatch();

        NotificationDeliveryAttempt saved = attempts.byId.get(attempt.id());
        assertThat(saved.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(saved.failureReason()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(saved.failureReason()).doesNotContain(SECRET_TOKEN);
        assertThat(saved.providerMessageId()).isNull();
        assertThat(saved.nextAttemptAt()).isNull();
    }

    @Test
    void failureBelowMaxAttemptsStaysPendingWithBackoff() {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        PushDeliveryWorker worker = worker(message -> PushSendResult.failed("PROVIDER_TIMEOUT"), 3);

        worker.processPendingBatch();

        NotificationDeliveryAttempt saved = attempts.byId.get(attempt.id());
        assertThat(saved.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(saved.attemptCount()).isEqualTo(1);
        assertThat(saved.nextAttemptAt()).isAfter(NOW);
        assertThat(saved.nextAttemptAt()).isEqualTo(NOW.plusMillis(BASE_BACKOFF_MS));
    }

    @Test
    void backoffGrowsExponentiallyAcrossFailures() {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        PushDeliveryWorker worker = worker(message -> PushSendResult.failed("PROVIDER_TIMEOUT"), 5);

        // Second failure: re-arm the attempt as due (the fake clock never advances).
        worker.processPendingBatch();
        attempts.makeDue(attempt.id(), NOW);
        worker.processPendingBatch();

        NotificationDeliveryAttempt saved = attempts.byId.get(attempt.id());
        assertThat(saved.attemptCount()).isEqualTo(2);
        assertThat(saved.nextAttemptAt()).isEqualTo(NOW.plusMillis(2 * BASE_BACKOFF_MS));
    }

    @Test
    void pendingAttemptWithFutureNextAttemptAtIsSkipped() {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        attempts.makeDue(attempt.id(), NOW.plus(Duration.ofMinutes(5)));
        PushDeliveryWorker worker = worker(new NoopPushNotificationSender(), 5);

        int processed = worker.processPendingBatch();

        assertThat(processed).isZero();
        assertThat(attempts.byId.get(attempt.id()).status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(attempts.byId.get(attempt.id()).attemptCount()).isZero();
    }

    @Test
    void senderExceptionRecordsSanitisedFailureWithoutAbortingBatch() {
        NotificationDeliveryAttempt first = persistPendingAttempt();
        NotificationDeliveryAttempt second = persistPendingAttempt();
        AtomicInteger calls = new AtomicInteger();
        PushDeliveryWorker worker = worker(message -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("boom with " + SECRET_TOKEN);
            }
            return PushSendResult.sent("provider-ok");
        }, 5);

        int processed = worker.processPendingBatch();

        assertThat(processed).isEqualTo(2);
        // One attempt failed with a sanitised reason, the other was still delivered.
        List<NotificationDeliveryAttempt> all = List.of(
                attempts.byId.get(first.id()), attempts.byId.get(second.id()));
        assertThat(all).filteredOn(a -> a.status() == DeliveryStatus.PENDING)
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.failureReason()).isEqualTo(PushDeliveryWorker.REASON_PROVIDER_ERROR);
                    assertThat(a.failureReason()).doesNotContain(SECRET_TOKEN);
                });
        assertThat(all).filteredOn(a -> a.status() == DeliveryStatus.SENT).hasSize(1);
    }

    @Test
    void inactiveTokenFailsAttempt() {
        UUID user = UUID.randomUUID();
        DeviceToken token = DeviceToken.register(user, SECRET_TOKEN, DevicePlatform.ANDROID, NOW);
        token.deactivate(NOW);
        deviceTokens.save(token);
        Notification notification = persistNotification(user);
        NotificationDeliveryAttempt attempt = attempts.save(NotificationDeliveryAttempt.pending(
                notification.id(), user, NotificationChannel.PUSH, token.id(), NOW));
        PushDeliveryWorker worker = worker(new NoopPushNotificationSender(), 1);

        worker.processPendingBatch();

        assertThat(attempts.byId.get(attempt.id()).failureReason()).isEqualTo("DEVICE_TOKEN_INACTIVE");
    }

    /**
     * Simulates two replicas: while worker 1 holds the claim (blocked mid-send), worker 2
     * polls and must see nothing. The fake repository enforces the same exclusivity the
     * production adapter gets from {@code FOR UPDATE SKIP LOCKED}.
     */
    @Test
    void twoWorkersCannotProcessTheSamePendingAttempt() throws Exception {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        CountDownLatch claimedByFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        PushNotificationSender blockingSender = message -> {
            sends.incrementAndGet();
            claimedByFirst.countDown();
            try {
                releaseFirst.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return PushSendResult.sent("provider-1");
        };
        PushNotificationSender countingSender = message -> {
            sends.incrementAndGet();
            return PushSendResult.sent("provider-2");
        };
        PushDeliveryWorker worker1 = worker(blockingSender, 5);
        PushDeliveryWorker worker2 = worker(countingSender, 5);

        Thread first = new Thread(worker1::processPendingBatch);
        first.start();
        assertThat(claimedByFirst.await(5, TimeUnit.SECONDS)).isTrue();

        int processedBySecond = worker2.processPendingBatch();

        releaseFirst.countDown();
        first.join(5_000);

        assertThat(processedBySecond).isZero();
        assertThat(sends.get()).isEqualTo(1);
        assertThat(attempts.byId.get(attempt.id()).status()).isEqualTo(DeliveryStatus.SENT);
    }

    private PushDeliveryWorker worker(PushNotificationSender sender, int maxAttempts) {
        return new PushDeliveryWorker(attempts, deviceTokens, notifications, sender, clock, meterRegistry,
                true, 100, maxAttempts, BASE_BACKOFF_MS);
    }

    private NotificationDeliveryAttempt persistPendingAttempt() {
        UUID user = UUID.randomUUID();
        DeviceToken token = persistToken(user);
        Notification notification = persistNotification(user);
        return attempts.save(NotificationDeliveryAttempt.pending(
                notification.id(), user, NotificationChannel.PUSH, token.id(), NOW));
    }

    private DeviceToken persistToken(UUID user) {
        return deviceTokens.save(DeviceToken.register(user, SECRET_TOKEN, DevicePlatform.ANDROID, NOW));
    }

    private Notification persistNotification(UUID user) {
        return notifications.save(Notification.create(user, NotificationType.LEVEL_UP, NotificationChannel.IN_APP,
                "Level up!", "You reached level 2.", NOW));
    }

    // --- Fakes -----------------------------------------------------------

    /**
     * In-memory repository whose {@code claimDue} mirrors the production adapter's
     * {@code FOR UPDATE SKIP LOCKED} contract: claimed rows are excluded from other
     * claimers until {@code save} releases them.
     */
    private static final class FakeAttemptRepository implements NotificationDeliveryAttemptRepository {
        private final Map<UUID, NotificationDeliveryAttempt> byId = new HashMap<>();
        private final Set<UUID> claimed = new HashSet<>();

        @Override
        public synchronized NotificationDeliveryAttempt save(NotificationDeliveryAttempt attempt) {
            byId.put(attempt.id(), attempt);
            claimed.remove(attempt.id());
            return attempt;
        }

        @Override
        public synchronized List<NotificationDeliveryAttempt> claimDue(Instant now, int limit) {
            List<NotificationDeliveryAttempt> due = byId.values().stream()
                    .filter(a -> a.status() == DeliveryStatus.PENDING)
                    .filter(a -> a.nextAttemptAt() != null && !a.nextAttemptAt().isAfter(now))
                    .filter(a -> !claimed.contains(a.id()))
                    .limit(limit)
                    .toList();
            due.forEach(a -> claimed.add(a.id()));
            return due;
        }

        @Override
        public synchronized boolean existsByNotificationIdAndChannel(UUID notificationId,
                                                                     NotificationChannel channel) {
            return byId.values().stream()
                    .anyMatch(a -> a.notificationId().equals(notificationId) && a.channel() == channel);
        }

        /** Test helper: rewrites nextAttemptAt (e.g. to re-arm an attempt as due). */
        synchronized void makeDue(UUID attemptId, Instant nextAttemptAt) {
            NotificationDeliveryAttempt a = byId.get(attemptId);
            byId.put(attemptId, new NotificationDeliveryAttempt(a.id(), a.notificationId(), a.userId(),
                    a.channel(), a.deviceTokenId(), a.status(), a.providerMessageId(), a.failureReason(),
                    a.attemptCount(), a.attemptedAt(), nextAttemptAt, a.createdAt(), a.updatedAt(), a.version()));
        }
    }

    private static final class FakeDeviceTokenRepository implements DeviceTokenRepository {
        private final Map<UUID, DeviceToken> byId = new HashMap<>();

        @Override
        public DeviceToken save(DeviceToken deviceToken) {
            byId.put(deviceToken.id(), deviceToken);
            return deviceToken;
        }

        @Override
        public Optional<DeviceToken> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<DeviceToken> findByUserIdAndToken(UUID userId, String token) {
            return byId.values().stream()
                    .filter(t -> t.userId().equals(userId) && t.token().equals(token))
                    .findFirst();
        }

        @Override
        public List<DeviceToken> findActiveByUserId(UUID userId) {
            return byId.values().stream()
                    .filter(t -> t.userId().equals(userId) && t.active())
                    .toList();
        }
    }

    private static final class FakeNotificationRepository implements NotificationRepository {
        private final Map<UUID, Notification> byId = new HashMap<>();

        @Override
        public Notification save(Notification notification) {
            byId.put(notification.id(), notification);
            return notification;
        }

        @Override
        public Optional<Notification> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Notification> findRecentByUserId(UUID userId, int limit) {
            return byId.values().stream()
                    .filter(n -> n.userId().equals(userId))
                    .limit(limit)
                    .toList();
        }
    }
}
