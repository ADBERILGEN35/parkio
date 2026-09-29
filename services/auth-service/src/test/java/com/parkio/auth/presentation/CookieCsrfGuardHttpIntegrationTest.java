package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.command.LoginCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HTTP evidence for cookie-backed CSRF guards on refresh/logout/logout-all
 * (CodeQL {@code java/spring-disabled-csrf-protection} / alert #7).
 *
 * <p><b>What this is.</b> These cases drive the real Spring Security filter chain
 * via MockMvc ({@code GatewayAuthFilter}, JWT filter, {@code SecurityFilterChain},
 * then {@link AuthController}). Rejected requests must leave the refresh-token row
 * and session epoch unchanged — an HTTP 403/401 alone is not enough.
 *
 * <p><b>What this is not.</b> MockMvc does not emulate a browser. It will attach
 * a {@code Cookie} header whenever the test sets one, including on a forged
 * cross-site POST. It does not enforce {@code SameSite=Strict}, CORS
 * {@code Access-Control-Allow-Origin}, or the browser's decision to omit cookies.
 * Those product controls are asserted by cookie attribute tests in
 * {@link com.parkio.auth.infrastructure.persistence.RefreshTokenSecurityIntegrationTest}
 * and by gateway CORS tests. This class only proves the <em>server-side</em>
 * Origin/Referer/mobile-header policy.
 *
 * <p>Already covered elsewhere and not repeated here:
 * <ul>
 *   <li>allowed {@code Origin} cookie refresh/logout success and foreign-{@code Origin}
 *       HTTP 403 — {@code RefreshTokenSecurityIntegrationTest}
 *       (that 403 did not previously assert no rotation/revocation);</li>
 *   <li>mobile body-token refresh rotation, reuse detection, logout, and
 *       bearer logout-all — {@link MobileAuthFlowIntegrationTest};</li>
 *   <li>login + {@code X-Parkio-Client: mobile} + browser {@code Origin} stays
 *       on the cookie path — {@code browserLikeRequestCannotOptIntoMobileRefreshTokenBody}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CookieCsrfGuardHttpIntegrationTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String MOBILE_HEADER = "X-Parkio-Client";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String ALLOWED_REFERER = "http://localhost:5173/app/login";
    private static final String FOREIGN_ORIGIN = "https://evil.example";
    private static final String FOREIGN_REFERER = "https://evil.example/csrf";
    private static final String PASSWORD = "StrongerPass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApplicationService authService;

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

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/refresh-token", "/api/v1/auth/logout"})
    void allowedRefererWithoutOriginSucceedsForCookieRefreshAndLogout(String path) throws Exception {
        AuthResult initial = registerVerifiedAndLogin("referer-ok-" + UUID.randomUUID() + "@example.com");

        MockHttpServletRequestBuilder request = cookieAuth(path, initial)
                .header("Referer", ALLOWED_REFERER);

        if ("/api/v1/auth/refresh-token".equals(path)) {
            MvcResult result = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(cookie().exists("parkio_refresh"))
                    .andExpect(jsonPath("$.refreshToken").doesNotExist())
                    .andReturn();
            String rotated = result.getResponse().getCookie("parkio_refresh").getValue();
            assertThat(rotated).isNotEqualTo(initial.refreshToken());
            assertThat(refreshTokens.findByTokenHash(hash(initial.refreshToken())))
                    .get()
                    .extracting(RefreshToken::revokedReason)
                    .isEqualTo(RefreshTokenRevocationReason.ROTATED);
            assertThat(refreshTokens.findByTokenHash(hash(rotated))).isPresent();
        } else {
            mockMvc.perform(request).andExpect(status().isNoContent());
            RefreshToken token = refreshTokens.findByTokenHash(hash(initial.refreshToken())).orElseThrow();
            assertThat(token.isRevoked()).isTrue();
            assertThat(token.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/refresh-token", "/api/v1/auth/logout"})
    void foreignMalformedOrAbsentOriginRefererRejectsWithoutMutating(String path) throws Exception {
        AuthResult initial = registerVerifiedAndLogin("reject-matrix-" + UUID.randomUUID() + "@example.com");
        SessionSnapshot snap = snapshot(initial);

        mockMvc.perform(cookieAuth(path, initial)).andExpect(status().isForbidden());
        assertUnchanged(snap);

        mockMvc.perform(cookieAuth(path, initial).header("Origin", FOREIGN_ORIGIN))
                .andExpect(status().isForbidden());
        assertUnchanged(snap);

        mockMvc.perform(cookieAuth(path, initial).header("Referer", FOREIGN_REFERER))
                .andExpect(status().isForbidden());
        assertUnchanged(snap);

        mockMvc.perform(cookieAuth(path, initial).header("Origin", "https://"))
                .andExpect(status().isForbidden());
        assertUnchanged(snap);

        mockMvc.perform(cookieAuth(path, initial).header("Origin", "not-a-uri"))
                .andExpect(status().isForbidden());
        assertUnchanged(snap);

        mockMvc.perform(cookieAuth(path, initial).header("Referer", "::::"))
                .andExpect(status().isForbidden());
        assertUnchanged(snap);

        mockMvc.perform(cookieAuth(path, initial).header("Referer", "/relative/path"))
                .andExpect(status().isForbidden());
        assertUnchanged(snap);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/refresh-token", "/api/v1/auth/logout"})
    void foreignOriginWinsOverAllowedRefererAndDoesNotMutate(String path) throws Exception {
        AuthResult initial = registerVerifiedAndLogin("origin-wins-" + UUID.randomUUID() + "@example.com");
        SessionSnapshot snap = snapshot(initial);

        mockMvc.perform(cookieAuth(path, initial)
                        .header("Origin", FOREIGN_ORIGIN)
                        .header("Referer", ALLOWED_REFERER))
                .andExpect(status().isForbidden());

        assertUnchanged(snap);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/refresh-token", "/api/v1/auth/logout"})
    void literalNullOriginPlusAllowedRefererRejectsWithoutMutating(String path) throws Exception {
        AuthResult initial = registerVerifiedAndLogin("null-origin-" + UUID.randomUUID() + "@example.com");
        SessionSnapshot snap = snapshot(initial);

        mockMvc.perform(cookieAuth(path, initial)
                        .header("Origin", "null")
                        .header("Referer", ALLOWED_REFERER))
                .andExpect(status().isForbidden());

        assertUnchanged(snap);
    }

    @Test
    void mobileHeaderWithBrowserOriginStaysOnCookieRefreshPath() throws Exception {
        AuthResult initial = registerVerifiedAndLogin("mobile-origin-" + UUID.randomUUID() + "@example.com");

        MvcResult result = mockMvc.perform(cookieAuth("/api/v1/auth/refresh-token", initial)
                        .header(MOBILE_HEADER, "mobile")
                        .header("Origin", ALLOWED_ORIGIN)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("parkio_refresh"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        String rotated = result.getResponse().getCookie("parkio_refresh").getValue();
        assertThat(rotated).isNotEqualTo(initial.refreshToken());
        assertThat(refreshTokens.findByTokenHash(hash(initial.refreshToken())))
                .get()
                .extracting(RefreshToken::revokedReason)
                .isEqualTo(RefreshTokenRevocationReason.ROTATED);
    }

    @Test
    void mobileHeaderWithBrowserRefererStaysOnCookieLogoutPath() throws Exception {
        AuthResult initial = registerVerifiedAndLogin("mobile-referer-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(cookieAuth("/api/v1/auth/logout", initial)
                        .header(MOBILE_HEADER, "mobile")
                        .header("Referer", ALLOWED_REFERER))
                .andExpect(status().isNoContent());

        RefreshToken token = refreshTokens.findByTokenHash(hash(initial.refreshToken())).orElseThrow();
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/refresh-token", "/api/v1/auth/logout"})
    void mobileHeaderWithOnlyCookieAndNoBodyTokenDoesNotAuthenticateOrMutate(String path) throws Exception {
        AuthResult initial = registerVerifiedAndLogin("mobile-cookie-only-" + UUID.randomUUID() + "@example.com");
        SessionSnapshot snap = snapshot(initial);

        mockMvc.perform(cookieAuth(path, initial)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        assertUnchanged(snap);
    }

    @Test
    void genuineMobileBodyTokenRefreshAndLogoutRemainSupported() throws Exception {
        String email = registerAndVerify("mobile-body-" + UUID.randomUUID() + "@example.com");
        JsonNode login = mobileLogin(email);
        String original = login.get("refreshToken").asText();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(refreshBody(original)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        String rotated = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(original);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .contentType("application/json")
                        .content(refreshBody(rotated)))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(refreshTokens.findByTokenHash(hash(rotated)).orElseThrow().isRevoked()).isTrue();
    }

    @Test
    void logoutAllRequiresValidBearerAndIgnoresRefreshCookie() throws Exception {
        AuthResult initial = registerVerifiedAndLogin("logout-all-" + UUID.randomUUID() + "@example.com");
        SessionSnapshot snap = snapshot(initial);

        mockMvc.perform(post("/api/v1/auth/logout-all")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", ALLOWED_ORIGIN)
                        .cookie(refreshCookie(initial)))
                .andExpect(status().isUnauthorized());
        assertUnchanged(snap);

        mockMvc.perform(post("/api/v1/auth/logout-all")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Authorization", "Bearer not-a-jwt")
                        .cookie(refreshCookie(initial)))
                .andExpect(status().isUnauthorized());
        assertUnchanged(snap);

        mockMvc.perform(post("/api/v1/auth/logout-all")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header(MOBILE_HEADER, "mobile")
                        .cookie(refreshCookie(initial)))
                .andExpect(status().isUnauthorized());
        assertUnchanged(snap);

        mockMvc.perform(post("/api/v1/auth/logout-all")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Authorization", "Bearer " + initial.accessToken())
                        .cookie(refreshCookie(initial)))
                .andExpect(status().isNoContent());

        assertThat(refreshTokens.findByTokenHash(snap.tokenHash()).orElseThrow().isRevoked()).isTrue();
        assertThat(authService.sessionEpoch(snap.userId())).isEqualTo(snap.epoch() + 1);
    }

    private MockHttpServletRequestBuilder cookieAuth(String path, AuthResult initial) {
        return post(path)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .cookie(refreshCookie(initial));
    }

    private static Cookie refreshCookie(AuthResult initial) {
        return new Cookie("parkio_refresh", initial.refreshToken());
    }

    private record SessionSnapshot(UUID userId, String tokenHash, long epoch, long activeCount) {
    }

    private SessionSnapshot snapshot(AuthResult initial) {
        UUID userId = initial.user().id();
        return new SessionSnapshot(
                userId,
                hash(initial.refreshToken()),
                authService.sessionEpoch(userId),
                refreshTokens.countActiveForUser(userId, Instant.now()));
    }

    private void assertUnchanged(SessionSnapshot snap) {
        RefreshToken token = refreshTokens.findByTokenHash(snap.tokenHash()).orElseThrow();
        assertThat(token.isRevoked()).as("refresh token must not be revoked").isFalse();
        assertThat(token.isReusedDetected()).as("reuse flag must not be set").isFalse();
        assertThat(token.revokedReason()).isNull();
        assertThat(refreshTokens.countActiveForUser(snap.userId(), Instant.now()))
                .as("active session count must not change")
                .isEqualTo(snap.activeCount());
        assertThat(authService.sessionEpoch(snap.userId()))
                .as("session epoch must not bump")
                .isEqualTo(snap.epoch());
    }

    private String hash(String rawRefreshToken) {
        return refreshTokenHasher.hash(rawRefreshToken);
    }

    private AuthResult registerVerifiedAndLogin(String email) {
        registerAndVerify(email);
        return authService.login(new LoginCommand(email, PASSWORD));
    }

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
        authService.register(new RegisterCommand(email, PASSWORD));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender, atLeastOnce()).sendVerificationLink(eq(email), tokenCaptor.capture(), any());
        authService.verifyEmail(new VerifyEmailCommand(tokenCaptor.getValue()));
        return email;
    }

    private String credentials(String email) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
    }

    private String refreshBody(String refreshToken) {
        return "{\"refreshToken\":\"%s\"}".formatted(refreshToken);
    }
}
