package com.parkio.auth.application.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.PasswordResetLimiter;
import com.parkio.auth.application.VerificationResendLimiter;
import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import com.parkio.auth.application.port.AccessTokenIssuer;
import com.parkio.auth.application.port.AdminAuditEventRepository;
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
import com.parkio.auth.application.result.AdminUserSummary;
import com.parkio.auth.application.result.IssuedAccessToken;
import com.parkio.auth.application.result.PageResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditEvent;
import com.parkio.auth.domain.admin.AdminAuditResult;
import com.parkio.auth.domain.event.UserRegisteredEvent;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.metrics.AdminMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T09:00:00Z");
    private static final Role USER_ROLE =
            new Role(UUID.fromString("00000000-0000-0000-0000-000000000001"), RoleName.USER);
    private static final Role MODERATOR_ROLE =
            new Role(UUID.fromString("00000000-0000-0000-0000-000000000002"), RoleName.MODERATOR);
    private static final Role ADMIN_ROLE =
            new Role(UUID.fromString("00000000-0000-0000-0000-000000000003"), RoleName.ADMIN);
    private static final Role SUPER_ADMIN_ROLE =
            new Role(UUID.fromString("00000000-0000-0000-0000-000000000004"), RoleName.SUPER_ADMIN);

    private FakeAuthUserRepository authUsers;
    private FakeRefreshTokenRepository refreshTokens;
    private FakeAdminAuditRepository auditEvents;
    private FakeOutboxEventAppender outbox;
    private FakeInboxEventRepository inbox;
    private AdminApplicationService adminService;
    private UUID adminId;
    private UUID superAdminId;
    private UUID regularUserId;

    @BeforeEach
    void setUp() {
        authUsers = new FakeAuthUserRepository();
        refreshTokens = new FakeRefreshTokenRepository();
        auditEvents = new FakeAdminAuditRepository();
        outbox = new FakeOutboxEventAppender();
        inbox = new FakeInboxEventRepository();
        RoleRepository roles = name -> switch (name) {
            case USER -> Optional.of(USER_ROLE);
            case MODERATOR -> Optional.of(MODERATOR_ROLE);
            case ADMIN -> Optional.of(ADMIN_ROLE);
            case SUPER_ADMIN -> Optional.of(SUPER_ADMIN_ROLE);
        };
        AuthApplicationService authService = buildAuthService(roles);
        AdminMetrics metrics = new AdminMetrics(new SimpleMeterRegistry());
        adminService = new AdminApplicationService(
                authUsers, refreshTokens, roles, auditEvents, outbox, inbox, authService, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));

        adminId = seedUser("admin@parkio.example", Set.of(ADMIN_ROLE), AuthUserStatus.ACTIVE, true);
        superAdminId = seedUser("super@parkio.example", Set.of(SUPER_ADMIN_ROLE), AuthUserStatus.ACTIVE, true);
        regularUserId = seedUser("user@parkio.example", Set.of(USER_ROLE), AuthUserStatus.ACTIVE, true);
    }

    @Test
    void userRoleIsForbidden() {
        assertThatThrownBy(() -> adminService.suspendUser(
                        regularUserId, Set.of("USER"), regularUserId, "test"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
    }

    @Test
    void adminCanSuspendRegularUser() {
        adminService.suspendUser(adminId, Set.of("ADMIN"), regularUserId, "policy violation");

        AuthUser suspended = authUsers.findById(regularUserId).orElseThrow();
        assertThat(suspended.status()).isEqualTo(AuthUserStatus.SUSPENDED);
        assertThat(suspended.sessionEpoch()).isGreaterThan(0L);
        assertThat(outbox.suspended).hasSize(1);
        assertThat(inbox.claimed).contains(outbox.suspended.getFirst().eventId());
        assertThat(auditEvents.saved).singleElement()
                .extracting(AdminAuditEvent::actionType)
                .isEqualTo(AdminAuditAction.ADMIN_USER_SUSPENDED);
    }

    @Test
    void adminCannotSuspendSuperAdmin() {
        assertThatThrownBy(() -> adminService.suspendUser(
                        adminId, Set.of("ADMIN"), superAdminId, "attempt"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.FORBIDDEN);
    }

    @Test
    void adminCannotGrantAdminRole() {
        assertThatThrownBy(() -> adminService.grantRole(
                        adminId, Set.of("ADMIN"), regularUserId, RoleName.ADMIN, "promote"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.PRIVILEGE_ESCALATION);
    }

    @Test
    void superAdminCanGrantAdminRole() {
        adminService.grantRole(superAdminId, Set.of("SUPER_ADMIN"), regularUserId, RoleName.ADMIN, "promote");

        AuthUser updated = authUsers.findById(regularUserId).orElseThrow();
        assertThat(updated.hasRole(RoleName.ADMIN)).isTrue();
        assertThat(auditEvents.saved).singleElement()
                .extracting(AdminAuditEvent::actionType)
                .isEqualTo(AdminAuditAction.ADMIN_USER_ROLE_GRANTED);
    }

    @Test
    void cannotRemoveLastSuperAdmin() {
        assertThatThrownBy(() -> adminService.revokeRole(
                        superAdminId, Set.of("SUPER_ADMIN"), superAdminId, RoleName.SUPER_ADMIN, "demote"))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).errorCode())
                .isEqualTo(AuthErrorCode.LAST_SUPER_ADMIN);
    }

    @Test
    void auditEventCreatedOnSuccess() {
        adminService.revokeAllSessions(adminId, Set.of("ADMIN"), regularUserId, "security reset");

        assertThat(auditEvents.saved).singleElement()
                .satisfies(event -> {
                    assertThat(event.result()).isEqualTo(AdminAuditResult.SUCCESS);
                    assertThat(event.actorUserId()).isEqualTo(adminId);
                    assertThat(event.targetResourceId()).isEqualTo(regularUserId);
                });
    }

    @Test
    void summariesExcludeSensitiveFields() {
        PageResult<AdminUserSummary> page = adminService.listUsers(
                new AdminUserSearchQuery(null, null, regularUserId, null, null, null, null, null, 0, 20, null));

        assertThat(page.content()).singleElement().satisfies(summary -> {
            assertThat(summary.email()).isEqualTo("user@parkio.example");
            assertThat(summary).hasOnlyFields(
                    "id", "email", "status", "emailVerified", "roles", "createdAt", "activeSessionCount");
        });
    }

    private AuthApplicationService buildAuthService(RoleRepository roles) {
        return new AuthApplicationService(
                authUsers,
                roles,
                refreshTokens,
                new FakePasswordResetRepository(),
                outbox,
                inbox,
                new FakePasswordHasher(),
                user -> new IssuedAccessToken("access-" + user.id(), NOW.plusSeconds(900)),
                new FakeRefreshTokenHasher(),
                new FakeSecureTokenGenerator(),
                new FakeLoginFailureTracker(),
                new FakeVerificationResendLimiter(),
                new FakePasswordResetLimiter(),
                new FakeEmailVerificationSender(),
                new FakePasswordResetEmailSender(),
                new com.parkio.auth.application.PasswordPolicy(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(30),
                Duration.ofDays(90),
                Duration.ofHours(24),
                Duration.ofHours(1));
    }

    private UUID seedUser(String email, Set<Role> roles, AuthUserStatus status, boolean verified) {
        AuthUser user = new AuthUser(
                UUID.randomUUID(),
                email,
                "bcrypt-hash-secret",
                status,
                null,
                verified,
                verified ? NOW : null,
                null,
                null,
                null,
                0L,
                roles,
                NOW.minus(Duration.ofDays(1)),
                null);
        authUsers.save(user);
        return user.id();
    }

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
            return Optional.empty();
        }

        @Override
        public boolean existsByEmail(String email) {
            return byId.values().stream().anyMatch(u -> u.email().equals(email));
        }

        @Override
        public PageResult<AuthUser> search(AdminUserSearchQuery query) {
            List<AuthUser> all = new ArrayList<>(byId.values());
            if (query.userId() != null) {
                all = all.stream().filter(u -> u.id().equals(query.userId())).toList();
            }
            return new PageResult<>(all, 0, all.size(), all.size(), 1);
        }

        @Override
        public long count() {
            return byId.size();
        }

        @Override
        public long countByStatus(AuthUserStatus status) {
            return byId.values().stream().filter(u -> u.status() == status).count();
        }

        @Override
        public long countVerified() {
            return byId.values().stream().filter(AuthUser::emailVerified).count();
        }

        @Override
        public long countUnverified() {
            return byId.values().stream().filter(u -> !u.emailVerified()).count();
        }

        @Override
        public long countCreatedSince(Instant since) {
            return byId.values().stream().filter(u -> !u.createdAt().isBefore(since)).count();
        }

        @Override
        public long countByRole(RoleName roleName) {
            return byId.values().stream().filter(u -> u.hasRole(roleName)).count();
        }
    }

    private static final class FakeRefreshTokenRepository implements RefreshTokenRepository {
        private final Map<UUID, RefreshToken> byId = new HashMap<>();

        @Override
        public RefreshToken save(RefreshToken token) {
            byId.put(token.id(), token);
            return token;
        }

        @Override
        public Optional<RefreshToken> findByTokenHash(String tokenHash) {
            return Optional.empty();
        }

        @Override
        public int revokeActiveFamily(UUID tokenFamilyId, com.parkio.auth.domain.RefreshTokenRevocationReason reason,
                                      Instant revokedAt) {
            return 0;
        }

        @Override
        public int revokeAllActiveForUser(UUID userId,
                                          com.parkio.auth.domain.RefreshTokenRevocationReason reason,
                                          Instant revokedAt) {
            int revoked = 0;
            for (RefreshToken token : byId.values()) {
                if (token.userId().equals(userId) && !token.isRevoked()) {
                    token.revoke(reason, revokedAt);
                    revoked++;
                }
            }
            return revoked;
        }

        @Override
        public List<RefreshToken> findActiveSessionsForUser(UUID userId, Instant now) {
            return byId.values().stream().filter(t -> t.userId().equals(userId) && t.isActive(now)).toList();
        }

        @Override
        public long countActiveForUser(UUID userId, Instant now) {
            return findActiveSessionsForUser(userId, now).size();
        }

        @Override
        public long countAllActive(Instant now) {
            return byId.values().stream().filter(t -> t.isActive(now)).count();
        }

        @Override
        public long countReuseDetected() {
            return 0;
        }

        @Override
        public Optional<RefreshToken> findByIdAndUserId(UUID sessionId, UUID userId) {
            RefreshToken token = byId.get(sessionId);
            if (token != null && token.userId().equals(userId)) {
                return Optional.of(token);
            }
            return Optional.empty();
        }

        @Override
        public boolean revokeById(UUID sessionId, UUID userId,
                                  com.parkio.auth.domain.RefreshTokenRevocationReason reason, Instant revokedAt) {
            Optional<RefreshToken> token = findByIdAndUserId(sessionId, userId);
            if (token.isEmpty() || token.get().isRevoked()) {
                return false;
            }
            token.get().revoke(reason, revokedAt);
            return true;
        }
    }

    private static final class FakeAdminAuditRepository implements AdminAuditEventRepository {
        private final List<AdminAuditEvent> saved = new ArrayList<>();

        @Override
        public void save(AdminAuditEvent event) {
            saved.add(event);
        }

        @Override
        public PageResult<AdminAuditEvent> search(AdminAuditSearchQuery query) {
            return new PageResult<>(saved, 0, saved.size(), saved.size(), 1);
        }

        @Override
        public List<AdminAuditEvent> findRecentForTarget(String targetResourceType, UUID targetResourceId, int limit) {
            return saved.stream()
                    .filter(e -> targetResourceId.equals(e.targetResourceId()))
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        private final List<UserSuspendedEvent> suspended = new ArrayList<>();
        private final List<UserRestoredEvent> restored = new ArrayList<>();

        @Override
        public void append(UserRegisteredEvent event) {
        }

        @Override
        public void append(UserSuspendedEvent event) {
            suspended.add(event);
        }

        @Override
        public void append(UserRestoredEvent event) {
            restored.add(event);
        }
    }

    private static final class FakeInboxEventRepository implements InboxEventRepository {
        private final java.util.Set<UUID> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean tryClaim(UUID eventId, String eventType, Instant processedAt) {
            return claimed.add(eventId);
        }
    }

    private static final class FakePasswordResetRepository implements PasswordResetRepository {
        @Override
        public com.parkio.auth.domain.PasswordResetToken save(com.parkio.auth.domain.PasswordResetToken token) {
            return token;
        }

        @Override
        public Optional<com.parkio.auth.domain.PasswordResetToken> findByTokenHash(String tokenHash) {
            return Optional.empty();
        }

        @Override
        public int consumeActiveForUser(UUID userId, Instant consumedAt) {
            return 0;
        }
    }

    private static final class FakePasswordHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "hash";
        }

        @Override
        public boolean matches(String rawPassword, String hash) {
            return true;
        }
    }

    private static final class FakeRefreshTokenHasher implements RefreshTokenHasher {
        @Override
        public String hash(String rawToken) {
            return "hash-" + rawToken;
        }
    }

    private static final class FakeEmailVerificationSender implements EmailVerificationSender {
        @Override
        public void sendVerificationLink(String email, String rawToken, EmailLocale locale) {
        }
    }

    private static final class FakePasswordResetEmailSender implements PasswordResetEmailSender {
        @Override
        public void sendResetLink(String email, String rawToken, EmailLocale locale) {
        }
    }

    private static final class FakeSecureTokenGenerator implements SecureTokenGenerator {
        @Override
        public String generate() {
            return "raw-token";
        }
    }

    private static final class FakeLoginFailureTracker implements LoginFailureTracker {
        @Override
        public boolean isLocked(String normalizedEmail, Instant now) {
            return false;
        }

        @Override
        public LoginFailureOutcome recordFailure(String normalizedEmail, Instant now) {
            return new LoginFailureOutcome(0, false, null);
        }

        @Override
        public void reset(String normalizedEmail) {
        }
    }

    private static final class FakeVerificationResendLimiter implements VerificationResendLimiter {
        @Override
        public boolean tryAcquire(String normalizedEmail) {
            return true;
        }
    }

    private static final class FakePasswordResetLimiter implements PasswordResetLimiter {
        @Override
        public boolean tryAcquire(String normalizedEmail) {
            return true;
        }
    }
}
