package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.PasswordResetLimiter;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.config.AuthRecoveryDispatchConfig;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CL-F15 v2 wiring over HTTP: the gateway-resolved client reaches login admission and the password reset
 * (where it becomes a known client), keyed by IPv6 /64; without the header the shared unknown client is used.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginThrottleClientKeyHttpTest {

    private static final String GATEWAY_SECRET = "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String PASSWORD = "StrongerPass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoleJpaRepository roles;

    @Autowired
    @Qualifier(AuthRecoveryDispatchConfig.EXECUTOR)
    private ThreadPoolTaskExecutor recoveryDispatch;

    @MockitoBean
    private LoginFailureTracker loginFailureTracker;

    @MockitoBean
    private EmailVerificationSender emailVerificationSender;

    @MockitoBean
    private PasswordResetEmailSender passwordResetEmailSender;

    @MockitoBean
    private PasswordResetLimiter passwordResetLimiter;

    private final AtomicReference<String> verificationToken = new AtomicReference<>();
    private final AtomicReference<String> resetToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
        when(passwordResetLimiter.tryAcquire(anyString())).thenReturn(true);
        doAnswer(invocation -> {
            verificationToken.set(invocation.getArgument(1));
            return null;
        }).when(emailVerificationSender).sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));
        doAnswer(invocation -> {
            resetToken.set(invocation.getArgument(1));
            return null;
        }).when(passwordResetEmailSender).sendResetLink(anyString(), anyString(), any(EmailLocale.class));
    }

    @AfterEach
    void drainRecoveryDispatch() throws InterruptedException {
        awaitRecoveryDispatch();
    }

    @Test
    void loginAdmissionAndSuccessUseTheGatewayResolvedClient() throws Exception {
        String email = verifiedUser();

        MockHttpServletResponse response = post("/api/v1/auth/login", "2001:DB8:5:6::7",
                Map.of("email", email, "password", PASSWORD));

        assertThat(response.getStatus()).isEqualTo(200);
        verify(loginFailureTracker).admit(eq(email), eq("2001:db8:5:6:0:0:0:0/64"), any(Instant.class));
        verify(loginFailureTracker).clearAfterSuccess(email, "2001:db8:5:6:0:0:0:0/64");
    }

    @Test
    void aCompletedResetMakesTheResettingClientKnown() throws Exception {
        String email = verifiedUser();
        String token = requestReset(email);

        MockHttpServletResponse response = post("/api/v1/auth/reset-password", "2001:db8:5:6::7",
                Map.of("token", token, "newPassword", "FreshStrong123"));

        assertThat(response.getStatus()).isBetween(200, 204);
        verify(loginFailureTracker).clearAfterPasswordReset(email, "2001:db8:5:6:0:0:0:0/64");
    }

    @Test
    void aResetWithoutTheClientHeaderUsesTheSharedUnknownClient() throws Exception {
        String email = verifiedUser();
        String token = requestReset(email);

        MockHttpServletResponse response = post("/api/v1/auth/reset-password", null,
                Map.of("token", token, "newPassword", "FreshStrong123"));

        assertThat(response.getStatus()).isBetween(200, 204);
        verify(loginFailureTracker).clearAfterPasswordReset(email, LoginFailureTracker.UNKNOWN_CLIENT);
    }

    /** CL-F15 v3: a successful refresh keeps the gateway-resolved client known; a failed refresh touches nothing. */
    @Test
    void aSuccessfulRefreshKeepsTheClientKnownAndAFailedRefreshDoesNot() throws Exception {
        String email = verifiedUser();
        MockHttpServletResponse login = postMobile("/api/v1/auth/login", "2001:db8:5:6::7",
                Map.of("email", email, "password", PASSWORD));
        assertThat(login.getStatus()).isEqualTo(200);
        String refreshToken = objectMapper.readTree(login.getContentAsString()).get("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();

        MockHttpServletResponse refreshed = postMobile("/api/v1/auth/refresh-token", "2001:db8:5:6::9",
                Map.of("refreshToken", refreshToken));
        assertThat(refreshed.getStatus()).isEqualTo(200);
        verify(loginFailureTracker).refreshKnownClient(email, "2001:db8:5:6:0:0:0:0/64");

        MockHttpServletResponse reused = postMobile("/api/v1/auth/refresh-token", "2001:db8:7:8::1",
                Map.of("refreshToken", refreshToken));
        assertThat(reused.getStatus()).isEqualTo(401);
        MockHttpServletResponse garbage = postMobile("/api/v1/auth/refresh-token", "2001:db8:7:8::1",
                Map.of("refreshToken", "not-a-refresh-token"));
        assertThat(garbage.getStatus()).isEqualTo(401);
        verify(loginFailureTracker, never()).refreshKnownClient(eq(email), eq("2001:db8:7:8:0:0:0:0/64"));
        verify(loginFailureTracker, times(1)).refreshKnownClient(anyString(), anyString());
    }

    /** Native-client transport: the refresh token travels in the body, no cookie and no Origin. */
    private MockHttpServletResponse postMobile(String path, String clientIp, Map<String, String> body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("X-Parkio-Client", "mobile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (clientIp != null) {
            request.header("X-Parkio-Client-Ip", clientIp);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String requestReset(String email) throws Exception {
        assertThat(post("/api/v1/auth/forgot-password", null, Map.of("email", email)).getStatus()).isEqualTo(200);
        String token = awaitSent(resetToken);
        awaitRecoveryDispatch();
        assertThat(token).isNotBlank();
        return token;
    }

    private String verifiedUser() throws Exception {
        String email = "throttle-wiring-" + UUID.randomUUID() + "@example.com";
        assertThat(post("/api/v1/auth/register", null,
                Map.of("email", email, "password", PASSWORD, "locale", "en")).getStatus()).isEqualTo(201);
        assertThat(post("/api/v1/auth/verify-email", null, Map.of("token", verificationToken.get())).getStatus())
                .isEqualTo(200);
        return email;
    }

    private MockHttpServletResponse post(String path, String clientIp, Map<String, String> body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (clientIp != null) {
            request.header("X-Parkio-Client-Ip", clientIp);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private static String awaitSent(AtomicReference<String> token) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (token.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return token.get();
    }

    private void awaitRecoveryDispatch() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while ((recoveryDispatch.getActiveCount() > 0 || recoveryDispatch.getQueueSize() > 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}
