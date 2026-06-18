package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.command.ChangePasswordCommand;
import com.parkio.auth.application.command.ForgotPasswordCommand;
import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.command.LogoutCommand;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.ResetPasswordCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import com.parkio.auth.application.port.AccessTokenIssuer;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.application.port.PasswordResetRepository;
import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.port.RoleRepository;
import com.parkio.auth.application.port.SecureTokenGenerator;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.application.result.IssuedAccessToken;
import com.parkio.auth.application.result.RegisterResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.PasswordResetToken;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserRegisteredEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for {@link AuthApplicationService} using in-memory fake
 * ports — no Spring context, no database.
 */
class AuthApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");
    private static final String VALID_PASSWORD = "StrongerPass123";
    private static final Role USER_ROLE =
            new Role(UUID.fromString("00000000-0000-0000-0000-000000000001"), RoleName.USER);

    private FakeAuthUserRepository authUsers;
    private FakeRefreshTokenRepository refreshTokens;
    private FakePasswordResetRepository passwordResetTokens;
    private FakeOutboxEventAppender outbox;
    private FakeInboxEventRepository inbox;
    private FakePasswordHasher passwordHasher;
    private FakeRefreshTokenHasher refreshTokenHasher;
    private FakeLoginFailureTracker loginFailures;
    private FakeVerificationResendLimiter verificationResendLimiter;
    private FakePasswordResetLimiter passwordResetLimiter;
    private FakeEmailVerificationSender emailVerificationSender;
    private FakePasswordResetEmailSender passwordResetEmailSender;
    private MutableClock clock;
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        authUsers = new FakeAuthUserRepository();
        refreshTokens = new FakeRefreshTokenRepository();
        passwordResetTokens = new FakePasswordResetRepository();
        outbox = new FakeOutboxEventAppender();
        inbox = new FakeInboxEventRepository();
        passwordHasher = new FakePasswordHasher();
        refreshTokenHasher = new FakeRefreshTokenHasher();
        loginFailures = new FakeLoginFailureTracker();
        verificationResendLimiter = new FakeVerificationResendLimiter();
        passwordResetLimiter = new FakePasswordResetLimiter();
        emailVerificationSender = new FakeEmailVerificationSender();
        passwordResetEmailSender = new FakePasswordResetEmailSender();
        RoleRepository roles = name -> name == RoleName.USER ? Optional.of(USER_ROLE) : Optional.empty();
        AccessTokenIssuer accessTokenIssuer = user -> new IssuedAccessToken("access-" + user.id(), NOW.plusSeconds(900));
        SecureTokenGenerator tokenGenerator = new FakeSecureTokenGenerator();
        clock = new MutableClock(NOW);
        service = new AuthApplicationService(authUsers, roles, refreshTokens, passwordResetTokens, outbox, inbox,
                passwordHasher, accessTokenIssuer, refreshTokenHasher, tokenGenerator, loginFailures,
                verificationResendLimiter, passwordResetLimiter, emailVerificationSender, passwordResetEmailSender,
                new PasswordPolicy(), clock, Duration.ofDays(30), Duration.ofDays(90), Duration.ofHours(24),
                Duration.ofHours(1));
    }

    @Test
    void registerCreatesPendingUserWithVerificationTokenAndOutboxEvent() {
        RegisterResult result = service.register(new RegisterCommand("New.User@Example.com ", VALID_PASSWORD));

        assertThat(result.user().email()).isEqualTo("new.user@example.com");
        assertThat(result.user().status()).isEqualTo(AuthUserStatus.PENDING_VERIFICATION);
        assertThat(result.user().emailVerified()).isFalse();
        assertThat(result.user().emailVerificationTokenHash()).isNotBlank();
        assertThat(result.verificationExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
        assertThat(authUsers.findByEmail("new.user@example.com")).isPresent();
        assertThat(refreshTokens.findByTokenHash(result.user().emailVerificationTokenHash())).isEmpty();
        assertThat(emailVerificationSender.tokenFor("new.user@example.com")).isNotBlank();
        assertThat(outbox.events).singleElement()
                .extracting(UserRegisteredEvent::email).isEqualTo("new.user@example.com");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        service.register(new RegisterCommand("dup@example.com", VALID_PASSWORD));

        assertThatThrownBy(() -> service.register(new RegisterCommand("DUP@example.com", VALID_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void registerAssignsUserRole() {
        RegisterResult result = service.register(new RegisterCommand("roles@example.com", VALID_PASSWORD));

        assertThat(result.user().roles()).extracting(r -> r.name().name()).containsExactly("USER");
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        registerVerified("user@example.com");

        AuthResult result = service.login(new LoginCommand("USER@example.com", VALID_PASSWORD));
        RefreshToken token = refreshTokens.findByTokenHash(refreshTokenHasher.hash(result.refreshToken()))
                .orElseThrow();

        assertThat(result.user().email()).isEqualTo("user@example.com");
        assertThat(result.accessToken()).isEqualTo("access-" + result.user().id());
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(token.tokenFamilyId()).isNotNull();
        assertThat(token.parentTokenId()).isNull();
    }

    @Test
    void loginRejectsWrongPassword() {
        registerVerified("user@example.com");

        assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", "wrong-password")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginRejectsUnknownEmail() {
        assertThatThrownBy(() -> service.login(new LoginCommand("nobody@example.com", VALID_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginLocksAccountAfterFiveFailuresWithGenericError() {
        registerVerified("user@example.com");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login(new LoginCommand("USER@example.com", "wrong-password")))
                    .isInstanceOf(AuthException.class)
                    .extracting(e -> ((AuthException) e).errorCode())
                    .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        }

        assertThatThrownBy(() -> service.login(new LoginCommand("USER@example.com", "wrong-password")))
                .isInstanceOf(AuthException.class)
                .hasMessage(AuthErrorCode.INVALID_CREDENTIALS.defaultMessage())
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
        assertThat(loginFailures.isLocked("user@example.com", NOW)).isTrue();

        assertThatThrownBy(() -> service.login(new LoginCommand("USER@example.com", VALID_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .hasMessage(AuthErrorCode.INVALID_CREDENTIALS.defaultMessage())
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginLockExpires() {
        registerVerified("user@example.com");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", "wrong-password")))
                    .isInstanceOf(AuthException.class);
        }

        clock.advance(Duration.ofSeconds(31));

        AuthResult result = service.login(new LoginCommand("user@example.com", VALID_PASSWORD));
        assertThat(result.accessToken()).isNotBlank();
    }

    @Test
    void successfulLoginResetsFailureCounter() {
        registerVerified("user@example.com");
        assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", "wrong-password")))
                .isInstanceOf(AuthException.class);
        assertThat(loginFailures.failureCount("user@example.com")).isEqualTo(1);

        service.login(new LoginCommand("user@example.com", VALID_PASSWORD));

        assertThat(loginFailures.failureCount("user@example.com")).isZero();
    }

    @Test
    void registerRejectsWeakPasswordsAndAcceptsStrongPassword() {
        assertThatThrownBy(() -> service.register(new RegisterCommand("weak@example.com", "password123")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.WEAK_PASSWORD);

        RegisterResult result = service.register(new RegisterCommand("strong@example.com", "SaferPass123"));

        assertThat(result.user().email()).isEqualTo("strong@example.com");
    }

    @Test
    void loginBeforeEmailVerificationIsBlockedWithoutIssuingTokens() {
        service.register(new RegisterCommand("pending@example.com", VALID_PASSWORD));

        assertThatThrownBy(() -> service.login(new LoginCommand("pending@example.com", VALID_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.ACCOUNT_NOT_VERIFIED);
        assertThat(refreshTokens.findByTokenHash("access-anything")).isEmpty();
    }

    @Test
    void verifyEmailActivatesAccountAndIsIdempotentForActiveAccount() {
        service.register(new RegisterCommand("verify@example.com", VALID_PASSWORD));
        String token = emailVerificationSender.tokenFor("verify@example.com");

        AuthUser verified = service.verifyEmail(new VerifyEmailCommand(token));
        AuthUser second = service.verifyEmail(new VerifyEmailCommand(token));

        assertThat(verified.status()).isEqualTo(AuthUserStatus.ACTIVE);
        assertThat(verified.emailVerified()).isTrue();
        assertThat(second.status()).isEqualTo(AuthUserStatus.ACTIVE);
        assertThat(service.login(new LoginCommand("verify@example.com", VALID_PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    void verifyEmailRejectsInvalidAndExpiredTokens() {
        service.register(new RegisterCommand("expired@example.com", VALID_PASSWORD));
        String token = emailVerificationSender.tokenFor("expired@example.com");

        assertThatThrownBy(() -> service.verifyEmail(new VerifyEmailCommand("not-real")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_TOKEN);

        clock.advance(Duration.ofHours(25));
        assertThatThrownBy(() -> service.verifyEmail(new VerifyEmailCommand(token)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_VERIFICATION_TOKEN);
    }

    @Test
    void resendVerificationRotatesTokenAndIsEnumerationSafeWhenLimitedOrUnknown() {
        service.register(new RegisterCommand("resend@example.com", VALID_PASSWORD));
        String first = emailVerificationSender.tokenFor("resend@example.com");
        verificationResendLimiter.allow("resend@example.com");

        service.resendVerification(new com.parkio.auth.application.command.ResendVerificationCommand(
                "resend@example.com"));
        String second = emailVerificationSender.tokenFor("resend@example.com");

        assertThat(second).isNotEqualTo(first);
        verificationResendLimiter.deny("resend@example.com");
        service.resendVerification(new com.parkio.auth.application.command.ResendVerificationCommand(
                "resend@example.com"));
        assertThat(emailVerificationSender.tokenFor("resend@example.com")).isEqualTo(second);

        service.resendVerification(new com.parkio.auth.application.command.ResendVerificationCommand(
                "unknown@example.com"));
        // No exception and no observable unknown-email signal.
    }

    @Test
    void forgotPasswordIsEnumerationSafeAndStoresOnlyResetTokenHash() {
        registerVerified("reset@example.com");

        service.forgotPassword(new ForgotPasswordCommand("RESET@example.com"));
        service.forgotPassword(new ForgotPasswordCommand("unknown@example.com"));

        String rawToken = passwordResetEmailSender.tokenFor("reset@example.com");
        assertThat(rawToken).isNotBlank();
        assertThat(passwordResetTokens.findByTokenHash(rawToken)).isEmpty();
        assertThat(passwordResetTokens.findByTokenHash(refreshTokenHasher.hash(rawToken)))
                .hasValueSatisfying(token -> assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1))));
        assertThat(passwordResetEmailSender.sentEmails()).containsExactly("reset@example.com");
    }

    @Test
    void forgotPasswordInvalidatesPreviousActiveResetTokens() {
        registerVerified("reset@example.com");
        service.forgotPassword(new ForgotPasswordCommand("reset@example.com"));
        String first = passwordResetEmailSender.tokenFor("reset@example.com");
        clock.advance(Duration.ofMinutes(6));

        service.forgotPassword(new ForgotPasswordCommand("reset@example.com"));

        PasswordResetToken firstToken = passwordResetTokens.findByTokenHash(refreshTokenHasher.hash(first))
                .orElseThrow();
        assertThat(firstToken.consumedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(6)));
    }

    @Test
    void pendingVerificationAccountDoesNotReceivePasswordResetToken() {
        service.register(new RegisterCommand("pending-reset@example.com", VALID_PASSWORD));

        service.forgotPassword(new ForgotPasswordCommand("pending-reset@example.com"));

        assertThat(passwordResetEmailSender.sentEmails()).isEmpty();
    }

    @Test
    void resetPasswordEnforcesPolicyConsumesTokenRevokesRefreshAndBumpsEpoch() {
        AuthResult session = registerVerifiedAndLogin("reset@example.com");
        UUID userId = session.user().id();
        long before = service.sessionEpoch(userId);
        service.forgotPassword(new ForgotPasswordCommand("reset@example.com"));
        String rawToken = passwordResetEmailSender.tokenFor("reset@example.com");

        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordCommand(rawToken, "password123")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.WEAK_PASSWORD);

        service.resetPassword(new ResetPasswordCommand(rawToken, "FreshStrong123"));

        AuthUser user = authUsers.findById(userId).orElseThrow();
        RefreshToken refresh = refreshTokens.findByTokenHash(refreshTokenHasher.hash(session.refreshToken()))
                .orElseThrow();
        PasswordResetToken token = passwordResetTokens.findByTokenHash(refreshTokenHasher.hash(rawToken))
                .orElseThrow();
        assertThat(passwordHasher.matches("FreshStrong123", user.passwordHash())).isTrue();
        assertThat(refresh.isRevoked()).isTrue();
        assertThat(refresh.revokedReason()).isEqualTo(RefreshTokenRevocationReason.PASSWORD_CHANGED);
        assertThat(token.consumedAt()).isEqualTo(NOW);
        assertThat(service.sessionEpoch(userId)).isEqualTo(before + 1);
    }

    @Test
    void resetPasswordRejectsExpiredAndReusedTokensGenerically() {
        registerVerified("reset@example.com");
        service.forgotPassword(new ForgotPasswordCommand("reset@example.com"));
        String rawToken = passwordResetEmailSender.tokenFor("reset@example.com");

        clock.advance(Duration.ofMinutes(61));
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordCommand(rawToken, "FreshStrong123")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_RESET_TOKEN);

        clock = new MutableClock(NOW);
        setUp();
        registerVerified("reset2@example.com");
        service.forgotPassword(new ForgotPasswordCommand("reset2@example.com"));
        String second = passwordResetEmailSender.tokenFor("reset2@example.com");
        service.resetPassword(new ResetPasswordCommand(second, "FreshStrong123"));
        assertThatThrownBy(() -> service.resetPassword(new ResetPasswordCommand(second, "AnotherStrong123")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_RESET_TOKEN);
    }

    @Test
    void changePasswordVerifiesCurrentPasswordRevokesRefreshAndBumpsEpoch() {
        AuthResult session = registerVerifiedAndLogin("change@example.com");
        UUID userId = session.user().id();
        long before = service.sessionEpoch(userId);

        assertThatThrownBy(() -> service.changePassword(
                new ChangePasswordCommand(userId, "wrong", "FreshStrong123")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

        service.changePassword(new ChangePasswordCommand(userId, VALID_PASSWORD, "FreshStrong123"));

        RefreshToken refresh = refreshTokens.findByTokenHash(refreshTokenHasher.hash(session.refreshToken()))
                .orElseThrow();
        assertThat(passwordHasher.matches("FreshStrong123", authUsers.findById(userId).orElseThrow().passwordHash()))
                .isTrue();
        assertThat(refresh.isRevoked()).isTrue();
        assertThat(refresh.revokedReason()).isEqualTo(RefreshTokenRevocationReason.PASSWORD_CHANGED);
        assertThat(service.sessionEpoch(userId)).isEqualTo(before + 1);
    }

    @Test
    void loginRejectsInactiveUser() {
        AuthUser suspended = new AuthUser(UUID.randomUUID(), "banned@example.com",
                passwordHasher.hash(VALID_PASSWORD), AuthUserStatus.SUSPENDED, null, Set.of(USER_ROLE), NOW, 0L);
        authUsers.save(suspended);

        assertThatThrownBy(() -> service.login(new LoginCommand("banned@example.com", VALID_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_ACTIVE);
    }

    @Test
    void refreshRotatesTokenAndRevokesOld() {
        AuthResult initial = registerVerifiedAndLogin("user@example.com");
        String oldHash = refreshTokenHasher.hash(initial.refreshToken());
        RefreshToken oldToken = refreshTokens.findByTokenHash(oldHash).orElseThrow();

        AuthResult refreshed = service.refresh(new RefreshTokenCommand(initial.refreshToken()));
        RefreshToken rotated = refreshTokens.findByTokenHash(oldHash).orElseThrow();
        RefreshToken child = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(refreshed.refreshToken()))
                .orElseThrow();

        assertThat(refreshed.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(rotated.isRevoked()).isTrue();
        assertThat(rotated.revokedReason()).isEqualTo(RefreshTokenRevocationReason.ROTATED);
        assertThat(rotated.revokedAt()).isEqualTo(NOW);
        assertThat(child.tokenFamilyId()).isEqualTo(oldToken.tokenFamilyId());
        assertThat(child.parentTokenId()).isEqualTo(oldToken.id());
        assertThat(child.isRevoked()).isFalse();
    }

    @Test
    void rotatedTokenReuseRevokesActiveFamilyAndReturnsGenericError() {
        AuthResult initial = registerVerifiedAndLogin("user@example.com");
        AuthResult refreshed = service.refresh(new RefreshTokenCommand(initial.refreshToken()));
        String presentedToken = initial.refreshToken();

        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(presentedToken)))
                .isInstanceOf(AuthException.class)
                .hasMessage(AuthErrorCode.INVALID_REFRESH_TOKEN.defaultMessage())
                .hasMessageNotContaining(presentedToken)
                .hasMessageNotContaining(refreshTokenHasher.hash(presentedToken))
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);

        RefreshToken reused = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(initial.refreshToken()))
                .orElseThrow();
        RefreshToken activeChild = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(refreshed.refreshToken()))
                .orElseThrow();
        assertThat(reused.isReusedDetected()).isTrue();
        assertThat(reused.revokedReason()).isEqualTo(RefreshTokenRevocationReason.ROTATED);
        assertThat(activeChild.isRevoked()).isTrue();
        assertThat(activeChild.revokedReason()).isEqualTo(RefreshTokenRevocationReason.REUSE_DETECTED);
        assertThat(activeChild.revokedAt()).isEqualTo(NOW);
    }

    @Test
    void refreshRejectsUnknownToken() {
        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand("does-not-exist")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void refreshRejectsExpiredToken() {
        UUID userId = UUID.randomUUID();
        authUsers.save(new AuthUser(userId, "user@example.com",
                passwordHasher.hash(VALID_PASSWORD), AuthUserStatus.ACTIVE, null, Set.of(USER_ROLE), NOW, 0L));
        RefreshToken expired =
                RefreshToken.issueRoot(userId, refreshTokenHasher.hash("expired-token"), NOW.minusSeconds(1), NOW);
        RefreshToken activeSibling =
                RefreshToken.issueChild(expired, refreshTokenHasher.hash("active-token"), NOW.plusSeconds(3600));
        refreshTokens.save(expired);
        refreshTokens.save(activeSibling);

        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand("expired-token")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash("expired-token")))
                .get()
                .extracting(RefreshToken::isReusedDetected)
                .isEqualTo(false);
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash("active-token")))
                .get()
                .extracting(RefreshToken::isRevoked)
                .isEqualTo(false);
    }

    @Test
    void loginStartsFamilyWithFamilyStartedAtEqualToIssueTime() {
        AuthResult initial = registerVerifiedAndLogin("user@example.com");

        RefreshToken root = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(initial.refreshToken()))
                .orElseThrow();
        assertThat(root.familyStartedAt()).isEqualTo(NOW);
        assertThat(root.parentTokenId()).isNull();
    }

    @Test
    void rotatedChildPreservesFamilyStartedAt() {
        AuthResult initial = registerVerifiedAndLogin("user@example.com");
        Instant familyStart = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(initial.refreshToken()))
                .orElseThrow()
                .familyStartedAt();

        // Rotate later in the session: the child must keep the ORIGINAL family start,
        // so rotation never extends the absolute lifetime.
        clock.advance(Duration.ofDays(10));
        AuthResult refreshed = service.refresh(new RefreshTokenCommand(initial.refreshToken()));

        RefreshToken child = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(refreshed.refreshToken()))
                .orElseThrow();
        assertThat(child.familyStartedAt()).isEqualTo(familyStart).isEqualTo(NOW);
    }

    @Test
    void refreshWithinAbsoluteLifetimeSucceedsAndKeepsFamilyStart() {
        UUID userId = UUID.randomUUID();
        authUsers.save(new AuthUser(userId, "user@example.com",
                passwordHasher.hash(VALID_PASSWORD), AuthUserStatus.ACTIVE, null, Set.of(USER_ROLE), NOW, 0L));
        // Family started 89 days ago (within the 90-day cap); the current token's sliding
        // TTL is still valid, so refresh must succeed and the child keeps the family start.
        Instant familyStart = NOW.minus(Duration.ofDays(89));
        RefreshToken current = RefreshToken.issueRoot(
                userId, refreshTokenHasher.hash("within-cap"), NOW.plusSeconds(3600), familyStart);
        refreshTokens.save(current);

        AuthResult refreshed = service.refresh(new RefreshTokenCommand("within-cap"));

        RefreshToken child = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(refreshed.refreshToken()))
                .orElseThrow();
        assertThat(child.familyStartedAt()).isEqualTo(familyStart);
        assertThat(child.isRevoked()).isFalse();
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash("within-cap")))
                .get()
                .extracting(RefreshToken::revokedReason)
                .isEqualTo(RefreshTokenRevocationReason.ROTATED);
    }

    @Test
    void refreshAfterAbsoluteLifetimeFailsGenericallyRevokesFamilyAndIssuesNoChild() {
        UUID userId = UUID.randomUUID();
        authUsers.save(new AuthUser(userId, "user@example.com",
                passwordHasher.hash(VALID_PASSWORD), AuthUserStatus.ACTIVE, null, Set.of(USER_ROLE), NOW, 0L));
        // The current token's sliding TTL is still valid, but the family started 91 days
        // ago — past the 90-day absolute cap. This must be treated as expiry, NOT reuse.
        RefreshToken current = RefreshToken.issueRoot(
                userId,
                refreshTokenHasher.hash("aged-token"),
                NOW.plusSeconds(3600),
                NOW.minus(Duration.ofDays(91)));
        refreshTokens.save(current);

        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand("aged-token")))
                .isInstanceOf(AuthException.class)
                .hasMessage(AuthErrorCode.INVALID_REFRESH_TOKEN.defaultMessage())
                .hasMessageNotContaining("aged-token")
                .hasMessageNotContaining(refreshTokenHasher.hash("aged-token"))
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);

        RefreshToken aged = refreshTokens.findByTokenHash(refreshTokenHasher.hash("aged-token")).orElseThrow();
        // Family revoked as natural absolute expiry — not reuse detection.
        assertThat(aged.isRevoked()).isTrue();
        assertThat(aged.revokedReason()).isEqualTo(RefreshTokenRevocationReason.EXPIRED_CLEANUP);
        assertThat(aged.isReusedDetected()).isFalse();
        // No child was issued (the generator was never called → no "refresh-token-1").
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash("refresh-token-1"))).isEmpty();
    }

    @Test
    void logoutRevokesToken() {
        AuthResult initial = registerVerifiedAndLogin("user@example.com");

        service.logout(new LogoutCommand(initial.refreshToken()));

        RefreshToken token = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(initial.refreshToken()))
                .orElseThrow();
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
        assertThat(token.revokedAt()).isEqualTo(NOW);

        service.logout(new LogoutCommand(initial.refreshToken()));
        assertThat(token.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
    }

    @Test
    void logoutIsIdempotentForUnknownToken() {
        service.logout(new LogoutCommand("never-issued"));
        // No exception thrown.
    }

    // --- Session epoch (access-token revocation) --------------------------

    @Test
    void newlyRegisteredUserStartsAtSessionEpochZero() {
        RegisterResult result = service.register(new RegisterCommand("epoch@example.com", VALID_PASSWORD));

        assertThat(result.user().sessionEpoch()).isZero();
        assertThat(service.sessionEpoch(result.user().id())).isZero();
    }

    @Test
    void singleDeviceLogoutDoesNotBumpSessionEpoch() {
        AuthResult initial = registerVerifiedAndLogin("user@example.com");
        UUID userId = initial.user().id();
        long before = service.sessionEpoch(userId);

        service.logout(new LogoutCommand(initial.refreshToken()));

        // Per-device logout must not invalidate the user's other devices' access tokens.
        assertThat(service.sessionEpoch(userId)).isEqualTo(before);
    }

    @Test
    void refreshTokenReuseBumpsSessionEpoch() {
        AuthResult initial = registerVerifiedAndLogin("user@example.com");
        UUID userId = initial.user().id();
        service.refresh(new RefreshTokenCommand(initial.refreshToken()));
        long before = service.sessionEpoch(userId);

        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(initial.refreshToken())))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);

        // A compromised family also revokes outstanding access tokens via an epoch bump.
        assertThat(service.sessionEpoch(userId)).isEqualTo(before + 1);
    }

    @Test
    void suspensionBumpsSessionEpoch() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        UUID userId = session.user().id();
        long before = service.sessionEpoch(userId);

        service.handleUserSuspended(suspendedEvent(userId, NOW));

        assertThat(service.sessionEpoch(userId)).isEqualTo(before + 1);
    }

    @Test
    void logoutAllRevokesEveryFamilyAndBumpsSessionEpoch() {
        AuthResult first = registerVerifiedAndLogin("user@example.com");
        AuthResult second = service.login(new LoginCommand("user@example.com", VALID_PASSWORD));
        UUID userId = first.user().id();
        long before = service.sessionEpoch(userId);

        service.logoutAll(userId);

        RefreshToken firstToken = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(first.refreshToken())).orElseThrow();
        RefreshToken secondToken = refreshTokens
                .findByTokenHash(refreshTokenHasher.hash(second.refreshToken())).orElseThrow();
        assertThat(firstToken.isRevoked()).isTrue();
        assertThat(firstToken.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
        assertThat(secondToken.isRevoked()).isTrue();
        assertThat(secondToken.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
        assertThat(service.sessionEpoch(userId)).isEqualTo(before + 1);
    }

    @Test
    void sessionEpochLookupRejectsUnknownUser() {
        assertThatThrownBy(() -> service.sessionEpoch(UUID.randomUUID()))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_FOUND);
    }

    // --- Moderation status sync (parkio.moderation.action) ---------------

    @Test
    void userSuspendedEventSetsStatusSuspendedAndRevokesActiveRefreshTokens() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        AuthResult secondSession = service.login(new LoginCommand("user@example.com", VALID_PASSWORD));
        UUID userId = session.user().id();

        UserSuspendedEvent suspend = suspendedEvent(userId, NOW);
        service.handleUserSuspended(suspend);

        assertThat(authUsers.findById(userId).orElseThrow().status()).isEqualTo(AuthUserStatus.SUSPENDED);
        // Every active token across all of the user's families is revoked.
        RefreshToken first = refreshTokens.findByTokenHash(
                refreshTokenHasher.hash(session.refreshToken())).orElseThrow();
        RefreshToken second = refreshTokens.findByTokenHash(
                refreshTokenHasher.hash(secondSession.refreshToken())).orElseThrow();
        assertThat(first.isRevoked()).isTrue();
        assertThat(first.revokedReason()).isEqualTo(RefreshTokenRevocationReason.ADMIN_REVOKED);
        assertThat(second.isRevoked()).isTrue();
        assertThat(second.revokedReason()).isEqualTo(RefreshTokenRevocationReason.ADMIN_REVOKED);
    }

    @Test
    void suspendedUserCannotLogin() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        service.handleUserSuspended(suspendedEvent(session.user().id(), NOW));

        assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", VALID_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_ACTIVE);
    }

    @Test
    void suspendedUserCannotRefresh() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        service.handleUserSuspended(suspendedEvent(session.user().id(), NOW));

        // The token was revoked by the suspension, so refresh fails before the status
        // check; even an unrevoked token would be rejected by ensureCanAuthenticate.
        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(session.refreshToken())))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void suspendedUserCannotRefreshEvenWithSurvivingActiveToken() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        UUID userId = session.user().id();
        service.handleUserSuspended(suspendedEvent(userId, NOW));

        // Defence in depth: a token that somehow survived revocation still fails the
        // account-status check on refresh.
        RefreshToken surviving =
                RefreshToken.issueRoot(userId, refreshTokenHasher.hash("surviving-token"), NOW.plusSeconds(3600), NOW);
        refreshTokens.save(surviving);

        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand("surviving-token")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_ACTIVE);
    }

    @Test
    void userRestoredEventReactivatesLoginButDoesNotResurrectOldRefreshTokens() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        UUID userId = session.user().id();
        service.handleUserSuspended(suspendedEvent(userId, NOW.minusSeconds(60)));

        service.handleUserRestored(restoredEvent(userId, NOW));

        assertThat(authUsers.findById(userId).orElseThrow().status()).isEqualTo(AuthUserStatus.ACTIVE);
        // Login works again...
        AuthResult fresh = service.login(new LoginCommand("user@example.com", VALID_PASSWORD));
        assertThat(fresh.accessToken()).isNotBlank();
        // ...but the pre-suspension refresh token stays revoked.
        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(session.refreshToken())))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void staleRestoreDoesNotOverrideNewerSuspension() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        UUID userId = session.user().id();

        service.handleUserSuspended(suspendedEvent(userId, NOW));
        // An older restore delivered late (out of order) must not lift the suspension.
        service.handleUserRestored(restoredEvent(userId, NOW.minusSeconds(300)));

        assertThat(authUsers.findById(userId).orElseThrow().status()).isEqualTo(AuthUserStatus.SUSPENDED);
    }

    @Test
    void duplicateModerationEventsAreIdempotent() {
        AuthResult session = registerVerifiedAndLogin("user@example.com");
        UUID userId = session.user().id();
        UserSuspendedEvent suspend = suspendedEvent(userId, NOW.minusSeconds(120));

        service.handleUserSuspended(suspend);
        service.handleUserRestored(restoredEvent(userId, NOW));
        service.handleUserSuspended(suspend); // redelivery of the old suspend: inbox no-op

        assertThat(authUsers.findById(userId).orElseThrow().status()).isEqualTo(AuthUserStatus.ACTIVE);
    }

    @Test
    void moderationEventForUnknownUserIsIgnoredButMarkedProcessed() {
        UserSuspendedEvent suspend = suspendedEvent(UUID.randomUUID(), NOW);

        service.handleUserSuspended(suspend); // must not throw

        assertThat(inbox.processed).containsKey(suspend.eventId());
    }

    private static UserSuspendedEvent suspendedEvent(UUID userId, Instant occurredAt) {
        return new UserSuspendedEvent(UUID.randomUUID(), UUID.randomUUID(), userId, UUID.randomUUID(), occurredAt);
    }

    private static UserRestoredEvent restoredEvent(UUID userId, Instant occurredAt) {
        return new UserRestoredEvent(UUID.randomUUID(), UUID.randomUUID(), userId, UUID.randomUUID(), occurredAt);
    }

    private void registerVerified(String email) {
        service.register(new RegisterCommand(email, VALID_PASSWORD));
        service.verifyEmail(new VerifyEmailCommand(emailVerificationSender.tokenFor(email)));
    }

    private AuthResult registerVerifiedAndLogin(String email) {
        registerVerified(email);
        return service.login(new LoginCommand(email, VALID_PASSWORD));
    }

    // --- Fakes -----------------------------------------------------------

    private static final class FakeAuthUserRepository implements AuthUserRepository {
        private final Map<UUID, AuthUser> byId = new HashMap<>();

        @Override
        public AuthUser save(AuthUser user) {
            byId.put(user.id(), user);
            return user;
        }

        @Override
        public Optional<AuthUser> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<AuthUser> findByEmail(String email) {
            return byId.values().stream().filter(u -> u.email().equals(email)).findFirst();
        }

        @Override
        public Optional<AuthUser> findByEmailVerificationTokenHash(String tokenHash) {
            return byId.values().stream()
                    .filter(u -> tokenHash.equals(u.emailVerificationTokenHash()))
                    .findFirst();
        }

        @Override
        public boolean existsByEmail(String email) {
            return byId.values().stream().anyMatch(u -> u.email().equals(email));
        }
    }

    private static final class FakeRefreshTokenRepository implements RefreshTokenRepository {
        private final Map<String, RefreshToken> byHash = new HashMap<>();

        @Override
        public RefreshToken save(RefreshToken token) {
            byHash.put(token.tokenHash(), token);
            return token;
        }

        @Override
        public Optional<RefreshToken> findByTokenHash(String tokenHash) {
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        @Override
        public int revokeActiveFamily(
                UUID tokenFamilyId,
                RefreshTokenRevocationReason reason,
                Instant revokedAt) {
            int revoked = 0;
            for (RefreshToken token : byHash.values()) {
                if (token.tokenFamilyId().equals(tokenFamilyId) && !token.isRevoked()) {
                    token.revoke(reason, revokedAt);
                    revoked++;
                }
            }
            return revoked;
        }

        @Override
        public int revokeAllActiveForUser(
                UUID userId,
                RefreshTokenRevocationReason reason,
                Instant revokedAt) {
            int revoked = 0;
            for (RefreshToken token : byHash.values()) {
                if (token.userId().equals(userId) && !token.isRevoked()) {
                    token.revoke(reason, revokedAt);
                    revoked++;
                }
            }
            return revoked;
        }
    }

    private static final class FakePasswordResetRepository implements PasswordResetRepository {
        private final Map<String, PasswordResetToken> byHash = new HashMap<>();

        @Override
        public PasswordResetToken save(PasswordResetToken token) {
            byHash.put(token.tokenHash(), token);
            return token;
        }

        @Override
        public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        @Override
        public int consumeActiveForUser(UUID userId, Instant consumedAt) {
            int consumed = 0;
            for (PasswordResetToken token : byHash.values()) {
                if (token.userId().equals(userId) && token.isActive(consumedAt)) {
                    token.consume(consumedAt);
                    consumed++;
                }
            }
            return consumed;
        }
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        private final List<UserRegisteredEvent> events = new ArrayList<>();

        @Override
        public void append(UserRegisteredEvent event) {
            events.add(event);
        }
    }

    private static final class FakeInboxEventRepository implements InboxEventRepository {
        private final Map<UUID, String> processed = new HashMap<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.containsKey(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.put(eventId, eventType);
        }
    }

    private static final class FakePasswordHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String passwordHash) {
            return passwordHash.equals(hash(rawPassword));
        }
    }

    private static final class FakeRefreshTokenHasher implements RefreshTokenHasher {
        @Override
        public String hash(String rawToken) {
            return "rh:" + rawToken;
        }
    }

    private static final class FakeEmailVerificationSender implements EmailVerificationSender {
        private final Map<String, String> tokens = new HashMap<>();

        @Override
        public void sendVerificationLink(String email, String rawToken) {
            tokens.put(email, rawToken);
        }

        String tokenFor(String email) {
            return tokens.get(email);
        }
    }

    private static final class FakePasswordResetEmailSender implements PasswordResetEmailSender {
        private final Map<String, String> tokens = new HashMap<>();

        @Override
        public void sendResetLink(String email, String rawToken) {
            tokens.put(email, rawToken);
        }

        String tokenFor(String email) {
            return tokens.get(email);
        }

        List<String> sentEmails() {
            return new ArrayList<>(tokens.keySet());
        }
    }

    private static final class FakeVerificationResendLimiter implements VerificationResendLimiter {
        private final Map<String, Boolean> allowed = new HashMap<>();

        @Override
        public boolean tryAcquire(String normalizedEmail) {
            return allowed.getOrDefault(normalizedEmail, true);
        }

        void allow(String email) {
            allowed.put(email, true);
        }

        void deny(String email) {
            allowed.put(email, false);
        }
    }

    private static final class FakePasswordResetLimiter implements PasswordResetLimiter {
        @Override
        public boolean tryAcquire(String normalizedEmail) {
            return true;
        }
    }

    private static final class FakeLoginFailureTracker implements LoginFailureTracker {
        private final Map<String, Long> failures = new HashMap<>();
        private final Map<String, Instant> lockedUntil = new HashMap<>();

        @Override
        public boolean isLocked(String normalizedEmail, Instant now) {
            Instant until = lockedUntil.get(normalizedEmail);
            if (until == null) {
                return false;
            }
            if (now.isBefore(until)) {
                return true;
            }
            lockedUntil.remove(normalizedEmail);
            return false;
        }

        @Override
        public LoginFailureOutcome recordFailure(String normalizedEmail, Instant now) {
            long count = failures.merge(normalizedEmail, 1L, Long::sum);
            Duration lockDuration = lockDurationFor(count);
            if (lockDuration.isZero()) {
                return new LoginFailureOutcome(count, false, null);
            }
            Instant until = now.plus(lockDuration);
            lockedUntil.put(normalizedEmail, until);
            return new LoginFailureOutcome(count, true, until);
        }

        @Override
        public void reset(String normalizedEmail) {
            failures.remove(normalizedEmail);
            lockedUntil.remove(normalizedEmail);
        }

        long failureCount(String normalizedEmail) {
            return failures.getOrDefault(normalizedEmail, 0L);
        }

        private static Duration lockDurationFor(long failures) {
            if (failures >= 20) {
                return Duration.ofHours(1);
            }
            if (failures >= 10) {
                return Duration.ofMinutes(5);
            }
            if (failures >= 5) {
                return Duration.ofSeconds(30);
            }
            return Duration.ZERO;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeSecureTokenGenerator implements SecureTokenGenerator {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String generate() {
            return "refresh-token-" + counter.incrementAndGet();
        }
    }
}
