package com.parkio.notification.infrastructure.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.notification.NotificationServiceApplication;
import com.parkio.notification.application.port.DeviceTokenRepository;
import com.parkio.notification.application.port.NotificationDeliveryAttemptRepository;
import com.parkio.notification.application.port.NotificationRepository;
import com.parkio.notification.domain.DevicePlatform;
import com.parkio.notification.domain.DeviceToken;
import com.parkio.notification.domain.Notification;
import com.parkio.notification.domain.NotificationChannel;
import com.parkio.notification.domain.NotificationDeliveryAttempt;
import com.parkio.notification.domain.NotificationType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.annotation.Testable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * #659 on PostgreSQL with the Flyway schema: the application context closes, as on SIGTERM, while a tick's Expo send
 * is still waiting. The lifecycle stop phase (1 s here, 30 s in production) ends first. The push scheduler's
 * {@code destroy()} then interrupts the tick, which records the send in flight as a terminal unknown outcome and
 * commits. Before, {@code destroy()} returned at once, and the context went on to close the EntityManagerFactory and
 * the DataSource, whose shutdown aborts the connection the tick still uses. The outcome then rolled back, and the
 * attempt was due again after the restart, so the same push could be sent twice.
 *
 * <p>The tick's own bookkeeping after the interrupt takes milliseconds, so the race is short. To make it certain, the
 * test wraps the attempt repository: a save on an interrupted thread first pauses for a second, less than the
 * scheduler waits for the tick. The test starts and closes its own application, because a test must not close a
 * context that the Spring test framework caches.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class PushDeliveryShutdownPostgresIT {

    /** How long a save on an interrupted thread pauses. */
    private static final Duration SAVE_PAUSE = Duration.ofSeconds(1);
    /** Upper bound for every wait, so a regression fails the test instead of hanging the build. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final SilentPeer SILENT_EXPO = SilentPeer.start();

    @AfterAll
    static void stopSilentExpo() {
        SILENT_EXPO.close();
    }

    @Test
    void closingTheContextDuringASendCommitsTheInFlightOutcome() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(NotificationServiceApplication.class)
                .initializers(application -> {
                    application.getBeanFactory().registerSingleton(
                            TestTypesExcluded.class.getName(), new TestTypesExcluded());
                    application.getBeanFactory().addBeanPostProcessor(new PauseInterruptedSaves());
                })
                .run(arguments());
        List<UUID> batch;
        Duration closing;
        try {
            batch = new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                    .execute(status -> List.of(pendingPushAttempt(context), pendingPushAttempt(context)));
            // A scheduled tick has claimed both attempts and waits on Expo for its first send.
            assertThat(SILENT_EXPO.awaitRequestHead(WAIT)).as("the tick's first send reached Expo").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } finally {
            long started = System.nanoTime();
            context.close();
            closing = Duration.ofNanos(System.nanoTime() - started);
        }

        JdbcTemplate database = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        List<Map<String, Object>> rows = database.queryForList("""
                SELECT id, status, failure_reason, attempt_count, next_attempt_at
                FROM notification_delivery_attempts WHERE id IN (?, ?)""", batch.toArray());
        assertThat(rows).as("the send in flight").filteredOn(row -> "FAILED".equals(row.get("status")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("failure_reason")).isEqualTo(ExpoPushNotificationSender.FAILURE_OUTCOME_UNKNOWN);
                    assertThat(row.get("attempt_count")).isEqualTo(1);
                    assertThat(row.get("next_attempt_at")).isNull();
                });
        List<UUID> untried = rows.stream()
                .filter(row -> "PENDING".equals(row.get("status")))
                .filter(row -> row.get("failure_reason") == null && Integer.valueOf(0).equals(row.get("attempt_count")))
                .map(row -> (UUID) row.get("id"))
                .toList();
        assertThat(untried).as("the attempt the tick never tried, untouched").hasSize(1);
        assertThat(database.queryForList("""
                SELECT id FROM notification_delivery_attempts
                WHERE status = 'PENDING' AND next_attempt_at <= now() AND id IN (?, ?)""",
                UUID.class, batch.toArray()))
                .as("attempts due after a restart").isEqualTo(untried);
        assertThat(SILENT_EXPO.connectionCount()).as("sends that reached Expo").isEqualTo(1);
        assertThat(closing).as("time to close the context").isLessThan(WAIT);
    }

    private static String[] arguments() {
        return new String[] {
                "--spring.main.register-shutdown-hook=false",
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.flyway.enabled=true",
                // Production waits up to 30 s for a running tick before destroy() interrupts it.
                "--spring.lifecycle.timeout-per-shutdown-phase=1s",
                "--parkio.notification.delivery.push.enabled=true",
                "--parkio.notification.delivery.push.fixed-delay-ms=100",
                "--parkio.notification.delivery.push.provider=expo",
                "--parkio.notification.delivery.push.expo.access-token=synthetic-test-only",
                "--parkio.notification.delivery.push.expo.base-url=" + SILENT_EXPO.baseUrl(),
                "--parkio.notification.delivery.push.expo.connect-timeout=500ms",
                // Longer than the test, so only the interrupt ends the first send.
                "--parkio.notification.delivery.push.expo.read-timeout=30s"};
    }

    private static UUID pendingPushAttempt(ConfigurableApplicationContext context) {
        Instant now = Instant.now();
        UUID user = UUID.randomUUID();
        DeviceToken token = context.getBean(DeviceTokenRepository.class).save(DeviceToken.register(
                user, "ExponentPushToken[synthetic-" + UUID.randomUUID() + "]", DevicePlatform.IOS, now));
        Notification notification = context.getBean(NotificationRepository.class).save(Notification.create(
                user, NotificationType.LEVEL_UP, NotificationChannel.PUSH, "Title", "Body", now));
        return context.getBean(NotificationDeliveryAttemptRepository.class).save(NotificationDeliveryAttempt.pending(
                notification.id(), user, NotificationChannel.PUSH, token.id(), now)).id();
    }

    /** Wraps the attempt repository, so that a save on an interrupted thread first pauses (see the class comment). */
    private static final class PauseInterruptedSaves implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            return bean instanceof NotificationDeliveryAttemptRepository attempts ? new PausingAttempts(attempts) : bean;
        }
    }

    private record PausingAttempts(NotificationDeliveryAttemptRepository delegate)
            implements NotificationDeliveryAttemptRepository {

        @Override
        public NotificationDeliveryAttempt save(NotificationDeliveryAttempt attempt) {
            if (Thread.interrupted()) {
                try {
                    Thread.sleep(SAVE_PAUSE.toMillis());
                } catch (InterruptedException again) {
                    // The flag is set again below either way.
                } finally {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.save(attempt);
        }

        @Override
        public List<NotificationDeliveryAttempt> claimDue(Instant now, int limit) {
            return delegate.claimDue(now, limit);
        }

        @Override
        public boolean existsByNotificationIdAndChannel(UUID notificationId, NotificationChannel channel) {
            return delegate.existsByNotificationIdAndChannel(notificationId, channel);
        }
    }

    /**
     * Keeps test classes, and the configurations nested in them, out of the application's component scan, as
     * {@code @SpringBootTest} does with Spring Boot's own package-private filter. Without it, the scan would pick up
     * other tests' {@code @TestConfiguration} classes, such as the never-firing scheduler.
     */
    private static final class TestTypesExcluded extends TypeExcludeFilter {

        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            AnnotationMetadata type = reader.getAnnotationMetadata();
            if (type.isAnnotated(TestComponent.class.getName()) || type.hasAnnotatedMethods(Testable.class.getName())) {
                return true;
            }
            String enclosing = type.getEnclosingClassName();
            return enclosing != null && match(factory.getMetadataReader(enclosing), factory);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestTypesExcluded;
        }

        @Override
        public int hashCode() {
            return TestTypesExcluded.class.hashCode();
        }
    }
}
