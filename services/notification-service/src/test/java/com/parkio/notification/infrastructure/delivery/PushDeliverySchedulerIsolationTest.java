package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.application.port.PushNotificationSender;
import com.parkio.notification.application.port.PushSendResult;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationType;
import com.parkio.notification.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import com.parkio.notification.infrastructure.smartreturn.SmartReturnUserClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * #641: a push delivery tick whose Expo send never answers must not hold up the rest of notification-service. With
 * the default bounds such a tick lasts about 8 minutes (100 sends x the 5 s read timeout), and about 100 minutes at
 * the configured maximum. While every {@code @Scheduled} job shared Spring Boot's single scheduler thread, the outbox
 * relay (1 s), the retention cleanup and both Smart Return jobs waited that long.
 *
 * <p>Here the first push send blocks until the test releases it, like an Expo response that never completes. While
 * it is blocked, the outbox relay, the retention cleanup and the Smart Return return checks and morning prompts must
 * keep their cadence, and the worker must not start a second tick. The real job beans and the production scheduling
 * configuration run; only their collaborators are replaced. The context stays cached, so the test cancels its
 * millisecond schedules when it ends. Closing the context instead would drop the H2 schema that every cached test
 * context shares ({@code ddl-auto: create-drop}).
 */
@SpringBootTest(properties = {
        "parkio.notification.delivery.push.enabled=true",
        "parkio.notification.delivery.push.fixed-delay-ms=50",
        "parkio.kafka.relay.enabled=true",
        "parkio.kafka.relay.poll-interval-ms=20",
        "parkio.lifecycle.retention.outbox-enabled=true",
        "parkio.lifecycle.retention.fixed-delay-ms=20",
        "parkio.smart-return.enabled=true",
        "parkio.smart-return.scheduler.enabled=true",
        // Both Smart Return jobs log every run at INFO, so they run less often than the relay.
        "parkio.smart-return.scheduler.return-check-fixed-delay-ms=50",
        "parkio.smart-return.scheduler.morning-prompt-cron=* * * * * *"
})
class PushDeliverySchedulerIsolationTest {

    /** Upper bound for every wait, so a regression fails the test instead of hanging the build. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final DeviceToken TOKEN = DeviceToken.register(
            UUID.randomUUID(), "ExponentPushToken[synthetic]", DevicePlatform.IOS, Instant.now());
    private static final Notification NOTIFICATION = Notification.create(
            TOKEN.userId(), NotificationType.LEVEL_UP, NotificationChannel.PUSH, "Title", "Body", Instant.now());

    private static final CountDownLatch PUSH_STALLED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_PUSH = new CountDownLatch(1);
    private static final Probe PUSH_SEND = new Probe();
    private static final Probe RELAY_POLL = new Probe();
    private static final Probe RETENTION = new Probe();
    private static final Probe RETURN_CHECK = new Probe();
    private static final Probe MORNING_PROMPT = new Probe();

    @TestBean
    PushNotificationSender pushNotificationSender;

    @TestBean
    NotificationDeliveryAttemptRepository notificationDeliveryAttemptRepository;

    @TestBean
    DeviceTokenRepository deviceTokenRepository;

    @TestBean
    NotificationRepository notificationRepository;

    @TestBean
    OutboxEventJpaRepository outboxEventJpaRepository;

    @TestBean
    JdbcTemplate jdbcTemplate;

    @TestBean
    SmartReturnUserClient smartReturnUserClient;

    @Autowired
    ScheduledTaskHolder scheduledTasks;

    /** The first send stalls until released, like an Expo response that never completes; later sends succeed. */
    static PushNotificationSender pushNotificationSender() {
        return message -> {
            PUSH_SEND.enter();
            try {
                if (PUSH_STALLED.getCount() > 0) {
                    PUSH_STALLED.countDown();
                    RELEASE_PUSH.await(3 * WAIT.toMillis(), TimeUnit.MILLISECONDS);
                }
                return PushSendResult.sent("synthetic-ticket");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally {
                PUSH_SEND.exit();
            }
        };
    }

    /** Every tick claims one due attempt for an active device token. */
    static NotificationDeliveryAttemptRepository notificationDeliveryAttemptRepository() {
        NotificationDeliveryAttemptRepository attempts = mock(NotificationDeliveryAttemptRepository.class);
        when(attempts.claimDue(any(), anyInt())).thenAnswer(invocation -> List.of(NotificationDeliveryAttempt.pending(
                NOTIFICATION.id(), NOTIFICATION.userId(), NotificationChannel.PUSH, TOKEN.id(), Instant.now())));
        return attempts;
    }

    static DeviceTokenRepository deviceTokenRepository() {
        DeviceTokenRepository tokens = mock(DeviceTokenRepository.class);
        when(tokens.findById(any())).thenReturn(Optional.of(TOKEN));
        return tokens;
    }

    static NotificationRepository notificationRepository() {
        NotificationRepository notifications = mock(NotificationRepository.class);
        when(notifications.findById(any())).thenReturn(Optional.of(NOTIFICATION));
        return notifications;
    }

    /** Every relay poll finds no pending row, so the relay only shows that it ran. */
    static OutboxEventJpaRepository outboxEventJpaRepository() {
        OutboxEventJpaRepository outbox = mock(OutboxEventJpaRepository.class);
        when(outbox.findUnpublishedBatchForUpdate(anyInt())).thenAnswer(invocation -> {
            RELAY_POLL.enter();
            RELAY_POLL.exit();
            return List.of();
        });
        return outbox;
    }

    /** The retention cleanup runs its outbox DELETE through this template; the statement deletes nothing. */
    static JdbcTemplate jdbcTemplate() {
        return mock(JdbcTemplate.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if ("update".equals(invocation.getMethod().getName()) && arguments.length > 0
                    && arguments[0] instanceof String sql && sql.contains("DELETE FROM outbox_events")) {
                RETENTION.enter();
                RETENTION.exit();
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
    }

    /** Neither Smart Return job finds due work, so each only shows that it ran. */
    static SmartReturnUserClient smartReturnUserClient() {
        SmartReturnUserClient users = mock(SmartReturnUserClient.class);
        when(users.claimDueReturnChecks(any(), anyInt())).thenAnswer(invocation -> {
            RETURN_CHECK.enter();
            RETURN_CHECK.exit();
            return List.of();
        });
        when(users.claimDuePrompts(any(), anyInt())).thenAnswer(invocation -> {
            MORNING_PROMPT.enter();
            MORNING_PROMPT.exit();
            return List.of();
        });
        return users;
    }

    @Test
    void aStalledPushSendDelaysNeitherTheOutboxRelayNorTheOtherScheduledJobs() throws Exception {
        assertThat(PUSH_STALLED.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .as("the push delivery tick started a send and stalled").isTrue();
        try {
            int relayPolls = RELAY_POLL.calls();
            int retentionRuns = RETENTION.calls();
            int returnChecks = RETURN_CHECK.calls();
            int morningPrompts = MORNING_PROMPT.calls();

            awaitCalls(RELAY_POLL, relayPolls + 5, "outbox relay polls while the push send is stalled");
            awaitCalls(RETENTION, retentionRuns + 3, "retention runs while the push send is stalled");
            awaitCalls(RETURN_CHECK, returnChecks + 3, "Smart Return return checks while the push send is stalled");
            awaitCalls(MORNING_PROMPT, morningPrompts + 2,
                    "Smart Return morning prompts while the push send is stalled");
            assertThat(PUSH_SEND.calls()).as("push sends while the first one is stalled").isEqualTo(1);
        } finally {
            RELEASE_PUSH.countDown();
        }
        awaitCalls(PUSH_SEND, 3, "push sends after the stall ends");

        assertThat(PUSH_SEND.threads()).as("push delivery threads")
                .allMatch(name -> name.startsWith("push-delivery-"));
        assertThat(RELAY_POLL.threads()).as("outbox relay threads").allMatch(name -> name.startsWith("scheduling-"));
        assertThat(RETENTION.threads()).as("retention threads").allMatch(name -> name.startsWith("scheduling-"));
        assertThat(RETURN_CHECK.threads()).as("Smart Return return-check threads")
                .allMatch(name -> name.startsWith("scheduling-"));
        assertThat(MORNING_PROMPT.threads()).as("Smart Return morning-prompt threads")
                .allMatch(name -> name.startsWith("scheduling-"));
        assertThat(PUSH_SEND.maxRunning()).as("concurrent push sends").isEqualTo(1);
    }

    @AfterEach
    void cancelTheSchedules() {
        RELEASE_PUSH.countDown();
        scheduledTasks.getScheduledTasks().forEach(ScheduledTask::cancel);
    }

    private static void awaitCalls(Probe probe, int atLeast, String description) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (probe.calls() < atLeast && System.nanoTime() - deadline < 0) {
            Thread.sleep(10);
        }
        assertThat(probe.calls()).as(description).isGreaterThanOrEqualTo(atLeast);
    }

    /** Counts the calls of one job, the threads it ran on, and how many of its runs overlapped. */
    private static final class Probe {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();
        private final Set<String> threads = ConcurrentHashMap.newKeySet();

        void enter() {
            calls.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
        }

        void exit() {
            running.decrementAndGet();
        }

        int calls() {
            return calls.get();
        }

        int maxRunning() {
            return maxRunning.get();
        }

        Set<String> threads() {
            return Set.copyOf(threads);
        }
    }
}
