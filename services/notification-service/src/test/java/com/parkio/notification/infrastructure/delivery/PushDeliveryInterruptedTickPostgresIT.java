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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * #651 on PostgreSQL with the Flyway schema. Shutdown interrupts a tick whose Expo send is still waiting: once the
 * lifecycle stop phase ends, the scheduler's {@code destroy()} calls {@code shutdownNow()}. The send in flight ends as
 * a terminal unknown outcome, because Expo may already have it. Spring's JDK request sets the interrupt flag again, so
 * every later send of the batch used to fail at once and was recorded as a terminal unknown outcome as well, although
 * it never reached Expo. The tick now leaves its batch: the attempts it never tried stay PENDING and due, its commit
 * releases their claims, and the next tick takes them. As in {@link PushDeliveryStalledExpoPostgresIT}, no scheduled
 * method fires on its own; the test runs the transactional tick itself.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "parkio.notification.delivery.push.enabled=true",
        "parkio.notification.delivery.push.provider=expo",
        "parkio.notification.delivery.push.expo.access-token=synthetic-test-only",
        "parkio.notification.delivery.push.expo.connect-timeout=500ms",
        // Longer than every wait below, so only the interrupt can end the first send in time.
        "parkio.notification.delivery.push.expo.read-timeout=30s"})
@Import(PushDeliveryStalledExpoPostgresIT.TicksOnDemand.class)
@Testcontainers(disabledWithoutDocker = true)
class PushDeliveryInterruptedTickPostgresIT {

    /** Upper bound for every wait, so a regression fails the test instead of hanging the build. */
    private static final Duration WAIT = Duration.ofSeconds(10);
    /** PostgreSQL SQLSTATE for NOWAIT on a locked row. */
    private static final String LOCK_NOT_AVAILABLE = "55P03";

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

    @Autowired private PushDeliveryWorker worker;
    @Autowired private DeviceTokenRepository deviceTokens;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationDeliveryAttemptRepository attempts;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void anInterruptedTickRecordsOnlyItsSendInFlightAndReleasesTheRestOfItsBatch() throws Exception {
        List<UUID> batch = List.of(pendingPushAttempt(), pendingPushAttempt(), pendingPushAttempt());
        ExecutorService tickThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> tick = tickThread.submit(worker::tick);
            // The tick has claimed the whole batch and waits on Expo for its first send.
            assertThat(SILENT_EXPO.awaitRequestHead(WAIT)).isTrue();
            assertThat(batch).as("claims while the first send waits").noneMatch(this::claimIsFree);

            // What the scheduler's destroy() does once the lifecycle stop phase has ended.
            tickThread.shutdownNow();
            tick.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            tickThread.shutdownNow();
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, status, failure_reason, attempt_count
                FROM notification_delivery_attempts WHERE id IN (?, ?, ?)""", batch.toArray());
        assertThat(rows).as("the send in flight").filteredOn(row -> "FAILED".equals(row.get("status")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("failure_reason")).isEqualTo(ExpoPushNotificationSender.FAILURE_OUTCOME_UNKNOWN);
                    assertThat(row.get("attempt_count")).isEqualTo(1);
                });
        List<UUID> untried = rows.stream()
                .filter(row -> "PENDING".equals(row.get("status")))
                .filter(row -> row.get("failure_reason") == null && Integer.valueOf(0).equals(row.get("attempt_count")))
                .map(row -> (UUID) row.get("id"))
                .toList();
        assertThat(untried).as("attempts the tick never tried, untouched").hasSize(2);
        assertThat(batch).as("claims after the tick committed").allMatch(this::claimIsFree);
        assertThat(SILENT_EXPO.connectionCount()).as("sends that reached Expo").isEqualTo(1);

        // The next tick claims exactly the attempts that the interrupted tick never tried.
        List<UUID> nextClaim = new TransactionTemplate(transactionManager).execute(status ->
                attempts.claimDue(Instant.now(), 100).stream().map(NotificationDeliveryAttempt::id).toList());
        assertThat(nextClaim).containsExactlyInAnyOrderElementsOf(untried);
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
