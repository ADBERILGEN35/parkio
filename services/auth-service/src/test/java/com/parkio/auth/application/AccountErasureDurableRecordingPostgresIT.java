package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.support.InMemoryDurableErasureRecordStore;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserErasureAcknowledgedEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.metrics.ErasureMetrics;
import com.parkio.auth.infrastructure.persistence.entity.ErasureRequestEntity;
import com.parkio.auth.infrastructure.persistence.jpa.ErasedUserTombstoneJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureRequestJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.ErasureServiceAckJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Import(AccountErasureDurableRecordingPostgresIT.Config.class)
class AccountErasureDurableRecordingPostgresIT {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final String PASSWORD = "pw";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_durable_it")
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
        registry.add("parkio.privacy.account-erasure.durable-recording-enabled", () -> "true");
        registry.add("parkio.privacy.account-erasure.participants", () -> "user");
    }

    @TestConfiguration
    static class Config {
        @Bean
        InMemoryDurableErasureRecordStore durableErasureRecordStore() {
            return new InMemoryDurableErasureRecordStore();
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

    @Autowired private AccountErasureApplicationService enabledService;
    @Autowired private InMemoryDurableErasureRecordStore store;
    @Autowired private ErasureRequestJpaRepository requests;
    @Autowired private ErasureServiceAckJpaRepository acks;
    @Autowired private ErasedUserTombstoneJpaRepository tombstones;
    @Autowired private ErasureMetrics metrics;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        acks.deleteAll();
        requests.deleteAll();
        tombstones.deleteAll();
        store.clear();
        when(users.save(any(AuthUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordHasher.matches(eq(PASSWORD), eq("hash"))).thenReturn(true);
        when(passwordHasher.hash(any())).thenReturn("replacement-hash");
        when(inbox.tryClaim(any(), any(), any())).thenReturn(true);
    }

    @Test
    void disabledFlagCompletesWithoutDurableColumn() {
        AuthUser user = newUser();
        AccountErasureApplicationService disabled = new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, metrics, clock, true, "user", false, null,
                transactionManager);
        var view = disabled.requestDeletion(user.id(), PASSWORD);
        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(recordingStatus(view.erasureRequestId())).isNull();
        assertThat(store.size()).isZero();

        disabled.handleAcknowledgement(ack(view.erasureRequestId(), user.id()));
        assertThat(status(view.erasureRequestId())).isEqualTo("COMPLETE");
        assertThat(recordingStatus(view.erasureRequestId())).isNull();
    }

    @Test
    void enabledWithoutProviderFailsExplicitly() {
        AuthUser user = newUser();
        AccountErasureApplicationService missing = new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, metrics, clock, true, "user", true, null,
                transactionManager);
        assertThatThrownBy(() -> missing.requestDeletion(user.id(), PASSWORD))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        assertThat(requests.count()).isZero();
        assertThat(store.size()).isZero();
    }

    @Test
    void persistenceFailureLeavesInProgressAndRetryAfterRestartCompletes() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        assertThatThrownBy(() -> enabledService.requestDeletion(user.id(), PASSWORD))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        ErasureRequestEntity pending = requests.findAll().get(0);
        assertThat(pending.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(pending.getDurableRecordingStatus()).isEqualTo("PENDING_DURABLE");
        assertThat(store.size()).isZero();
        assertThat(store.lastPutSawActiveTransaction()).isFalse();

        enabledService.handleAcknowledgement(ack(pending.getId(), user.id()));
        assertThat(status(pending.getId())).isEqualTo("IN_PROGRESS");

        AccountErasureApplicationService restarted = new AccountErasureApplicationService(
                users, refreshTokens, passwordResets, passwordHasher, outbox, inbox,
                requests, acks, tombstones, metrics, clock, true, "user", true, store,
                transactionManager);
        restarted.persistDurableRecord(pending.getId());
        restarted.persistDurableRecord(pending.getId());
        assertThat(recordingStatus(pending.getId())).isEqualTo("DURABLY_RECORDED");
        assertThat(status(pending.getId())).isEqualTo("COMPLETE");
        assertThat(store.size()).isEqualTo(1);
        assertThat(columnIsVarchar("erasure_requests", "durable_recording_status")).isTrue();
    }

    @Test
    void requestRetryAfterFailedPersistAndAllAcksCommitsComplete() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        assertThatThrownBy(() -> enabledService.requestDeletion(user.id(), PASSWORD))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.DURABLE_RECORDING_UNAVAILABLE);
        UUID requestId = requests.findAll().get(0).getId();
        enabledService.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("IN_PROGRESS");
        assertThat(recordingStatus(requestId)).isEqualTo("PENDING_DURABLE");

        var retry = enabledService.requestDeletion(user.id(), PASSWORD);
        assertThat(retry.erasureRequestId()).isEqualTo(requestId);
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        assertThat(status(requestId)).isEqualTo("COMPLETE");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASED);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void persistThenAckCompletesAndDoesNotHoldTransactionDuringPersist() {
        AuthUser user = newUser();
        var view = enabledService.requestDeletion(user.id(), PASSWORD);
        assertThat(view.status()).isEqualTo("IN_PROGRESS");
        assertThat(recordingStatus(view.erasureRequestId())).isEqualTo("DURABLY_RECORDED");
        assertThat(store.lastPutSawActiveTransaction()).isFalse();
        assertThat(store.findByRequestId(view.erasureRequestId())).isPresent();

        enabledService.handleAcknowledgement(ack(view.erasureRequestId(), user.id()));
        assertThat(status(view.erasureRequestId())).isEqualTo("COMPLETE");
        assertThat(user.status()).isEqualTo(AuthUserStatus.ERASED);
    }

    @Test
    void ackThenPersistCompletes() {
        AuthUser user = newUser();
        store.failNextPuts(1);
        assertThatThrownBy(() -> enabledService.requestDeletion(user.id(), PASSWORD))
                .isInstanceOf(AuthException.class);
        UUID requestId = requests.findAll().get(0).getId();
        enabledService.handleAcknowledgement(ack(requestId, user.id()));
        assertThat(status(requestId)).isEqualTo("IN_PROGRESS");
        store.clear();
        enabledService.persistDurableRecord(requestId);
        assertThat(recordingStatus(requestId)).isEqualTo("DURABLY_RECORDED");
        assertThat(status(requestId)).isEqualTo("COMPLETE");
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
        return new UserErasureAcknowledgedEvent(
                UUID.randomUUID(), requestId, userId, "user", "SUCCESS", NOW);
    }

    private String status(UUID requestId) {
        return requests.findById(requestId).orElseThrow().getStatus();
    }

    private String recordingStatus(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT durable_recording_status FROM erasure_requests WHERE id = ?",
                String.class, requestId);
    }

    private boolean columnIsVarchar(String table, String column) {
        String type = jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, String.class, table, column);
        return "character varying".equals(type);
    }
}
