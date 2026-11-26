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
    void actualExpoTicketIsPersistedAndNeverRetried() {
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://expo.invalid");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://expo.invalid/push/send"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"data\":{\"status\":\"ok\",\"id\":\"provider-ticket-123\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        var sender = new ExpoPushNotificationSender(builder.build(),
                new com.parkio.notification.infrastructure.metrics.PushSenderMetrics(meterRegistry), "default");
        var attempt = persistPendingAttempt();
        var worker = worker(sender, 5);
        assertThat(worker.processPendingBatch()).isEqualTo(1);
        assertThat(worker.processPendingBatch()).isZero();
        assertThat(attempts.byId.get(attempt.id()).providerMessageId()).isEqualTo("provider-ticket-123");
        assertThat(attempts.byId.get(attempt.id()).status()).isEqualTo(DeliveryStatus.SENT);
        assertThat(meterRegistry.counter("parkio.notification.delivery.retry.count").count()).isZero();
        server.verify();
    }

    @Test
    void actualRefusedConnectionKeepsBoundedRetryAndToken() throws Exception {
        int closedPort;
        try (var probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Windows reports a refused loopback connect only after SYN retries (about 2 s).
        factory.setConnectTimeout(Duration.ofSeconds(5));
        var sender = new ExpoPushNotificationSender(org.springframework.web.client.RestClient.builder()
                .baseUrl("http://127.0.0.1:" + closedPort).requestFactory(factory).build(),
                new com.parkio.notification.infrastructure.metrics.PushSenderMetrics(meterRegistry), "default");
        var attempt = persistPendingAttempt();
        var worker = worker(sender, 3);

        worker.processPendingBatch();

        var saved = attempts.byId.get(attempt.id());
        assertThat(saved.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(saved.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(saved.attemptCount()).isEqualTo(1);
        assertThat(saved.nextAttemptAt()).isEqualTo(NOW.plusMillis(BASE_BACKOFF_MS));
        assertThat(deviceTokens.findById(saved.deviceTokenId()).orElseThrow().active()).isTrue();
        assertThat(meterRegistry.counter("parkio.notification.delivery.retry.count").count()).isEqualTo(1);
    }

    /** #595: a send whose response never completes ends at the read timeout, and the batch still releases it. */
    @Test
    void actualStalledResponseIsTerminalUnknownAndReleasesTheClaim() throws Exception {
        Duration readTimeout = Duration.ofMillis(800);
        try (var silentExpo = SilentPeer.start()) {
            var sender = new ExpoPushNotificationSender(org.springframework.web.client.RestClient.builder()
                    .baseUrl(silentExpo.baseUrl())
                    .requestFactory(com.parkio.notification.infrastructure.config.ExpoPushConfig.requestFactory(
                            org.springframework.boot.http.client.HttpClientSettings.defaults(),
                            Duration.ofMillis(300), readTimeout))
                    .build(),
                    new com.parkio.notification.infrastructure.metrics.PushSenderMetrics(meterRegistry), "default");
            var attempt = persistPendingAttempt();
            var worker = worker(sender, 5);

            long started = System.nanoTime();
            int processed = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(10), worker::processPendingBatch);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(processed).isEqualTo(1);
            assertThat(silentExpo.awaitRequestHead(Duration.ofSeconds(1))).isTrue();
            assertThat(elapsed).isGreaterThanOrEqualTo(readTimeout);
            var saved = attempts.byId.get(attempt.id());
            assertThat(saved.status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(saved.failureReason()).isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
            assertThat(saved.attemptCount()).isEqualTo(1);
            assertThat(saved.nextAttemptAt()).isNull();
            assertThat(attempts.claimed).isEmpty();
            assertThat(worker.processPendingBatch()).isZero();
            assertThat(silentExpo.connectionCount()).isEqualTo(1);
            assertThat(deviceTokens.findById(saved.deviceTokenId()).orElseThrow().active()).isTrue();
            assertThat(meterRegistry.counter("parkio.notification.delivery.retry.count").count()).isZero();
            assertThat(meterRegistry.counter("parkio.notification.push.outcome_unknown.count").count()).isEqualTo(1);
            assertThat(meterRegistry.counter("parkio.notification.push.provider_unavailable.count").count()).isZero();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PROVIDER_OUTCOME_UNKNOWN", "PROVIDER_CONFIGURATION_ERROR"})
    void unresolvedOrConfigurationFailureIsTerminalAndPreservesToken(String reason) {
        var attempt = persistPendingAttempt();
        var worker = worker(message -> PushSendResult.failed(reason), 5);
        worker.processPendingBatch();
        assertThat(worker.processPendingBatch()).isZero();
        var saved = attempts.byId.get(attempt.id());
        assertThat(saved.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(saved.failureReason()).isEqualTo(reason);
        assertThat(saved.attemptCount()).isEqualTo(1);
        assertThat(saved.nextAttemptAt()).isNull();
        assertThat(deviceTokens.findById(saved.deviceTokenId()).orElseThrow().active()).isTrue();
        assertThat(meterRegistry.counter("parkio.notification.delivery.retry.count").count()).isZero();
    }

    @Test
    void disabledWorkerDoesNotClaimOrSyntheticallyDeliverPendingAttempt() {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        AtomicInteger sends = new AtomicInteger();
        PushDeliveryWorker worker = worker(message -> {
            sends.incrementAndGet();
            return PushSendResult.sent("must-not-be-used");
        }, 5, false);

        worker.tick();
        int processed = worker.processPendingBatch();

        assertThat(processed).isZero();
        assertThat(sends).hasValue(0);
        NotificationDeliveryAttempt unchanged = attempts.byId.get(attempt.id());
        assertThat(unchanged.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(unchanged.attemptCount()).isZero();
        assertThat(unchanged.providerMessageId()).isNull();
        assertThat(meterRegistry.counter("parkio.notification.delivery.worker.success.count").count())
                .isZero();
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

    /**
     * #651: shutdown interrupts a tick that is still sending ({@code shutdownNow()} once the lifecycle stop phase
     * ends). The send in flight keeps its conservative unknown outcome. Spring's JDK request sets the interrupt flag
     * again, so every later send of the batch would fail at once as well; the tick leaves the batch instead, and the
     * attempts it never tried stay due for the next tick.
     */
    @Test
    void interruptedTickLeavesTheAttemptsItNeverTriedForTheNextTick() {
        List<UUID> batch = List.of(
                persistPendingAttempt().id(), persistPendingAttempt().id(), persistPendingAttempt().id());
        AtomicInteger sends = new AtomicInteger();
        // Like the Expo sender when shutdownNow() interrupts its send: an unknown outcome, the interrupt flag set.
        PushDeliveryWorker interruptedWorker = worker(message -> {
            sends.incrementAndGet();
            Thread.currentThread().interrupt();
            return PushSendResult.failed(ExpoPushNotificationSender.FAILURE_OUTCOME_UNKNOWN);
        }, 5);

        int processed;
        boolean interruptKept;
        try {
            processed = interruptedWorker.processPendingBatch();
        } finally {
            // Clears the flag, so it cannot leak into the next test.
            interruptKept = Thread.interrupted();
        }
        attempts.endTransaction();

        assertThat(interruptKept).as("the interrupt stays set for the executor").isTrue();
        assertThat(sends).as("sends").hasValue(1);
        assertThat(processed).isEqualTo(1);
        List<NotificationDeliveryAttempt> afterTick = batch.stream().map(attempts.byId::get).toList();
        assertThat(afterTick).filteredOn(a -> a.status() == DeliveryStatus.FAILED).singleElement().satisfies(a -> {
            assertThat(a.failureReason()).isEqualTo(ExpoPushNotificationSender.FAILURE_OUTCOME_UNKNOWN);
            assertThat(a.attemptCount()).isEqualTo(1);
        });
        assertThat(afterTick).filteredOn(a -> a.status() == DeliveryStatus.PENDING).hasSize(2).allSatisfy(a -> {
            assertThat(a.attemptCount()).isZero();
            assertThat(a.failureReason()).isNull();
            assertThat(a.nextAttemptAt()).isEqualTo(NOW);
        });

        // The next tick takes the two attempts that the interrupted one never tried.
        assertThat(worker(new NoopPushNotificationSender(), 5).processPendingBatch()).isEqualTo(2);
        assertThat(batch.stream().map(attempts.byId::get).map(NotificationDeliveryAttempt::status))
                .containsExactlyInAnyOrder(DeliveryStatus.FAILED, DeliveryStatus.SENT, DeliveryStatus.SENT);
    }

    /**
     * #659: the interrupt can also land while the tick looks up an attempt's token and notification, after the
     * check before that attempt. Its send would then start on an interrupted thread and fail at once as a terminal
     * unknown outcome, although it never reached Expo. The tick leaves that attempt untouched instead.
     */
    @Test
    void interruptDuringTheLookupsLeavesThatAttemptUntouched() {
        NotificationDeliveryAttempt attempt = persistPendingAttempt();
        NotificationRepository interruptedLookup = new NotificationRepository() {
            @Override
            public Notification save(Notification notification) {
                return notifications.save(notification);
            }

            @Override
            public Optional<Notification> findById(UUID id) {
                // shutdownNow() lands during the lookup.
                Thread.currentThread().interrupt();
                return notifications.findById(id);
            }

            @Override
            public List<Notification> findRecentByUserId(UUID userId, int limit) {
                return notifications.findRecentByUserId(userId, limit);
            }
        };
        AtomicInteger sends = new AtomicInteger();
        // Like the Expo sender: a send started on an interrupted thread fails at once as an unknown outcome.
        PushNotificationSender sender = message -> {
            sends.incrementAndGet();
            return Thread.currentThread().isInterrupted()
                    ? PushSendResult.failed(ExpoPushNotificationSender.FAILURE_OUTCOME_UNKNOWN)
                    : PushSendResult.sent("synthetic-ticket");
        };
        PushDeliveryWorker interruptedWorker = new PushDeliveryWorker(attempts, deviceTokens, interruptedLookup,
                sender, clock, meterRegistry, true, 100, 5, BASE_BACKOFF_MS);

        int processed;
        boolean interruptKept;
        try {
            processed = interruptedWorker.processPendingBatch();
        } finally {
            // Clears the flag, so it cannot leak into the next test.
            interruptKept = Thread.interrupted();
        }
        attempts.endTransaction();

        assertThat(interruptKept).as("the interrupt stays set for the executor").isTrue();
        assertThat(sends).as("sends").hasValue(0);
        assertThat(processed).isZero();
        NotificationDeliveryAttempt untouched = attempts.byId.get(attempt.id());
        assertThat(untouched.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(untouched.attemptCount()).isZero();
        assertThat(untouched.failureReason()).isNull();
        assertThat(untouched.nextAttemptAt()).isEqualTo(NOW);
        assertThat(meterRegistry.counter("parkio.notification.delivery.worker.failure.count").count()).isZero();

        // The next tick sends it.
        assertThat(worker(sender, 5).processPendingBatch()).isEqualTo(1);
        assertThat(attempts.byId.get(attempt.id()).status()).isEqualTo(DeliveryStatus.SENT);
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
    void invalidDeviceTokenDeactivatesToken() {
        UUID user = UUID.randomUUID();
        DeviceToken token = persistToken(user);
        Notification notification = notifications.save(Notification.create(
                user,
                NotificationType.LEVEL_UP,
                NotificationChannel.IN_APP,
                "Level up!",
                "You reached level 2.",
                Map.of("deeplink", "/gamification"),
                NOW));
        NotificationDeliveryAttempt attempt = attempts.save(NotificationDeliveryAttempt.pending(
                notification.id(), user, NotificationChannel.PUSH, token.id(), NOW));
        PushDeliveryWorker worker = worker(
                message -> PushSendResult.failed(ExpoPushNotificationSender.FAILURE_INVALID_DEVICE_TOKEN), 1);

        worker.processPendingBatch();

        assertThat(deviceTokens.findById(token.id())).get().satisfies(saved -> assertThat(saved.active()).isFalse());
        assertThat(attempts.byId.get(attempt.id()).failureReason())
                .isEqualTo(ExpoPushNotificationSender.FAILURE_INVALID_DEVICE_TOKEN);
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
        return worker(sender, maxAttempts, true);
    }

    private PushDeliveryWorker worker(PushNotificationSender sender, int maxAttempts, boolean enabled) {
        return new PushDeliveryWorker(attempts, deviceTokens, notifications, sender, clock, meterRegistry,
                enabled, 100, maxAttempts, BASE_BACKOFF_MS);
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

        /**
         * Test helper: the end of the tick's transaction. In production its commit releases the
         * {@code FOR UPDATE SKIP LOCKED} claims of the rows the tick did not save.
         */
        synchronized void endTransaction() {
            claimed.clear();
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
