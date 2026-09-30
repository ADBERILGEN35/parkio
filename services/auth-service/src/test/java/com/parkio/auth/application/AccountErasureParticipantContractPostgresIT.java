package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.messaging.ErasureAckKafkaConsumer;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import com.parkio.platform.messaging.EventEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * U05 coordinator contract on real PostgreSQL with the default configured participant set:
 * auth reaches COMPLETE only with a SUCCESS ACK from every one of the eight participants. ACKs
 * enter through the real Kafka consumer as the envelope a participant ACK outbox relay publishes
 * (docs/architecture/erasure-ack-outbox-contract.md). Assertions read persisted rows
 * ({@code auth_users}, {@code erasure_requests}, {@code erasure_service_acks}). The participant
 * list is not overridden, so a change to the default set fails this test.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountErasureParticipantContractPostgresIT {

    private static final String PASSWORD = "pw";
    private static final String ACK_TYPE = "UserErasureAcknowledged";
    static final List<String> PARTICIPANTS = List.of(
            "user", "parking", "media", "moderation", "gamification", "notification", "analytics", "ai-validation");
    private static final List<String> NON_SUCCESS_STATUSES = List.of("PENDING", "UNKNOWN", "success", "");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_contract_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
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
        registry.add("parkio.privacy.account-erasure.enabled", () -> "true");
    }

    @MockBean private PasswordHasher passwordHasher;
    @MockBean private OutboxEventAppender outbox;
    @MockBean private LoginFailureTracker loginFailureTracker;
    @MockBean private EmailVerificationSender emailVerificationSender;

    @Autowired private AccountErasureApplicationService erasure;
    @Autowired private ErasureAckKafkaConsumer consumer;
    @Autowired private AuthUserRepository users;
    @Autowired private RoleJpaRepository roles;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void stubHasher() {
        when(passwordHasher.matches(eq(PASSWORD), eq("hash"))).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
    }

    static Stream<Arguments> participantUnknownStatus() {
        return PARTICIPANTS.stream().flatMap(p -> NON_SUCCESS_STATUSES.stream().map(s -> Arguments.of(p, s)));
    }

    @Test
    void successFromAllEightParticipantsCompletes() throws Exception {
        Erasure e = start();

        for (String service : PARTICIPANTS) {
            assertThat(requestStatus(e.requestId())).isEqualTo("IN_PROGRESS");
            deliver(e, service, "SUCCESS");
        }

        assertThat(requestStatus(e.requestId())).isEqualTo("COMPLETE");
        assertThat(userStatus(e.userId())).isEqualTo("ERASED");
        assertThat(userEmail(e.userId())).startsWith("erased-");
        assertThat(ackStatuses(e.requestId())).containsOnlyKeys(PARTICIPANTS).allSatisfy((service, status) ->
                assertThat(status).isEqualTo("SUCCESS"));
    }

    @ParameterizedTest(name = "missing {0}")
    @FieldSource("PARTICIPANTS")
    void anyMissingParticipantBlocksComplete(String missing) throws Exception {
        Erasure e = start();
        String present = PARTICIPANTS.stream().filter(s -> !s.equals(missing)).findFirst().orElseThrow();

        for (String service : PARTICIPANTS) {
            if (!service.equals(missing)) {
                deliver(e, service, "SUCCESS");
            }
        }
        // Neither a forged name, the coordinator's own name, nor a repeat stands in for it.
        deliver(e, "forged-service", "SUCCESS");
        deliver(e, "auth", "SUCCESS");
        deliver(e, present, "SUCCESS");

        assertIncomplete(e, "IN_PROGRESS");
        assertThat(ackStatuses(e.requestId())).hasSize(7).doesNotContainKey(missing)
                .doesNotContainKeys("forged-service", "auth");
    }

    @ParameterizedTest(name = "{0} reports FAILED")
    @FieldSource("PARTICIPANTS")
    void failedFromAnyParticipantBlocksComplete(String participant) throws Exception {
        Erasure e = start();

        for (String service : PARTICIPANTS) {
            deliver(e, service, service.equals(participant) ? "FAILED" : "SUCCESS");
        }

        assertIncomplete(e, "FAILED_RETRYING");
        Map<String, String> acks = ackStatuses(e.requestId());
        assertThat(acks).hasSize(8).containsEntry(participant, "FAILED");
        assertThat(acks.values().stream().filter("SUCCESS"::equals)).hasSize(7);
    }

    /**
     * A status outside SUCCESS/FAILED violates {@code chk_erasure_service_acks_status}: the whole
     * ACK transaction rolls back (inbox claim included), the consumer rethrows so the container
     * retries and then dead-letters it, and it never counts toward COMPLETE.
     */
    @ParameterizedTest(name = "{0} reports \"{1}\"")
    @MethodSource("participantUnknownStatus")
    void unknownStatusFromAnyParticipantIsRejectedAndBlocksComplete(String participant, String status)
            throws Exception {
        Erasure e = start();
        for (String service : PARTICIPANTS) {
            if (!service.equals(participant)) {
                deliver(e, service, "SUCCESS");
            }
        }
        ConsumerRecord<String, String> unknown = record(e, participant, status);
        UUID unknownEventId = UUID.fromString(objectMapper.readTree(unknown.value()).get("eventId").asText());

        assertThatThrownBy(() -> consumer.onMessage(unknown, ACK_TYPE, () -> { }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertIncomplete(e, "IN_PROGRESS");
        assertThat(ackStatuses(e.requestId())).hasSize(7).doesNotContainKey(participant)
                .allSatisfy((service, st) -> assertThat(st).isEqualTo("SUCCESS"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbox_events WHERE id = ?", Long.class, unknownEventId))
                .isZero();
    }

    @ParameterizedTest(name = "duplicate from {0}")
    @FieldSource("PARTICIPANTS")
    void duplicateAckDeliveryDoesNotChangeCoordinatorState(String participant) throws Exception {
        Erasure e = start();
        ConsumerRecord<String, String> ack = record(e, participant, "SUCCESS");

        consumer.onMessage(ack, ACK_TYPE, () -> { });
        List<Map<String, Object>> afterFirst = ackRows(e.requestId());
        consumer.onMessage(ack, ACK_TYPE, () -> { });
        consumer.onMessage(ack, ACK_TYPE, () -> { });
        assertThat(ackRows(e.requestId())).isEqualTo(afterFirst);
        assertIncomplete(e, "IN_PROGRESS");

        for (String service : PARTICIPANTS) {
            deliver(e, service, "SUCCESS");
        }
        assertThat(requestStatus(e.requestId())).isEqualTo("COMPLETE");
        String erasedEmail = userEmail(e.userId());
        List<Map<String, Object>> completed = ackRows(e.requestId());

        consumer.onMessage(ack, ACK_TYPE, () -> { });
        deliver(e, participant, "SUCCESS"); // coordinator-side replay: new eventId, same (request, service)
        assertThat(requestStatus(e.requestId())).isEqualTo("COMPLETE");
        assertThat(userEmail(e.userId())).isEqualTo(erasedEmail);
        assertThat(ackRows(e.requestId())).hasSize(8);
        assertThat(ackStatuses(e.requestId())).isEqualTo(statuses(completed));
    }

    private record Erasure(UUID requestId, UUID userId) {
    }

    private Erasure start() {
        Instant now = Instant.now();
        UUID roleId = roles.findByName(RoleName.USER).orElseThrow().getId();
        AuthUser user = AuthUser.register(
                "rider-" + UUID.randomUUID() + "@example.com", "hash", "vhash", now.plusSeconds(3600), now,
                EmailLocale.TR, Set.of(new Role(roleId, RoleName.USER)), now);
        user.verifyEmail(now);
        users.save(user);
        UUID requestId = erasure.requestDeletion(user.id(), PASSWORD).erasureRequestId();
        assertThat(requestStatus(requestId)).isEqualTo("IN_PROGRESS");
        assertThat(userStatus(user.id())).isEqualTo("ERASURE_IN_PROGRESS");
        return new Erasure(requestId, user.id());
    }

    private void assertIncomplete(Erasure e, String expectedRequestStatus) {
        assertThat(requestStatus(e.requestId())).isEqualTo(expectedRequestStatus);
        assertThat(userStatus(e.userId())).isEqualTo("ERASURE_IN_PROGRESS");
        assertThat(userEmail(e.userId())).doesNotStartWith("erased-");
    }

    private void deliver(Erasure e, String service, String status) throws Exception {
        consumer.onMessage(record(e, service, status), ACK_TYPE, () -> { });
    }

    /** The envelope a participant ACK outbox relay publishes to {@code parkio.privacy.erasure}. */
    private ConsumerRecord<String, String> record(Erasure e, String service, String status) throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("erasureRequestId", e.requestId().toString());
        payload.put("authUserId", e.userId().toString());
        payload.put("serviceName", service);
        payload.put("status", status);
        payload.put("occurredAt", occurredAt.toString());
        EventEnvelope envelope = new EventEnvelope(eventId, ACK_TYPE, "AccountErasure", e.requestId(),
                occurredAt, 1, null, objectMapper.valueToTree(payload));
        return new ConsumerRecord<>(ErasureAckKafkaConsumer.TOPIC, 0, 0L, e.requestId().toString(),
                objectMapper.writeValueAsString(envelope));
    }

    private String requestStatus(UUID requestId) {
        return jdbc.queryForObject("SELECT status FROM erasure_requests WHERE id = ?", String.class, requestId);
    }

    private String userStatus(UUID userId) {
        return jdbc.queryForObject("SELECT status FROM auth_users WHERE id = ?", String.class, userId);
    }

    private String userEmail(UUID userId) {
        return jdbc.queryForObject("SELECT email FROM auth_users WHERE id = ?", String.class, userId);
    }

    private List<Map<String, Object>> ackRows(UUID requestId) {
        return jdbc.queryForList(
                "SELECT * FROM erasure_service_acks WHERE erasure_request_id = ? ORDER BY service_name", requestId);
    }

    private Map<String, String> ackStatuses(UUID requestId) {
        return statuses(ackRows(requestId));
    }

    private static Map<String, String> statuses(List<Map<String, Object>> rows) {
        Map<String, String> byService = new LinkedHashMap<>();
        rows.forEach(row -> byService.put((String) row.get("service_name"), (String) row.get("status")));
        return byService;
    }
}
