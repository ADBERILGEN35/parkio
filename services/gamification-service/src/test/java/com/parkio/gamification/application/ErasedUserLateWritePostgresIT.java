package com.parkio.gamification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gamification.application.event.ParkingSpotVerifiedEvent;
import com.parkio.gamification.application.event.UserErasureRequestedEvent;
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
 * N-07 / #357: after a user is erased, a late ParkingSpotVerified for that user must not
 * recreate progress/outbox rows; the inbox claim still consumes the event. An unaffected
 * bystander still receives awards. Concurrent erase+write cannot resurrect the erased user.
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

    @Autowired private GamificationApplicationService service;
    @Autowired private AccountErasureHandler erasure;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void lateVerifiedForErasedOwnerIsConsumedWithoutRecreatingOwnerData() {
        UUID erased = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:00:00Z");

        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        UUID lateEventId = UUID.randomUUID();
        service.handleParkingSpotVerified(new ParkingSpotVerifiedEvent(
                lateEventId, UUID.randomUUID(), erased, bystander, "AVAILABLE", now));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, lateEventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", Long.class, erased))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type IN "
                        + "('PointsEarned','PointsDeducted','UserLevelChanged','TrustScoreUpdated',"
                        + "'ContributionScoreUpdated')",
                Long.class,
                erased)).isZero();

        // Bystander (verifier) still receives the verifier award.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", Long.class, bystander))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT total_points FROM user_level_progress WHERE user_id = ?", Long.class, bystander))
                .isPositive();
    }

    @Test
    void concurrentEraseAndLateWriteLeaveNoErasedUserProgress() throws Exception {
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
                    service.handleParkingSpotVerified(new ParkingSpotVerifiedEvent(
                            UUID.randomUUID(), UUID.randomUUID(), user, UUID.randomUUID(), "AVAILABLE", now));
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
                "SELECT COUNT(*) FROM user_level_progress WHERE user_id = ?", Long.class, user))
                .isZero();
    }
}
