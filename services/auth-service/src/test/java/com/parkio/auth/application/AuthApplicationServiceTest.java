package com.parkio.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private FakeInboxEventRepository inbox;
    private FakePasswordHasher passwordHasher;
    private FakeRefreshTokenHasher refreshTokenHasher;
    private AuthApplicationService service;

    @BeforeEach
    void setUp() {
        authUsers = new FakeAuthUserRepository();
        refreshTokens = new FakeRefreshTokenRepository();
        outbox = new FakeOutboxEventAppender();
        inbox = new FakeInboxEventRepository();
        passwordHasher = new FakePasswordHasher();
        refreshTokenHasher = new FakeRefreshTokenHasher();
        RoleRepository roles = name -> name == RoleName.USER ? Optional.of(USER_ROLE) : Optional.empty();
        AccessTokenIssuer accessTokenIssuer = user -> new IssuedAccessToken("access-" + user.id(), NOW.plusSeconds(900));
        SecureTokenGenerator tokenGenerator = new FakeSecureTokenGenerator();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new AuthApplicationService(authUsers, roles, refreshTokens, outbox, inbox, passwordHasher,
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
                passwordHasher.hash("password1"), AuthUserStatus.SUSPENDED, null, Set.of(USER_ROLE), NOW, 0L);
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
                passwordHasher.hash("password1"), AuthUserStatus.ACTIVE, null, Set.of(USER_ROLE), NOW, 0L));
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

    // --- Moderation status sync (parkio.moderation.action) ---------------

    @Test
    void userSuspendedEventSetsStatusSuspendedAndRevokesActiveRefreshTokens() {
        AuthResult session = service.register(new RegisterCommand("user@example.com", "password1"));
        AuthResult secondSession = service.login(new LoginCommand("user@example.com", "password1"));
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
        AuthResult session = service.register(new RegisterCommand("user@example.com", "password1"));
        service.handleUserSuspended(suspendedEvent(session.user().id(), NOW));

        assertThatThrownBy(() -> service.login(new LoginCommand("user@example.com", "password1")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_ACTIVE);
    }

    @Test
    void suspendedUserCannotRefresh() {
        AuthResult session = service.register(new RegisterCommand("user@example.com", "password1"));
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
        AuthResult session = service.register(new RegisterCommand("user@example.com", "password1"));
        UUID userId = session.user().id();
        service.handleUserSuspended(suspendedEvent(userId, NOW));

        // Defence in depth: a token that somehow survived revocation still fails the
        // account-status check on refresh.
        RefreshToken surviving =
                RefreshToken.issueRoot(userId, refreshTokenHasher.hash("surviving-token"), NOW.plusSeconds(3600));
        refreshTokens.save(surviving);

        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand("surviving-token")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.USER_NOT_ACTIVE);
    }

    @Test
    void userRestoredEventReactivatesLoginButDoesNotResurrectOldRefreshTokens() {
        AuthResult session = service.register(new RegisterCommand("user@example.com", "password1"));
        UUID userId = session.user().id();
        service.handleUserSuspended(suspendedEvent(userId, NOW.minusSeconds(60)));

        service.handleUserRestored(restoredEvent(userId, NOW));

        assertThat(authUsers.findById(userId).orElseThrow().status()).isEqualTo(AuthUserStatus.ACTIVE);
        // Login works again...
        AuthResult fresh = service.login(new LoginCommand("user@example.com", "password1"));
        assertThat(fresh.accessToken()).isNotBlank();
        // ...but the pre-suspension refresh token stays revoked.
        assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(session.refreshToken())))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void staleRestoreDoesNotOverrideNewerSuspension() {
        AuthResult session = service.register(new RegisterCommand("user@example.com", "password1"));
        UUID userId = session.user().id();

        service.handleUserSuspended(suspendedEvent(userId, NOW));
        // An older restore delivered late (out of order) must not lift the suspension.
        service.handleUserRestored(restoredEvent(userId, NOW.minusSeconds(300)));

        assertThat(authUsers.findById(userId).orElseThrow().status()).isEqualTo(AuthUserStatus.SUSPENDED);
    }

    @Test
    void duplicateModerationEventsAreIdempotent() {
        AuthResult session = service.register(new RegisterCommand("user@example.com", "password1"));
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

    private static final class FakeSecureTokenGenerator implements SecureTokenGenerator {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public String generate() {
            return "refresh-token-" + counter.incrementAndGet();
        }
    }
}
