package com.parkio.auth.application.admin;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.event.UserRestoredEvent;
import com.parkio.auth.application.event.UserSuspendedEvent;
import com.parkio.auth.application.port.AdminAuditEventRepository;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.InboxEventRepository;
import com.parkio.auth.application.port.OutboxEventAppender;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.port.RoleRepository;
import com.parkio.auth.application.result.AdminAuditSummary;
import com.parkio.auth.application.result.AdminDashboardSummary;
import com.parkio.auth.application.result.AdminSecuritySummary;
import com.parkio.auth.application.result.AdminSessionSummary;
import com.parkio.auth.application.result.AdminUserDetail;
import com.parkio.auth.application.result.AdminUserSummary;
import com.parkio.auth.application.result.PageResult;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditEvent;
import com.parkio.auth.domain.admin.AdminAuditResult;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.metrics.AdminMetrics;
import com.parkio.auth.infrastructure.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AdminApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AdminApplicationService.class);

    static final String TARGET_AUTH_USER = AdminAuditTargets.AUTH_USER;
    private static final UUID ZERO_CASE_ID = new UUID(0L, 0L);
    private static final int RECENT_AUDIT_LIMIT = 10;

    private final AuthUserRepository authUsers;
    private final RefreshTokenRepository refreshTokens;
    private final RoleRepository roles;
    private final AdminAuditEventRepository auditEvents;
    private final OutboxEventAppender outbox;
    private final InboxEventRepository inbox;
    private final AuthApplicationService authService;
    private final AdminMetrics adminMetrics;
    private final Clock clock;

    public AdminApplicationService(
            AuthUserRepository authUsers,
            RefreshTokenRepository refreshTokens,
            RoleRepository roles,
            AdminAuditEventRepository auditEvents,
            OutboxEventAppender outbox,
            InboxEventRepository inbox,
            AuthApplicationService authService,
            AdminMetrics adminMetrics,
            Clock clock) {
        this.authUsers = authUsers;
        this.refreshTokens = refreshTokens;
        this.roles = roles;
        this.auditEvents = auditEvents;
        this.outbox = outbox;
        this.inbox = inbox;
        this.authService = authService;
        this.adminMetrics = adminMetrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminDashboardSummary getDashboardSummary() {
        Instant now = clock.instant();
        long total = authUsers.count();
        Map<AuthUserStatus, Long> byStatus = new EnumMap<>(AuthUserStatus.class);
        for (AuthUserStatus status : AuthUserStatus.values()) {
            byStatus.put(status, authUsers.countByStatus(status));
        }
        long verified = authUsers.countVerified();
        long unverified = authUsers.countUnverified();
        Instant todayStart = now.minus(Duration.ofDays(1));
        Instant last7Start = now.minus(Duration.ofDays(7));
        Instant last30Start = now.minus(Duration.ofDays(30));
        long today = authUsers.countCreatedSince(todayStart);
        long last7 = authUsers.countCreatedSince(last7Start);
        long last30 = authUsers.countCreatedSince(last30Start);
        double conversion = total > 0 ? (double) verified / (double) total : 0.0;
        long activeSessions = refreshTokens.countAllActive(now);
        return new AdminDashboardSummary(
                total, byStatus, verified, unverified, today, last7, last30, conversion, activeSessions);
    }

    @Transactional(readOnly = true)
    public PageResult<AdminUserSummary> listUsers(AdminUserSearchQuery query) {
        Instant now = clock.instant();
        PageResult<AuthUser> page = authUsers.search(query);
        List<AdminUserSummary> content = page.content().stream()
                .map(user -> toSummary(user, now))
                .toList();
        return new PageResult<>(content, page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    @Transactional(readOnly = true)
    public AdminUserDetail getUserDetail(UUID userId) {
        Instant now = clock.instant();
        AuthUser user = requireUser(userId);
        List<AdminSessionSummary> sessions = refreshTokens.findActiveSessionsForUser(userId, now).stream()
                .map(this::toSessionSummary)
                .toList();
        List<AdminAuditSummary> audit = auditEvents
                .findRecentForTarget(TARGET_AUTH_USER, userId, RECENT_AUDIT_LIMIT)
                .stream()
                .map(this::toAuditSummary)
                .toList();
        return new AdminUserDetail(toSummary(user, now), sessions, audit);
    }

    @Transactional(readOnly = true)
    public List<AdminSessionSummary> listSessions(UUID actorId, Set<String> actorRoles, UUID targetId) {
        AdminAuthority.requireAdmin(actorRoles);
        requireUser(targetId);
        Instant now = clock.instant();
        return refreshTokens.findActiveSessionsForUser(targetId, now).stream()
                .map(this::toSessionSummary)
                .toList();
    }

    public void suspendUser(UUID actorId, Set<String> actorRoles, UUID targetId, String reason) {
        AdminAuthority.requireAdmin(actorRoles);
        AuthUser target = requireUser(targetId);
        ensureCanManageTarget(actorRoles, target);
        if (target.status() == AuthUserStatus.SUSPENDED) {
            throw new AuthException(AuthErrorCode.INVALID_ADMIN_ACTION, "User is already suspended.");
        }
        if (target.status() == AuthUserStatus.BANNED) {
            // Suspension is temporary; overwriting BANNED would let a later
            // reactivate quietly un-ban the account.
            throw new AuthException(AuthErrorCode.INVALID_ADMIN_ACTION, "User is banned; suspension does not apply.");
        }
        Instant now = clock.instant();
        target.suspend(now);
        target.bumpSessionEpoch();
        authUsers.save(target);
        int revoked = refreshTokens.revokeAllActiveForUser(
                target.id(), RefreshTokenRevocationReason.ADMIN_REVOKED, now);
        publishSuspended(target.id(), actorId);
        recordSuccess(
                actorId,
                actorRoles,
                AdminAuditAction.ADMIN_USER_SUSPENDED,
                targetId,
                reason,
                "{\"revokedCount\":" + revoked + "}");
    }

    public void reactivateUser(UUID actorId, Set<String> actorRoles, UUID targetId, String reason) {
        AdminAuthority.requireAdmin(actorRoles);
        AuthUser target = requireUser(targetId);
        ensureCanManageTarget(actorRoles, target);
        if (target.status() != AuthUserStatus.SUSPENDED) {
            throw new AuthException(AuthErrorCode.INVALID_ADMIN_ACTION, "User is not suspended.");
        }
        Instant now = clock.instant();
        target.restore(now);
        authUsers.save(target);
        publishRestored(target.id(), actorId);
        recordSuccess(actorId, actorRoles, AdminAuditAction.ADMIN_USER_REACTIVATED, targetId, reason, null);
    }

    public void revokeAllSessions(UUID actorId, Set<String> actorRoles, UUID targetId, String reason) {
        AdminAuthority.requireAdmin(actorRoles);
        AuthUser target = requireUser(targetId);
        ensureCanManageTarget(actorRoles, target);
        Instant now = clock.instant();
        int revoked = refreshTokens.revokeAllActiveForUser(
                target.id(), RefreshTokenRevocationReason.ADMIN_REVOKED, now);
        recordSuccess(
                actorId,
                actorRoles,
                AdminAuditAction.ADMIN_USER_SESSIONS_REVOKED,
                targetId,
                reason,
                "{\"revokedCount\":" + revoked + "}");
    }

    public void revokeSession(
            UUID actorId, Set<String> actorRoles, UUID targetId, UUID sessionId, String reason) {
        AdminAuthority.requireAdmin(actorRoles);
        AuthUser target = requireUser(targetId);
        ensureCanManageTarget(actorRoles, target);
        Instant now = clock.instant();
        if (refreshTokens.findByIdAndUserId(sessionId, targetId).isEmpty()) {
            throw new AuthException(AuthErrorCode.SESSION_NOT_FOUND);
        }
        boolean revoked = refreshTokens.revokeById(
                sessionId, targetId, RefreshTokenRevocationReason.ADMIN_REVOKED, now);
        if (!revoked) {
            throw new AuthException(AuthErrorCode.SESSION_NOT_FOUND);
        }
        recordSuccess(
                actorId,
                actorRoles,
                AdminAuditAction.ADMIN_SESSION_REVOKED,
                targetId,
                reason,
                "{\"sessionId\":\"" + sessionId + "\"}");
    }

    public void resendVerification(UUID actorId, Set<String> actorRoles, UUID targetId, String reason) {
        AdminAuthority.requireAdmin(actorRoles);
        AuthUser target = requireUser(targetId);
        ensureCanManageTarget(actorRoles, target);
        authService.resendVerificationForUser(target);
        recordSuccess(actorId, actorRoles, AdminAuditAction.ADMIN_VERIFICATION_RESENT, targetId, reason, null);
    }

    public void grantRole(UUID actorId, Set<String> actorRoles, UUID targetId, RoleName roleName, String reason) {
        AdminAuthority.requireAdmin(actorRoles);
        AuthUser target = requireUser(targetId);
        ensureCanManageTarget(actorRoles, target);
        validateRoleChange(actorRoles, roleName, true);
        if (target.hasRole(roleName)) {
            throw new AuthException(AuthErrorCode.CONFLICT, "User already has role " + roleName.name());
        }
        Role role = roles.findByName(roleName)
                .orElseThrow(() -> new AuthException(AuthErrorCode.CONFLICT, "Role not seeded: " + roleName.name()));
        target.grantRole(role);
        authUsers.save(target);
        recordSuccess(
                actorId,
                actorRoles,
                AdminAuditAction.ADMIN_USER_ROLE_GRANTED,
                targetId,
                reason,
                "{\"role\":\"" + roleName.name() + "\"}");
    }

    public void revokeRole(UUID actorId, Set<String> actorRoles, UUID targetId, RoleName roleName, String reason) {
        AdminAuthority.requireAdmin(actorRoles);
        AuthUser target = requireUser(targetId);
        ensureCanManageTarget(actorRoles, target);
        validateRoleChange(actorRoles, roleName, false);
        if (roleName == RoleName.SUPER_ADMIN) {
            ensureNotLastSuperAdmin();
        }
        if (!target.hasRole(roleName)) {
            throw new AuthException(AuthErrorCode.CONFLICT, "User does not have role " + roleName.name());
        }
        target.revokeRole(roleName);
        authUsers.save(target);
        recordSuccess(
                actorId,
                actorRoles,
                AdminAuditAction.ADMIN_USER_ROLE_REVOKED,
                targetId,
                reason,
                "{\"role\":\"" + roleName.name() + "\"}");
    }

    @Transactional(readOnly = true)
    public PageResult<AdminAuditSummary> listAuditEvents(AdminAuditSearchQuery query) {
        PageResult<AdminAuditEvent> page = auditEvents.search(query);
        List<AdminAuditSummary> content = page.content().stream()
                .map(this::toAuditSummary)
                .toList();
        return new PageResult<>(content, page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    @Transactional(readOnly = true)
    public AdminSecuritySummary getSecuritySummary() {
        Instant now = clock.instant();
        return new AdminSecuritySummary(
                authUsers.countByStatus(AuthUserStatus.SUSPENDED),
                authUsers.countByStatus(AuthUserStatus.PENDING_VERIFICATION),
                refreshTokens.countAllActive(now),
                refreshTokens.countReuseDetected());
    }

    public void bootstrapSuperAdmin(String email) {
        String normalized = AuthUser.normalizeEmail(email);
        AuthUser user = authUsers.findByEmail(normalized)
                .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND, "User must exist before bootstrap."));
        long superAdminCount = authUsers.countByRole(RoleName.SUPER_ADMIN);
        if (superAdminCount > 0) {
            if (user.hasRole(RoleName.SUPER_ADMIN)) {
                recordBootstrapSuccess(user);
                return;
            }
            throw new AuthException(AuthErrorCode.CONFLICT, "A different SUPER_ADMIN already exists.");
        }
        if (user.status() != AuthUserStatus.ACTIVE || !user.emailVerified()) {
            throw new AuthException(
                    AuthErrorCode.INVALID_ADMIN_ACTION,
                    "User must be ACTIVE and email-verified before bootstrap.");
        }
        Role superAdminRole = roles.findByName(RoleName.SUPER_ADMIN)
                .orElseThrow(() -> new AuthException(AuthErrorCode.CONFLICT, "SUPER_ADMIN role is not seeded"));
        user.grantRole(superAdminRole);
        authUsers.save(user);
        recordBootstrapSuccess(user);
        log.info("Bootstrapped SUPER_ADMIN for user {}", user.id());
    }

    private AuthUser requireUser(UUID userId) {
        return authUsers.findById(userId).orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    }

    private void ensureCanManageTarget(Set<String> actorRoles, AuthUser target) {
        if (target.hasRole(RoleName.SUPER_ADMIN) && !AdminAuthority.isSuperAdmin(actorRoles)) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "Cannot modify SUPER_ADMIN accounts.");
        }
    }

    private void validateRoleChange(Set<String> actorRoles, RoleName roleName, boolean grant) {
        if (grant && roleName == RoleName.ADMIN && !AdminAuthority.isSuperAdmin(actorRoles)) {
            throw new AuthException(AuthErrorCode.PRIVILEGE_ESCALATION);
        }
        if (roleName == RoleName.ADMIN || roleName == RoleName.SUPER_ADMIN) {
            AdminAuthority.requireSuperAdmin(actorRoles);
        }
    }

    private void ensureNotLastSuperAdmin() {
        if (authUsers.countByRole(RoleName.SUPER_ADMIN) <= 1) {
            throw new AuthException(AuthErrorCode.LAST_SUPER_ADMIN);
        }
    }

    private void publishSuspended(UUID userId, UUID moderatorId) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        inbox.tryClaim(eventId, UserSuspendedEvent.TYPE, occurredAt);
        outbox.append(new UserSuspendedEvent(eventId, ZERO_CASE_ID, userId, moderatorId, occurredAt));
    }

    private void publishRestored(UUID userId, UUID moderatorId) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        inbox.tryClaim(eventId, UserRestoredEvent.TYPE, occurredAt);
        outbox.append(new UserRestoredEvent(eventId, ZERO_CASE_ID, userId, moderatorId, occurredAt));
    }

    private AdminUserSummary toSummary(AuthUser user, Instant now) {
        List<RoleName> roleNames = user.roles().stream().map(Role::name).sorted().toList();
        long activeSessionCount = refreshTokens.countActiveForUser(user.id(), now);
        return new AdminUserSummary(
                user.id(),
                user.email(),
                user.status(),
                user.emailVerified(),
                roleNames,
                user.createdAt(),
                activeSessionCount);
    }

    private AdminSessionSummary toSessionSummary(RefreshToken token) {
        return new AdminSessionSummary(
                token.id(),
                token.familyStartedAt(),
                token.isRevoked(),
                token.revokedReason(),
                token.expiresAt());
    }

    private AdminAuditSummary toAuditSummary(AdminAuditEvent event) {
        return new AdminAuditSummary(
                event.id(),
                event.occurredAt(),
                event.actorUserId(),
                event.actorRoles(),
                event.actionType(),
                event.targetResourceType(),
                event.targetResourceId(),
                event.result(),
                event.reason(),
                event.correlationId());
    }

    private void recordSuccess(
            UUID actorId,
            Set<String> actorRoles,
            AdminAuditAction action,
            UUID targetId,
            String reason,
            String metadataJson) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        AdminAuditEvent event = new AdminAuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                actorId,
                serializeRoles(actorRoles),
                action,
                TARGET_AUTH_USER,
                targetId,
                AdminAuditResult.SUCCESS,
                reason,
                correlationId,
                correlationId,
                metadataJson);
        auditEvents.save(event);
        adminMetrics.recordAction(action, AdminAuditResult.SUCCESS);
    }

    private void recordBootstrapSuccess(AuthUser user) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        AdminAuditEvent event = new AdminAuditEvent(
                UUID.randomUUID(),
                clock.instant(),
                user.id(),
                RoleName.SUPER_ADMIN.name(),
                AdminAuditAction.ADMIN_BOOTSTRAP_SUPER_ADMIN,
                TARGET_AUTH_USER,
                user.id(),
                AdminAuditResult.SUCCESS,
                "bootstrap",
                correlationId,
                correlationId,
                "{\"email\":\"" + user.email() + "\"}");
        auditEvents.save(event);
        adminMetrics.recordAction(AdminAuditAction.ADMIN_BOOTSTRAP_SUPER_ADMIN, AdminAuditResult.SUCCESS);
    }

    private static String serializeRoles(Set<String> actorRoles) {
        return actorRoles.stream().sorted().collect(Collectors.joining(","));
    }
}