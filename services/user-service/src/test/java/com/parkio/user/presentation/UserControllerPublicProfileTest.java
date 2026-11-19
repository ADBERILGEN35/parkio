package com.parkio.user.presentation;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.user.application.UserApplicationService;
import com.parkio.user.application.result.PublicProfileView;
import com.parkio.user.domain.TrustBand;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserStatus;
import com.parkio.user.domain.UserTrustProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Public-profile contract via standalone MockMvc (no Spring context). Any signed-in
 * user can read any user's public profile, so the response carries only what another
 * user needs to see. The account's moderation status stays with the owner
 * ({@code GET /users/me}) and moderators.
 */
class UserControllerPublicProfileTest {

    private static final UUID AUTH_USER_ID = UUID.fromString("0b8f6c3a-0000-0000-0000-000000000551");
    private static final Instant MEMBER_SINCE = Instant.parse("2026-01-01T09:00:00Z");

    @Test
    void publicProfileOfSuspendedAccountDoesNotExposeModerationStatus() throws Exception {
        UserApplicationService userService = mock(UserApplicationService.class);
        UserProfile suspended = new UserProfile(
                UUID.fromString("0b8f6c3a-0000-0000-0000-000000000552"),
                AUTH_USER_ID,
                "driver@parkio.dev",
                "Jane Driver",
                "+905550000000",
                "Istanbul",
                UserStatus.SUSPENDED,
                Instant.parse("2026-06-01T10:00:00Z"),
                MEMBER_SINCE,
                3L);
        UserTrustProfile trust = new UserTrustProfile(
                UUID.fromString("0b8f6c3a-0000-0000-0000-000000000553"),
                suspended.id(),
                55,
                TrustBand.MEDIUM_TRUST,
                420L,
                3,
                1L);
        when(userService.getPublicProfile(AUTH_USER_ID)).thenReturn(PublicProfileView.of(suspended, trust));

        mvc(userService)
                .perform(get("/api/v1/users/{userId}/public-profile", AUTH_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.userId").value(AUTH_USER_ID.toString()))
                .andExpect(jsonPath("$.displayName").value("Jane Driver"))
                .andExpect(jsonPath("$.city").value("Istanbul"))
                .andExpect(jsonPath("$.trustBand").value("MEDIUM_TRUST"))
                .andExpect(jsonPath("$.currentLevel").value(3))
                .andExpect(jsonPath("$.memberSince").exists())
                // Exactly the six public fields above: no account status, email, phone
                // number or vehicle data under any other name either.
                .andExpect(jsonPath("$.*", hasSize(6)));
    }

    private static MockMvc mvc(UserApplicationService userService) {
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
        return MockMvcBuilders.standaloneSetup(new UserController(userService, false))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .build();
    }
}
