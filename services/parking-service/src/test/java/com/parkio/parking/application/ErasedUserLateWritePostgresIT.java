package com.parkio.parking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.application.event.UserErasureRequestedEvent;
import com.parkio.parking.application.port.MediaReadinessPort;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.testsupport.PostgisTestImages;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * N-07 / #362: after a user is erased, delayed create/search/view/session writes must not
 * recreate U-linked rows; a late moderator reject naming an erased moderator claims the inbox
 * but does not persist {@code rejected_by = U}. An unaffected bystander still works.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ErasedUserLateWritePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgisTestImages.dockerImageName());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired private ParkingApplicationService parking;
    @Autowired private ParkingSessionService sessions;
    @Autowired private AccountErasureHandler erasure;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private MediaReadinessPort mediaReadiness;

    @BeforeEach
    void mediaReady() {
        doNothing().when(mediaReadiness).ensureMediaReady(any(), any());
    }

    @Test
    void tombstoneBlocksCreateSearchViewAndSessionWrites() {
        UUID erased = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:00:00Z");

        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erased, now));

        assertThatThrownBy(() -> parking.createSpot(createCommand(erased)))
                .isInstanceOf(ParkingException.class)
                .extracting(ex -> ((ParkingException) ex).errorCode())
                .isEqualTo(ParkingErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spots WHERE owner_user_id = ?", Long.class, erased))
                .isZero();

        ParkingSpot bystanderSpot = parking.createSpot(createCommand(bystander));
        assertThat(bystanderSpot.ownerUserId()).isEqualTo(bystander);

        parking.searchNearby(new SearchNearbyQuery(erased, 41.0082, 28.9784, 1000.0, 10));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spot_search_logs WHERE searcher_user_id = ?",
                Long.class,
                erased))
                .isZero();

        parking.searchNearby(new SearchNearbyQuery(bystander, 41.0082, 28.9784, 1000.0, 10));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spot_search_logs WHERE searcher_user_id = ?",
                Long.class,
                bystander))
                .isEqualTo(1L);

        // Publish so the spot is visible for a non-owner viewer path.
        parking.applyAiValidationResult(
                bystanderSpot.id(), "PASSED", List.of(), UUID.randomUUID(), now);
        parking.getSpotForViewer(bystanderSpot.id(), erased, false);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spot_view_logs WHERE viewer_user_id = ?",
                Long.class,
                erased))
                .isZero();

        parking.getSpotForViewer(bystanderSpot.id(), bystander, false);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spot_view_logs WHERE viewer_user_id = ?",
                Long.class,
                bystander))
                .isEqualTo(1L);

        assertThatThrownBy(() -> sessions.startSession(
                        erased, ParkingSource.MANUAL, 41.0082, 28.9784, null, null))
                .isInstanceOf(ParkingException.class)
                .extracting(ex -> ((ParkingException) ex).errorCode())
                .isEqualTo(ParkingErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_sessions WHERE user_id = ?", Long.class, erased))
                .isZero();

        sessions.startSession(bystander, ParkingSource.MANUAL, 41.0082, 28.9784, null, null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_sessions WHERE user_id = ?", Long.class, bystander))
                .isEqualTo(1L);
    }

    @Test
    void lateModeratorRejectForErasedModeratorClaimsInboxWithoutPersistingRejectedBy() {
        UUID erasedModerator = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-14T12:15:00Z");

        erasure.handle(new UserErasureRequestedEvent(
                UUID.randomUUID(), UUID.randomUUID(), erasedModerator, now));

        ParkingSpot spot = parking.createSpot(createCommand(owner));
        UUID lateEventId = UUID.randomUUID();

        // Same order as ModerationActionsKafkaConsumer: claim inbox, then apply.
        int claimed = jdbc.update(
                """
                INSERT INTO inbox_events (id, event_type, processed_at)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                lateEventId,
                "ParkingSpotRejectedByModerator",
                Timestamp.from(now));
        assertThat(claimed).isEqualTo(1);
        parking.rejectSpotByModerator(spot.id(), lateEventId, now, erasedModerator, null);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, lateEventId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spots WHERE id = ? AND rejected_by = ?",
                Long.class,
                spot.id(),
                erasedModerator))
                .isZero();
    }

    @Test
    void concurrentEraseAndCreateLeaveNoSpotsForErasedUser() throws Exception {
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
                    try {
                        parking.createSpot(createCommand(user));
                    } catch (ParkingException ignored) {
                        // refused after erase won the fence
                    }
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
                "SELECT COUNT(*) FROM parking_spots WHERE owner_user_id = ?", Long.class, user))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_sessions WHERE user_id = ?", Long.class, user))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spot_search_logs WHERE searcher_user_id = ?",
                Long.class,
                user))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spot_view_logs WHERE viewer_user_id = ?",
                Long.class,
                user))
                .isZero();
    }

    private static CreateSpotCommand createCommand(UUID owner) {
        return new CreateSpotCommand(
                owner,
                UUID.randomUUID(),
                41.0082,
                28.9784,
                "Main St",
                "Nice spot",
                false,
                Set.of(VehicleType.SEDAN),
                ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL,
                Set.of());
    }
}
