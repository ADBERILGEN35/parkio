package com.parkio.analytics.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.analytics.application.AnalyticsApplicationService;
import com.parkio.analytics.application.result.OverviewView;
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
 * Verifies the header-based RBAC gate on the controller: platform analytics is
 * ADMIN-only (moderators and ordinary users are denied), while personal analytics is
 * owner-scoped. Standalone MockMvc with a mocked application service — no Spring
 * context, no DB.
 */
class AnalyticsControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-08T12:00:00Z"), ZoneOffset.UTC);

    private AnalyticsApplicationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AnalyticsApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(service))
                .setControllerAdvice(new GlobalExceptionHandler(CLOCK))
                .build();
    }

    @Test
    void platformOverviewDeniedForOrdinaryUser() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview").header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(service, never()).getOverview();
    }

    @Test
    void platformOverviewDeniedForModerator() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview").header("X-User-Roles", "MODERATOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(service, never()).getOverview();
    }

    @Test
    void platformOverviewDeniedWhenRolesHeaderMissing() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview"))
                .andExpect(status().isForbidden());
        verify(service, never()).getOverview();
    }

    @Test
    void platformOverviewAllowedForAdmin() throws Exception {
        when(service.getOverview()).thenReturn(new OverviewView(0, 0, 0, 0, 0, 0, 0));

        mockMvc.perform(get("/api/v1/analytics/overview").header("X-User-Roles", "USER,ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void platformMetricsDeniedForModerator() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/metrics").header("X-User-Roles", "MODERATOR"))
                .andExpect(status().isForbidden());
        verify(service, never()).getMetrics();
    }

    @Test
    void userCanReadOwnAnalytics() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.getUserAnalytics(userId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/analytics/users/" + userId).header("X-User-Id", userId.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void userCannotReadAnotherUsersAnalytics() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/users/" + UUID.randomUUID())
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
