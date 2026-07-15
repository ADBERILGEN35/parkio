package com.parkio.user.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.result.AccountStatusView;
import com.parkio.user.domain.PreferredLocale;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.domain.UserStatus;
import com.parkio.user.domain.exception.UserErrorCode;
import com.parkio.user.domain.exception.UserException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Slice test for internal user endpoints via standalone MockMvc (no Spring context).
 */
class InternalUserControllerTest {

    private final UserApplicationService userService = mock(UserApplicationService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(new InternalUserController(userService, false))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }

    @Test
    void returnsUserIdAndStatusOnly() throws Exception {
        UUID authUserId = UUID.randomUUID();
        when(userService.getAccountStatus(authUserId))
                .thenReturn(new AccountStatusView(authUserId, UserStatus.SUSPENDED));

        mvc.perform(get("/internal/users/{id}/status", authUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(authUserId.toString()))
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                // privacy guard: no profile fields are present
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.city").doesNotExist());
    }

    @Test
    void missingProfileYields404() throws Exception {
        when(userService.getAccountStatus(any()))
                .thenThrow(new UserException(UserErrorCode.PROFILE_NOT_FOUND));

        mvc.perform(get("/internal/users/{id}/status", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    void returnsPreferredLocale() throws Exception {
        UUID authUserId = UUID.randomUUID();
        UserPreference preference = mock(UserPreference.class);
        when(preference.preferredLocale()).thenReturn(PreferredLocale.EN);
        when(userService.getMyPreferences(authUserId)).thenReturn(preference);

        mvc.perform(get("/internal/users/{id}/preferred-locale", authUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLocale").value("en"));
    }

    @Test
    void missingProfilePreferredLocaleDefaultsToTr() throws Exception {
        when(userService.getMyPreferences(any()))
                .thenThrow(new UserException(UserErrorCode.PROFILE_NOT_FOUND));

        mvc.perform(get("/internal/users/{id}/preferred-locale", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLocale").value("tr"));
    }
}
