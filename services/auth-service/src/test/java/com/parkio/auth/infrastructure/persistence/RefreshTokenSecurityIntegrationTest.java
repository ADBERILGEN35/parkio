package com.parkio.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.RefreshTokenHasher;
import com.parkio.auth.application.port.RefreshTokenRepository;
import com.parkio.auth.application.result.AuthResult;
import com.parkio.auth.domain.RefreshToken;
import com.parkio.auth.domain.RefreshTokenRevocationReason;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.domain.exception.AuthErrorCode;
import com.parkio.auth.domain.exception.AuthException;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.mockito.ArgumentCaptor;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenSecurityIntegrationTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private AuthApplicationService authService;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    @Autowired
    private RefreshTokenHasher refreshTokenHasher;

    @Autowired
    private RoleJpaRepository roles;

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void loginSetsHttpOnlySecureSameSiteRefreshCookiesWithoutJsonRefreshToken() throws Exception {
        String email = "login-cookie-" + UUID.randomUUID() + "@example.com";
        registerAndVerify(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content("""
                                {"email":"%s","password":"StrongerPass123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("parkio_refresh"))
                .andExpect(cookie().httpOnly("parkio_refresh", true))
                .andExpect(cookie().secure("parkio_refresh", true))
                .andExpect(header().stringValues(
                        "Set-Cookie",
                        org.hamcrest.Matchers.hasItems(
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("parkio_refresh="),
                                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth/refresh-token"),
                                        org.hamcrest.Matchers.containsString("HttpOnly"),
                                        org.hamcrest.Matchers.containsString("Secure"),
                                        org.hamcrest.Matchers.containsString("SameSite=Strict")),
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("parkio_refresh="),
                                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth/logout"),
                                        org.hamcrest.Matchers.containsString("HttpOnly"),
                                        org.hamcrest.Matchers.containsString("Secure"),
                                        org.hamcrest.Matchers.containsString("SameSite=Strict")))))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void refreshReadsCookieRotatesAndSetsNewCookie() throws Exception {
        AuthResult initial = registerVerifiedAndLogin(
                "cookie-refresh-" + UUID.randomUUID() + "@example.com");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("parkio_refresh", initial.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("parkio_refresh"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        String rotatedCookie = result.getResponse().getCookie("parkio_refresh").getValue();
        assertThat(rotatedCookie).isNotEqualTo(initial.refreshToken());
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(initial.refreshToken())))
                .get()
                .extracting(RefreshToken::revokedReason)
                .isEqualTo(RefreshTokenRevocationReason.ROTATED);
        assertThat(refreshTokens.findByTokenHash(refreshTokenHasher.hash(rotatedCookie))).isPresent();
    }

    @Test
    void logoutClearsCookieAndRevokesCurrentRefreshToken() throws Exception {
        AuthResult initial = registerVerifiedAndLogin(
                "logout-cookie-" + UUID.randomUUID() + "@example.com");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("parkio_refresh", initial.refreshToken())))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(
                        "Set-Cookie",
                        org.hamcrest.Matchers.hasItems(
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("parkio_refresh="),
                                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth/refresh-token"),
                                        org.hamcrest.Matchers.containsString("Max-Age=0")),
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("parkio_refresh="),
                                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth/logout"),
                                        org.hamcrest.Matchers.containsString("Max-Age=0")))));

        RefreshToken token = refreshTokens.findByTokenHash(refreshTokenHasher.hash(initial.refreshToken()))
                .orElseThrow();
        assertThat(token.isRevoked()).isTrue();
        assertThat(token.revokedReason()).isEqualTo(RefreshTokenRevocationReason.LOGOUT);
    }

    @Test
    void missingRefreshCookieReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void crossSiteRefreshAndLogoutAreRejected() throws Exception {
        AuthResult initial = registerVerifiedAndLogin(
                "cross-site-" + UUID.randomUUID() + "@example.com");
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie("parkio_refresh", initial.refreshToken());

        mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "https://evil.example")
                        .cookie(cookie))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "https://evil.example")
                        .cookie(cookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void reusedRotatedTokenCommitsFamilyRevocationAndReturnsGenericError() throws Exception {
        AuthResult initial = registerVerifiedAndLogin("reuse-" + UUID.randomUUID() + "@example.com");
        AuthResult refreshed = authService.refresh(new RefreshTokenCommand(initial.refreshToken()));
        String initialHash = refreshTokenHasher.hash(initial.refreshToken());
        String childHash = refreshTokenHasher.hash(refreshed.refreshToken());

        String response = mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .cookie(new jakarta.servlet.http.Cookie("parkio_refresh", initial.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.message").value(AuthErrorCode.INVALID_REFRESH_TOKEN.defaultMessage()))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(response)
                .doesNotContain(initial.refreshToken())
                .doesNotContain(initialHash)
                .doesNotContain(childHash);

        RefreshToken reused = refreshTokens.findByTokenHash(initialHash).orElseThrow();
        RefreshToken child = refreshTokens.findByTokenHash(childHash).orElseThrow();
        assertThat(reused.isReusedDetected()).isTrue();
        assertThat(child.isRevoked()).isTrue();
        assertThat(child.revokedReason()).isEqualTo(RefreshTokenRevocationReason.REUSE_DETECTED);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenCommand(refreshed.refreshToken())))
                .isInstanceOf(AuthException.class)
                .extracting(error -> ((AuthException) error).errorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void staleRefreshTokenUpdateFailsOptimisticLocking() {
        RefreshToken saved = refreshTokens.save(RefreshToken.issueRoot(
                UUID.randomUUID(),
                "optimistic-" + UUID.randomUUID(),
                Instant.parse("2026-07-09T00:00:00Z")));
        RefreshToken firstReader = refreshTokens.findByTokenHash(saved.tokenHash()).orElseThrow();
        RefreshToken secondReader = refreshTokens.findByTokenHash(saved.tokenHash()).orElseThrow();
        Instant revokedAt = Instant.parse("2026-06-09T00:00:00Z");

        firstReader.revoke(RefreshTokenRevocationReason.ROTATED, revokedAt);
        refreshTokens.save(firstReader);
        secondReader.revoke(RefreshTokenRevocationReason.ROTATED, revokedAt);

        assertThatThrownBy(() -> refreshTokens.save(secondReader))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private AuthResult registerVerifiedAndLogin(String email) {
        registerAndVerify(email);
        return authService.login(new com.parkio.auth.application.command.LoginCommand(email, "StrongerPass123"));
    }

    private void registerAndVerify(String email) {
        clearInvocations(emailVerificationSender);
        authService.register(new RegisterCommand(email, "StrongerPass123"));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender, atLeastOnce()).sendVerificationLink(eq(email), tokenCaptor.capture());
        authService.verifyEmail(new VerifyEmailCommand(tokenCaptor.getValue()));
    }
}
