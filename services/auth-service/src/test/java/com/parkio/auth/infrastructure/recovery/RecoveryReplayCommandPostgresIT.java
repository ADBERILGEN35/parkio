package com.parkio.auth.infrastructure.recovery;

import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.EVIDENCE_IDENTITY;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.USERS;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.args;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.ErasureRestoreReplayService;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.auth.infrastructure.messaging.ErasureAckKafkaConsumer;
import com.parkio.auth.infrastructure.persistence.PostgresDatabaseIdentity;
import com.parkio.platform.messaging.EventEnvelope;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The recovery-replay command on real PostgreSQL (Flyway through V27) with the real coordinator,
 * the real ACK consumer, the real connected-identity read and the real backup-anchor query. ACKs
 * are delivered through the consumer exactly as a participant's outbox relay publishes them; Kafka
 * itself is not needed for this. Synthetic ids and the fixture evidence only.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles(RecoveryReplayLaunch.PROFILE)
@Testcontainers(disabledWithoutDocker = true)
class RecoveryReplayCommandPostgresIT {

    private static final List<String> PARTICIPANTS = AccountErasureApplicationService.DEFAULT_PARTICIPANTS;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_recovery_replay_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
        registry.add("parkio.privacy.account-erasure.participants", () -> String.join(",", PARTICIPANTS));
        registry.add("parkio.privacy.account-erasure.restore-replay.enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.recovery-replay.poll-interval", () -> "PT0.1S");
    }

    @TempDir Path dir;

    @Autowired private RecoveryReplayCommand command;
    @Autowired private ErasureAckKafkaConsumer consumer;
    @Autowired private ErasureRestoreReplayService restoreReplay;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> insertedRequests = new ArrayList<>();
    private String connected;
    private UUID attempt;
    private String dataset;
    private Path evidence;
    private Path trust;
    private Path verdict;
    private JsonNode set;

    @BeforeEach
    void inputs() {
        connected = PostgresDatabaseIdentity.of(jdbc);
        attempt = UUID.randomUUID();
        dataset = "backup-stamp-" + attempt;
        evidence = RecoveryFixtures.trustedSet(dir, attempt.toString(), dataset, connected);
        trust = RecoveryFixtures.trustDocument(dir, EVIDENCE_IDENTITY);
        verdict = dir.resolve("verdict.json");
        set = RecoveryFixtures.readTree(evidence).path("erasureSet");
    }

    @AfterEach
    void removeInsertedRequests() {
        insertedRequests.forEach(id -> jdbc.update("DELETE FROM erasure_requests WHERE id = ?", id));
    }

    @Test
    void everyParticipantAndAuthAcknowledgingCompletesTheAttempt() throws Exception {
        durablyRecorded(user(0));

        CompletableFuture<RecoveryReplayCommand.Outcome> run = start("--timeout-seconds=60");
        awaitAttempt();
        for (String participant : PARTICIPANTS) {
            acknowledgeAll(participant, "SUCCESS");
        }
        RecoveryReplayCommand.Outcome outcome = run.get(90, TimeUnit.SECONDS);

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.COMPLETE);
        assertThat(commands()).isEqualTo(USERS);
        assertThat(acks("auth", "SUCCESS")).isEqualTo(USERS);
        for (String participant : PARTICIPANTS) {
            assertThat(acks(participant, "SUCCESS")).as(participant).isEqualTo(USERS);
        }
        JsonNode written = RecoveryFixtures.readTree(verdict);
        assertThat(written.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(written.path("exitCode").asInt()).isZero();
        assertThat(written.path("auth").path("success").asLong()).isEqualTo(USERS);
        assertThat(written.path("participants").size()).isEqualTo(PARTICIPANTS.size());
        assertThat(written.path("participants").has("auth")).isFalse();
        assertThat(written.path("coverage").path("statement").asText())
                .startsWith("erasure coverage verified through sequence 4 (frontier version ");
    }

    @Test
    void aMissingParticipantKeepsTheAttemptBlockedUntilTheBoundedWaitEnds() throws Exception {
        CompletableFuture<RecoveryReplayCommand.Outcome> run = start("--timeout-seconds=3");
        awaitAttempt();
        for (String participant : PARTICIPANTS.subList(1, PARTICIPANTS.size())) {
            acknowledgeAll(participant, "SUCCESS");
        }
        RecoveryReplayCommand.Outcome outcome = run.get(60, TimeUnit.SECONDS);

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.TIMEOUT);
        JsonNode written = RecoveryFixtures.readTree(verdict);
        assertThat(written.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(written.path("participants").path(PARTICIPANTS.get(0)).path("missing").asLong()).isEqualTo(USERS);
    }

    @Test
    void aFailedParticipantBlocksTheAttempt() throws Exception {
        CompletableFuture<RecoveryReplayCommand.Outcome> run = start("--timeout-seconds=60");
        awaitAttempt();
        deliver("media", user(0), "FAILED");
        RecoveryReplayCommand.Outcome outcome = run.get(60, TimeUnit.SECONDS);

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.BLOCKED);
        assertThat(RecoveryFixtures.readTree(verdict).path("participants").path("media").path("failed").asLong())
                .isEqualTo(1);
    }

    @Test
    void aMissingAuthAcknowledgementKeepsTheAttemptBlocked() throws Exception {
        CompletableFuture<RecoveryReplayCommand.Outcome> run = start("--timeout-seconds=3");
        awaitAttempt();
        jdbc.update("DELETE FROM erasure_restore_acks WHERE recovery_attempt_id = ? AND service_name = 'auth'", attempt);
        for (String participant : PARTICIPANTS) {
            acknowledgeAll(participant, "SUCCESS");
        }
        RecoveryReplayCommand.Outcome outcome = run.get(60, TimeUnit.SECONDS);

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.TIMEOUT);
        assertThat(RecoveryFixtures.readTree(verdict).path("auth").path("missing").asLong()).isEqualTo(USERS);
    }

    @Test
    void aDurablyRecordedErasureMissingFromTheTrustedSetIsRefusedBeforeAnyReplay() {
        durablyRecorded(UUID.randomUUID());

        RecoveryReplayCommand.Outcome outcome = command.execute(args(evidence, trust, attempt.toString(), dataset,
                connected, verdict));

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.INVALID_EVIDENCE);
        assertNothingReplayed();
    }

    @Test
    void anAttemptAlreadyStartedForAnotherDatasetIsRefused() {
        List<ErasureLedgerEntry> other = List.of(new ErasureLedgerEntry(UUID.randomUUID(), Instant.parse("2026-09-01T00:00:00Z")));
        restoreReplay.startRestoreReplay(attempt, "another-dataset", other);

        RecoveryReplayCommand.Outcome outcome = command.execute(args(evidence, trust, attempt.toString(), dataset,
                connected, verdict));

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.ATTEMPT_MISMATCH);
        assertThat(commands()).isEqualTo(1);
    }

    @Test
    void aConnectedDatabaseOtherThanTheTicketsTargetIsRefused() {
        String elsewhere = "postgresql:7000000000000000123:parkio_auth";
        evidence = RecoveryFixtures.trustedSet(dir, attempt.toString(), dataset, elsewhere);

        RecoveryReplayCommand.Outcome outcome = command.execute(args(evidence, trust, attempt.toString(), dataset,
                elsewhere, verdict));

        assertThat(outcome.exit()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED);
        assertNothingReplayed();
    }

    private CompletableFuture<RecoveryReplayCommand.Outcome> start(String... extra) {
        String[] arguments = args(evidence, trust, attempt.toString(), dataset, connected, verdict, extra);
        return CompletableFuture.supplyAsync(() -> command.execute(arguments));
    }

    private void awaitAttempt() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM erasure_restore_attempts WHERE recovery_attempt_id = ?", Integer.class, attempt);
            if (rows != null && rows == 1) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the command did not start attempt " + attempt);
    }

    private void assertNothingReplayed() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erasure_restore_attempts WHERE recovery_attempt_id = ?",
                Integer.class, attempt)).isZero();
        assertThat(commands()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erasure_restore_acks WHERE recovery_attempt_id = ?",
                Integer.class, attempt)).isZero();
        assertThat(RecoveryFixtures.readTree(verdict).path("status").asText()).isNotEqualTo("COMPLETE");
    }

    private void durablyRecorded(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO erasure_requests (id, auth_user_id, status, requested_at, durable_recording_status)
                VALUES (?, ?, 'COMPLETE', now(), 'DURABLY_RECORDED')
                """, id, user);
        insertedRequests.add(id);
    }

    private UUID user(int index) {
        return UUID.fromString(set.path("entries").get(index).path("authUserId").asText());
    }

    private void acknowledgeAll(String participant, String status) throws Exception {
        for (JsonNode entry : set.path("entries")) {
            deliver(participant, UUID.fromString(entry.path("authUserId").asText()), status);
        }
    }

    private void deliver(String participant, UUID user, String status) throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("recoveryAttemptId", attempt.toString());
        payload.put("restoredDatasetId", dataset);
        payload.put("erasureSetDigest", set.path("erasureSetDigest").asText());
        payload.put("authUserId", user.toString());
        payload.put("serviceName", participant);
        payload.put("status", status);
        payload.put("occurredAt", occurredAt.toString());
        EventEnvelope envelope = new EventEnvelope(eventId, UserErasureRestoreAcknowledgedEvent.TYPE, "AccountErasure",
                user, occurredAt, 1, null, objectMapper.valueToTree(payload));
        consumer.onMessage(new ConsumerRecord<>(ErasureAckKafkaConsumer.TOPIC, 0, 0L, user.toString(),
                objectMapper.writeValueAsString(envelope)), UserErasureRestoreAcknowledgedEvent.TYPE, () -> { });
    }

    private int commands() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE event_type = ? AND payload::jsonb ->> 'recoveryAttemptId' = ?
                """, Integer.class, UserErasureRestoreReplayRequestedEvent.TYPE, attempt.toString());
    }

    private int acks(String service, String status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM erasure_restore_acks
                WHERE recovery_attempt_id = ? AND service_name = ? AND status = ?
                """, Integer.class, attempt, service, status);
    }
}
