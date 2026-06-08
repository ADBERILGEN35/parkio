package com.parkio.moderation.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.moderation.application.ModerationApplicationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the header-based auth gate on the controller: identity is required and
 * moderator endpoints require a MODERATOR/ADMIN role. Uses standalone MockMvc with a
 * mocked application service — no Spring context, no DB.
 */
class ModerationControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC);

    private ModerationApplicationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ModerationApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ModerationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
                .build();
    }

    @Test
    void moderatorEndpointWithoutUserIdReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/moderation/cases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MISSING_USER_ID"));
    }

    @Test
    void moderatorEndpointWithoutModeratorRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/v1/moderation/cases")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void moderatorEndpointWithModeratorRoleIsAllowed() throws Exception {
        when(service.listCases(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/moderation/cases")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Roles", "MODERATOR"))
                .andExpect(status().isOk());
    }
}
