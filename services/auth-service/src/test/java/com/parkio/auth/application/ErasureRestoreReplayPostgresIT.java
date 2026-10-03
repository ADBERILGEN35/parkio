package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.durable.ErasureLedgerEntry;
import com.parkio.auth.application.durable.ErasureSetDigest;
import com.parkio.auth.application.port.ErasureRestoreRepository.RestoreAttempt;
import com.parkio.auth.domain.event.UserErasureRestoreAcknowledgedEvent;
import com.parkio.auth.domain.event.UserErasureRestoreReplayRequestedEvent;
import com.parkio.auth.infrastructure.messaging.ErasureAckKafkaConsumer;
import com.parkio.platform.messaging.EventEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
 * Restore replay on real PostgreSQL (Flyway V26): an attempt queues one replay command per user
 * in the auth outbox, and only ACKs of a configured participant for a user of that attempt, with
 * the attempt's dataset and erasure-set digest, count. ACKs are delivered through the real
 * consumer, as a participant's outbox relay publishes them. Synthetic ids only.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ErasureRestoreReplayPostgresIT {

    private static final String DATASET = "backup-stamp-2026-10-03T00-00-00Z";
    private static final List<String> PARTICIPANTS = List.of("gamification", "user");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_restore_replay_it")
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
    }

    @Autowired private ErasureRestoreReplayService restoreReplay;
    @Autowired private ErasureAckKafkaConsumer consumer;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void startingAReplayRecordsTheAttemptAndQueuesOneCommandPerUser() throws Exception {
        UUID attemptId = UUID.randomUUID();
        List<ErasureLedgerEntry> set = erasureSet(3);

        RestoreAttempt attempt = restoreReplay.startRestoreReplay(attemptId, DATASET, set);

        assertThat(attempt.erasureSetDigest()).isEqualTo(ErasureSetDigest.of(set));
        assertThat(attempt.userCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM erasure_restore_attempt_users WHERE recovery_attempt_id = ?",
                Integer.class, attemptId)).isEqualTo(3);
        List<Map<String, Object>> rows = commands(attemptId);
        assertThat(rows).hasSize(3);
        for (ErasureLedgerEntry entry : set) {
            Map<String, Object> row = rows.stream()
                    .filter(candidate -> entry.authUserId().equals(candidate.get("aggregate_id")))
                    .findFirst().orElseThrow();
            assertThat(row.get("aggregate_type")).isEqualTo("AccountErasure");
            assertThat(row.get("event_id"))
                    .isEqualTo(UserErasureRestoreReplayRequestedEvent.eventIdFor(attemptId, entry.authUserId()));
            JsonNode payload = objectMapper.readTree((String) row.get("payload"));
            assertThat(payload.path("recoveryAttemptId").asText()).isEqualTo(attemptId.toString());
            assertThat(payload.path("restoredDatasetId").asText()).isEqualTo(DATASET);
            assertThat(payload.path("erasureSetDigest").asText()).isEqualTo(attempt.erasureSetDigest());
            assertThat(payload.path("authUserId").asText()).isEqualTo(entry.authUserId().toString());
        }
    }

    @Test
    void restartingTheSameAttemptChangesNothingAndAnotherDatasetOrSetIsRefused() {
        UUID attemptId = UUID.randomUUID();
        List<ErasureLedgerEntry> set = erasureSet(2);
        RestoreAttempt first = restoreReplay.startRestoreReplay(attemptId, DATASET, set);

        assertThat(restoreReplay.startRestoreReplay(attemptId, DATASET, set)).isEqualTo(first);
        assertThatThrownBy(() -> restoreReplay.startRestoreReplay(attemptId, "another-dataset", set))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> restoreReplay.startRestoreReplay(attemptId, DATASET, erasureSet(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(commands(attemptId)).hasSize(2);
    }

    @Test
    void everyParticipantMustAcknowledgeEveryUser() throws Exception {
        UUID attemptId = UUID.randomUUID();
        List<ErasureLedgerEntry> set = erasureSet(3);
        RestoreAttempt attempt = restoreReplay.startRestoreReplay(attemptId, DATASET, set);
        for (ErasureLedgerEntry entry : set) {
            deliver(attempt, entry.authUserId(), "gamification", "SUCCESS");
        }
        deliver(attempt, set.get(0).authUserId(), "user", "SUCCESS");
        deliver(attempt, set.get(1).authUserId(), "user", "SUCCESS");

        RestoreReplayVerdict blocked = restoreReplay.verdict(attemptId);
        assertThat(blocked.status()).isEqualTo(RestoreReplayVerdict.Status.BLOCKED);
        assertThat(blocked.missing()).containsExactly(Map.entry("user", 1L));

        deliver(attempt, set.get(2).authUserId(), "user", "SUCCESS");
        RestoreReplayVerdict complete = restoreReplay.verdict(attemptId);
        assertThat(complete.status()).isEqualTo(RestoreReplayVerdict.Status.COMPLETE);
        assertThat(complete.userCount()).isEqualTo(3);
    }

    @Test
    void acknowledgementsOfAnotherAttemptDatasetSetParticipantOrUserDoNotCount() throws Exception {
        UUID attemptId = UUID.randomUUID();
        List<ErasureLedgerEntry> set = erasureSet(1);
        RestoreAttempt attempt = restoreReplay.startRestoreReplay(attemptId, DATASET, set);
        UUID user = set.get(0).authUserId();
        RestoreAttempt prior = new RestoreAttempt(UUID.randomUUID(), DATASET, attempt.erasureSetDigest(), 1, Instant.now());

        deliver(prior, user, "gamification", "SUCCESS");
        deliver(new RestoreAttempt(attemptId, "another-dataset", attempt.erasureSetDigest(), 1, Instant.now()),
                user, "gamification", "SUCCESS");
        deliver(new RestoreAttempt(attemptId, DATASET, ErasureSetDigest.of(erasureSet(1)), 1, Instant.now()),
                user, "gamification", "SUCCESS");
        deliver(attempt, user, "billing", "SUCCESS");
        deliver(attempt, UUID.randomUUID(), "gamification", "SUCCESS");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM erasure_restore_acks WHERE recovery_attempt_id IN (?, ?)",
                Integer.class, attemptId, prior.recoveryAttemptId())).isZero();
        RestoreReplayVerdict verdict = restoreReplay.verdict(attemptId);
        assertThat(verdict.status()).isEqualTo(RestoreReplayVerdict.Status.BLOCKED);
        assertThat(verdict.missing()).containsOnly(Map.entry("gamification", 1L), Map.entry("user", 1L));
        assertThat(restoreReplay.verdict(prior.recoveryAttemptId()).status()).isEqualTo(RestoreReplayVerdict.Status.BLOCKED);
    }

    @Test
    void aFailedAcknowledgementBlocksUntilTheParticipantSucceeds() throws Exception {
        UUID attemptId = UUID.randomUUID();
        List<ErasureLedgerEntry> set = erasureSet(1);
        RestoreAttempt attempt = restoreReplay.startRestoreReplay(attemptId, DATASET, set);
        UUID user = set.get(0).authUserId();
        deliver(attempt, user, "gamification", "SUCCESS");
        deliver(attempt, user, "user", "FAILED");

        RestoreReplayVerdict blocked = restoreReplay.verdict(attemptId);
        assertThat(blocked.status()).isEqualTo(RestoreReplayVerdict.Status.BLOCKED);
        assertThat(blocked.failed()).containsExactly(Map.entry("user", 1L));

        deliver(attempt, user, "user", "SUCCESS");
        assertThat(restoreReplay.verdict(attemptId).status()).isEqualTo(RestoreReplayVerdict.Status.COMPLETE);
    }

    @Test
    void aDuplicateDeliveryChangesNothingAndAnUnknownStatusRollsBack() throws Exception {
        UUID attemptId = UUID.randomUUID();
        List<ErasureLedgerEntry> set = erasureSet(1);
        RestoreAttempt attempt = restoreReplay.startRestoreReplay(attemptId, DATASET, set);
        UUID user = set.get(0).authUserId();
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> consumer.onMessage(record(eventId, attempt, user, "gamification", "PENDING"),
                UserErasureRestoreAcknowledgedEvent.TYPE, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        // The rejected delivery rolled its inbox claim back, so the same event id still counts later.
        consumer.onMessage(record(eventId, attempt, user, "gamification", "SUCCESS"),
                UserErasureRestoreAcknowledgedEvent.TYPE, () -> { });
        consumer.onMessage(record(eventId, attempt, user, "gamification", "FAILED"),
                UserErasureRestoreAcknowledgedEvent.TYPE, () -> { });

        assertThat(jdbc.queryForObject("""
                SELECT status FROM erasure_restore_acks
                WHERE recovery_attempt_id = ? AND auth_user_id = ? AND service_name = 'gamification'
                """, String.class, attemptId, user)).isEqualTo("SUCCESS");
    }

    private void deliver(RestoreAttempt attempt, UUID user, String service, String status) throws Exception {
        consumer.onMessage(record(UUID.randomUUID(), attempt, user, service, status),
                UserErasureRestoreAcknowledgedEvent.TYPE, () -> { });
    }

    /** The envelope a participant's outbox relay publishes to {@code parkio.privacy.erasure}. */
    private ConsumerRecord<String, String> record(UUID eventId, RestoreAttempt attempt, UUID user, String service,
                                                  String status) throws Exception {
        Instant occurredAt = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("recoveryAttemptId", attempt.recoveryAttemptId().toString());
        payload.put("restoredDatasetId", attempt.restoredDatasetId());
        payload.put("erasureSetDigest", attempt.erasureSetDigest());
        payload.put("authUserId", user.toString());
        payload.put("serviceName", service);
        payload.put("status", status);
        payload.put("occurredAt", occurredAt.toString());
        EventEnvelope envelope = new EventEnvelope(eventId, UserErasureRestoreAcknowledgedEvent.TYPE, "AccountErasure",
                user, occurredAt, 1, null, objectMapper.valueToTree(payload));
        return new ConsumerRecord<>(ErasureAckKafkaConsumer.TOPIC, 0, 0L, user.toString(),
                objectMapper.writeValueAsString(envelope));
    }

    private List<Map<String, Object>> commands(UUID attemptId) {
        return jdbc.queryForList("""
                SELECT event_id, aggregate_type, aggregate_id, payload FROM outbox_events
                WHERE event_type = ? AND payload::jsonb ->> 'recoveryAttemptId' = ?
                """, UserErasureRestoreReplayRequestedEvent.TYPE, attemptId.toString());
    }

    private static List<ErasureLedgerEntry> erasureSet(int users) {
        return IntStream.range(0, users)
                .mapToObj(index -> new ErasureLedgerEntry(UUID.randomUUID(), Instant.parse("2026-09-29T08:16:00Z").plusSeconds(index)))
                .toList();
    }
}
