package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.PasswordResetLimiter;
import com.parkio.auth.application.VerificationResendLimiter;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.notification.EmailDeliveryException;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import com.parkio.auth.application.AuthApplicationService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Public resend-verification / forgot-password must not reveal account existence
 * when the email provider fails. Register / admin paths may still surface 503.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicEmailDeliveryEnumerationHttpTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String PASSWORD = "StrongerPass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthApplicationService authService;

    @Autowired
    private RoleJpaRepository roles;

    @MockBean
    private LoginFailureTracker loginFailureTracker;

    @MockBean
    private VerificationResendLimiter verificationResendLimiter;

    @MockBean
    private PasswordResetLimiter passwordResetLimiter;

    @MockBean
    private EmailVerificationSender emailVerificationSender;

    @MockBean
    private PasswordResetEmailSender passwordResetEmailSender;

    private final AtomicReference<String> lastVerificationToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
        when(verificationResendLimiter.tryAcquire(anyString())).thenReturn(true);
        when(passwordResetLimiter.tryAcquire(anyString())).thenReturn(true);
        lastVerificationToken.set(null);
        org.mockito.Mockito.reset(emailVerificationSender, passwordResetEmailSender);
        org.mockito.Mockito.doAnswer(invocation -> {
            lastVerificationToken.set(invocation.getArgument(1));
            return null;
        }).when(emailVerificationSender).sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));
    }

    @Test
    void resendVerificationReturnsAcceptedForEligibleUnknownAndIneligibleWhenProviderFails() throws Exception {
        String pending = "pending-enum-" + UUID.randomUUID() + "@example.com";
        String verified = "verified-enum-" + UUID.randomUUID() + "@example.com";
        String unknown = "unknown-enum-" + UUID.randomUUID() + "@example.com";

        authService.register(new RegisterCommand(pending, PASSWORD));
        registerAndVerify(verified);

        doThrow(new EmailDeliveryException("provider 429 (rate_limited)", null))
                .when(emailVerificationSender)
                .sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));

        assertUniformResend(pending);
        assertUniformResend(verified);
        assertUniformResend(unknown);
    }

    @Test
    void forgotPasswordReturnsOkForEligibleUnknownAndIneligibleWhenProviderFails() throws Exception {
        String pending = "pending-reset-enum-" + UUID.randomUUID() + "@example.com";
        String verified = "verified-reset-enum-" + UUID.randomUUID() + "@example.com";
        String unknown = "unknown-reset-enum-" + UUID.randomUUID() + "@example.com";

        authService.register(new RegisterCommand(pending, PASSWORD));
        registerAndVerify(verified);

        doThrow(new EmailDeliveryException("provider 503 (provider_5xx)", null))
                .when(passwordResetEmailSender)
                .sendResetLink(anyString(), anyString(), any(EmailLocale.class));

        assertUniformForgot(pending);
        assertUniformForgot(verified);
        assertUniformForgot(unknown);
    }

    @Test
    void registerStillSurfacesDeliveryFailureAsServiceUnavailable() throws Exception {
        doThrow(new EmailDeliveryException("provider 401 (auth)", null))
                .when(emailVerificationSender)
                .sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"register-fail-%s@example.com","password":"%s","locale":"en"}
                                """.formatted(UUID.randomUUID(), PASSWORD)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("EMAIL_DELIVERY_UNAVAILABLE"));
    }

    private void assertUniformResend(String email) throws Exception {
        ResultActions actions = mockMvc.perform(post("/api/v1/auth/resend-verification")
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","locale":"en"}
                        """.formatted(email)));
        actions.andExpect(status().isAccepted());
        assertThat(actions.andReturn().getResponse().getContentAsString()).isEmpty();
    }

    private void assertUniformForgot(String email) throws Exception {
        ResultActions actions = mockMvc.perform(post("/api/v1/auth/forgot-password")
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","locale":"en"}
                        """.formatted(email)));
        actions.andExpect(status().isOk());
        assertThat(actions.andReturn().getResponse().getContentAsString()).isEmpty();
    }

    private void registerAndVerify(String email) {
        authService.register(new RegisterCommand(email, PASSWORD));
        String token = lastVerificationToken.get();
        assertThat(token).isNotBlank();
        authService.verifyEmail(new VerifyEmailCommand(token));
        org.mockito.Mockito.clearInvocations(emailVerificationSender);
        lastVerificationToken.set(null);
        org.mockito.Mockito.doAnswer(invocation -> {
            lastVerificationToken.set(invocation.getArgument(1));
            return null;
        }).when(emailVerificationSender).sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));
    }
}
