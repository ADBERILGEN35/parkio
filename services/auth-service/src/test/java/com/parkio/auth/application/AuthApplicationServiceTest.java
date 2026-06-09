package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.parkio.auth.domain.AuthUserStatus;
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
    private static final Role USER_ROLE =
            new Role(UUID.fromString("00000000-0000-0000-0000-000000000001"), RoleName.USER);

    private FakeAuthUserRepository authUsers;
    private FakeRefreshTokenRepository refreshTokens;
    private FakeOutboxEventAppender outbox;
    private FakePasswordHasher passwordHasher;
    private FakeRefreshTokenHasher refreshTokenHasher;
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        authUsers = new FakeAuthUserRepository();
        refreshTokens = new FakeRefreshTokenRepository();
        outbox = new FakeOutboxEventAppender();
        passwordHasher = new FakePasswordHasher();
        refreshTokenHasher = new FakeRefreshTokenHasher();
        RoleRepository roles = name -> name == RoleName.USER ? Optional.of(USER_ROLE) : Optional.empty();
        AccessTokenIssuer accessTokenIssuer = user -> new IssuedAccessToken("access-" + user.id(), NOW.plusSeconds(900));
        SecureTokenGenerator tokenGenerator = new FakeSecureTokenGenerator();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AuthApplicationService(authUsers, roles, refreshTokens, outbox, passwordHasher,
                accessTokenIssuer, refreshTokenHasher, tokenGenerator, clock, Duration.ofDays(30));
    }

    @Test
    void registerCreatesActiveUserWithTokensAndOutboxEvent() {
        AuthResult result = service.register(new RegisterCommand("New.User@Example.com ", "password1"));

        assertThat(result.user().email()).isEqualTo("new.user@example.com");
        assertThat(result.user().status()).isEqualTo(AuthUserStatus.ACTIVE);
        assertThat(result.accessToken()).isEqualTo("access-" + result.user().id());
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(authUsers.findByEmail("new.user@example.com")).isPresent();
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(result.refreshToken()))).isPresent();
        assertThat(outbox.events).singleElement()
                .extracting(UserRegisteredEvent::email).isEqualTo("new.user@example.com");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        service.register(new RegisterCommand("dup@example.com", "password1"));

        assertThatThrownBy(() -> service.register(new RegisterCommand("DUP@example.com", "password1")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);
    }

    @Test
    void registerAssignsUserRole() {
        AuthResult result = service.register(new RegisterCommand("roles@example.com", "password1"));

        assertThat(result.user().roles()).extracting(r -> r.name().name()).containsExactly("USER");
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        service.register(new RegisterCommand("user@example.com", "password1"));

        AuthResult result = service.login(new LoginCommand("USER@example.com", "password1"));
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
        service.register(new RegisterCommand("user@example.com", "password1"));

        assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", "wrong-password")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginRejectsUnknownEmail() {
        assertThatThrownBy(() -> service.login(new LoginCommand("nobody@example.com", "password1")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginRejectsInactiveUser() {
        AuthUser suspended = new AuthUser(UUID.randomUUID(), "banned@example.com",
                passwordHasher.hash("password1"), AuthUserStatus.SUSPENDED, Set.of(USER_ROLE), NOW, 0L);
        authUsers.save(suspended);

        assertThatThrownBy(() -> service.login(new LoginCommand("banned@example.com", "password1")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_ACTIVE);
    }

    @Test
    void refreshRotatesTokenAndRevokesOld() {
        AuthResult initial = service.register(new RegisterCommand("user@example.com", "password1"));
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
        AuthResult initial = service.register(new RegisterCommand("user@example.com", "password1"));
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
                passwordHasher.hash("password1"), AuthUserStatus.ACTIVE, Set.of(USER_ROLE), NOW, 0L));
        RefreshToken expired =
                RefreshToken.issueRoot(userId, refreshTokenHasher.hash("expired-token"), NOW.minusSeconds(1));
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
    void logoutRevokesToken() {
        AuthResult initial = service.register(new RegisterCommand("user@example.com", "password1"));

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
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        private final List<UserRegisteredEvent> events = new ArrayList<>();

        @Override
        public void append(UserRegisteredEvent event) {
            events.add(event);
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

    private static final class FakeSecureTokenGenerator implements SecureTokenGenerator {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String generate() {
            return "refresh-token-" + counter.incrementAndGet();
        }
    }
}
