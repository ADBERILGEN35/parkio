package com.parkio.auth.application;

import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.command.LogoutCommand;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.port.AccessTokenIssuer;
import com.parkio.auth.application.port.AuthUserRepository;
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

    private final AuthUserRepository authUsers;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final OutboxEventAppender outbox;
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

        return issueTokens(saved);
    }

    public AuthResult login(LoginCommand command) {
        String email = AuthUser.normalizeEmail(command.email());
        AuthUser user = authUsers.findByEmail(email)
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS));

        if (!passwordHasher.matches(command.rawPassword(), user.passwordHash())) {
            throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS);
        }
        user.ensureCanAuthenticate();

        return issueTokens(user);
    }

    public AuthResult refresh(RefreshTokenCommand command) {
        String tokenHash = refreshTokenHasher.hash(command.rawRefreshToken());
        RefreshToken existing = refreshTokens.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        if (!existing.isActive(clock.instant())) {
            // TODO(security-hardening): refresh-token reuse detection is NOT
            // implemented. A presented-but-revoked token almost certainly means
            // a rotated token was replayed (possible theft), yet it is treated
            // as an ordinary invalid token. Production hardening should detect
            // reuse of an already-rotated token and revoke the whole token
            // family / all of the user's sessions. See README "Security
            // hardening backlog". Deliberately deferred — do not add token-family
            // tracking here without that change being scoped.
            throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        AuthUser user = authUsers.findById(existing.userId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN));
        user.ensureCanAuthenticate();

        // Rotate: the presented token is revoked and a fresh one is issued.
        existing.revoke();
        refreshTokens.save(existing);

        return issueTokens(user);
    }

    public void logout(LogoutCommand command) {
        // Idempotent: revoking an unknown/already-revoked token is a no-op.
        String tokenHash = refreshTokenHasher.hash(command.rawRefreshToken());
        refreshTokens.findByTokenHash(tokenHash).ifPresent(token -> {
            token.revoke();
            refreshTokens.save(token);
        });
    }

    @Transactional(readOnly = true)
    public AuthUser currentUser(UUID userId) {
        return authUsers.findById(userId)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    }

    private AuthResult issueTokens(AuthUser user) {
        IssuedAccessToken access = accessTokenIssuer.issue(user);

        String rawRefreshToken = tokenGenerator.generate();
        Instant refreshExpiresAt = clock.instant().plus(refreshTokenTtl);
        refreshTokens.save(RefreshToken.issue(user.id(), refreshTokenHasher.hash(rawRefreshToken), refreshExpiresAt));

        return new AuthResult(user, access.token(), access.expiresAt(), rawRefreshToken, refreshExpiresAt);
    }
}
