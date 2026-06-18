package com.parkio.moderation.presentation;

import com.parkio.moderation.application.ModerationApplicationService;
import com.parkio.moderation.application.command.CreateReportCommand;
import com.parkio.moderation.application.command.ResolveCaseCommand;
import com.parkio.moderation.domain.ModerationStatus;
import com.parkio.moderation.domain.UserReport;
import com.parkio.moderation.domain.exception.ModerationErrorCode;
import com.parkio.moderation.domain.exception.ModerationException;
import com.parkio.moderation.presentation.dto.AppealResponse;
import com.parkio.moderation.presentation.dto.CaseResponse;
import com.parkio.moderation.presentation.dto.CreateAppealRequest;
import com.parkio.moderation.presentation.dto.CreateReportRequest;
import com.parkio.moderation.presentation.dto.ReportResponse;
import com.parkio.moderation.presentation.dto.ResolveAppealRequest;
import com.parkio.moderation.presentation.dto.ResolveCaseRequest;
import com.parkio.moderation.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Moderation API. Translates HTTP into application calls and domain objects into
 * response DTOs — JPA entities never cross this boundary.
 *
 * <p>Identity comes from the gateway-injected {@code X-User-Id} header; requests
 * without a valid id fail closed. Moderator endpoints require a {@code MODERATOR} or
 * {@code ADMIN} role in the {@code X-User-Roles} header. Account-level actions —
 * resolving a case with a sanction/override ({@link ModerationAction#requiresAdmin()})
 * and resolving appeals — additionally require {@code ADMIN}, enforcing separation of
 * duties (ai-context/07). The application service re-checks these (defense in depth).
 */
@Tag(name = "Moderation", description = "Reports, appeals and moderator case management")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/moderation")
public class ModerationController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLES_HEADER = "X-User-Roles";
    private static final Set<String> MODERATOR_ROLES = Set.of("MODERATOR", "ADMIN");
    private static final String ADMIN_ROLE = "ADMIN";

    private final ModerationApplicationService moderationService;

    public ModerationController(ModerationApplicationService moderationService) {
        this.moderationService = moderationService;
    }

    // --- User-facing endpoints ---

    @Operation(summary = "Submit a moderation report")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/reports")
    public ResponseEntity<ReportResponse> createReport(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody CreateReportRequest request) {
        UserReport report = moderationService.createReport(new CreateReportCommand(
                requireUserId(userId), request.targetType(), request.targetId(),
                request.reason(), request.description()));
        return ResponseEntity
                .created(URI.create("/api/v1/moderation/reports/" + report.id()))
                .body(ReportResponse.from(report));
    }

    @Operation(summary = "List current user's reports")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/reports/me")
    public List<ReportResponse> getMyReports(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return moderationService.getMyReports(requireUserId(userId)).stream()
                .map(ReportResponse::from)
                .toList();
    }

    @Operation(summary = "Submit an appeal")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/appeals")
    public ResponseEntity<AppealResponse> createAppeal(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Valid @RequestBody CreateAppealRequest request) {
        AppealResponse body = AppealResponse.from(
                moderationService.createAppeal(requireUserId(userId), request.caseId(), request.note()));
        return ResponseEntity
                .created(URI.create("/api/v1/moderation/appeals/" + body.id()))
                .body(body);
    }

    // --- Moderator/admin endpoints ---

    @Operation(summary = "List moderation cases (moderator)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/cases")
    public List<CaseResponse> listCases(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @RequestParam(value = "status", required = false) ModerationStatus status) {
        requireModerator(userId, roles);
        return moderationService.listCases(status).stream()
                .map(CaseResponse::from)
                .toList();
    }

    @Operation(summary = "Get moderation case by id (moderator)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/cases/{caseId}")
    public CaseResponse getCase(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @PathVariable("caseId") UUID caseId) {
        requireModerator(userId, roles);
        return CaseResponse.from(moderationService.getCase(caseId));
    }

    @Operation(summary = "Assign moderation case (moderator)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/cases/{caseId}/assign")
    public CaseResponse assignCase(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @PathVariable("caseId") UUID caseId) {
        UUID moderatorId = requireModerator(userId, roles);
        return CaseResponse.from(moderationService.assignCase(caseId, moderatorId));
    }

    @Operation(summary = "Resolve moderation case (moderator)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/cases/{caseId}/resolve")
    public CaseResponse resolveCase(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @PathVariable("caseId") UUID caseId,
            @Valid @RequestBody ResolveCaseRequest request) {
        UUID moderatorId = requireModerator(userId, roles);
        boolean isAdmin = hasAdminRole(roles);
        // Account sanctions and trust/score overrides are ADMIN-only (separation of duties).
        if (request.action().requiresAdmin() && !isAdmin) {
            throw new ModerationException(ModerationErrorCode.FORBIDDEN,
                    "Admin role required to suspend, restore, or apply trust/score penalties.");
        }
        return CaseResponse.from(moderationService.resolveCase(
                new ResolveCaseCommand(caseId, moderatorId, request.action(), request.note(), isAdmin)));
    }

    @Operation(summary = "List appeals (moderator)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/appeals")
    public List<AppealResponse> listAppeals(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles) {
        requireModerator(userId, roles);
        return moderationService.listAppeals().stream()
                .map(AppealResponse::from)
                .toList();
    }

    @Operation(summary = "Resolve appeal (admin)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/appeals/{appealId}/resolve")
    public AppealResponse resolveAppeal(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @PathVariable("appealId") UUID appealId,
            @Valid @RequestBody ResolveAppealRequest request) {
        // Resolving an appeal reverses a moderation sanction (can restore a suspended
        // account), so it is reserved to ADMIN — moderators may review the queue only.
        UUID adminId = requireAdmin(userId, roles);
        return AppealResponse.from(
                moderationService.resolveAppeal(appealId, adminId, request.accepted(), request.note(), true));
    }

    // --- Auth helpers ---

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ModerationException(ModerationErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new ModerationException(ModerationErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }

    /**
     * Requires a valid user id <em>and</em> a moderator/admin role. Returns the
     * moderator's user id so callers can attribute the action.
     */
    private static UUID requireModerator(String userIdHeader, String rolesHeader) {
        UUID userId = requireUserId(userIdHeader);
        if (!hasModeratorRole(rolesHeader)) {
            throw new ModerationException(ModerationErrorCode.FORBIDDEN, "Moderator or admin role required.");
        }
        return userId;
    }

    private static boolean hasModeratorRole(String rolesHeader) {
        return parseRoles(rolesHeader).stream().anyMatch(MODERATOR_ROLES::contains);
    }

    /**
     * Requires a valid user id <em>and</em> the ADMIN role. Returns the admin's user id
     * so callers can attribute the action.
     */
    private static UUID requireAdmin(String userIdHeader, String rolesHeader) {
        UUID userId = requireUserId(userIdHeader);
        if (!hasAdminRole(rolesHeader)) {
            throw new ModerationException(ModerationErrorCode.FORBIDDEN, "Admin role required.");
        }
        return userId;
    }

    private static boolean hasAdminRole(String rolesHeader) {
        return parseRoles(rolesHeader).contains(ADMIN_ROLE);
    }

    private static Set<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
