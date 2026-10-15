package com.parkio.aivalidation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.aivalidation.application.event.MediaUploadedEvent;
import com.parkio.aivalidation.application.event.ParkingSpotCreatedEvent;
import com.parkio.aivalidation.application.event.UserErasureRequestedEvent;
import com.parkio.aivalidation.domain.exception.AiValidationErrorCode;
import com.parkio.aivalidation.domain.exception.AiValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * N-07 / #361: after a user is erased, late MediaUploaded/ParkingSpotCreated for that owner
 * must not persist results/outbox for that media; inbox still consumes the event. Manual
 * validation refuses erased requesters. An unaffected bystander still gets results.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ErasedUserLateWritePostgresIT {

    /** Bounded allowance for slow Docker pulls / contended scheduled runners (N-19 / #368). */
    private static final Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofMinutes(3);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withStartupTimeout(CONTAINER_STARTUP_TIMEOUT);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired private AiValidationApplicationService service;
    @Autowired private AccountErasureHandler erasure;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void lateMediaUploadedForErasedOwnerIsConsumedWithoutPersistingResult() {
        UUID erased = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:00:00Z");

        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        UUID lateEventId = UUID.randomUUID();
        UUID lateMedia = UUID.randomUUID();
        service.handleMediaUploaded(new MediaUploadedEvent(
                lateEventId, lateMedia, erased, "image/jpeg", 1024, "abc", now));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, lateEventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_validation_results WHERE media_id = ?", Long.class, lateMedia))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'AiValidationCompleted' "
                        + "AND payload::text LIKE ?",
                Long.class,
                "%" + lateMedia + "%"))
                .isZero();

        UUID bystanderEvent = UUID.randomUUID();
        UUID bystanderMedia = UUID.randomUUID();
        service.handleMediaUploaded(new MediaUploadedEvent(
                bystanderEvent, bystanderMedia, bystander, "image/jpeg", 1024, "xyz", now));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_validation_results WHERE media_id = ?", Long.class, bystanderMedia))
                .isEqualTo(1L);
    }

    @Test
    void lateParkingSpotCreatedForErasedOwnerIsConsumedWithoutPersistingResult() {
        UUID erased = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:15:00Z");
        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        UUID lateEventId = UUID.randomUUID();
        UUID lateMedia = UUID.randomUUID();
        service.handleParkingSpotCreated(new ParkingSpotCreatedEvent(
                lateEventId, UUID.randomUUID(), erased, lateMedia, 41.0, 29.0, "ACTIVE", now));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, lateEventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_validation_results WHERE media_id = ?", Long.class, lateMedia))
                .isZero();
    }

    @Test
    void manualValidationRefusesErasedRequester() {
        UUID erased = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:20:00Z");
        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        assertThatThrownBy(() -> service.createManualValidation(UUID.randomUUID(), null, erased))
                .isInstanceOf(AiValidationException.class)
                .extracting(ex -> ((AiValidationException) ex).errorCode())
                .isEqualTo(AiValidationErrorCode.FORBIDDEN);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_validation_results WHERE requested_by_user_id = ?",
                Long.class,
                erased))
                .isZero();
    }

    @Test
    void concurrentEraseAndLateWriteLeaveNoResultForErasedOwnerMedia() throws Exception {
        UUID user = UUID.randomUUID();
        UUID media = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:30:00Z");
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<?> erase = pool.submit(() -> {
                try {
                    bothReady.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    erasure.handle(new UserErasureRequestedEvent(
                            UUID.randomUUID(), UUID.randomUUID(), user, now));
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            Future<?> late = pool.submit(() -> {
                try {
                    bothReady.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    service.handleMediaUploaded(new MediaUploadedEvent(
                            UUID.randomUUID(), media, user, "image/jpeg", 1024, "race", now));
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            assertThat(bothReady.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            erase.get(30, TimeUnit.SECONDS);
            late.get(30, TimeUnit.SECONDS);
            if (failure.get() != null) {
                throw new AssertionError("concurrent erase/write failed", failure.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM erased_user_tombstones WHERE auth_user_id = ?", Long.class, user))
                .isEqualTo(1L);
        // Either the late write won before erase (result without requested_by) then erase
        // left it, or erase won and no result. Owner-linked requested_by must stay zero.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_validation_results WHERE requested_by_user_id = ?",
                Long.class,
                user))
                .isZero();
    }
}
