package com.parkio.parking.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.LinkReviewApplicationService;
import com.parkio.parking.application.port.RegistryPersistencePort;
import com.parkio.parking.presentation.dto.RegistryLinkCandidateResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/parking/admin/municipal/registry/link-candidates")
@ConditionalOnProperty(
        name = "parkio.municipal.registry.review-api-enabled",
        havingValue = "true")
public class RegistryLinkReviewController {
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "SUPER_ADMIN");

    private final LinkReviewApplicationService service;
    private final ObjectMapper objectMapper;

    public RegistryLinkReviewController(
            LinkReviewApplicationService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public record CandidatePage(
            List<RegistryLinkCandidateResponse> content,
            int page,
            int size,
            long totalElements) {}

    public record AcceptRequest(long expectedVersion, UUID chosenFacilityId) {}

    public record DecisionRequest(long expectedVersion, String reason) {}

    @GetMapping
    public CandidatePage pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "PENDING") String reviewState,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        requireAdmin(roles);
        RegistryPersistencePort.CandidatePage result = service.pending(page, size, reviewState);
        return new CandidatePage(
                result.content().stream()
                        .map(candidate -> RegistryLinkCandidateResponse.from(candidate, objectMapper))
                        .toList(),
                result.page(),
                result.size(),
                result.totalElements());
    }

    @GetMapping("/{id}")
    public RegistryLinkCandidateResponse detail(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        requireAdmin(roles);
        return service.detail(id)
                .map(candidate -> RegistryLinkCandidateResponse.from(candidate, objectMapper))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found"));
    }

    @PostMapping("/{id}/accept")
    public RegistryLinkCandidateResponse accept(
            @PathVariable UUID id,
            @RequestBody AcceptRequest request,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Id", required = false) String reviewer) {
        requireAdmin(roles);
        return map(() -> service.accept(
                id, request.expectedVersion(), request.chosenFacilityId(), requireReviewer(reviewer)));
    }

    @PostMapping("/{id}/reject")
    public RegistryLinkCandidateResponse reject(
            @PathVariable UUID id,
            @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Id", required = false) String reviewer) {
        requireAdmin(roles);
        return map(() -> service.reject(
                id, request.expectedVersion(), request.reason(), requireReviewer(reviewer)));
    }

    @PostMapping("/{id}/distinct")
    public RegistryLinkCandidateResponse distinct(
            @PathVariable UUID id,
            @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Id", required = false) String reviewer) {
        requireAdmin(roles);
        return map(() -> service.distinct(
                id, request.expectedVersion(), request.reason(), requireReviewer(reviewer)));
    }

    @PostMapping("/{id}/reopen")
    public RegistryLinkCandidateResponse reopen(
            @PathVariable UUID id,
            @RequestBody DecisionRequest request,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Id", required = false) String reviewer) {
        requireAdmin(roles);
        return map(() -> service.reopen(
                id, request.expectedVersion(), request.reason(), requireReviewer(reviewer)));
    }

    private RegistryLinkCandidateResponse map(ReviewAction action) {
        try {
            return RegistryLinkCandidateResponse.from(action.run(), objectMapper);
        } catch (LinkReviewApplicationService.CandidateNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found");
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
        }
    }

    private static void requireAdmin(String roles) {
        if (roles == null || roles.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
        boolean admin = Arrays.stream(roles.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_ROLES::contains);
        if (!admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
        }
    }

    private static String requireReviewer(String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer identity is required");
        }
        return reviewer;
    }

    @FunctionalInterface
    private interface ReviewAction {
        RegistryPersistencePort.Candidate run();
    }
}
