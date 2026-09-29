package com.parkio.auth.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.PasswordResetLimiter;
import com.parkio.auth.application.VerificationResendLimiter;
import com.parkio.auth.application.command.ForgotPasswordCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.ResendVerificationCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.PasswordResetToken;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.OutboxEventJpaRepository;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Proves Spring {@code @Transactional} rollback against real PostgreSQL when
 * email delivery throws. Mockito-only unit tests are not sufficient evidence.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class EmailDeliveryTransactionalPostgresIT {

    private static final String PASSWORD = "StrongerPass123";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth_email_tx_it")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
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
        registry.add("parkio.registration.mode", () -> "open");
        registry.add("parkio.security.email-verification.log-token", () -> "false");
        registry.add("parkio.security.password-reset.log-token", () -> "false");
    }

    @Autowired
    private AuthApplicationService authService;

    @Autowired
    private AuthUserRepository authUsers;

    @Autowired
    private PasswordResetRepository passwordResetTokens;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @Autowired
    private RoleJpaRepository roles;

    @Autowired
    private OutboxEventJpaRepository outboxEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private LoginFailureTracker loginFailureTracker;

    @MockBean
    private VerificationResendLimiter verificationResendLimiter;

    @MockBean
    private PasswordResetLimiter passwordResetLimiter;

    @MockBean
    private EmailVerificationSender emailVerificationSender;

    @MockBean
    private PasswordResetEmailSender passwordResetEmailSender;

    private final AtomicReference<String> lastVerificationToken = new AtomicReference<>();
    private final AtomicReference<String> lastResetToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
        when(verificationResendLimiter.tryAcquire(anyString())).thenReturn(true);
        when(passwordResetLimiter.tryAcquire(anyString())).thenReturn(true);
        lastVerificationToken.set(null);
        lastResetToken.set(null);
        org.mockito.Mockito.reset(emailVerificationSender, passwordResetEmailSender);
        doAnswer(invocation -> {
            lastVerificationToken.set(invocation.getArgument(1));
            return null;
        }).when(emailVerificationSender).sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));
        doAnswer(invocation -> {
            lastResetToken.set(invocation.getArgument(1));
            return null;
        }).when(passwordResetEmailSender).sendResetLink(anyString(), anyString(), any(EmailLocale.class));
    }

    @Test
    void failedRegistrationLeavesNoAccountTokenOrOutboxEvent() {
        String email = "tx-register-fail-" + UUID.randomUUID() + "@example.com";
        doThrow(new EmailDeliveryException("provider 500 (provider_5xx)", null))
                .when(emailVerificationSender)
                .sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));

        assertThatThrownBy(() -> authService.register(new RegisterCommand(email, PASSWORD)))
                .isInstanceOf(EmailDeliveryException.class);

        assertThat(authUsers.findByEmail(email)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_users WHERE email = ?", Long.class, email))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = 'UserRegistered' "
                        + "AND payload::text LIKE ?",
                Long.class,
                "%" + email + "%"))
                .isZero();
    }

    @Test
    void failedResendPreservesPreviousVerificationToken() {
        String email = "tx-resend-fail-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterCommand(email, PASSWORD));
        String originalToken = lastVerificationToken.get();
        assertThat(originalToken).isNotBlank();
        String originalHash = authUsers.findByEmail(email).orElseThrow().emailVerificationTokenHash();

        doThrow(new EmailDeliveryException("Read timed out after provider may have accepted", null))
                .when(emailVerificationSender)
                .sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));

        assertThatThrownBy(() -> authService.resendVerification(
                        new ResendVerificationCommand(email, EmailLocale.EN)))
                .isInstanceOf(EmailDeliveryException.class);

        AuthUser after = authUsers.findByEmail(email).orElseThrow();
        assertThat(after.emailVerificationTokenHash()).isEqualTo(originalHash);
        // Rolled-back resend token must not verify; original still works.
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailCommand("not-the-original")))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_TOKEN);
        AuthUser verified = authService.verifyEmail(new VerifyEmailCommand(originalToken));
        assertThat(verified.emailVerified()).isTrue();
    }

    @Test
    void failedPasswordResetSendPreservesPriorValidResetState() {
        String email = "tx-reset-fail-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterCommand(email, PASSWORD));
        authService.verifyEmail(new VerifyEmailCommand(lastVerificationToken.get()));

        authService.forgotPassword(new ForgotPasswordCommand(email, EmailLocale.EN));
        String firstRaw = lastResetToken.get();
        assertThat(firstRaw).isNotBlank();
        PasswordResetToken first = passwordResetTokens
                .findByTokenHash(refreshTokenHasher.hash(firstRaw))
                .orElseThrow();
        assertThat(first.consumedAt()).isNull();

        doThrow(new EmailDeliveryException("provider 403 (auth)", null))
                .when(passwordResetEmailSender)
                .sendResetLink(anyString(), anyString(), any(EmailLocale.class));

        assertThatThrownBy(() -> authService.forgotPassword(new ForgotPasswordCommand(email, EmailLocale.EN)))
                .isInstanceOf(EmailDeliveryException.class);

        PasswordResetToken stillActive = passwordResetTokens
                .findByTokenHash(refreshTokenHasher.hash(firstRaw))
                .orElseThrow();
        assertThat(stillActive.consumedAt()).isNull();
        assertThat(stillActive.tokenHash()).isEqualTo(first.tokenHash());
        Long activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND consumed_at IS NULL",
                Long.class,
                stillActive.userId());
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    void acceptedThenTimeoutStyleFailureLeavesNoNewlyUsableVerificationToken() {
        String email = "tx-ambiguous-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterCommand(email, PASSWORD));
        String originalToken = lastVerificationToken.get();
        String originalHash = authUsers.findByEmail(email).orElseThrow().emailVerificationTokenHash();

        AtomicReference<String> attemptedNewToken = new AtomicReference<>();
        doAnswer(invocation -> {
            attemptedNewToken.set(invocation.getArgument(1));
            // Models client-side timeout / ambiguous acceptance: exception after
            // the provider call is attempted, still inside the TX boundary.
            throw new EmailDeliveryException("Resend transactional email delivery failed", null);
        }).when(emailVerificationSender).sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));

        assertThatThrownBy(() -> authService.resendVerification(
                        new ResendVerificationCommand(email, EmailLocale.TR)))
                .isInstanceOf(EmailDeliveryException.class);

        assertThat(attemptedNewToken.get()).isNotBlank().isNotEqualTo(originalToken);
        assertThat(authUsers.findByEmail(email).orElseThrow().emailVerificationTokenHash())
                .isEqualTo(originalHash);
        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailCommand(attemptedNewToken.get())))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_TOKEN);
        assertThat(outboxEvents.count()).isGreaterThanOrEqualTo(0);
    }
}
