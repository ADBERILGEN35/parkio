package com.parkio.auth.infrastructure.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "parkio.registration.mode=invite")
class RegistrationModeInviteFixtureSecurityTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pa01d_inviteModeAnonymousGet() throws Exception {
        mockMvc.perform(get("/api/v1/auth/registration-mode").header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("INVITE"));
    }
}
