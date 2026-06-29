package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Transport-level proof for the native mobile auth contract
 * ({@code X-Parkio-Client: mobile}). The application service, rotation and reuse
 * detection are shared with the web cookie flow and covered elsewhere; these tests
 * assert only the mobile vs web transport differences in {@link AuthController}:
 *
 * <ul>
 *   <li>web login/refresh never leak the refresh token into the JSON body;</li>
 *   <li>mobile login/refresh return the rotated refresh token in the body and set
 *       no cookie;</li>
 *   <li>mobile refresh/logout read the token from the body and do not require a
 *       browser {@code Origin}, while the web (cookie) path still enforces it;</li>
 *   <li>rotation and reuse detection behave identically through the mobile path;</li>
 *   <li>logout and logout-all revoke the right tokens for mobile.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MobileAuthFlowIntegrationTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String MOBILE_HEADER = "X-Parkio-Client";
    private static final String PASSWORD = "StrongerPass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.parkio.auth.application.AuthApplicationService authApplicationService;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @Autowired
    private RoleJpaRepository roles;

    @MockBean
    private LoginFailureTracker loginFailureTracker;

    @MockBean
    private EmailVerificationSender emailVerificationSender;

    @BeforeEach
    void seedUserRole() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
    }

    // --- Web transport is unchanged -------------------------------------------------

    @Test
    void webLoginKeepsRefreshTokenOutOfBodyAndInCookie() throws Exception {
        String email = registerAndVerify("web-login-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(credentials(email)))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("parkio_refresh"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void webRefreshWithoutOriginIsStillForbidden() throws Exception {
        // No mobile header + no Origin must remain a 403 for the browser cookie flow.
        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET))
                .andExpect(status().isForbidden());
    }

    // --- Mobile login ---------------------------------------------------------------

    @Test
    void mobileLoginReturnsRefreshTokenInBodyAndSetsNoCookie() throws Exception {
        String email = registerAndVerify("mobile-login-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(credentials(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void browserLikeRequestCannotOptIntoMobileRefreshTokenBody() throws Exception {
        String email = registerAndVerify("browser-mobile-header-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(credentials(email)))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("parkio_refresh"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    // --- Mobile refresh (rotation + no Origin) --------------------------------------

    @Test
    void mobileRefreshRotatesUsingBodyTokenWithoutOrigin() throws Exception {
        String email = registerAndVerify("mobile-refresh-" + UUID.randomUUID() + "@example.com");
        JsonNode login = mobileLogin(email);
        String original = login.get("refreshToken").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(refreshBody(original)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        String rotated = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(original);
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(original)))
                .get()
                .extracting(RefreshToken::revokedReason)
                .isEqualTo(RefreshTokenRevocationReason.ROTATED);
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(rotated))).isPresent();
    }

    @Test
    void mobileRefreshWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void mobileRefreshReuseDetectionRevokesFamily() throws Exception {
        String email = registerAndVerify("mobile-reuse-" + UUID.randomUUID() + "@example.com");
        JsonNode login = mobileLogin(email);
        String original = login.get("refreshToken").asText();

        // First rotation succeeds.
        String rotated = objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(refreshBody(original)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("refreshToken").asText();

        // Replaying the now-revoked original token is reuse → family compromised.
        String response = mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(refreshBody(original)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response).doesNotContain(original).doesNotContain(rotated);

        RefreshToken reused = refreshTokens.findByTokenHash(refreshTokenHasher.hash(original)).orElseThrow();
        RefreshToken child = refreshTokens.findByTokenHash(refreshTokenHasher.hash(rotated)).orElseThrow();
        assertThat(reused.isReusedDetected()).isTrue();
        assertThat(child.isRevoked()).isTrue();
        assertThat(child.revokedReason()).isEqualTo(RefreshTokenRevocationReason.REUSE_DETECTED);
    }

    // --- Mobile logout / logout-all -------------------------------------------------

    @Test
    void mobileLogoutRevokesBodyTokenWithoutOriginOrCookie() throws Exception {
        String email = registerAndVerify("mobile-logout-" + UUID.randomUUID() + "@example.com");
        JsonNode login = mobileLogin(email);
        String token = login.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(refreshBody(token)))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));

        RefreshToken revoked = refreshTokens.findByTokenHash(refreshTokenHasher.hash(token)).orElseThrow();
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
    }

    @Test
    void mobileLogoutAllRevokesEverySessionViaBearer() throws Exception {
        String email = registerAndVerify("mobile-logout-all-" + UUID.randomUUID() + "@example.com");
        // Two independent mobile sessions (families).
        JsonNode first = mobileLogin(email);
        JsonNode second = mobileLogin(email);
        String firstToken = first.get("refreshToken").asText();
        String secondToken = second.get("refreshToken").asText();
        String accessToken = second.get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout-all")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(firstToken)).orElseThrow().isRevoked())
                .isTrue();
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(secondToken)).orElseThrow().isRevoked())
                .isTrue();
    }

    // --- helpers --------------------------------------------------------------------

    private JsonNode mobileLogin(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(credentials(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String registerAndVerify(String email) {
        clearInvocations(emailVerificationSender);
        // Register + capture verification token + verify, reusing the application service
        // directly for setup (the registration HTTP path is covered by other tests).
        authApplicationService.register(new RegisterCommand(email, PASSWORD));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender, atLeastOnce()).sendVerificationLink(eq(email), tokenCaptor.capture());
        authApplicationService.verifyEmail(new VerifyEmailCommand(tokenCaptor.getValue()));
        return email;
    }

    private String credentials(String email) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
    }

    private String refreshBody(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }
}
