package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationType;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * #595 on PostgreSQL with the Flyway schema: a tick whose Expo send never gets a response ends at the read timeout,
 * records the attempt as a terminal unknown outcome and commits, which releases its {@code FOR UPDATE SKIP LOCKED}
 * claim. Scheduled methods never fire on their own here; the test calls the transactional tick itself.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "parkio.notification.delivery.push.enabled=true",
        "parkio.notification.delivery.push.provider=expo",
        "parkio.notification.delivery.push.expo.access-token=synthetic-test-only",
        "parkio.notification.delivery.push.expo.connect-timeout=500ms",
        "parkio.notification.delivery.push.expo.read-timeout=2s"})
@Testcontainers(disabledWithoutDocker = true)
class PushDeliveryStalledExpoPostgresIT {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    /** PostgreSQL SQLSTATE for NOWAIT on a locked row. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    /** Scheduling slack for a loaded runner. An unbounded send never returns at all. */
    private static final Duration SLACK = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final SilentPeer SILENT_EXPO = SilentPeer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.notification.delivery.push.expo.base-url", SILENT_EXPO::baseUrl);
    }

    @AfterAll
    static void stopSilentExpo() {
        SILENT_EXPO.close();
    }

    /** Only the test decides when a tick runs: no scheduled method fires on its own. */
    @TestConfiguration
    static class TicksOnDemand {
        @Bean
        SchedulingConfigurer noScheduledFirings() {
            return registrar -> registrar.setTaskScheduler(new NeverFiringScheduler());
        }
    }

    /** Takes every schedule and never runs it. Spring's {@code ScheduledTask} accepts the missing future. */
    static final class NeverFiringScheduler implements TaskScheduler {
        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return null;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return null;
        }
    }

    @Autowired private PushDeliveryWorker worker;
    @Autowired private DeviceTokenRepository deviceTokens;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryAttemptRepository attempts;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void stalledExpoSendEndsTheTickAtTheReadBoundAndReleasesItsClaim() throws Exception {
        UUID attemptId = pendingPushAttempt();
        ExecutorService tickThread = Executors.newSingleThreadExecutor();
        try {
            long started = System.nanoTime();
            Future<?> tick = tickThread.submit(worker::tick);

            // The tick has claimed the row and waits on Expo while holding the claim.
            assertThat(SILENT_EXPO.awaitRequestHead(Duration.ofSeconds(30))).isTrue();
            assertThat(claimIsFree(attemptId)).as("the claim is held while the send waits").isFalse();

            tick.get(READ_TIMEOUT.plus(SLACK).toMillis(), TimeUnit.MILLISECONDS);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isGreaterThanOrEqualTo(READ_TIMEOUT);
            assertThat(claimIsFree(attemptId)).as("the tick committed and released its claim").isTrue();
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT status, failure_reason, attempt_count, next_attempt_at
                    FROM notification_delivery_attempts WHERE id = ?""", attemptId);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_reason")).isEqualTo(ExpoPushNotificationSender.FAILURE_OUTCOME_UNKNOWN);
            assertThat(row.get("attempt_count")).isEqualTo(1);
            assertThat(row.get("next_attempt_at")).isNull();

            // Terminal: the next tick does not send it again.
            worker.tick();
            assertThat(SILENT_EXPO.connectionCount()).isEqualTo(1);
        } finally {
            tickThread.shutdownNow();
        }
    }

    private UUID pendingPushAttempt() {
        Instant now = Instant.now();
        UUID user = UUID.randomUUID();
        DeviceToken token = deviceTokens.save(DeviceToken.register(
                user, "ExponentPushToken[synthetic-" + UUID.randomUUID() + "]", DevicePlatform.IOS, now));
        Notification notification = notifications.save(Notification.create(
                user, NotificationType.LEVEL_UP, NotificationChannel.PUSH, "Title", "Body", now));
        return attempts.save(NotificationDeliveryAttempt.pending(
                notification.id(), user, NotificationChannel.PUSH, token.id(), now)).id();
    }

    /** True when another transaction can lock the attempt row at once, i.e. no tick holds its claim. */
    private boolean claimIsFree(UUID attemptId) {
        try {
            return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM notification_delivery_attempts WHERE id = ? FOR UPDATE NOWAIT",
                        UUID.class, attemptId);
                return true;
            }));
        } catch (DataAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return false;
            }
            throw ex;
        }
    }
}
