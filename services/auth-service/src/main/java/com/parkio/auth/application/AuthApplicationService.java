package com.parkio.auth.application;

import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.command.LogoutCommand;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import com.parkio.auth.application.port.AccessTokenIssuer;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.port.RoleRepository;
import com.parkio.auth.application.port.SecureTokenGenerator;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.application.result.IssuedAccessToken;
import com.parkio.auth.domain.AuthUser;
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
    private final OutboxEventAppender outbox;
    private final InboxEventRepository inbox;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenHasher refreshTokenHasher;
    private final SecureTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Duration refreshTokenTtl;

    public AuthApplicationService(AuthUserRepository authUsers,
                                  RoleRepository roles,
                                  RefreshTokenRepository refreshTokens,
                                  OutboxEventAppender outbox,
                                  InboxEventRepository inbox,
                                  PasswordHasher passwordHasher,
                                  AccessTokenIssuer accessTokenIssuer,
                                  RefreshTokenHasher refreshTokenHasher,
                                  SecureTokenGenerator tokenGenerator,
                                  Clock clock,
                                  @Value("${parkio.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
        this.authUsers = authUsers;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.outbox = outbox;
        this.inbox = inbox;
        this.passwordHasher = passwordHasher;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenHasher = refreshTokenHasher;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public AuthResult register(RegisterCommand command) {
        String email = AuthUser.normalizeEmail(command.email());

        if (authUsers.existsByEmail(email)) {
            throw new AuthException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Role userRole = roles.findByName(RoleName.USER)
                .orElseThrow(() -> new IllegalStateException("USER role is not seeded"));

        String passwordHash = passwordHasher.hash(command.rawPassword());
        AuthUser user = AuthUser.register(email, passwordHash, Set.of(userRole), clock.instant());
        AuthUser saved = authUsers.save(user);

        outbox.append(UserRegisteredEvent.of(saved.id(), saved.email(), clock.instant()));

        return issueTokens(saved, null);
    }

    public AuthResult login(LoginCommand command) {
        String email = AuthUser.normalizeEmail(command.email());
        AuthUser user = authUsers.findByEmail(email)
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

        if (!passwordHasher.matches(command.rawPassword(), user.passwordHash())) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        user.ensureCanAuthenticate();

        return issueTokens(user, null);
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
            log.warn(
                    "Refresh token reuse detected; userId={}, tokenFamilyId={}, activeTokensRevoked={}",
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
        String tokenHash = refreshTokenHasher.hash(command.rawRefreshToken());
        refreshTokens.findByTokenHash(tokenHash).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.revoke(RefreshTokenRevocationReason.LOGOUT, clock.instant());
                refreshTokens.save(token);
            }
        });
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
                authUsers.save(user);
                int revoked = refreshTokens.revokeAllActiveForUser(
                        user.id(), RefreshTokenRevocationReason.ADMIN_REVOKED, clock.instant());
                log.info("User {} suspended by moderation event {}; activeRefreshTokensRevoked={}",
                        user.id(), event.eventId(), revoked);
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
        Instant refreshExpiresAt = clock.instant().plus(refreshTokenTtl);
        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        RefreshToken refreshToken = parent == null
                ? RefreshToken.issueRoot(user.id(), tokenHash, refreshExpiresAt)
                : RefreshToken.issueChild(parent, tokenHash, refreshExpiresAt);
        refreshTokens.save(refreshToken);

        return new AuthResult(user, access.token(), access.expiresAt(), rawRefreshToken, refreshExpiresAt);
    }
}
