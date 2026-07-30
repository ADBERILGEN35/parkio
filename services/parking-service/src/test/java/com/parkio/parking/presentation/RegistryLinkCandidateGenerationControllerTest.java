package com.parkio.parking.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.LinkCandidateGenerationOrchestrator;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistryLinkCandidateGenerationControllerTest {
    private LinkCandidateGenerationOrchestrator orchestrator;
    private LinkCandidateGenerationRunPort runs;
    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orchestrator = mock(LinkCandidateGenerationOrchestrator.class);
        runs = mock(LinkCandidateGenerationRunPort.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new RegistryLinkCandidateGenerationController(orchestrator, runs)).build();
    }

    @Test
    void adminCanGenerateAndReadRuns() throws Exception {
        when(orchestrator.generate(any())).thenReturn(run());
        when(runs.findById(runId)).thenReturn(Optional.of(run()));

        mvc.perform(post("/api/v1/parking/admin/municipal/registry/link-candidates/generate")
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-Id", "admin-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceFamilyLeft":"IZUM","sourceFamilyRight":"OSM",
                                 "dryRun":true,"persistCandidates":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidate-runs/{id}", runId)
                        .header("X-User-Roles", "SUPER_ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void missingAndNonAdminRolesAreRejected() throws Exception {
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidate-runs/{id}", runId))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidate-runs/{id}", runId)
                        .header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRunIsNotFound() throws Exception {
        when(runs.findById(runId)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/parking/admin/municipal/registry/link-candidate-runs/{id}", runId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    private LinkCandidateGenerationRunPort.RunRecord run() {
        Instant now = Instant.parse("2026-07-30T19:00:00Z");
        return new LinkCandidateGenerationRunPort.RunRecord(
                runId, "IZUM_OSM", "registry-link-candidate-v1", true, false,
                100, 100, 1000, 20, "{}", "COMPLETED",
                new LinkCandidateGenerationRunPort.Aggregates(1, 1, 1, 0, 0, Map.of(), 0, 0),
                "[]", null, "admin-1", "test", now, now, 10L);
    }
}
