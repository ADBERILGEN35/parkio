package com.parkio.parking.presentation;

import com.parkio.parking.application.ConcurrentGenerationException;
import com.parkio.parking.application.LinkCandidateGenerationOrchestrator;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
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
@RequestMapping("/api/v1/parking/admin/municipal/registry")
@ConditionalOnProperty(
        name = "parkio.municipal.registry.candidate-generation-enabled",
        havingValue = "true")
public class RegistryLinkCandidateGenerationController {
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN", "SUPER_ADMIN");
    private final LinkCandidateGenerationOrchestrator orchestrator;
    private final LinkCandidateGenerationRunPort runs;

    public RegistryLinkCandidateGenerationController(
            LinkCandidateGenerationOrchestrator orchestrator,
            LinkCandidateGenerationRunPort runs) {
        this.orchestrator = orchestrator;
        this.runs = runs;
    }

    public record GenerateRequest(
            String sourceFamilyLeft,
            String sourceFamilyRight,
            Double maxDistanceMeters,
            Integer leftRecordLimit,
            Integer pairLimit,
            Integer sampleLimit,
            boolean dryRun,
            boolean persistCandidates,
            List<UUID> leftFacilityIds,
            List<String> leftExternalIds,
            String algorithmVersion,
            String correlationId) {}

    @PostMapping("/link-candidates/generate")
    public LinkCandidateGenerationRunPort.RunRecord generate(
            @RequestBody GenerateRequest request,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = "X-User-Id", required = false) String operatorUserId) {
        requireAdmin(roles);
        try {
            return orchestrator.generate(new LinkCandidateGenerationOrchestrator.Request(
                    request.sourceFamilyLeft(), request.sourceFamilyRight(),
                    request.maxDistanceMeters(), request.leftRecordLimit(), request.pairLimit(),
                    request.sampleLimit(), request.dryRun(), request.persistCandidates(),
                    request.leftFacilityIds(), request.leftExternalIds(), request.algorithmVersion(),
                    operatorUserId, request.correlationId()));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (ConcurrentGenerationException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping("/link-candidate-runs/{runId}")
    public LinkCandidateGenerationRunPort.RunRecord detail(
            @PathVariable UUID runId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        requireAdmin(roles);
        return runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "generation run not found"));
    }

    @GetMapping("/link-candidate-runs")
    public LinkCandidateGenerationRunPort.RunPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sourceFamilyPair,
            @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        requireAdmin(roles);
        try {
            return runs.findPage(page, size, sourceFamilyPair);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
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
        if (!admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required");
    }
}
