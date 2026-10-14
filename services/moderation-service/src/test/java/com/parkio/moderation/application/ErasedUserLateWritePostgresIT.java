package com.parkio.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.moderation.application.command.CreateReportCommand;
import com.parkio.moderation.application.event.ParkingSpotRejectedEvent;
import com.parkio.moderation.application.event.ParkingSpotVerifiedEvent;
import com.parkio.moderation.application.event.UserErasureRequestedEvent;
import com.parkio.moderation.domain.ModerationReason;
import com.parkio.moderation.domain.ModerationTargetType;
import com.parkio.moderation.domain.exception.ModerationErrorCode;
import com.parkio.moderation.domain.exception.ModerationException;
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
 * N-07 / #360: after a user is erased, late owner-linked parking events must not recreate
 * moderation_cases for that owner; the inbox claim still consumes the event. Report/appeal
 * commands naming erased subjects are refused. An unaffected bystander still opens cases.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ErasedUserLateWritePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired private ModerationApplicationService service;
    @Autowired private AccountErasureHandler erasure;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void lateRejectedForErasedOwnerIsConsumedWithoutRecreatingOwnerCase() {
        UUID erased = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:00:00Z");

        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        UUID lateEventId = UUID.randomUUID();
        service.handleParkingSpotRejected(new ParkingSpotRejectedEvent(
                lateEventId, UUID.randomUUID(), erased, UUID.randomUUID(), "REJECTED", now));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, lateEventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM moderation_cases WHERE owner_user_id = ?", Long.class, erased))
                .isZero();

        UUID bystanderEvent = UUID.randomUUID();
        UUID bystanderSpot = UUID.randomUUID();
        service.handleParkingSpotRejected(new ParkingSpotRejectedEvent(
                bystanderEvent, bystanderSpot, bystander, UUID.randomUUID(), "REJECTED", now));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM moderation_cases WHERE owner_user_id = ?", Long.class, bystander))
                .isEqualTo(1L);
    }

    @Test
    void lateIllegalVerifiedForErasedOwnerIsConsumedWithoutRecreatingOwnerCase() {
        UUID erased = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:15:00Z");

        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        UUID lateEventId = UUID.randomUUID();
        service.handleParkingSpotVerified(new ParkingSpotVerifiedEvent(
                lateEventId, UUID.randomUUID(), erased, UUID.randomUUID(), "ILLEGAL_OR_RISKY", now));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, lateEventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM moderation_cases WHERE owner_user_id = ?", Long.class, erased))
                .isZero();
    }

    @Test
    void reportAndAppealCommandsRefuseErasedSubjects() {
        UUID erased = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:20:00Z");
        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        assertThatThrownBy(() -> service.createReport(new CreateReportCommand(
                erased, ModerationTargetType.PARKING_SPOT, UUID.randomUUID(),
                ModerationReason.OLD_PHOTO, "blurry")))
                .isInstanceOf(ModerationException.class)
                .extracting(ex -> ((ModerationException) ex).errorCode())
                .isEqualTo(ModerationErrorCode.FORBIDDEN);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_reports WHERE reporter_user_id = ?", Long.class, erased))
                .isZero();

        assertThatThrownBy(() -> service.createAppeal(erased, UUID.randomUUID(), "please restore"))
                .isInstanceOf(ModerationException.class)
                .extracting(ex -> ((ModerationException) ex).errorCode())
                .isEqualTo(ModerationErrorCode.FORBIDDEN);
    }

    @Test
    void concurrentEraseAndLateWriteLeaveNoErasedOwnerCase() throws Exception {
        UUID user = UUID.randomUUID();
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
                    service.handleParkingSpotRejected(new ParkingSpotRejectedEvent(
                            UUID.randomUUID(), UUID.randomUUID(), user, UUID.randomUUID(), "REJECTED", now));
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
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM moderation_cases WHERE owner_user_id = ?", Long.class, user))
                .isZero();
    }
}
