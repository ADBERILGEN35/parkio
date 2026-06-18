package com.parkio.auth.application;

import com.parkio.auth.application.command.ChangePasswordCommand;
import com.parkio.auth.application.command.ForgotPasswordCommand;
import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.command.LogoutCommand;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.ResendVerificationCommand;
import com.parkio.auth.application.command.ResetPasswordCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import com.parkio.auth.application.port.AccessTokenIssuer;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
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
import com.parkio.auth.domain.PasswordResetToken;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.event.UserRegisteredEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.domain.exception.LoginLockedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication use cases: register, login, refresh, logout and current-user
 * lookup. Depends only on domain types and application ports; framework
 * concerns (JWT, BCrypt, JPA) sit behind the ports in infrastructure.
 *
 * <p>Methods that change state run in a single transaction so that, on
 * registration, the {@code auth_users}/{@code refresh_tokens} writes and the
 * {@code outbox_events} insert commit atomically (ai-context/06).
 */
@Service
@Transactional
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

    private final AuthUserRepository authUsers;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetRepository passwordResetTokens;
    private final OutboxEventAppender outbox;
    private final InboxEventRepository inbox;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenHasher refreshTokenHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final LoginFailureTracker loginFailures;
    private final VerificationResendLimiter verificationResendLimiter;
    private final PasswordResetLimiter passwordResetLimiter;
    private final EmailVerificationSender emailVerificationSender;
    private final PasswordResetEmailSender passwordResetEmailSender;
    private final PasswordPolicy passwordPolicy;
    private final Clock clock;
    private final Duration refreshTokenTtl;
    private final Duration refreshAbsoluteTtl;
    private final Duration emailVerificationTtl;
    private final Duration passwordResetTtl;

    public AuthApplicationService(AuthUserRepository authUsers,
                                  RoleRepository roles,
                                  RefreshTokenRepository refreshTokens,
                                  PasswordResetRepository passwordResetTokens,
                                  OutboxEventAppender outbox,
                                  InboxEventRepository inbox,
                                  PasswordHasher passwordHasher,
                                  AccessTokenIssuer accessTokenIssuer,
                                  RefreshTokenHasher refreshTokenHasher,
                                  SecureTokenGenerator tokenGenerator,
                                  LoginFailureTracker loginFailures,
                                  VerificationResendLimiter verificationResendLimiter,
                                  PasswordResetLimiter passwordResetLimiter,
                                  EmailVerificationSender emailVerificationSender,
                                  PasswordResetEmailSender passwordResetEmailSender,
                                  PasswordPolicy passwordPolicy,
                                  Clock clock,
                                  @Value("${parkio.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl,
                                  @Value("${parkio.security.jwt.refresh-absolute-ttl:P90D}")
                                          Duration refreshAbsoluteTtl,
                                  @Value("${parkio.security.email-verification.token-ttl:PT24H}")
                                          Duration emailVerificationTtl,
                                  @Value("${parkio.security.password-reset.token-ttl:PT30M}")
                                          Duration passwordResetTtl) {
        this.authUsers = authUsers;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.passwordResetTokens = passwordResetTokens;
        this.outbox = outbox;
        this.inbox = inbox;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenHasher = refreshTokenHasher;
        this.tokenGenerator = tokenGenerator;
        this.loginFailures = loginFailures;
        this.verificationResendLimiter = verificationResendLimiter;
        this.passwordResetLimiter = passwordResetLimiter;
        this.emailVerificationSender = emailVerificationSender;
        this.passwordResetEmailSender = passwordResetEmailSender;
        this.passwordPolicy = passwordPolicy;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl;
        this.refreshAbsoluteTtl = refreshAbsoluteTtl;
        this.emailVerificationTtl = emailVerificationTtl;
        this.passwordResetTtl = passwordResetTtl;
    }

    public RegisterResult register(RegisterCommand command) {
        String email = AuthUser.normalizeEmail(command.email());
        passwordPolicy.validate(command.rawPassword());

        if (authUsers.existsByEmail(email)) {
            throw new AuthException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Role userRole = roles.findByName(RoleName.USER)
                .orElseThrow(() -> new IllegalStateException("USER role is not seeded"));

        Instant now = clock.instant();
        String rawVerificationToken = tokenGenerator.generate();
        Instant verificationExpiresAt = now.plus(emailVerificationTtl);
        String passwordHash = passwordHasher.hash(command.rawPassword());
        AuthUser user = AuthUser.register(
                email,
                passwordHash,
                refreshTokenHasher.hash(rawVerificationToken),
                verificationExpiresAt,
                now,
                Set.of(userRole),
                now);
        AuthUser saved = authUsers.save(user);

        outbox.append(UserRegisteredEvent.of(saved.id(), saved.email(), now));
        emailVerificationSender.sendVerificationLink(saved.email(), rawVerificationToken);

        return new RegisterResult(saved, verificationExpiresAt);
    }

    public AuthResult login(LoginCommand command) {
        String email = AuthUser.normalizeEmail(command.email());
        Instant now = clock.instant();
        if (loginFailures.isLocked(email, now)) {
            log.warn("Login blocked by account lockout; emailHash={}", Integer.toHexString(email.hashCode()));
            throw new LoginLockedException();
        }

        AuthUser user = authUsers.findByEmail(email).orElse(null);
        if (user == null || !passwordHasher.matches(command.rawPassword(), user.passwordHash())) {
            LoginFailureTracker.LoginFailureOutcome outcome = loginFailures.recordFailure(email, now);
            if (outcome.lockoutApplied()) {
                log.warn(
                        "Login account lockout applied; emailHash={}, failures={}, lockedUntil={}",
                        Integer.toHexString(email.hashCode()),
                        outcome.failureCount(),
                        outcome.lockedUntil());
                throw new LoginLockedException();
            }
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        user.ensureCanAuthenticate();
        loginFailures.reset(email);

        return issueTokens(user, null);
    }

    public AuthUser verifyEmail(VerifyEmailCommand command) {
        String tokenHash = refreshTokenHasher.hash(command.rawToken());
        AuthUser user = authUsers.findByEmailVerificationTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_VERIFICATION_TOKEN));

        Instant now = clock.instant();
        if (user.emailVerified()) {
            return user;
        }
        if (user.emailVerificationTokenExpired(now)) {
            throw new AuthException(AuthErrorCode.INVALID_VERIFICATION_TOKEN);
        }
        user.verifyEmail(now);
        return authUsers.save(user);
    }

    public void resendVerification(ResendVerificationCommand command) {
        String email = AuthUser.normalizeEmail(command.email());
        if (!verificationResendLimiter.tryAcquire(email)) {
            log.info("Email verification resend suppressed by cooldown; emailHash={}",
                    Integer.toHexString(email.hashCode()));
            return;
        }

        authUsers.findByEmail(email)
                .filter(user -> !user.emailVerified())
                .ifPresent(user -> {
                    Instant now = clock.instant();
                    String rawVerificationToken = tokenGenerator.generate();
                    Instant expiresAt = now.plus(emailVerificationTtl);
                    user.issueEmailVerificationToken(refreshTokenHasher.hash(rawVerificationToken), expiresAt, now);
                    AuthUser saved = authUsers.save(user);
                    emailVerificationSender.sendVerificationLink(saved.email(), rawVerificationToken);
                });
    }

    public void forgotPassword(ForgotPasswordCommand command) {
        String email = AuthUser.normalizeEmail(command.email());
        if (!passwordResetLimiter.tryAcquire(email)) {
            log.info("Password reset request suppressed by cooldown; emailHash={}",
                    Integer.toHexString(email.hashCode()));
            return;
        }

        authUsers.findByEmail(email)
                .filter(user -> user.emailVerified() && user.status().canAuthenticate())
                .ifPresent(user -> {
                    Instant now = clock.instant();
                    passwordResetTokens.consumeActiveForUser(user.id(), now);
                    String rawResetToken = tokenGenerator.generate();
                    PasswordResetToken resetToken = PasswordResetToken.issue(
                            user.id(),
                            refreshTokenHasher.hash(rawResetToken),
                            now.plus(passwordResetTtl),
                            now);
                    passwordResetTokens.save(resetToken);
                    passwordResetEmailSender.sendResetLink(user.email(), rawResetToken);
                });
    }

    public void resetPassword(ResetPasswordCommand command) {
        passwordPolicy.validate(command.newPassword());
        PasswordResetToken resetToken = passwordResetTokens.findByTokenHash(
                        refreshTokenHasher.hash(command.rawToken()))
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_RESET_TOKEN));
        Instant now = clock.instant();
        if (!resetToken.isActive(now)) {
            throw new AuthException(AuthErrorCode.INVALID_RESET_TOKEN);
        }

        AuthUser user = authUsers.findById(resetToken.userId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_RESET_TOKEN));
        if (!user.emailVerified() || !user.status().canAuthenticate()) {
            throw new AuthException(AuthErrorCode.INVALID_RESET_TOKEN);
        }

        user.changePassword(passwordHasher.hash(command.newPassword()));
        long newEpoch = user.bumpSessionEpoch();
        authUsers.save(user);
        resetToken.consume(now);
        passwordResetTokens.save(resetToken);
        int revoked = refreshTokens.revokeAllActiveForUser(
                user.id(), RefreshTokenRevocationReason.PASSWORD_CHANGED, now);
        log.info("Password reset completed; userId={}, activeRefreshTokensRevoked={}, sessionEpoch={}",
                user.id(), revoked, newEpoch);
    }

    public void changePassword(ChangePasswordCommand command) {
        passwordPolicy.validate(command.newPassword());
        AuthUser user = authUsers.findById(command.userId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
        user.ensureCanAuthenticate();
        if (!passwordHasher.matches(command.currentPassword(), user.passwordHash())) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        Instant now = clock.instant();
        user.changePassword(passwordHasher.hash(command.newPassword()));
        long newEpoch = user.bumpSessionEpoch();
        authUsers.save(user);
        int revoked = refreshTokens.revokeAllActiveForUser(
                user.id(), RefreshTokenRevocationReason.PASSWORD_CHANGED, now);
        log.info("Password changed; userId={}, activeRefreshTokensRevoked={}, sessionEpoch={}",
                user.id(), revoked, newEpoch);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthResult refresh(RefreshTokenCommand command) {
        String tokenHash = refreshTokenHasher.hash(command.rawRefreshToken());
        RefreshToken existing = refreshTokens.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        Instant now = clock.instant();
        if (existing.isExpired(now)) {
            throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (existing.isRevoked()) {
            existing.markReuseDetected();
            refreshTokens.save(existing);
            int revokedCount = refreshTokens.revokeActiveFamily(
                    existing.tokenFamilyId(),
                    RefreshTokenRevocationReason.REUSE_DETECTED,
                    now);
            // Reuse means the family is compromised: also bump the session epoch so any
            // access token already minted from this session is rejected at the gateway
            // within its cache TTL, not left valid until its 15-minute expiry. Committed
            // despite the throw by noRollbackFor=AuthException. Tolerant of a missing user
            // (keep the generic error) — the epoch bump is best-effort hardening.
            authUsers.findById(existing.userId()).ifPresent(user -> {
                long newEpoch = user.bumpSessionEpoch();
                authUsers.save(user);
                log.warn(
                        "Refresh token reuse detected; userId={}, tokenFamilyId={}, activeTokensRevoked={}, "
                                + "sessionEpoch={}",
                        existing.userId(), existing.tokenFamilyId(), revokedCount, newEpoch);
            });
            throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        // Absolute session lifetime cap: the family has outlived its maximum age even
        // though this (current, non-revoked) token's sliding TTL is still valid. This is
        // a natural expiry, not reuse — revoke the remaining active family and reject with
        // the SAME generic error so a client cannot distinguish absolute from sliding
        // expiry. noRollbackFor=AuthException commits the revocation despite the throw.
        if (existing.isFamilyAbsoluteLifetimeExceeded(now, refreshAbsoluteTtl)) {
            int revokedCount = refreshTokens.revokeActiveFamily(
                    existing.tokenFamilyId(),
                    RefreshTokenRevocationReason.EXPIRED_CLEANUP,
                    now);
            log.info(
                    "Refresh rejected: token family exceeded absolute session lifetime; "
                            + "userId={}, tokenFamilyId={}, activeTokensRevoked={}",
                    existing.userId(),
                    existing.tokenFamilyId(),
                    revokedCount);
            throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        AuthUser user = authUsers.findById(existing.userId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN));
        user.ensureCanAuthenticate();

        // Rotate: the presented token is revoked and a fresh one is issued.
        existing.revoke(RefreshTokenRevocationReason.ROTATED, now);
        refreshTokens.save(existing);

        return issueTokens(user, existing);
    }

    public void logout(LogoutCommand command) {
        // Idempotent: revoking an unknown/already-revoked token is a no-op.
        // Single-device logout: revokes only the presented refresh token and does NOT
        // bump the session epoch, so this user's other devices keep working and their
        // already-issued access tokens live out their short TTL. Use logoutAll to kill
        // every session and invalidate access tokens immediately.
        String tokenHash = refreshTokenHasher.hash(command.rawRefreshToken());
        refreshTokens.findByTokenHash(tokenHash).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.revoke(RefreshTokenRevocationReason.LOGOUT, clock.instant());
                refreshTokens.save(token);
            }
        });
    }

    /**
     * Global "log out everywhere" for the authenticated user: revokes every active
     * refresh token across all of the user's families and bumps the session epoch so
     * all outstanding access tokens are rejected at the gateway within its cache TTL.
     * Idempotent and safe to call repeatedly. The caller is identified by the validated
     * access token (not the refresh cookie), so it works even without a refresh cookie.
     */
    public void logoutAll(UUID userId) {
        AuthUser user = authUsers.findById(userId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
        Instant now = clock.instant();
        int revoked = refreshTokens.revokeAllActiveForUser(
                user.id(), RefreshTokenRevocationReason.LOGOUT, now);
        long newEpoch = user.bumpSessionEpoch();
        authUsers.save(user);
        log.info("User {} logged out everywhere; activeRefreshTokensRevoked={}, sessionEpoch={}",
                user.id(), revoked, newEpoch);
    }

    /**
     * Current session epoch for a user, read by the gateway's per-request access-token
     * revocation check via the internal endpoint. Throws {@link AuthException}
     * ({@code USER_NOT_FOUND}) for an unknown id so the gateway can fail closed.
     */
    @Transactional(readOnly = true)
    public long sessionEpoch(UUID userId) {
        return authUsers.findById(userId)
                .map(AuthUser::sessionEpoch)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    }

    /**
     * Idempotent handler for moderation's {@code UserSuspended} (consumed from
     * {@code parkio.moderation.action}): marks the auth account {@code SUSPENDED} so
     * login and refresh are rejected, and revokes every active refresh token across
     * the user's families so existing sessions cannot mint new access tokens.
     * Stale (out-of-order) events are ignored via {@code occurredAt >= statusChangedAt}.
     * Inbox-deduplicated; the status change, the revocations and the inbox record
     * commit in one transaction.
     *
     * <p>Auth-service is the registration source of truth, so an unknown {@code userId}
     * cannot be a not-yet-provisioned account — it is logged and acked (no pending
     * store needed here, unlike user-service profile provisioning).
     */
    public void handleUserSuspended(UserSuspendedEvent event) {
        if (inbox.existsByEventId(event.eventId())) {
            return;
        }
        authUsers.findById(event.userId()).ifPresentOrElse(user -> {
            if (user.suspend(event.occurredAt())) {
                // Bump the session epoch in the same save so existing access tokens are
                // rejected at the gateway right away, alongside revoking the refresh tokens.
                user.bumpSessionEpoch();
                authUsers.save(user);
                int revoked = refreshTokens.revokeAllActiveForUser(
                        user.id(), RefreshTokenRevocationReason.ADMIN_REVOKED, clock.instant());
                log.info("User {} suspended by moderation event {}; activeRefreshTokensRevoked={}, sessionEpoch={}",
                        user.id(), event.eventId(), revoked, user.sessionEpoch());
            } else {
                log.info("Ignoring stale UserSuspended event {} for user {} (occurredAt {} < statusChangedAt {})",
                        event.eventId(), user.id(), event.occurredAt(), user.statusChangedAt());
            }
        }, () -> log.warn("UserSuspended event {} for unknown auth user {}; ignoring",
                event.eventId(), event.userId()));
        inbox.markProcessed(event.eventId(), UserSuspendedEvent.TYPE, clock.instant());
    }

    /**
     * Idempotent handler for moderation's {@code UserRestored}: marks the auth account
     * {@code ACTIVE} again so future logins succeed. Refresh tokens revoked during the
     * suspension stay revoked — restoration never resurrects old sessions. Same
     * ordering/idempotency semantics as {@link #handleUserSuspended}.
     */
    public void handleUserRestored(UserRestoredEvent event) {
        if (inbox.existsByEventId(event.eventId())) {
            return;
        }
        authUsers.findById(event.userId()).ifPresentOrElse(user -> {
            if (user.restore(event.occurredAt())) {
                authUsers.save(user);
                log.info("User {} restored by moderation event {}", user.id(), event.eventId());
            } else {
                log.info("Ignoring stale UserRestored event {} for user {} (occurredAt {} < statusChangedAt {})",
                        event.eventId(), user.id(), event.occurredAt(), user.statusChangedAt());
            }
        }, () -> log.warn("UserRestored event {} for unknown auth user {}; ignoring",
                event.eventId(), event.userId()));
        inbox.markProcessed(event.eventId(), UserRestoredEvent.TYPE, clock.instant());
    }

    @Transactional(readOnly = true)
    public AuthUser currentUser(UUID userId) {
        return authUsers.findById(userId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    }

    private AuthResult issueTokens(AuthUser user, RefreshToken parent) {
        IssuedAccessToken access = accessTokenIssuer.issue(user);

        String rawRefreshToken = tokenGenerator.generate();
        Instant now = clock.instant();
        Instant refreshExpiresAt = now.plus(refreshTokenTtl);
        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        // A login starts a new family (familyStartedAt = now); a rotation inherits the
        // parent's familyStartedAt so the absolute lifetime is never extended.
        RefreshToken refreshToken = parent == null
                ? RefreshToken.issueRoot(user.id(), tokenHash, refreshExpiresAt, now)
                : RefreshToken.issueChild(parent, tokenHash, refreshExpiresAt);
        refreshTokens.save(refreshToken);

        return new AuthResult(user, access.token(), access.expiresAt(), rawRefreshToken, refreshExpiresAt);
    }
}
