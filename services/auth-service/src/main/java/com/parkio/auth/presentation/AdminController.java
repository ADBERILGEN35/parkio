package com.parkio.auth.presentation;

import com.parkio.auth.application.admin.AdminApplicationService;
import com.parkio.auth.application.admin.AdminAuditSearchQuery;
import com.parkio.auth.application.admin.AdminAuthority;
import com.parkio.auth.application.admin.AdminUserSearchQuery;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.presentation.dto.admin.AdminPageResponse;
import com.parkio.auth.presentation.dto.admin.AdminReasonRequest;
import com.parkio.auth.presentation.dto.admin.AdminRoleChangeRequest;
import com.parkio.auth.presentation.dto.admin.AdminAuditEventResponse;
import com.parkio.auth.presentation.dto.admin.AdminDashboardResponse;
import com.parkio.auth.presentation.dto.admin.AdminSecuritySummaryResponse;
import com.parkio.auth.presentation.dto.admin.AdminSessionResponse;
import com.parkio.auth.presentation.dto.admin.AdminUserDetailResponse;
import com.parkio.auth.presentation.dto.admin.AdminUserSummaryResponse;
import com.parkio.auth.presentation.openapi.StandardApiResponses;
import com.parkio.auth.shared.AuthPrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "Administrative user and security management")
@SecurityRequirement(name = "bearerAuth")
@StandardApiResponses
/**
 * Admin API. The caller's identity and roles come only from the access token that
 * {@code JwtAuthenticationFilter} verified ({@link AuthPrincipal}); the gateway's
 * {@code X-User-Id}/{@code X-User-Roles} headers are ignored here. Every internal
 * service holds the shared gateway secret, so trusting those headers would let any of
 * them claim an admin role or another actor (CL-F16).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminApplicationService adminService;

    public AdminController(AdminApplicationService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard(
            @AuthenticationPrincipal AuthPrincipal principal) {
        AdminAuthority.requireAdmin(roles(principal));
        return AdminResponseMapper.toDashboard(adminService.getDashboardSummary());
    }

    @GetMapping("/users")
    public AdminPageResponse<AdminUserSummaryResponse> listUsers(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) AuthUserStatus status,
            @RequestParam(required = false) Boolean emailVerified,
            @RequestParam(required = false) RoleName role,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        AdminAuthority.requireAdmin(roles(principal));
        return AdminResponseMapper.toPage(
                adminService.listUsers(new AdminUserSearchQuery(
                        q, email, userId, status, emailVerified, role, createdFrom, createdTo, page, size, sort)),
                AdminResponseMapper::toUserSummary);
    }

    @GetMapping("/users/{id}")
    public AdminUserDetailResponse getUser(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId) {
        AdminAuthority.requireAdmin(roles(principal));
        return AdminResponseMapper.toUserDetail(adminService.getUserDetail(userId));
    }

    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<Void> suspendUser(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminReasonRequest request) {
        adminService.suspendUser(actor(principal), roles(principal), userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/reactivate")
    public ResponseEntity<Void> reactivateUser(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminReasonRequest request) {
        adminService.reactivateUser(actor(principal), roles(principal), userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/revoke-sessions")
    public ResponseEntity<Void> revokeAllSessions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminReasonRequest request) {
        adminService.revokeAllSessions(actor(principal), roles(principal), userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/resend-verification")
    public ResponseEntity<Void> resendVerification(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId,
            @RequestBody(required = false) AdminReasonRequest request) {
        String reason = request == null ? null : request.reason();
        adminService.resendVerification(actor(principal), roles(principal), userId, reason);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/users/{id}/sessions")
    public java.util.List<AdminSessionResponse> listSessions(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId) {
        return adminService.listSessions(actor(principal), roles(principal), userId).stream()
                .map(AdminResponseMapper::toSession)
                .toList();
    }

    @DeleteMapping("/users/{id}/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId,
            @PathVariable("sessionId") UUID sessionId,
            @RequestBody(required = false) AdminReasonRequest request,
            @RequestParam(required = false) String reason) {
        String resolvedReason = request != null ? request.reason() : reason;
        if (resolvedReason == null || resolvedReason.isBlank()) {
            resolvedReason = "admin session revoke";
        }
        adminService.revokeSession(
                actor(principal), roles(principal), userId, sessionId, resolvedReason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/roles")
    public ResponseEntity<Void> changeRole(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminRoleChangeRequest request) {
        Set<String> roles = roles(principal);
        if (request.action() == AdminRoleChangeRequest.RoleAction.GRANT) {
            adminService.grantRole(actor(principal), roles, userId, request.role(), request.reason());
        } else {
            adminService.revokeRole(actor(principal), roles, userId, request.role(), request.reason());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit-events")
    public AdminPageResponse<AdminAuditEventResponse> listAuditEvents(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) UUID targetResourceId,
            @RequestParam(required = false) AdminAuditAction actionType,
            @RequestParam(required = false) AdminAuditResult result,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        AdminAuthority.requireAdmin(roles(principal));
        return AdminResponseMapper.toPage(
                adminService.listAuditEvents(new AdminAuditSearchQuery(
                        actorUserId, targetResourceId, actionType, result, occurredFrom, occurredTo, page, size, sort)),
                AdminResponseMapper::toAudit);
    }

    @GetMapping("/security/summary")
    public AdminSecuritySummaryResponse securitySummary(
            @AuthenticationPrincipal AuthPrincipal principal) {
        AdminAuthority.requireAdmin(roles(principal));
        return AdminResponseMapper.toSecurity(adminService.getSecuritySummary());
    }

    private static UUID actor(AuthPrincipal principal) {
        if (principal == null) {
            throw new AuthException(AuthErrorCode.FORBIDDEN, "Admin role required.");
        }
        return principal.userId();
    }

    private static Set<String> roles(AuthPrincipal principal) {
        return principal == null ? Set.of() : AdminAuthority.normalize(principal.roles());
    }
}
