package com.parkio.parking.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.ParkingSessionHistoryCursor;
import com.parkio.parking.application.ParkingSessionHistoryPage;
import com.parkio.parking.application.ParkingSessionService;
import com.parkio.parking.application.ParkingSessionStaleRowProcessor;
import com.parkio.parking.application.port.ParkingSessionRepository;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSession;
import com.parkio.parking.domain.ParkingSessionCompletionReason;
import com.parkio.parking.domain.ParkingSessionCompletionType;
import com.parkio.parking.domain.ParkingSessionStatus;
import com.parkio.parking.domain.ParkingSessionStalePolicy;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.lifecycle.ParkingExpiryJob;
import com.parkio.parking.infrastructure.lifecycle.ParkingSessionStaleCompletionJob;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ParkingSessionPostgisIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres");
    private static final Instant BASE_TIME = Instant.parse("2026-07-21T09:00:00Z");
    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_parking_session_it")
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
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
    }

    @Autowired
    private ParkingSessionRepository sessions;

    @Autowired
    private ParkingSessionService sessionService;

    @Autowired
    private ParkingSessionStaleRowProcessor staleRows;

    @Autowired
    private ParkingSessionStaleCompletionJob staleCompletionJob;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Autowired
    private ParkingApplicationService parkingApplicationService;

    @MockitoSpyBean
    private ParkingSessionRepositoryAdapter repositoryAdapter;

    @MockitoSpyBean
    private ParkingSpotRepositoryAdapter spotRepositoryAdapter;

    private TransactionTemplate transaction;

    @BeforeEach
    void clearSessions() {
        jdbc.update("DELETE FROM idempotency_records");
        jdbc.update("DELETE FROM parking_spot_verifications");
        jdbc.update("DELETE FROM parking_spot_status_history");
        jdbc.update("DELETE FROM parking_spot_view_logs");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM parking_sessions");
        jdbc.update("DELETE FROM parking_spots");
        clearInvocations(repositoryAdapter, spotRepositoryAdapter);
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void flywayAppliesV15AndHibernateValidatesTheMapping() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '15' AND success",
                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'parking_sessions' AND column_name = 'started_at'
                """,
                String.class))
                .isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject(
                """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'parking_sessions' AND column_name = 'parking_source'
                """,
                String.class))
                .isEqualTo("NO");
        var estimatedFeeColumn = jdbc.queryForMap(
                """
                SELECT data_type, numeric_precision, numeric_scale, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'parking_sessions' AND column_name = 'estimated_fee'
                """);
        assertThat(estimatedFeeColumn.get("data_type")).isEqualTo("numeric");
        assertThat(((Number) estimatedFeeColumn.get("numeric_precision")).intValue()).isEqualTo(12);
        assertThat(((Number) estimatedFeeColumn.get("numeric_scale")).intValue()).isEqualTo(2);
        assertThat(estimatedFeeColumn.get("is_nullable")).isEqualTo("YES");
        assertThat(jdbc.queryForObject(
                """
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'parking_sessions'
                  AND indexname = 'uq_parking_sessions_active_user'
                """,
                String.class))
                .containsIgnoringCase("UNIQUE")
                .contains("WHERE")
                .contains("ACTIVE");
        assertThat(jdbc.queryForObject(
                """
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'parking_sessions'
                  AND indexname = 'idx_parking_sessions_user_history'
                """,
                String.class))
                .contains("started_at DESC, id DESC");
    }

    @Test
    void flywayAppliesV17AndV18StaleLifecycleColumnsAndIndexes() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '17' AND success",
                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '18' AND success",
                Integer.class))
                .isEqualTo(1);

        for (String column : List.of(
                "last_confirmed_at", "reminder_stage", "completion_reason", "completion_type")) {
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_name = 'parking_sessions' AND column_name = ?
                    """,
                    Integer.class,
                    column))
                    .as("column %s", column)
                    .isEqualTo(1);
        }

        assertThat(jdbc.queryForObject(
                """
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'parking_sessions' AND column_name = 'last_confirmed_at'
                """,
                String.class))
                .startsWith("timestamp");
        assertThat(jdbc.queryForObject(
                """
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'parking_sessions' AND column_name = 'reminder_stage'
                """,
                String.class))
                .isEqualTo("smallint");

        assertThat(jdbc.queryForObject(
                """
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'parking_sessions'
                  AND indexname = 'idx_parking_sessions_stale_active'
                """,
                String.class))
                .isEqualTo("idx_parking_sessions_stale_active");
        assertThat(jdbc.queryForObject(
                """
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'parking_sessions'
                  AND indexname = 'idx_parking_sessions_reminder_candidates'
                """,
                String.class))
                .isEqualTo("idx_parking_sessions_reminder_candidates");
        assertThat(jdbc.queryForObject(
                """
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'parking_sessions'
                  AND indexname = 'idx_parking_sessions_terminal_ended'
                """,
                String.class))
                .isEqualTo("idx_parking_sessions_terminal_ended");
    }

    @Test
    void postgresCandidateQueriesHandleOptionalReminderThresholdBoundariesAndBatching() {
        Instant boundary = clock.instant().minus(Duration.ofHours(24));
        ParkingSession oldest = saveActiveSession(boundary.minusSeconds(2));
        ParkingSession exactBoundary = saveActiveSession(boundary);
        ParkingSession tooRecent = saveActiveSession(boundary.plusSeconds(1));

        assertThat(sessions.findReminderCandidates(0, boundary, null, 2))
                .extracting(ParkingSession::getId)
                .containsExactly(oldest.getId(), exactBoundary.getId());
        assertThat(sessions.findReminderCandidates(0, boundary, null, 1))
                .extracting(ParkingSession::getId)
                .containsExactly(oldest.getId());

        assertThat(sessions.findReminderCandidates(0, boundary, boundary.minusSeconds(1), 10))
                .extracting(ParkingSession::getId)
                .containsExactly(oldest.getId());
        assertThat(sessions.findReminderCandidates(0, boundary, boundary, 10))
                .extracting(ParkingSession::getId)
                .containsExactly(oldest.getId(), exactBoundary.getId());

        assertThat(sessions.findStaleActiveCandidates(boundary, boundary, 10))
                .extracting(ParkingSession::getId)
                .containsExactly(oldest.getId(), exactBoundary.getId());
        assertThat(sessions.findStaleActiveCandidates(
                        boundary.minus(Duration.ofDays(1)),
                        boundary.minus(Duration.ofDays(1)),
                        10))
                .isEmpty();
        assertThat(sessions.findReminderCandidates(0, boundary.minus(Duration.ofDays(1)), null, 10))
                .isEmpty();

        assertThat(sessions.findReminderCandidates(0, boundary, null, 10))
                .extracting(ParkingSession::getId)
                .doesNotContain(tooRecent.getId());
    }

    @Test
    void staleSchedulerRunsRepeatedTicksWithoutSqlState42P18OrFailureCounterIncrease() {
        Instant now = clock.instant();
        ParkingSession firstReminder = saveActiveSession(now.minus(Duration.ofHours(25)));
        ParkingSession secondReminder = saveActiveSession(now.minus(Duration.ofHours(50)));
        transaction.executeWithoutResult(status -> {
            ParkingSession loaded = sessions.findById(secondReminder.getId()).orElseThrow();
            loaded.markReminderSent(
                    com.parkio.parking.domain.ParkingSessionReminderStage.FIRST,
                    now.minus(Duration.ofHours(25)));
            sessions.save(loaded);
        });
        ParkingSession autoComplete = saveActiveSession(now.minus(Duration.ofHours(73)));

        double failuresBefore = meterRegistry
                .get("parking.sessions.scheduler.failed")
                .counter()
                .count();

        staleCompletionJob.processStaleSessions();
        int outboxAfterFirstTick = jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id IN (?, ?, ?)
                  AND event_type IN ('ParkingSessionReminderRequested', 'ParkingSessionCompleted')
                """,
                Integer.class,
                firstReminder.getId(),
                secondReminder.getId(),
                autoComplete.getId());
        staleCompletionJob.processStaleSessions();

        assertThat(meterRegistry
                        .get("parking.sessions.scheduler.failed")
                        .counter()
                        .count())
                .isEqualTo(failuresBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id IN (?, ?, ?)
                  AND event_type IN ('ParkingSessionReminderRequested', 'ParkingSessionCompleted')
                """,
                Integer.class,
                firstReminder.getId(),
                secondReminder.getId(),
                autoComplete.getId()))
                .isEqualTo(outboxAfterFirstTick);
        assertThat(outboxAfterFirstTick).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM parking_sessions WHERE id = ?",
                String.class,
                autoComplete.getId()))
                .isEqualTo(ParkingSessionStatus.COMPLETED.name());
        assertThat(jdbc.queryForObject(
                "SELECT reminder_stage FROM parking_sessions WHERE id = ?",
                Integer.class,
                firstReminder.getId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT reminder_stage FROM parking_sessions WHERE id = ?",
                Integer.class,
                secondReminder.getId()))
                .isEqualTo(2);
    }

    @Test
    void manualCompleteRacesSchedulerAutoCompleteAndExactlyOneWins() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant started = Instant.now().minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minus(Duration.ofMinutes(5));
        ParkingSession created = transaction.execute(status -> sessions.save(ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, started)));
        assertThat(created).isNotNull();
        UUID sessionId = created.getId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            Future<String> manual = executor.submit(() -> {
                await(barrier);
                try {
                    sessionService.completeSession(userId, sessionId);
                    return "MANUAL";
                } catch (RuntimeException exception) {
                    return "MANUAL_FAIL:" + exception.getClass().getSimpleName();
                }
            });
            Future<String> auto = executor.submit(() -> {
                await(barrier);
                try {
                    return staleRows.tryAutoComplete(sessionId, Instant.now()) ? "AUTO" : "SKIPPED";
                } catch (RuntimeException exception) {
                    return "SKIPPED";
                }
            });
            outcomes.add(manual.get(30, TimeUnit.SECONDS));
            outcomes.add(auto.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        long wins = outcomes.stream()
                .filter(outcome -> outcome.equals("MANUAL") || outcome.equals("AUTO"))
                .count();
        assertThat(wins).isEqualTo(1);

        ParkingSession persisted = transaction.execute(status -> sessions
                .findByIdAndUserId(sessionId, userId)
                .orElseThrow());
        assertThat(persisted.getStatus()).isEqualTo(ParkingSessionStatus.COMPLETED);
        if (outcomes.contains("MANUAL")) {
            assertThat(persisted.getCompletionType()).isEqualTo(ParkingSessionCompletionType.MANUAL);
            assertThat(persisted.getCompletionReason()).isEqualTo(ParkingSessionCompletionReason.MANUAL);
        } else {
            assertThat(persisted.getCompletionType()).isEqualTo(ParkingSessionCompletionType.AUTO);
            assertThat(persisted.getCompletionReason())
                    .isEqualTo(ParkingSessionCompletionReason.AUTO_TIMEOUT);
        }
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type = 'ParkingSessionCompleted'
                """,
                Integer.class,
                sessionId)).isEqualTo(1);
    }

    @Test
    void confirmActiveRacesSchedulerAutoCompleteAndExactlyOneMutationWins() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant started = Instant.now().minus(ParkingSessionStalePolicy.defaults().autoCompleteAfter())
                .minus(Duration.ofMinutes(5));
        ParkingSession created = transaction.execute(status -> sessions.save(ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, started)));
        assertThat(created).isNotNull();
        UUID sessionId = created.getId();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            Future<String> confirm = executor.submit(() -> {
                await(barrier);
                try {
                    sessionService.confirmActiveSession(userId, sessionId);
                    return "CONFIRM";
                } catch (RuntimeException exception) {
                    return "CONFIRM_FAIL:" + exception.getClass().getSimpleName();
                }
            });
            Future<String> auto = executor.submit(() -> {
                await(barrier);
                try {
                    return staleRows.tryAutoComplete(sessionId, Instant.now()) ? "AUTO" : "SKIPPED";
                } catch (RuntimeException exception) {
                    return "SKIPPED";
                }
            });
            outcomes.add(confirm.get(30, TimeUnit.SECONDS));
            outcomes.add(auto.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        long wins = outcomes.stream()
                .filter(outcome -> outcome.equals("CONFIRM") || outcome.equals("AUTO"))
                .count();
        assertThat(wins).isEqualTo(1);

        ParkingSession persisted = transaction.execute(status -> sessions
                .findByIdAndUserId(sessionId, userId)
                .orElseThrow());
        if (outcomes.contains("AUTO")) {
            assertThat(persisted.getStatus()).isEqualTo(ParkingSessionStatus.COMPLETED);
            assertThat(persisted.getCompletionReason())
                    .isEqualTo(ParkingSessionCompletionReason.AUTO_TIMEOUT);
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM outbox_events
                    WHERE aggregate_id = ? AND event_type = 'ParkingSessionCompleted'
                    """,
                    Integer.class,
                    sessionId)).isEqualTo(1);
        } else {
            assertThat(persisted.isActive()).isTrue();
            assertThat(persisted.getLastConfirmedAt()).isNotNull();
            assertThat(jdbc.queryForObject(
                    """
                    SELECT count(*) FROM outbox_events
                    WHERE aggregate_id = ? AND event_type = 'ParkingSessionCompleted'
                    """,
                    Integer.class,
                    sessionId)).isZero();
        }
    }

    @Test
    void databaseEnforcesPartialActiveUniquenessAndLifecycleConstraints() {
        UUID userId = UUID.randomUUID();
        insertSession(
                UUID.randomUUID(), userId, ParkingSessionStatus.ACTIVE,
                BASE_TIME, null, null, 41.0082, 28.9784);

        assertThatThrownBy(() -> insertSession(
                UUID.randomUUID(), userId, ParkingSessionStatus.ACTIVE,
                BASE_TIME.plusSeconds(1), null, null, 41.0, 29.0))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertSession(
                UUID.randomUUID(), userId, ParkingSessionStatus.COMPLETED,
                BASE_TIME.minusSeconds(60), BASE_TIME, null, 41.0, 29.0);
        insertSession(
                UUID.randomUUID(), userId, ParkingSessionStatus.CANCELLED,
                BASE_TIME.minusSeconds(120), BASE_TIME.minusSeconds(30), null, 41.0, 29.0);

        assertThatThrownBy(() -> insertSession(
                UUID.randomUUID(), UUID.randomUUID(), ParkingSessionStatus.ACTIVE,
                BASE_TIME, BASE_TIME.plusSeconds(1), null, 41.0, 29.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSession(
                UUID.randomUUID(), UUID.randomUUID(), ParkingSessionStatus.COMPLETED,
                BASE_TIME, null, null, 41.0, 29.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSession(
                UUID.randomUUID(), UUID.randomUUID(), ParkingSessionStatus.CANCELLED,
                BASE_TIME, BASE_TIME.minusSeconds(1), null, 41.0, 29.0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseGeneratesPostgisLocationAndRejectsImmutableChanges() {
        UUID id = UUID.randomUUID();
        insertSession(
                id, UUID.randomUUID(), ParkingSessionStatus.ACTIVE,
                BASE_TIME, null, new BigDecimal("25.50"), 41.0082, 28.9784);

        var location = jdbc.queryForMap(
                """
                SELECT ST_Y(location::geometry) AS latitude,
                       ST_X(location::geometry) AS longitude
                FROM parking_sessions WHERE id = ?
                """,
                id);
        assertThat(((Number) location.get("latitude")).doubleValue()).isEqualTo(41.0082);
        assertThat(((Number) location.get("longitude")).doubleValue()).isEqualTo(28.9784);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE parking_sessions SET latitude = ? WHERE id = ?", 41.1, id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                """
                UPDATE parking_sessions
                SET location = ST_SetSRID(ST_MakePoint(29.1, 41.1), 4326)::geography
                WHERE id = ?
                """,
                id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE parking_sessions SET created_at = ? WHERE id = ?",
                utc(BASE_TIME.plusSeconds(1)),
                id))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.update(
                """
                UPDATE parking_sessions
                SET status = 'COMPLETED', ended_at = ?, updated_at = ?, version = version + 1
                WHERE id = ?
                """,
                utc(BASE_TIME.plusSeconds(60)),
                utc(BASE_TIME.plusSeconds(60)),
                id))
                .isEqualTo(1);
    }

    @Test
    void databaseAcceptsExactNumericTwelveTwoBoundaries() {
        UUID userId = UUID.randomUUID();
        ParkingSession created = transaction.execute(status -> sessions.save(ParkingSession.start(
                userId,
                ParkingSource.MANUAL,
                41.0,
                29.0,
                new BigDecimal("9999999999.99"),
                null,
                BASE_TIME)));
        assertThat(created).isNotNull();

        ParkingSession persisted = transaction.execute(status -> sessions
                .findByIdAndUserId(created.getId(), userId)
                .orElseThrow());
        assertThat(persisted).isNotNull();
        assertThat(persisted.getEstimatedFee()).isEqualByComparingTo("9999999999.99");
        assertThat(persisted.getEstimatedFee()).hasScaleOf(2);

        assertThatThrownBy(() -> insertSession(
                UUID.randomUUID(), UUID.randomUUID(), ParkingSessionStatus.ACTIVE,
                BASE_TIME, null, new BigDecimal("-0.01"), 41.0, 29.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSession(
                UUID.randomUUID(), UUID.randomUUID(), ParkingSessionStatus.ACTIVE,
                BASE_TIME, null, new BigDecimal("10000000000.00"), 41.0, 29.0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void optimisticLockingRejectsAStaleTerminalTransition() {
        UUID userId = UUID.randomUUID();
        ParkingSession created = transaction.execute(status -> sessions.save(ParkingSession.start(
                userId, ParkingSource.MANUAL, 41.0, 29.0, null, null, BASE_TIME)));
        assertThat(created).isNotNull();

        ParkingSession stale = transaction.execute(status -> sessions
                .findByIdAndUserId(created.getId(), userId)
                .orElseThrow());
        assertThat(stale).isNotNull();

        transaction.executeWithoutResult(status -> {
            ParkingSession current = sessions.findByIdAndUserId(created.getId(), userId).orElseThrow();
            current.complete(BASE_TIME.plusSeconds(60), com.parkio.parking.domain.ParkingSessionCompletionType.MANUAL);
            sessions.save(current);
        });

        stale.cancel(BASE_TIME.plusSeconds(90));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> sessions.save(stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        ParkingSession persisted = transaction.execute(status -> sessions
                .findByIdAndUserId(created.getId(), userId)
                .orElseThrow());
        assertThat(persisted).isNotNull();
        assertThat(persisted.getStatus()).isEqualTo(ParkingSessionStatus.COMPLETED);
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    @Test
    void historyQueryIsBoundedTerminalOnlyAndDeterministicallyOrdered() {
        UUID userId = UUID.randomUUID();
        Instant sameStartedAt = BASE_TIME.minusSeconds(60);
        UUID highestId = UUID.fromString("f0000000-0000-0000-0000-000000000001");
        UUID middleId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID lowestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID olderId = UUID.fromString("90000000-0000-0000-0000-000000000001");

        insertSession(
                highestId, userId, ParkingSessionStatus.COMPLETED,
                sameStartedAt, BASE_TIME, null, 41.0, 29.0);
        insertSession(
                middleId, userId, ParkingSessionStatus.CANCELLED,
                sameStartedAt, BASE_TIME, null, 41.0, 29.0);
        insertSession(
                lowestId, userId, ParkingSessionStatus.COMPLETED,
                sameStartedAt, BASE_TIME, null, 41.0, 29.0);
        insertSession(
                olderId, userId, ParkingSessionStatus.COMPLETED,
                sameStartedAt.minusSeconds(1), BASE_TIME, null, 41.0, 29.0);
        UUID activeId = UUID.randomUUID();
        insertSession(
                activeId, userId, ParkingSessionStatus.ACTIVE,
                BASE_TIME, null, null, 41.0, 29.0);

        ParkingSessionHistoryPage firstPage = sessions.findHistoryByUserId(userId, 2);

        assertThat(firstPage.sessions())
                .extracting(ParkingSession::getId)
                .containsExactly(highestId, middleId);
        assertThat(firstPage.hasNext()).isTrue();

        ParkingSessionHistoryCursor cursor = firstPage.nextCursor().orElseThrow();
        ParkingSessionHistoryPage secondPage = sessions.findHistoryByUserId(userId, cursor, 2);

        assertThat(secondPage.sessions())
                .extracting(ParkingSession::getId)
                .containsExactly(lowestId, olderId)
                .doesNotContain(activeId);
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.nextCursor()).isEmpty();
    }

    @Test
    void manualStartPersistsMaximumFeeAndRejectsRoundingThroughSupportedHttpPath()
            throws Exception {
        UUID userId = UUID.randomUUID();

        MvcResult created = startApi(userId, "9999999999.99")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parkingSource").value("MANUAL"))
                .andExpect(jsonPath("$.estimatedFee").value("9999999999.99"))
                .andReturn();
        UUID sessionId = UUID.fromString(JsonPath.read(
                created.getResponse().getContentAsString(), "$.id"));

        assertThat(jdbc.queryForObject(
                "SELECT estimated_fee FROM parking_sessions WHERE id = ?",
                BigDecimal.class,
                sessionId))
                .isEqualByComparingTo("9999999999.99");

        startApi(UUID.randomUUID(), "1.234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE estimated_fee = 1.23",
                Integer.class))
                .isZero();
    }

    @Test
    void sequentialDuplicateAndConcurrentIndexViolationUseSameStableDomainConflict()
            throws Exception {
        UUID sequentialUser = UUID.randomUUID();
        startApi(sequentialUser, null).andExpect(status().isCreated());
        startApi(sequentialUser, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_PARKING_SESSION_EXISTS"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        UUID concurrentApiUser = UUID.randomUUID();
        CyclicBarrier apiBarrier = new CyclicBarrier(2);
        Callable<String> apiAttempt = () -> {
            await(apiBarrier);
            MvcResult result = startApi(concurrentApiUser, null).andReturn();
            if (result.getResponse().getStatus() == 201) {
                return "201";
            }
            return result.getResponse().getStatus() + ":"
                    + JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code");
        };

        UUID concurrentUser = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<String> attempt = () -> {
            try {
                transaction.executeWithoutResult(status -> {
                    await(barrier);
                    sessions.save(ParkingSession.start(
                            concurrentUser,
                            ParkingSource.MANUAL,
                            41.0,
                            29.0,
                            null,
                            null,
                            BASE_TIME));
                });
                return "CREATED";
            } catch (ParkingException exception) {
                return exception.errorCode().name();
            }
        };

        try {
            Future<String> firstApi = executor.submit(apiAttempt);
            Future<String> secondApi = executor.submit(apiAttempt);
            assertThat(List.of(
                            firstApi.get(30, TimeUnit.SECONDS),
                            secondApi.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            "201", "409:" + ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS.name());

            Future<String> first = executor.submit(attempt);
            Future<String> second = executor.submit(attempt);
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(
                            "CREATED", ParkingErrorCode.ACTIVE_PARKING_SESSION_EXISTS.name());
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class,
                concurrentUser))
                .isEqualTo(1);
    }

    @Test
    void simultaneousSameKeyRequestsReplayOneCommittedMutation() throws Exception {
        UUID userId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        CountDownLatch firstSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        AtomicBoolean firstSave = new AtomicBoolean(true);
        clearInvocations(repositoryAdapter);
        doAnswer(invocation -> {
            if (firstSave.compareAndSet(true, false)) {
                firstSaveEntered.countDown();
                if (!releaseFirstSave.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the first session save");
                }
            }
            return invocation.callRealMethod();
        }).when(repositoryAdapter).save(any(ParkingSession.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() ->
                    startApi(userId, "25.00", idempotencyKey).andReturn());
            assertThat(firstSaveEntered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> retry = executor.submit(() ->
                    startApi(userId, "25.00", idempotencyKey).andReturn());
            awaitBlockedIdempotencyClaim();
            releaseFirstSave.countDown();

            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult retryResult = retry.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            UUID sessionId = sessionId(firstResult);
            assertThat(retryResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(retryResult.getResponse().getContentAsString())
                    .isEqualTo(firstResult.getResponse().getContentAsString());
            assertThat(sessionId(retryResult)).isEqualTo(sessionId);

            MvcResult storedReplay = startApi(userId, "25.00", idempotencyKey).andReturn();
            assertThat(storedReplay.getResponse().getStatus()).isEqualTo(201);
            assertThat(storedReplay.getResponse().getContentAsString())
                    .isEqualTo(firstResult.getResponse().getContentAsString());

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM parking_sessions WHERE user_id = ?",
                    Integer.class,
                    userId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM parking_sessions WHERE user_id = ? AND status = 'ACTIVE'",
                    Integer.class,
                    userId))
                    .isEqualTo(1);
            var idempotencyRecord = jdbc.queryForMap(
                    """
                    SELECT status, response_status, response_body
                    FROM idempotency_records
                    WHERE user_id = ? AND http_method = 'POST' AND idempotency_key = ?
                    """,
                    userId,
                    idempotencyKey);
            assertThat(idempotencyRecord.get("status")).isEqualTo("COMPLETED");
            assertThat(((Number) idempotencyRecord.get("response_status")).intValue()).isEqualTo(201);
            assertThat((String) idempotencyRecord.get("response_body"))
                    .contains(sessionId.toString());
            verify(repositoryAdapter, times(1)).save(any(ParkingSession.class));
        } finally {
            releaseFirstSave.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void communityClaimAtomicallyPersistsSpotSessionHistoryOutboxAndIdempotency()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID claimerId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(ownerId, ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult result = claimApi(claimerId, spot.id(), idempotencyKey)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.id").value(spot.id().toString()))
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("parkingSession");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM parking_spots WHERE id = ?", String.class, spot.id()))
                .isEqualTo("FILLED");
        var session = jdbc.queryForMap(
                """
                SELECT user_id, status, parking_source, latitude, longitude,
                       estimated_fee, reminder_at
                FROM parking_sessions
                WHERE user_id = ?
                """,
                claimerId);
        assertThat(session.get("user_id")).isEqualTo(claimerId);
        assertThat(session.get("status")).isEqualTo("ACTIVE");
        assertThat(session.get("parking_source")).isEqualTo("COMMUNITY");
        assertThat(((Number) session.get("latitude")).doubleValue()).isEqualTo(spot.latitude());
        assertThat(((Number) session.get("longitude")).doubleValue()).isEqualTo(spot.longitude());
        assertThat(session.get("estimated_fee")).isNull();
        assertThat(session.get("reminder_at")).isNull();
        assertCommittedCommunityClaim(spot.id(), claimerId, idempotencyKey);

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/active"), claimerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parkingSource").value("COMMUNITY"))
                .andExpect(jsonPath("$.latitude").value(spot.latitude()))
                .andExpect(jsonPath("$.longitude").value(spot.longitude()));
    }

    @Test
    void activeSessionConflictRollsBackEntireCommunityClaim() throws Exception {
        UUID claimerId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        startApi(claimerId, null).andExpect(status().isCreated());
        String idempotencyKey = UUID.randomUUID().toString();

        claimApi(claimerId, spot.id(), idempotencyKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_PARKING_SESSION_EXISTS"));

        assertRolledBackCommunityClaim(spot.id(), claimerId, idempotencyKey, 1);
    }

    @Test
    void failureAfterSessionFlushRollsBackSessionSpotHistoryOutboxAndIdempotency()
            throws Exception {
        UUID claimerId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        String idempotencyKey = UUID.randomUUID().toString();
        doThrow(new ObjectOptimisticLockingFailureException(ParkingSpot.class, spot.id()))
                .when(spotRepositoryAdapter)
                .save(argThat(candidate -> candidate.id().equals(spot.id())
                        && candidate.status() == ParkingSpotStatus.FILLED));

        claimApi(claimerId, spot.id(), idempotencyKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertRolledBackCommunityClaim(spot.id(), claimerId, idempotencyKey, 0);
    }

    @Test
    void expiredConsumedAndOwnerClaimsNeverCreateCommunitySessions() throws Exception {
        UUID actorId = UUID.randomUUID();
        ParkingSpot expired = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().minusSeconds(1));
        ParkingSpot consumed = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.FILLED, clock.instant().plusSeconds(600));
        ParkingSpot owned = insertSpot(
                actorId, ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));

        claimApi(actorId, expired.id(), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPOT_EXPIRED"));
        claimApi(actorId, consumed.id(), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPOT_NOT_CLAIMABLE"));
        claimApi(actorId, owned.id(), UUID.randomUUID().toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OWNER_CANNOT_CLAIM"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ?",
                Integer.class,
                actorId))
                .isZero();
    }

    @Test
    void simultaneousSameKeyCommunityClaimsReplayOneCommittedMutation() throws Exception {
        UUID claimerId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        String idempotencyKey = UUID.randomUUID().toString();
        CountDownLatch firstSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        AtomicBoolean firstSave = new AtomicBoolean(true);
        clearInvocations(repositoryAdapter);
        doAnswer(invocation -> {
            ParkingSession candidate = invocation.getArgument(0);
            if (candidate.getParkingSource() == ParkingSource.COMMUNITY
                    && firstSave.compareAndSet(true, false)) {
                firstSaveEntered.countDown();
                if (!releaseFirstSave.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release community session save");
                }
            }
            return invocation.callRealMethod();
        }).when(repositoryAdapter).save(any(ParkingSession.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() ->
                    claimApi(claimerId, spot.id(), idempotencyKey).andReturn());
            assertThat(firstSaveEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> retry = executor.submit(() ->
                    claimApi(claimerId, spot.id(), idempotencyKey).andReturn());
            awaitBlockedIdempotencyClaim();
            releaseFirstSave.countDown();

            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult retryResult = retry.get(30, TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(retryResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(retryResult.getResponse().getContentAsString())
                    .isEqualTo(firstResult.getResponse().getContentAsString());

            MvcResult storedReplay = claimApi(claimerId, spot.id(), idempotencyKey).andReturn();
            assertThat(storedReplay.getResponse().getStatus()).isEqualTo(200);
            assertThat(storedReplay.getResponse().getContentAsString())
                    .isEqualTo(firstResult.getResponse().getContentAsString());

            assertCommittedCommunityClaim(spot.id(), claimerId, idempotencyKey);
            verify(repositoryAdapter, times(1)).save(any(ParkingSession.class));
        } finally {
            releaseFirstSave.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void manualStartAndCommunityClaimRaceCommitExactlyOneCoherentSession() throws Exception {
        UUID userId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        String manualKey = UUID.randomUUID().toString();
        String claimKey = UUID.randomUUID().toString();
        CyclicBarrier sessionBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            ParkingSession candidate = invocation.getArgument(0);
            if (candidate.getUserId().equals(userId)) {
                await(sessionBarrier);
            }
            return invocation.callRealMethod();
        }).when(repositoryAdapter).save(any(ParkingSession.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        MvcResult manualResult;
        MvcResult claimResult;
        try {
            Future<MvcResult> manual = executor.submit(() ->
                    startApi(userId, null, manualKey).andReturn());
            Future<MvcResult> claim = executor.submit(() ->
                    claimApi(userId, spot.id(), claimKey).andReturn());
            manualResult = manual.get(30, TimeUnit.SECONDS);
            claimResult = claim.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ?",
                Integer.class,
                userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class,
                userId))
                .isEqualTo(1);
        String committedSource = jdbc.queryForObject(
                "SELECT parking_source FROM parking_sessions WHERE user_id = ?",
                String.class,
                userId);

        if ("MANUAL".equals(committedSource)) {
            assertThat(manualResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(claimResult.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(
                    claimResult.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("ACTIVE_PARKING_SESSION_EXISTS");
            assertCompletedIdempotency(userId, manualKey, 201);
            assertRolledBackCommunityClaim(spot.id(), userId, claimKey, 1);
        } else {
            assertThat(committedSource).isEqualTo("COMMUNITY");
            assertThat(claimResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(manualResult.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(
                    manualResult.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("ACTIVE_PARKING_SESSION_EXISTS");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM parking_spots WHERE id = ?", String.class, spot.id()))
                    .isEqualTo("FILLED");
            assertCommittedCommunityClaim(spot.id(), userId, claimKey);
            assertMissingIdempotency(userId, manualKey);
        }
    }

    @Test
    void communityClaimAndExpiryJobRaceCommitOnlyTheExpiredAggregate() throws Exception {
        UUID userId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().minusSeconds(1));
        String claimKey = UUID.randomUUID().toString();
        CountDownLatch expirySaveEntered = new CountDownLatch(1);
        CountDownLatch claimSaveEntered = new CountDownLatch(1);
        CountDownLatch releaseExpirySave = new CountDownLatch(1);
        AtomicBoolean firstExpiredSave = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ParkingSpot candidate = invocation.getArgument(0);
            if (candidate.id().equals(spot.id()) && candidate.status() == ParkingSpotStatus.EXPIRED) {
                if (firstExpiredSave.compareAndSet(true, false)) {
                    expirySaveEntered.countDown();
                    if (!releaseExpirySave.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release expiry-job persistence");
                    }
                } else {
                    claimSaveEntered.countDown();
                }
            }
            return invocation.callRealMethod();
        }).when(spotRepositoryAdapter).save(any(ParkingSpot.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        try {
            ParkingExpiryJob expiryJob = new ParkingExpiryJob(
                    parkingApplicationService, meterRegistry, 1);
            Future<?> expiry = executor.submit(expiryJob::expireElapsedSpots);
            assertThat(expirySaveEntered.await(10, TimeUnit.SECONDS)).isTrue();

            Future<MvcResult> claim = executor.submit(() ->
                    claimApi(userId, spot.id(), claimKey).andReturn());
            assertThat(claimSaveEntered.await(10, TimeUnit.SECONDS)).isTrue();
            MvcResult claimResult = claim.get(30, TimeUnit.SECONDS);
            assertThat(claimResult.getResponse().getStatus()).isEqualTo(409);
            assertThat((String) JsonPath.read(
                    claimResult.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("SPOT_EXPIRED");
            assertRolledBackCommunityClaim(spot.id(), userId, claimKey, 0);

            releaseExpirySave.countDown();
            expiry.get(30, TimeUnit.SECONDS);
        } finally {
            releaseExpirySave.countDown();
            executor.shutdownNow();
            meterRegistry.close();
        }

        assertThat(jdbc.queryForObject(
                "SELECT status FROM parking_spots WHERE id = ?", String.class, spot.id()))
                .isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ?",
                Integer.class,
                userId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_spot_status_history WHERE spot_id = ? AND reason = 'EXPIRED'",
                Integer.class,
                spot.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_spot_status_history WHERE spot_id = ? AND reason = 'CLAIMED'",
                Integer.class,
                spot.id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'ParkingSpotExpired'",
                Integer.class,
                spot.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'ParkingSpotClaimed'",
                Integer.class,
                spot.id()))
                .isZero();
        assertMissingIdempotency(userId, claimKey);
    }

    @Test
    void differentKeysForSameUserAndSpotCommitOneClaimAndOneIdempotencyRecord()
            throws Exception {
        UUID userId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        String firstKey = UUID.randomUUID().toString();
        String secondKey = UUID.randomUUID().toString();
        CyclicBarrier sessionBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            ParkingSession candidate = invocation.getArgument(0);
            if (candidate.getParkingSource() == ParkingSource.COMMUNITY) {
                await(sessionBarrier);
            }
            return invocation.callRealMethod();
        }).when(repositoryAdapter).save(any(ParkingSession.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        MvcResult firstResult;
        MvcResult secondResult;
        try {
            Future<MvcResult> first = executor.submit(() ->
                    claimApi(userId, spot.id(), firstKey).andReturn());
            Future<MvcResult> second = executor.submit(() ->
                    claimApi(userId, spot.id(), secondKey).andReturn());
            firstResult = first.get(30, TimeUnit.SECONDS);
            secondResult = second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(List.of(firstResult, secondResult))
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
        MvcResult conflict = firstResult.getResponse().getStatus() == 409
                ? firstResult
                : secondResult;
        assertThat((String) JsonPath.read(
                conflict.getResponse().getContentAsString(), "$.code"))
                .isEqualTo("ACTIVE_PARKING_SESSION_EXISTS");
        String committedKey = firstResult.getResponse().getStatus() == 200 ? firstKey : secondKey;
        String rolledBackKey = firstResult.getResponse().getStatus() == 409 ? firstKey : secondKey;

        assertThat(jdbc.queryForObject(
                "SELECT status FROM parking_spots WHERE id = ?", String.class, spot.id()))
                .isEqualTo("FILLED");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM parking_sessions
                WHERE user_id = ? AND status = 'ACTIVE' AND parking_source = 'COMMUNITY'
                """,
                Integer.class,
                userId))
                .isEqualTo(1);
        assertCommittedCommunityClaim(spot.id(), userId, committedKey);
        assertMissingIdempotency(userId, rolledBackKey);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM idempotency_records
                WHERE user_id = ? AND idempotency_key IN (?, ?)
                """,
                Integer.class,
                userId,
                firstKey,
                secondKey))
                .isEqualTo(1);
    }

    @Test
    void differentKeysForSameUserAreSerializedByActiveSessionConstraint() throws Exception {
        UUID claimerId = UUID.randomUUID();
        ParkingSpot firstSpot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        ParkingSpot secondSpot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        CyclicBarrier sessionBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            ParkingSession candidate = invocation.getArgument(0);
            if (candidate.getParkingSource() == ParkingSource.COMMUNITY) {
                await(sessionBarrier);
            }
            return invocation.callRealMethod();
        }).when(repositoryAdapter).save(any(ParkingSession.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> claimApi(
                    claimerId, firstSpot.id(), UUID.randomUUID().toString()).andReturn());
            Future<MvcResult> second = executor.submit(() -> claimApi(
                    claimerId, secondSpot.id(), UUID.randomUUID().toString()).andReturn());
            List<MvcResult> results = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult conflict = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat((String) JsonPath.read(
                    conflict.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("ACTIVE_PARKING_SESSION_EXISTS");
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class,
                claimerId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_spots WHERE id IN (?, ?) AND status = 'FILLED'",
                Integer.class,
                firstSpot.id(),
                secondSpot.id()))
                .isEqualTo(1);
    }

    @Test
    void twoUserClaimRaceCommitsOneCompleteAggregateOfChanges() throws Exception {
        ParkingSpot spot = insertSpot(
                UUID.randomUUID(), ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();
        CyclicBarrier spotSaveBarrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            ParkingSpot candidate = invocation.getArgument(0);
            if (candidate.id().equals(spot.id()) && candidate.status() == ParkingSpotStatus.FILLED) {
                await(spotSaveBarrier);
            }
            return invocation.callRealMethod();
        }).when(spotRepositoryAdapter).save(any(ParkingSpot.class));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> claimApi(
                    firstUser, spot.id(), UUID.randomUUID().toString()).andReturn());
            Future<MvcResult> second = executor.submit(() -> claimApi(
                    secondUser, spot.id(), UUID.randomUUID().toString()).andReturn());
            List<MvcResult> results = List.of(
                    first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertThat(results).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);
            MvcResult conflict = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            assertThat((String) JsonPath.read(
                    conflict.getResponse().getContentAsString(), "$.code"))
                    .isEqualTo("CONFLICT");
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE parking_source = 'COMMUNITY'",
                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_spot_status_history WHERE spot_id = ? AND reason = 'CLAIMED'",
                Integer.class,
                spot.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'ParkingSpotClaimed'",
                Integer.class,
                spot.id()))
                .isEqualTo(1);
    }

    @Test
    void httpLifecycleEnforcesOwnershipAndPersistsCompleteAndCancel() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID completedId = sessionId(startApi(owner, "25.00")
                .andExpect(status().isCreated())
                .andReturn());

        transitionApi(otherUser, completedId, "complete")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_FOUND"));
        transitionApi(owner, completedId, "complete")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty());

        UUID cancelledId = sessionId(startApi(owner, null)
                .andExpect(status().isCreated())
                .andReturn());
        transitionApi(owner, cancelledId, "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(jdbc.queryForList(
                "SELECT status FROM parking_sessions WHERE user_id = ? ORDER BY started_at, id",
                String.class,
                owner))
                .containsExactlyInAnyOrder("COMPLETED", "CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'ParkingSessionStarted'",
                Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'ParkingSessionCompleted'",
                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'ParkingSessionCancelled'",
                Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                 WHERE event_type = 'ParkingSessionCompleted'
                   AND aggregate_type = 'ParkingSession'
                   AND aggregate_id = ?
                   AND payload::text NOT LIKE '%latitude%'
                   AND payload::text NOT LIKE '%longitude%'
                """,
                Integer.class,
                completedId))
                .isEqualTo(1);
    }

    @Test
    void httpHistoryPreservesDeterministicKeysetOrderingAndOwnership() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant sameStartedAt = BASE_TIME.minusSeconds(60);
        UUID highestId = UUID.fromString("f0000000-0000-0000-0000-000000000001");
        UUID middleId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID lowestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID olderId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        insertSession(highestId, userId, ParkingSessionStatus.COMPLETED,
                sameStartedAt, BASE_TIME, null, 41.0, 29.0);
        insertSession(middleId, userId, ParkingSessionStatus.CANCELLED,
                sameStartedAt, BASE_TIME, null, 41.0, 29.0);
        insertSession(lowestId, userId, ParkingSessionStatus.COMPLETED,
                sameStartedAt, BASE_TIME, null, 41.0, 29.0);
        insertSession(olderId, userId, ParkingSessionStatus.CANCELLED,
                sameStartedAt.minusSeconds(1), BASE_TIME, null, 41.0, 29.0);
        insertSession(UUID.randomUUID(), UUID.randomUUID(), ParkingSessionStatus.COMPLETED,
                BASE_TIME, BASE_TIME.plusSeconds(1), null, 41.0, 29.0);

        MvcResult first = mockMvc.perform(authenticated(
                        get("/api/v1/parking/sessions/history"), userId)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(highestId.toString()))
                .andExpect(jsonPath("$.items[1].id").value(middleId.toString()))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), userId)
                        .param("size", "2")
                        .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(lowestId.toString()))
                .andExpect(jsonPath("$.items[1].id").value(olderId.toString()))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void httpDeleteRemovesTerminalSessionsPreservesActiveAndDoesNotCascadeCommunityArtifacts()
            throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID claimerId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        ParkingSpot spot = insertSpot(ownerId, ParkingSpotStatus.ACTIVE, clock.instant().plusSeconds(600));
        String claimKey = UUID.randomUUID().toString();
        claimApi(claimerId, spot.id(), claimKey).andExpect(status().isOk());

        UUID communitySessionId = jdbc.queryForObject(
                """
                SELECT id FROM parking_sessions
                WHERE user_id = ? AND status = 'ACTIVE' AND parking_source = 'COMMUNITY'
                """,
                UUID.class,
                claimerId);
        assertThat(communitySessionId).isNotNull();
        transitionApi(claimerId, communitySessionId, "complete").andExpect(status().isOk());

        UUID completedId = UUID.randomUUID();
        UUID cancelledId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID strangerCompletedId = UUID.randomUUID();
        insertSession(completedId, claimerId, ParkingSessionStatus.COMPLETED,
                BASE_TIME.minusSeconds(40), BASE_TIME.minusSeconds(30), null, 41.1, 29.1);
        insertSession(cancelledId, claimerId, ParkingSessionStatus.CANCELLED,
                BASE_TIME.minusSeconds(20), BASE_TIME.minusSeconds(10), null, 41.2, 29.2);
        insertSession(activeId, claimerId, ParkingSessionStatus.ACTIVE,
                BASE_TIME, null, null, 41.3, 29.3);
        insertSession(strangerCompletedId, strangerId, ParkingSessionStatus.COMPLETED,
                BASE_TIME.minusSeconds(5), BASE_TIME.minusSeconds(1), null, 40.0, 29.0);

        mockMvc.perform(authenticated(
                        delete("/api/v1/parking/sessions/{sessionId}", communitySessionId), claimerId))
                .andExpect(status().isNoContent());
        mockMvc.perform(authenticated(
                        delete("/api/v1/parking/sessions/{sessionId}", activeId), claimerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARKING_SESSION_NOT_TERMINAL"));
        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/history"), claimerId))
                .andExpect(status().isNoContent());
        mockMvc.perform(authenticated(delete("/api/v1/parking/sessions/history"), claimerId))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?", Integer.class, communitySessionId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?", Integer.class, completedId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?", Integer.class, cancelledId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?", Integer.class, activeId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE id = ?", Integer.class, strangerCompletedId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM parking_spots WHERE id = ?", String.class, spot.id()))
                .isEqualTo("FILLED");
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM parking_spot_status_history
                WHERE spot_id = ? AND reason = 'CLAIMED'
                """,
                Integer.class,
                spot.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type = 'ParkingSpotClaimed'
                """,
                Integer.class,
                spot.id())).isEqualTo(1);
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/active"), claimerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId.toString()));
        mockMvc.perform(authenticated(get("/api/v1/parking/sessions/history"), claimerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    private void insertSession(
            UUID id,
            UUID userId,
            ParkingSessionStatus status,
            Instant startedAt,
            Instant endedAt,
            BigDecimal estimatedFee,
            double latitude,
            double longitude) {
        String completionType = switch (status) {
            case ACTIVE -> null;
            case COMPLETED, CANCELLED -> ParkingSessionCompletionType.MANUAL.name();
        };
        String completionReason = switch (status) {
            case ACTIVE -> null;
            case COMPLETED, CANCELLED -> ParkingSessionCompletionReason.MANUAL.name();
        };
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO parking_sessions (
                        id, user_id, status, parking_source, started_at, ended_at,
                        latitude, longitude, estimated_fee, last_confirmed_at,
                        completion_type, completion_reason, reminder_stage,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0)
                    """);
            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.setString(3, status.name());
            statement.setString(4, ParkingSource.MANUAL.name());
            statement.setObject(5, utc(startedAt));
            if (endedAt == null) {
                statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setObject(6, utc(endedAt));
            }
            statement.setDouble(7, latitude);
            statement.setDouble(8, longitude);
            if (estimatedFee == null) {
                statement.setNull(9, Types.NUMERIC);
            } else {
                statement.setBigDecimal(9, estimatedFee);
            }
            statement.setObject(10, utc(startedAt));
            if (completionType == null) {
                statement.setNull(11, Types.VARCHAR);
            } else {
                statement.setString(11, completionType);
            }
            if (completionReason == null) {
                statement.setNull(12, Types.VARCHAR);
            } else {
                statement.setString(12, completionReason);
            }
            statement.setObject(13, utc(startedAt));
            statement.setObject(14, utc(endedAt == null ? startedAt : endedAt));
            return statement;
        });
    }

    private ParkingSession saveActiveSession(Instant startedAt) {
        ParkingSession saved = transaction.execute(status -> sessions.save(ParkingSession.start(
                UUID.randomUUID(),
                ParkingSource.MANUAL,
                41.0082,
                28.9784,
                null,
                null,
                startedAt)));
        assertThat(saved).isNotNull();
        return saved;
    }

    private ParkingSpot insertSpot(UUID ownerId, ParkingSpotStatus status, Instant expiresAt) {
        Instant createdAt = clock.instant().minusSeconds(60);
        ParkingSpot spot = new ParkingSpot(
                UUID.randomUUID(),
                ownerId,
                UUID.randomUUID(),
                41.0082,
                28.9784,
                "Community integration test spot",
                null,
                false,
                Set.of(VehicleType.ANY),
                ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL,
                Set.of(),
                status,
                1.0,
                0,
                0,
                expiresAt,
                createdAt,
                createdAt,
                null,
                status.isPendingModeration() ? null : createdAt,
                createdAt.plus(java.time.Duration.ofHours(24)),
                0,
                status.isPendingModeration() ? null : createdAt,
                null);
        ParkingSpot saved = transaction.execute(transactionStatus -> spotRepositoryAdapter.save(spot));
        assertThat(saved).isNotNull();
        clearInvocations(spotRepositoryAdapter);
        return saved;
    }

    private void assertCommittedCommunityClaim(
            UUID spotId, UUID userId, String idempotencyKey) {
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM parking_sessions
                WHERE user_id = ? AND status = 'ACTIVE' AND parking_source = 'COMMUNITY'
                """,
                Integer.class,
                userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM parking_spot_status_history
                WHERE spot_id = ? AND reason = 'CLAIMED' AND new_status = 'FILLED'
                """,
                Integer.class,
                spotId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type = 'ParkingSpotClaimed'
                """,
                Integer.class,
                spotId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events o
                JOIN parking_sessions s ON s.id = o.aggregate_id
                WHERE o.event_type = 'ParkingSessionStarted'
                  AND o.aggregate_type = 'ParkingSession'
                  AND s.user_id = ?
                  AND s.parking_source = 'COMMUNITY'
                  AND o.payload::text NOT LIKE '%latitude%'
                """,
                Integer.class,
                userId))
                .isEqualTo(1);
        var idempotency = jdbc.queryForMap(
                """
                SELECT status, response_status FROM idempotency_records
                WHERE user_id = ? AND http_method = 'POST' AND idempotency_key = ?
                """,
                userId,
                idempotencyKey);
        assertThat(idempotency.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) idempotency.get("response_status")).intValue()).isEqualTo(200);
    }

    private void assertRolledBackCommunityClaim(
            UUID spotId, UUID userId, String idempotencyKey, int expectedExistingSessions) {
        assertThat(jdbc.queryForObject(
                "SELECT status FROM parking_spots WHERE id = ?", String.class, spotId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_sessions WHERE user_id = ?",
                Integer.class,
                userId))
                .isEqualTo(expectedExistingSessions);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM parking_spot_status_history WHERE spot_id = ? AND reason = 'CLAIMED'",
                Integer.class,
                spotId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'ParkingSpotClaimed'",
                Integer.class,
                spotId))
                .isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE event_type = 'ParkingSessionStarted'
                  AND payload::text LIKE '%"source":"COMMUNITY"%'
                """,
                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                userId,
                idempotencyKey))
                .isZero();
    }

    private void assertCompletedIdempotency(UUID userId, String idempotencyKey, int responseStatus) {
        var idempotency = jdbc.queryForMap(
                """
                SELECT status, response_status FROM idempotency_records
                WHERE user_id = ? AND http_method = 'POST' AND idempotency_key = ?
                """,
                userId,
                idempotencyKey);
        assertThat(idempotency.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) idempotency.get("response_status")).intValue()).isEqualTo(responseStatus);
    }

    private void assertMissingIdempotency(UUID userId, String idempotencyKey) {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idempotency_key = ?",
                Integer.class,
                userId,
                idempotencyKey))
                .isZero();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private org.springframework.test.web.servlet.ResultActions startApi(UUID userId, String fee)
            throws Exception {
        return startApi(userId, fee, UUID.randomUUID().toString());
    }

    private org.springframework.test.web.servlet.ResultActions startApi(
            UUID userId, String fee, String idempotencyKey) throws Exception {
        String body = fee == null
                ? "{\"latitude\":41.0,\"longitude\":29.0}"
                : "{\"latitude\":41.0,\"longitude\":29.0,\"estimatedFee\":\"" + fee + "\"}";
        return mockMvc.perform(authenticated(post("/api/v1/parking/sessions"), userId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions transitionApi(
            UUID userId, UUID sessionId, String transition) throws Exception {
        return mockMvc.perform(authenticated(post(
                        "/api/v1/parking/sessions/{sessionId}/{transition}", sessionId, transition), userId)
                .header("Idempotency-Key", UUID.randomUUID()));
    }

    private org.springframework.test.web.servlet.ResultActions claimApi(
            UUID userId, UUID spotId, String idempotencyKey) throws Exception {
        return mockMvc.perform(authenticated(
                        post("/api/v1/parking/spots/{spotId}/claim", spotId), userId)
                .header("Idempotency-Key", idempotencyKey));
    }

    private static MockHttpServletRequestBuilder authenticated(
            MockHttpServletRequestBuilder request, UUID userId) {
        return request.header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-User-Id", userId);
    }

    private static UUID sessionId(MvcResult result) throws Exception {
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private static String causeChain(Throwable failure) {
        StringBuilder causes = new StringBuilder("resolved exception");
        Throwable current = failure;
        while (current != null) {
            causes.append(" -> ")
                    .append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage());
            current = current.getCause();
        }
        return causes.toString();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Concurrent parking-session test could not synchronize", exception);
        }
    }

    private void awaitBlockedIdempotencyClaim() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blockedClaims = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%idempotency_records%'
                    """,
                    Integer.class);
            if (blockedClaims != null && blockedClaims > 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        throw new AssertionError("The retry did not block on the in-flight idempotency claim");
    }
}
