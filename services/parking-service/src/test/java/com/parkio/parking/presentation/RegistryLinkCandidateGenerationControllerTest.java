package com.parkio.parking.presentation;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ConcurrentGenerationException;
import com.parkio.parking.application.LinkCandidateGenerationOrchestrator;
import com.parkio.parking.application.port.LinkCandidateGenerationRunPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistryLinkCandidateGenerationControllerTest {
    private static final String GENERATE =
            "/api/v1/parking/admin/municipal/registry/link-candidates/generate";
    private static final String RUNS =
            "/api/v1/parking/admin/municipal/registry/link-candidate-runs";
    private static final String ADMIN_BODY = """
            {
              "sourceFamilyLeft":"IZUM",
              "sourceFamilyRight":"OSM",
              "dryRun":true,
              "persistCandidates":false
            }
            """;

    private LinkCandidateGenerationOrchestrator orchestrator;
    private LinkCandidateGenerationRunPort runs;
    private MockMvc mvc;
    private UUID runId;

    @BeforeEach
    void setUp() {
        orchestrator = mock(LinkCandidateGenerationOrchestrator.class);
        runs = mock(LinkCandidateGenerationRunPort.class);
        runId = UUID.randomUUID();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-07-30T19:00:00Z"), ZoneOffset.UTC);
        var controller = new RegistryLinkCandidateGenerationController(orchestrator, runs);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(fixedClock))
                .build();
    }

    @Test
    void unauthenticatedGenerateReturns401() throws Exception {
        assertSafeError(mvc.perform(post(GENERATE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ADMIN_BODY)), 401, "UNAUTHORIZED");
    }

    @Test
    void userGenerateReturns403() throws Exception {
        assertSafeError(mvc.perform(post(GENERATE)
                .header("X-User-Roles", "USER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ADMIN_BODY)), 403, "FORBIDDEN");
    }

    @Test
    void adminDryRunReturns200() throws Exception {
        when(orchestrator.generate(any())).thenReturn(run(true, false));
        mvc.perform(post(GENERATE)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-Id", "admin-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADMIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.persistCandidates").value(false));
    }

    @Test
    void adminPersistReturns200() throws Exception {
        when(orchestrator.generate(any())).thenReturn(run(false, true));
        mvc.perform(post(GENERATE)
                        .header("X-User-Roles", "SUPER_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceFamilyLeft":"IZUM","sourceFamilyRight":"OSM",
                                 "dryRun":false,"persistCandidates":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.persistCandidates").value(true));
    }

    @Test
    void invalidBoundsReturns400() throws Exception {
        when(orchestrator.generate(any())).thenThrow(new IllegalArgumentException("maxDistanceMeters must be positive"));
        assertSafeError(adminPost("""
                {"sourceFamilyLeft":"IZUM","sourceFamilyRight":"OSM","maxDistanceMeters":-1,
                 "dryRun":true,"persistCandidates":false}
                """), 400, "BAD_REQUEST");
    }

    @Test
    void contradictoryDryRunPersistReturns400() throws Exception {
        when(orchestrator.generate(any()))
                .thenThrow(new IllegalArgumentException("persistCandidates=true requires dryRun=false"));
        assertSafeError(adminPost("""
                {"sourceFamilyLeft":"IZUM","sourceFamilyRight":"OSM",
                 "dryRun":true,"persistCandidates":true}
                """), 400, "BAD_REQUEST");
    }

    @Test
    void unsupportedSourcePairReturns400() throws Exception {
        when(orchestrator.generate(any())).thenThrow(new IllegalArgumentException("unsupported source family pair"));
        assertSafeError(adminPost("""
                {"sourceFamilyLeft":"IZUM","sourceFamilyRight":"IZELMAN",
                 "dryRun":true,"persistCandidates":false}
                """), 400, "BAD_REQUEST");
    }

    @Test
    void concurrentRunReturns409() throws Exception {
        when(orchestrator.generate(any())).thenThrow(new ConcurrentGenerationException("IZUM_OSM"));
        assertSafeError(adminPost(ADMIN_BODY), 409, "CONFLICT");
    }

    @Test
    void unknownRunReturns404() throws Exception {
        when(runs.findById(runId)).thenReturn(Optional.empty());
        assertSafeError(mvc.perform(get(RUNS + "/" + runId)
                .header("X-User-Roles", "ADMIN")), 404, "NOT_FOUND");
    }

    @Test
    void malformedBodyReturns400() throws Exception {
        assertSafeError(mvc.perform(post(GENERATE)
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{")), 400, "MALFORMED_REQUEST");
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        assertSafeError(mvc.perform(put(GENERATE)
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ADMIN_BODY)), 405, "METHOD_NOT_ALLOWED");
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeak() throws Exception {
        when(orchestrator.generate(any())).thenThrow(
                new RuntimeException("SQL SELECT jwt-token com.parkio.SecretRepository"));
        assertSafeError(adminPost(ADMIN_BODY), 500, "INTERNAL_ERROR")
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("jwt-token"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("com.parkio"))));
    }

    private ResultActions adminPost(String body) throws Exception {
        return mvc.perform(post(GENERATE)
                .header("X-User-Roles", "ADMIN")
                .header("X-User-Id", "admin-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions assertSafeError(ResultActions action, int expectedStatus, String expectedCode)
            throws Exception {
        return action.andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.code", notNullValue()))
                .andExpect(jsonPath("$.message", notNullValue()))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("Exception"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("at com.parkio"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("SELECT "))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("Bearer "))));
    }

    private LinkCandidateGenerationRunPort.RunRecord run(boolean dryRun, boolean persist) {
        return new LinkCandidateGenerationRunPort.RunRecord(
                runId, "IZUM_OSM", "registry-link-candidate-v1", dryRun, persist,
                100, 100, 1000, 20, "{}", "COMPLETED",
                new LinkCandidateGenerationRunPort.Aggregates(1, 1, 1, persist ? 1 : 0, 0, Map.of(), 0, 0),
                "[]", null, "admin-1", "test", Instant.parse("2026-07-30T19:00:00Z"),
                Instant.parse("2026-07-30T19:00:01Z"), 1000L);
    }
}
