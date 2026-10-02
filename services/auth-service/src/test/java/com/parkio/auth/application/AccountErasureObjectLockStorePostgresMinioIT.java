package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.DurableErasureRecordStore;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.durable.ObjectLockDurableErasureRecordStore;
import com.parkio.auth.infrastructure.durable.ObjectLockTestBuckets;
import com.parkio.auth.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureRequestJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureServiceAckJpaRepository;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The erasure service with the object-lock store enabled through configuration, on real
 * PostgreSQL (Flyway) and a disposable MinIO object-lock bucket: a request is durably recorded
 * in evidence format v1 before COMPLETE, and an unreachable store leaves it PENDING_DURABLE,
 * without COMPLETE, until a retry after the store is back. Synthetic users and keys only.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountErasureObjectLockStorePostgresMinioIT {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final String PASSWORD = "pw";
    private static final String ACCESS_KEY = "parkio-test";
    private static final String SECRET_KEY = "parkio-test-secret";
    private static final String BUCKET = "parkio-erasure-evidence-service-it";
    private static final String DATABASE = "auth-db:object-lock-service-it";
    private static final String PRODUCER_ID = "auth-object-lock-service-it";
    private static final String PRODUCER_KEY = "object-lock-service-it-key-not-a-secret";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_object_lock_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse(
                    // RELEASE.2024-09-13T20-26-02Z; same publisher digest as CI Compose.
                    "ghcr.io/adberilgen35/parkio/minio@sha256:efba309ba4dc89e48f37304db52a0b854c0e701ba944ca02205c4e292c1a756c"))
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

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
        registry.add("parkio.privacy.account-erasure.enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.durable-recording-enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.participants", () -> "user");
        String prefix = "parkio.privacy.account-erasure.durable-store.object-lock.";
        registry.add(prefix + "enabled", () -> "true");
        registry.add(prefix + "endpoint", AccountErasureObjectLockStorePostgresMinioIT::minioEndpoint);
        registry.add(prefix + "bucket", () -> BUCKET);
        registry.add(prefix + "access-key", () -> ACCESS_KEY);
        registry.add(prefix + "secret-key", () -> SECRET_KEY);
        registry.add(prefix + "retention-mode", () -> "GOVERNANCE");
        registry.add(prefix + "retention", () -> "1d");
        registry.add(prefix + "database-identity", () -> DATABASE);
        registry.add(prefix + "producer-id", () -> PRODUCER_ID);
        registry.add(prefix + "producer-key", () -> PRODUCER_KEY);
        registry.add(prefix + "call-timeout", () -> "3s");
        // The store checks object lock at startup, so the bucket must exist first.
        try {
            ObjectLockTestBuckets.createLockedBucket(minioClient(), BUCKET);
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the object-lock test bucket", ex);
        }
    }

    @MockBean private AuthUserRepository users;
    @MockBean private RefreshTokenRepository refreshTokens;
    @MockBean private PasswordResetRepository passwordResets;
    @MockBean private PasswordHasher passwordHasher;
    @MockBean private OutboxEventAppender outbox;
    @MockBean private InboxEventRepository inbox;
    @MockBean private LoginFailureTracker loginFailureTracker;
    @MockBean private EmailVerificationSender emailVerificationSender;

    @Autowired private AccountErasureApplicationService service;
    @Autowired private DurableErasureRecordStore store;
    @Autowired private ErasureRequestJpaRepository requests;
    @Autowired private ErasureServiceAckJpaRepository acks;
    @Autowired private ErasedUserTombstoneJpaRepository tombstones;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        acks.deleteAll();
        requests.deleteAll();
        tombstones.deleteAll();
        when(users.save(any(AuthUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordHasher.matches(eq(PASSWORD), eq("hash"))).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), any(), any())).thenReturn(true);
    }

    @Test
    void theConfiguredStoreIsTheObjectLockStore() {
        assertThat(store).isInstanceOf(ObjectLockDurableErasureRecordStore.class);
    }

    @Test
    void aRequestIsDurablyRecordedInFormatV1BeforeComplete() {
        AuthUser user = newUser();

        var view = service.requestDeletion(user.id(), PASSWORD);

        assertThat(recordingStatus(view.erasureRequestId())).isEqualTo("DURABLY_RECORDED");
        assertThat(recoveredRequestIds()).contains(view.erasureRequestId().toString());
        service.handleAcknowledgement(ack(view.erasureRequestId(), user.id()));
        assertThat(status(view.erasureRequestId())).isEqualTo("COMPLETE");
    }

    @Test
    void anUnreachableStoreLeavesTheRequestPendingDurableUntilARetry() throws Exception {
        AuthUser user = newUser();
        UUID requestId;
        MINIO.getDockerClient().pauseContainerCmd(MINIO.getContainerId()).exec();
        try {
            var view = service.requestDeletion(user.id(), PASSWORD);
            requestId = view.erasureRequestId();
            assertThat(view.status()).isEqualTo("IN_PROGRESS");
            assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");

            // Completing needs the durable evidence; the ack transaction rolls back and the
            // consumer redelivers it later, so nothing completes while the store is away.
            UserErasureAcknowledgedEvent ack = ack(requestId, user.id());
            assertThatThrownBy(() -> service.handleAcknowledgement(ack))
                    .isInstanceOf(AuthException.class)
                    .extracting(ex -> ((AuthException) ex).errorCode())
                    .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
            assertThat(status(requestId)).isEqualTo("IN_PROGRESS");
            assertThat(acks.count()).isZero();
        } finally {
            MINIO.getDockerClient().unpauseContainerCmd(MINIO.getContainerId()).exec();
            ObjectLockTestBuckets.awaitReady(minioEndpoint());
            // Also wait until bucket calls stop answering XMinioServerNotInitialized.
            ObjectLockTestBuckets.createLockedBucket(minioClient(), BUCKET);
        }
        assertThat(recoveredRequestIds()).doesNotContain(requestId.toString());

        service.persistDurableRecord(requestId);

        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        assertThat(status(requestId)).isEqualTo("IN_PROGRESS");
        assertThat(recoveredRequestIds()).contains(requestId.toString());

        service.handleAcknowledgement(ack(requestId, user.id()));

        assertThat(status(requestId)).isEqualTo("COMPLETE");
    }

    /** Request ids recoverable from the bucket alone (UNKNOWN only before the first record). */
    private List<String> recoveredRequestIds() {
        RecoveryVerdict verdict = new DurableErasureEvidenceVerifier(
                DATABASE, Map.of(PRODUCER_ID, PRODUCER_KEY.getBytes(StandardCharsets.UTF_8)))
                .recover(((ObjectLockDurableErasureRecordStore) store).evidence(), null);
        assertThat(verdict.verdict()).isIn(Verdict.ACCEPT_ISOLATED, Verdict.UNKNOWN);
        return verdict.pending().stream().map(VerifiedPending::erasureRequestId).toList();
    }

    private AuthUser newUser() {
        AuthUser user = AuthUser.register(
                "rider-" + UUID.randomUUID() + "@example.com",
                "hash",
                "vhash",
                NOW.plusSeconds(3600),
                NOW,
                EmailLocale.TR,
                Set.of(new Role(UUID.randomUUID(), RoleName.USER)),
                NOW);
        user.verifyEmail(NOW);
        when(users.findById(user.id())).thenReturn(Optional.of(user));
        return user;
    }

    private UserErasureAcknowledgedEvent ack(UUID requestId, UUID userId) {
        return new UserErasureAcknowledgedEvent(UUID.randomUUID(), requestId, userId, "user", "SUCCESS", NOW);
    }

    private String status(UUID requestId) {
        return requests.findById(requestId).orElseThrow().getStatus();
    }

    private String recordingStatus(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT durable_recording_status FROM erasure_requests WHERE id = ?", String.class, requestId);
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static MinioClient minioClient() {
        return MinioClient.builder().endpoint(minioEndpoint()).credentials(ACCESS_KEY, SECRET_KEY).region("us-east-1").build();
    }
}
