package com.parkio.gateway.infrastructure.waitlist.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.parkio.gateway.application.waitlist.SubmitWaitlistCommand;
import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.application.waitlist.WaitlistEmailSender;
import com.parkio.gateway.application.waitlist.WaitlistHasher;
import com.parkio.gateway.application.waitlist.WaitlistInterestRepository;
import com.parkio.gateway.application.waitlist.WaitlistOpsNotifier;
import com.parkio.gateway.application.waitlist.WaitlistRateLimiter;
import com.parkio.gateway.infrastructure.security.JwtTokenValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

/**
 * Real-PostgreSQL proof (production image family postgres:16-alpine, Flyway V1..V4,
 * Spring Boot's auto-configured JDBC transaction manager) of the confirmation /
 * ops-outbox coupling. H2 cannot prove savepoint recovery: PostgreSQL aborts the
 * whole transaction on any error unless it is rolled back to a savepoint.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "parkio.waitlist.ops-notifications.enabled=true",
        "parkio.waitlist.ops-notifications.environment=acceptance",
        "parkio.waitlist.ops-notifications.poll-interval=PT1H"
})
@ActiveProfiles("test")
class WaitlistOpsNotificationPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_gateway")
                    .withUsername("parkio_gateway")
                    .withPassword("parkio_gateway");

    static Path inbox;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        inbox = Files.createTempDirectory("waitlist-ops-inbox-it");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.waitlist.ops-notifications.export-dir", inbox::toString);
    }

    @Autowired
    private WaitlistApplicationService service;

    @Autowired
    private WaitlistInterestRepository repository;

    @Autowired
    private WaitlistOpsNotifier notifier;

    @Autowired
    private WaitlistOpsNotificationExporter exporter;

    @Autowired
    private WaitlistHasher hasher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private WaitlistRateLimiter rateLimiter;

    @MockBean
    private WaitlistEmailSender emailSender;

    @MockBean
    private JwtTokenValidator tokenValidator;

    private final AtomicReference<String> token = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(rateLimiter.check(anyString(), anyString())).thenReturn(Mono.empty());
        doAnswer(invocation -> {
            token.set(invocation.getArgument(1));
            return null;
        }).when(emailSender).sendConfirmation(anyString(), anyString(), any(), anyString());
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS it_fail_outbox_insert ON waitlist_ops_notification_outbox");
        jdbcTemplate.update("DELETE FROM waitlist_ops_notification_outbox");
        jdbcTemplate.update("DELETE FROM waitlist_interest");
    }

    @Test
    void runsOnRealPostgresWithFlywayV4AndJdbcTransactionManager() {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("16.");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("4");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();
        assertThat(transactionManager).isInstanceOf(JdbcTransactionManager.class);
        assertThat(((JdbcTransactionManager) transactionManager).isNestedTransactionAllowed()).isTrue();
        // Constraints from V4 are really enforced by PostgreSQL.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO waitlist_ops_notification_outbox
                    (id, event_type, dedup_key, occurred_at, status, next_attempt_at, created_at)
                VALUES (?, 'waitlist.subscription_submitted', 'k', now(), 'PENDING', now(), now())
                """, UUID.randomUUID())).hasMessageContaining("chk_waitlist_ops_outbox_event_type");
        System.out.println("IT_EVIDENCE postgres_server_version=" + version
                + " transaction_manager=" + transactionManager.getClass().getName());
    }

    @Test
    void committedConfirmationCreatesExactlyOneOutboxRow() {
        String rawToken = submit("pg.committed@example.test");

        service.confirm(rawToken).block();

        assertThat(status()).isEqualTo("CONFIRMED");
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT dedup_key FROM waitlist_ops_notification_outbox", String.class))
                .matches("waitlist:subscription_confirmed:[0-9a-f]{64}");
        assertThat(exporter.exportDue().exported()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM waitlist_ops_notification_outbox", String.class)).isEqualTo("EXPORTED");
    }

    @Test
    void outerRollbackCreatesNeitherConfirmationNorNotification() {
        String rawToken = submit("pg.rollback@example.test");
        String tokenHash = hasher.hash(rawToken);
        UUID interestId = interestId();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(repository.confirmByTokenHash(tokenHash, Instant.now())).isTrue();
            notifier.subscriptionConfirmed(interestId, Instant.now());
            // Both writes are visible inside the transaction ...
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM waitlist_ops_notification_outbox", Integer.class)).isEqualTo(1);
            tx.setRollbackOnly();
        });

        // ... and both are gone after the outer rollback.
        assertThat(status()).isEqualTo("PENDING");
        assertThat(outboxCount()).isZero();
        assertThat(exporter.exportDue().exported()).isZero();
    }

    @Test
    void genuineSqlFailureInsideSavepointKeepsConfirmationCommittable() {
        String rawToken = submit("pg.savepoint@example.test");
        // A real PostgreSQL error raised by the server during the outbox INSERT.
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION it_fail_outbox_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'synthetic outbox failure' USING ERRCODE = 'P0001';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER it_fail_outbox_insert BEFORE INSERT ON waitlist_ops_notification_outbox
                FOR EACH ROW EXECUTE FUNCTION it_fail_outbox_insert()
                """);
        double before = counter("record_failed");

        service.confirm(rawToken).block();

        // Without ROLLBACK TO SAVEPOINT PostgreSQL would reject the COMMIT (25P02) and the
        // confirmation would be lost; it is committed instead.
        assertThat(status()).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT confirmed_at IS NOT NULL FROM waitlist_interest", Boolean.class)).isTrue();
        assertThat(outboxCount()).isZero();
        assertThat(counter("record_failed")).isEqualTo(before + 1);
    }

    @Test
    void duplicateConfirmationAndReplayCreateNoSecondNotification() {
        String rawToken = submit("pg.duplicate@example.test");
        service.confirm(rawToken).block();
        service.confirm(rawToken).block();
        UUID interestId = interestId();
        double duplicatesBefore = counter("duplicate_suppressed");

        // Replay inside a live transaction: the unique violation is confined to the
        // savepoint and the surrounding transaction still commits other work.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            notifier.subscriptionConfirmed(interestId, Instant.now());
            jdbcTemplate.update("UPDATE waitlist_interest SET city = 'replay-marker'");
        });

        assertThat(outboxCount()).isEqualTo(1);
        assertThat(counter("duplicate_suppressed")).isEqualTo(duplicatesBefore + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT city FROM waitlist_interest", String.class))
                .isEqualTo("replay-marker");
    }

    @Test
    void retentionPurgesOnlyOldTerminalRowsAndKeepsPendingWork() {
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        insertOutbox("EXPORTED", old);
        insertOutbox("FAILED", old);
        insertOutbox("PENDING", old);
        insertOutbox("EXPORTED", Instant.now());
        jdbcTemplate.update("UPDATE waitlist_ops_notification_outbox SET next_attempt_at = ? WHERE status = 'PENDING'",
                Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)));

        exporter.exportDue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_ops_notification_outbox WHERE status = 'PENDING'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM waitlist_ops_notification_outbox WHERE status <> 'PENDING'", Integer.class))
                .isEqualTo(1);
    }

    private void insertOutbox(String status, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO waitlist_ops_notification_outbox
                    (id, event_type, dedup_key, occurred_at, status, next_attempt_at, created_at)
                VALUES (?, 'waitlist.subscription_confirmed', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "waitlist:subscription_confirmed:" + UUID.randomUUID(),
                Timestamp.from(createdAt), status, Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private String submit(String email) {
        token.set(null);
        service.submit(new SubmitWaitlistCommand(
                email, Instant.now(), null, null, null, "parkio.dev-landing", "tr", "198.51.100.40", null)).block();
        assertThat(token.get()).isNotNull();
        return token.get();
    }

    private UUID interestId() {
        return jdbcTemplate.queryForObject("SELECT id FROM waitlist_interest", UUID.class);
    }

    private String status() {
        return jdbcTemplate.queryForObject("SELECT status FROM waitlist_interest", String.class);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM waitlist_ops_notification_outbox", Integer.class);
    }

    private double counter(String outcome) {
        Counter counter = meterRegistry.find(JdbcWaitlistOpsNotificationOutbox.METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
