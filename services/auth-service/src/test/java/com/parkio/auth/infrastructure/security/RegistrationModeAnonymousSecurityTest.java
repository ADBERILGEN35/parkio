package com.parkio.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PA-01: exact anonymous GET registration-mode through the real Spring Security
 * filter chain. Gateway internal secret remains required (direct-service trust).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationModeAnonymousSecurityTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String PATH = "/api/v1/auth/registration-mode";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pa01a_anonymousExactGetReturns200WithoutBearer() throws Exception {
        mockMvc.perform(get(PATH).header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").exists())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=30")));
    }

    @Test
    void pa01b_defaultTestFixtureIsOpenMode() throws Exception {
        // test application.yml sets parkio.registration.mode=open
        mockMvc.perform(get(PATH).header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("OPEN"));
    }

    @Test
    void pa01e_stateChangingMethodsAreNotPublic() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put(PATH)
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch(PATH)
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete(PATH).header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pa01f_protectedAuthMeRemainsUnauthorizedWithoutBearer() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pa01g_jwksPublicRoutePreserved() throws Exception {
        mockMvc.perform(get("/api/v1/auth/.well-known/jwks.json")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"));
    }

    @Test
    void similarPathsDoNotInheritAnonymousAccess() throws Exception {
        mockMvc.perform(get(PATH + "-extra").header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(401, 403, 404));

        mockMvc.perform(get("/api/v1/auth/registration-mode/extra")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(401, 403, 404));
    }

    @Test
    void jwtL_directServiceWithoutGatewaySecretIsRejected() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());
    }
}
