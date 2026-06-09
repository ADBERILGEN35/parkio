package com.parkio.aivalidation.presentation;

import com.parkio.aivalidation.application.AiValidationApplicationService;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.exception.AiValidationErrorCode;
import com.parkio.aivalidation.domain.exception.AiValidationException;
import com.parkio.aivalidation.presentation.dto.AiValidationResponse;
import com.parkio.aivalidation.presentation.dto.ManualValidationRequest;
import com.parkio.aivalidation.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Advisory AI validation API. Translates HTTP into application calls and domain objects
 * into response DTOs — JPA entities never cross this boundary.
 *
 * <p>Read endpoints expose advisory results. The manual endpoint is restricted to
 * moderators/admins: identity comes from the gateway-injected {@code X-User-Id} (fail
 * closed `401`) and the role from {@code X-User-Roles} (`403` without MODERATOR/ADMIN).
 */
@Tag(name = "AI Validation", description = "Advisory AI validation results and manual overrides")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/ai-validations")
public class AiValidationController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLES_HEADER = "X-User-Roles";
    private static final Set<String> MODERATOR_ROLES = Set.of("MODERATOR", "ADMIN");

    private final AiValidationApplicationService validationService;

    public AiValidationController(AiValidationApplicationService validationService) {
        this.validationService = validationService;
    }

    @Operation(summary = "Get AI validation by id")
    @GetMapping("/{validationId}")
    public AiValidationResponse getById(@PathVariable("validationId") UUID validationId) {
        return AiValidationResponse.from(validationService.getById(validationId));
    }

    @Operation(summary = "List AI validations for media")
    @GetMapping("/media/{mediaId}")
    public List<AiValidationResponse> getByMedia(@PathVariable("mediaId") UUID mediaId) {
        return validationService.getByMediaId(mediaId).stream()
                .map(AiValidationResponse::from)
                .toList();
    }

    @Operation(summary = "List AI validations for parking spot")
    @GetMapping("/parking/{parkingSpotId}")
    public List<AiValidationResponse> getByParking(@PathVariable("parkingSpotId") UUID parkingSpotId) {
        return validationService.getByParkingSpotId(parkingSpotId).stream()
                .map(AiValidationResponse::from)
                .toList();
    }

    @Operation(summary = "Create manual validation (moderator)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/manual")
    public ResponseEntity<AiValidationResponse> createManual(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = ROLES_HEADER, required = false) String roles,
            @Valid @RequestBody ManualValidationRequest request) {
        UUID moderatorId = requireModerator(userId, roles);
        AiValidationResult result = validationService.createManualValidation(
                request.mediaId(), request.parkingSpotId(), moderatorId);
        return ResponseEntity
                .created(URI.create("/api/v1/ai-validations/" + result.id()))
                .body(AiValidationResponse.from(result));
    }

    // --- Auth helpers ---

    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new AiValidationException(AiValidationErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new AiValidationException(AiValidationErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }

    /** Requires a valid user id and a moderator/admin role; returns the moderator's id. */
    private static UUID requireModerator(String userIdHeader, String rolesHeader) {
        UUID userId = requireUserId(userIdHeader);
        if (!hasModeratorRole(rolesHeader)) {
            throw new AiValidationException(AiValidationErrorCode.FORBIDDEN, "Moderator or admin role required.");
        }
        return userId;
    }

    private static boolean hasModeratorRole(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return false;
        }
        Set<String> roles = Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return roles.stream().anyMatch(MODERATOR_ROLES::contains);
    }
}
