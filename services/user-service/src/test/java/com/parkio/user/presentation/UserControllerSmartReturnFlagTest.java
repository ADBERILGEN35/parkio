package com.parkio.user.presentation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.user.application.UserApplicationService;
import com.parkio.user.domain.UserPreference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UserControllerSmartReturnFlagTest {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final UUID USER_ID = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000021");

    @Test
    void smartReturnEndpointIsHiddenWhenFeatureFlagIsOff() throws Exception {
        UserApplicationService userService = mock(UserApplicationService.class);

        mvc(userService, false)
                .perform(get("/api/v1/users/me/smart-return").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SMART_RETURN_DISABLED"));

        verify(userService, never()).getMySmartReturn(USER_ID);
    }

    @Test
    void smartReturnEndpointWorksWhenFeatureFlagIsOn() throws Exception {
        UserApplicationService userService = mock(UserApplicationService.class);
        when(userService.getMySmartReturn(USER_ID)).thenReturn(UserPreference.createDefault(UUID.randomUUID()));

        mvc(userService, true)
                .perform(get("/api/v1/users/me/smart-return").header(USER_ID_HEADER, USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.reminderLeadMinutes").value(UserPreference.DEFAULT_SMART_RETURN_LEAD_MINUTES))
                .andExpect(jsonPath("$.todayStatus").value("UNKNOWN"));

        verify(userService).getMySmartReturn(USER_ID);
    }

    private static MockMvc mvc(UserApplicationService userService, boolean enabled) {
        Clock clock = Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC);
        return MockMvcBuilders.standaloneSetup(new UserController(userService, enabled))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }
}
