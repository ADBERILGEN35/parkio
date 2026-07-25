package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.port.MediaReadinessPort;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the moderation lifecycle against real PostgreSQL — the only place the V16
 * migration, its partial index and the {@code FOR UPDATE SKIP LOCKED} claim queries
 * actually execute (unit tests run on H2 with Flyway disabled).
 *
 * <p>The invariant under test is the one the defect violated: a spot's advertised lifetime
 * is never consumed while it waits on moderation, and it starts exactly once, at publication.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ModerationLifecyclePostgisIT {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_parking_lifecycle_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGIS::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.moderation-timeout.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        // Short windows so the timeout/retry paths are reachable without sleeping.
        registry.add("parkio.parking.moderation.validation-timeout", () -> "1ms");
        registry.add("parkio.parking.moderation.validation-retry-backoff", () -> "1ms");
        registry.add("parkio.parking.moderation.max-validation-attempts", () -> "2");
        registry.add("parkio.parking.moderation.review-timeout", () -> "1ms");
        registry.add("parkio.parking.moderation.max-publishable-age", () -> "30m");
        registry.add("parkio.parking.moderation.active-duration", () -> "10m");
    }

    @Autowired
    private ParkingApplicationService parking;

    @Autowired
    private ParkingSpotRepository spots;

    @Autowired
    private JdbcTemplate jdbc;

    /** Media readiness is a cross-service HTTP call; the lifecycle under test is local. */
    @MockBean
    private MediaReadinessPort mediaReadiness;

    @BeforeEach
    void clearSpots() {
        jdbc.update("DELETE FROM parking_spot_status_history");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM parking_spots");
    }

    @Test
    void migrationAddsLifecycleColumnsAndThePartialTimeoutIndex() {
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'parking_spots'
                  AND column_name IN ('activated_at', 'moderation_deadline_at',
                                      'moderation_attempts', 'moderation_decided_at',
                                      'moderation_request_id')
                """,
                Integer.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'parking_spots' AND column_name = 'expires_at'
                """,
                String.class))
                .isEqualTo("YES");
        assertThat(jdbc.queryForObject(
                """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'parking_spots' AND column_name = 'moderation_deadline_at'
                """,
                String.class))
                .isEqualTo("NO");
        assertThat(jdbc.queryForObject(
                """
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'parking_spots'
                  AND indexname = 'idx_parking_spots_moderation_deadline'
                """,
                String.class))
                .contains("PENDING_VALIDATION")
                .contains("PENDING_REVIEW");
    }

    @Test
    void pendingSpotHasNullExpiresAtAndSurvivesExpiryBatch() {
        ParkingSpot created = parking.createSpot(command());

        assertThat(reload(created.id()).expiresAt()).isNull();
        assertThat(reload(created.id()).activatedAt()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT expires_at FROM parking_spots WHERE id = ?",
                Instant.class, created.id()))
                .isNull();

        // The expiry batch must not see a pending spot however long it has waited.
        assertThat(parking.expireElapsedSpots(100)).isZero();
        assertThat(reload(created.id()).status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);
    }

    @Test
    void pendingSpotKeepsItsFullLifetimeOnFreshApproval() {
        ParkingSpot created = parking.createSpot(command());

        parking.applyAiValidationResult(created.id(), "PASSED", List.of(),
                UUID.randomUUID(), Instant.now());

        ParkingSpot published = reload(created.id());
        assertThat(published.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(published.activatedAt()).isNotNull();
        // The full advertised window is granted from publication, not from submission.
        assertThat(published.expiresAt()).isAfter(published.activatedAt());
        assertThat(expiredBeforeApprovedRows()).isZero();
    }

    @Test
    void approvalPastMaxPublishableAgeFailsAsStaleOnRealPostgres() {
        ParkingSpot created = parking.createSpot(command());
        Instant staleCreatedAt = Instant.now().minusSeconds(31 * 60);
        jdbc.update("UPDATE parking_spots SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(staleCreatedAt), created.id());

        parking.applyAiValidationResult(created.id(), "PASSED", List.of(),
                UUID.randomUUID(), Instant.now());

        ParkingSpot failed = reload(created.id());
        assertThat(failed.status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
        assertThat(failed.activatedAt()).isNull();
        assertThat(failed.expiresAt()).isNull();
        assertThat(outboxEventTypes()).contains("ParkingSpotReviewFailed");
    }

    @Test
    void historicalExpiredFromPendingIsNotAutoRescuedByV16Semantics() {
        // Seed the exact signature the old V16 rescue targeted. V16 no longer mutates
        // these automatically — they stay EXPIRED until the ops remediation script runs.
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(3600);
        Instant expiredAt = Instant.now().minusSeconds(3000);
        insertRawSpot(id, "EXPIRED", createdAt, expiredAt, null);
        jdbc.update("""
                INSERT INTO parking_spot_status_history
                    (id, spot_id, previous_status, new_status, reason, created_at)
                VALUES (?, ?, 'PENDING_VALIDATION', 'EXPIRED', 'EXPIRED', ?)
                """,
                UUID.randomUUID(), id, java.sql.Timestamp.from(expiredAt));

        int rescueCandidates = jdbc.queryForObject("""
                SELECT count(*) FROM parking_spots s
                 WHERE s.status = 'EXPIRED'
                   AND EXISTS (
                       SELECT 1 FROM parking_spot_status_history h
                        WHERE h.spot_id = s.id
                          AND h.new_status = 'EXPIRED'
                          AND h.previous_status IN ('PENDING_VALIDATION', 'PENDING_REVIEW')
                   )
                """, Integer.class);

        assertThat(rescueCandidates).isEqualTo(1);
        assertThat(reload(id).status()).isEqualTo(ParkingSpotStatus.EXPIRED);
        // Approval must not resurrect a terminal EXPIRED row.
        parking.approveSpotByModerator(id, UUID.randomUUID(), Instant.now());
        assertThat(reload(id).status()).isEqualTo(ParkingSpotStatus.EXPIRED);
    }

    @Test
    void overdueValidationIsClaimedRetriedThenFailedTerminally() {
        ParkingSpot created = parking.createSpot(command());

        // maxValidationAttempts=2, so two retries then a terminal failure.
        assertThat(parking.processModerationTimeouts(100)).isEqualTo(1);
        assertThat(parking.processModerationTimeouts(100)).isEqualTo(1);
        assertThat(reload(created.id()).moderationAttempts()).isEqualTo(2);
        assertThat(reload(created.id()).status()).isEqualTo(ParkingSpotStatus.PENDING_VALIDATION);

        assertThat(parking.processModerationTimeouts(100)).isEqualTo(1);
        assertThat(reload(created.id()).status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);

        // Retry requests and the terminal failure both travel through the outbox — the
        // event-driven path is preserved, no service is called directly.
        assertThat(outboxEventTypes())
                .contains("ParkingSpotModerationRetryRequested", "ParkingSpotReviewFailed");
    }

    @Test
    void reviewFailedSpotCannotBeResurrectedByALaterVerdict() {
        ParkingSpot created = parking.createSpot(command());
        for (int i = 0; i < 3; i++) {
            parking.processModerationTimeouts(100);
        }
        assertThat(reload(created.id()).status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);

        parking.approveSpotByModerator(created.id(), UUID.randomUUID(), Instant.now());
        parking.applyAiValidationResult(created.id(), "PASSED", List.of(),
                UUID.randomUUID(), Instant.now());

        assertThat(reload(created.id()).status()).isEqualTo(ParkingSpotStatus.REVIEW_FAILED);
    }

    @Test
    void duplicateVerdictsDoNotRestartTheLifetime() {
        ParkingSpot created = parking.createSpot(command());
        parking.applyAiValidationResult(created.id(), "PASSED", List.of(),
                UUID.randomUUID(), Instant.now());
        Instant firstExpiry = reload(created.id()).expiresAt();
        Instant firstActivation = reload(created.id()).activatedAt();

        parking.applyAiValidationResult(created.id(), "PASSED", List.of(),
                UUID.randomUUID(), Instant.now());
        parking.approveSpotByModerator(created.id(), UUID.randomUUID(), Instant.now());

        assertThat(reload(created.id()).expiresAt()).isEqualTo(firstExpiry);
        assertThat(reload(created.id()).activatedAt()).isEqualTo(firstActivation);
    }

    @Test
    void moderatorApprovalSharesPublicationPathWithAiApproval() {
        ParkingSpot created = parking.createSpot(command());
        parking.applyAiValidationResult(created.id(), "WARNING", List.of(),
                UUID.randomUUID(), Instant.now());
        assertThat(reload(created.id()).status()).isEqualTo(ParkingSpotStatus.PENDING_REVIEW);
        assertThat(reload(created.id()).expiresAt()).isNull();

        Instant approvedAt = Instant.now();
        parking.approveSpotByModerator(created.id(), UUID.randomUUID(), approvedAt);

        ParkingSpot published = reload(created.id());
        assertThat(published.status()).isEqualTo(ParkingSpotStatus.ACTIVE);
        assertThat(published.activatedAt()).isNotNull();
        assertThat(published.expiresAt()).isAfter(published.activatedAt());
        assertThat(outboxEventTypes()).contains("ParkingSpotActivated");
    }

    private void insertRawSpot(UUID id, String status, Instant createdAt, Instant expiresAt,
                               Instant activatedAt) {
        jdbc.update("""
                INSERT INTO parking_spots (
                    id, owner_user_id, media_id, latitude, longitude, location,
                    address_text, description, manual_location_edited, suitable_vehicle_types,
                    parking_context, legal_status, violation_reasons, status,
                    confidence_score, verification_count, filled_report_count,
                    expires_at, created_at, updated_at, version,
                    activated_at, moderation_deadline_at, moderation_attempts
                ) VALUES (
                    ?, ?, ?, 41.0082, 28.9784,
                    ST_SetSRID(ST_MakePoint(28.9784, 41.0082), 4326)::geography,
                    'IT', null, false, 'SEDAN', 'STREET_PARKING', 'LEGAL', '',
                    ?, 1.0, 0, 0, ?, ?, ?, 0, ?, ?, 0
                )
                """,
                id, UUID.randomUUID(), UUID.randomUUID(), status,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt),
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt),
                activatedAt == null ? null : java.sql.Timestamp.from(activatedAt),
                java.sql.Timestamp.from(createdAt));
    }

    private ParkingSpot reload(UUID spotId) {
        return spots.findById(spotId).orElseThrow();
    }

    private int expiredBeforeApprovedRows() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM parking_spots WHERE status = 'EXPIRED' AND activated_at IS NULL",
                Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> outboxEventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox_events", String.class);
    }

    private static CreateSpotCommand command() {
        return new CreateSpotCommand(UUID.randomUUID(), UUID.randomUUID(), 41.0082, 28.9784,
                "Lifecycle IT", null, false, Set.of(VehicleType.SEDAN),
                ParkingContext.STREET_PARKING, LegalStatus.LEGAL, Set.of());
    }
}
