package com.parkio.auth.presentation;

import com.parkio.auth.application.admin.AdminApplicationService;
import com.parkio.auth.application.admin.AdminAuditSearchQuery;
import com.parkio.auth.application.admin.AdminAuthority;
import com.parkio.auth.application.admin.AdminUserSearchQuery;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.admin.AdminAuditAction;
import com.parkio.auth.domain.admin.AdminAuditResult;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin", description = "Administrative user and security management")
@SecurityRequirement(name = "bearerAuth")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    private final AdminApplicationService adminService;

    public AdminController(AdminApplicationService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader) {
        AdminAuthority.requireAdmin(AdminAuthority.parseRoles(rolesHeader));
        return AdminResponseMapper.toDashboard(adminService.getDashboardSummary());
    }

    @GetMapping("/users")
    public AdminPageResponse<AdminUserSummaryResponse> listUsers(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
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
        AdminAuthority.requireAdmin(AdminAuthority.parseRoles(rolesHeader));
        return AdminResponseMapper.toPage(
                adminService.listUsers(new AdminUserSearchQuery(
                        q, email, userId, status, emailVerified, role, createdFrom, createdTo, page, size, sort)),
                AdminResponseMapper::toUserSummary);
    }

    @GetMapping("/users/{id}")
    public AdminUserDetailResponse getUser(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId) {
        AdminAuthority.requireAdmin(AdminAuthority.parseRoles(rolesHeader));
        return AdminResponseMapper.toUserDetail(adminService.getUserDetail(userId));
    }

    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<Void> suspendUser(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminReasonRequest request) {
        adminService.suspendUser(actorId, AdminAuthority.parseRoles(rolesHeader), userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/reactivate")
    public ResponseEntity<Void> reactivateUser(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminReasonRequest request) {
        adminService.reactivateUser(actorId, AdminAuthority.parseRoles(rolesHeader), userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/revoke-sessions")
    public ResponseEntity<Void> revokeAllSessions(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminReasonRequest request) {
        adminService.revokeAllSessions(actorId, AdminAuthority.parseRoles(rolesHeader), userId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/resend-verification")
    public ResponseEntity<Void> resendVerification(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId,
            @RequestBody(required = false) AdminReasonRequest request) {
        String reason = request == null ? null : request.reason();
        adminService.resendVerification(actorId, AdminAuthority.parseRoles(rolesHeader), userId, reason);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/users/{id}/sessions")
    public java.util.List<AdminSessionResponse> listSessions(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId) {
        return adminService.listSessions(actorId, AdminAuthority.parseRoles(rolesHeader), userId).stream()
                .map(AdminResponseMapper::toSession)
                .toList();
    }

    @DeleteMapping("/users/{id}/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId,
            @PathVariable("sessionId") UUID sessionId,
            @RequestBody(required = false) AdminReasonRequest request,
            @RequestParam(required = false) String reason) {
        String resolvedReason = request != null ? request.reason() : reason;
        if (resolvedReason == null || resolvedReason.isBlank()) {
            resolvedReason = "admin session revoke";
        }
        adminService.revokeSession(
                actorId, AdminAuthority.parseRoles(rolesHeader), userId, sessionId, resolvedReason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/roles")
    public ResponseEntity<Void> changeRole(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @PathVariable("id") UUID userId,
            @Valid @RequestBody AdminRoleChangeRequest request) {
        Set<String> roles = AdminAuthority.parseRoles(rolesHeader);
        if (request.action() == AdminRoleChangeRequest.RoleAction.GRANT) {
            adminService.grantRole(actorId, roles, userId, request.role(), request.reason());
        } else {
            adminService.revokeRole(actorId, roles, userId, request.role(), request.reason());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit-events")
    public AdminPageResponse<AdminAuditEventResponse> listAuditEvents(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) UUID targetResourceId,
            @RequestParam(required = false) AdminAuditAction actionType,
            @RequestParam(required = false) AdminAuditResult result,
            @RequestParam(required = false) Instant occurredFrom,
            @RequestParam(required = false) Instant occurredTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        AdminAuthority.requireAdmin(AdminAuthority.parseRoles(rolesHeader));
        return AdminResponseMapper.toPage(
                adminService.listAuditEvents(new AdminAuditSearchQuery(
                        actorUserId, targetResourceId, actionType, result, occurredFrom, occurredTo, page, size, sort)),
                AdminResponseMapper::toAudit);
    }

    @GetMapping("/security/summary")
    public AdminSecuritySummaryResponse securitySummary(
            @RequestHeader(USER_ID_HEADER) UUID actorId,
            @RequestHeader(USER_ROLES_HEADER) String rolesHeader) {
        AdminAuthority.requireAdmin(AdminAuthority.parseRoles(rolesHeader));
        return AdminResponseMapper.toSecurity(adminService.getSecuritySummary());
    }
}
