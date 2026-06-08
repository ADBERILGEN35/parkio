package com.parkio.aivalidation.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.aivalidation.application.AiValidationApplicationService;
import com.parkio.aivalidation.domain.AiValidationResult;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the role gate on the manual endpoint: identity is required and the role
 * must be MODERATOR/ADMIN. Standalone MockMvc with a mocked service — no Spring context.
 */
class AiValidationControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC);

    private AiValidationApplicationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AiValidationApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AiValidationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
                .build();
    }

    @Test
    void manualEndpointWithoutUserIdReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/ai-validations/manual")
                        .contentType("application/json")
                        .content("{\"mediaId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void manualEndpointWithoutModeratorRoleReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/ai-validations/manual")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "USER")
                        .contentType("application/json")
                        .content("{\"mediaId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void manualEndpointWithModeratorRoleIsAllowed() throws Exception {
        UUID mediaId = UUID.randomUUID();
        AiValidationResult result = new DeterministicAiValidator()
                .validate(mediaId, null, UUID.randomUUID(), CLOCK.instant());
        when(service.createManualValidation(any(), any(), any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/ai-validations/manual")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "MODERATOR")
                        .contentType("application/json")
                        .content("{\"mediaId\":\"" + mediaId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaId").value(mediaId.toString()));
    }
}
