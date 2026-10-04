package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.PasswordResetLimiter;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.application.port.PasswordHasher;
import com.parkio.auth.application.port.PasswordResetEmailSender;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.config.AuthRecoveryDispatchConfig;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import com.parkio.auth.infrastructure.security.JwtService;
import java.nio.charset.StandardCharsets;
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
 * CL-F36: BCrypt only uses the first 72 bytes of a password, and the encoder in use
 * rejects longer input with an exception (a 500). Passwords that set a credential must be
 * rejected with a 400 when their UTF-8 encoding exceeds 72 bytes, on register, reset and
 * change, while a 72-byte password (including multibyte characters) works.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordByteLengthHttpTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    /** 38 characters, 73 UTF-8 bytes ("ş" is two bytes): within the 100-character DTO limit. */
    private static final String TURKISH_73_BYTES = "Aa1" + "ş".repeat(35);
    /** 73 ASCII bytes. */
    private static final String ASCII_73_BYTES = "Aa1" + "x".repeat(70);
    /** 37 characters, exactly 72 UTF-8 bytes. */
    private static final String TURKISH_72_BYTES = "Aa1" + "ş".repeat(34) + "x";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoleJpaRepository roles;

    @Autowired
    private AuthUserRepository authUsers;

    @Autowired
    private PasswordHasher passwordHasher;

    @Autowired
    private JwtService jwtService;

    /** Forgot-password sends its e-mail here after the response (CL-F14.2, #197). */
    @Autowired
    @Qualifier(AuthRecoveryDispatchConfig.EXECUTOR)
    private ThreadPoolTaskExecutor recoveryDispatch;

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

    /** No recovery work may still be running when the next test resets the shared mocks. */
    @AfterEach
    void drainRecoveryDispatch() throws InterruptedException {
        awaitRecoveryDispatch();
    }

    @Test
    void fixtureByteLengths() {
        assertThat(TURKISH_73_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThat(ASCII_73_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThat(TURKISH_72_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(72);
    }

    @Test
    void registerRejectsPasswordsOverSeventyTwoBytes() throws Exception {
        for (String password : new String[] {TURKISH_73_BYTES, ASCII_73_BYTES}) {
            MockHttpServletResponse response = post("/api/v1/auth/register", null,
                    Map.of("email", email(), "password", password, "locale", "en"));

            assertThat(response.getStatus()).as(password).isEqualTo(400);
            assertThat(code(response)).as(password).isEqualTo("PASSWORD_TOO_LONG");
        }
    }

    @Test
    void registerAcceptsAMultibyteSeventyTwoBytePasswordThatThenVerifies() throws Exception {
        String email = email();
        MockHttpServletResponse response = post("/api/v1/auth/register", null,
                Map.of("email", email, "password", TURKISH_72_BYTES, "locale", "en"));

        assertThat(response.getStatus()).isEqualTo(201);
        AuthUser user = authUsers.findByEmail(email).orElseThrow();
        assertThat(passwordHasher.matches(TURKISH_72_BYTES, user.passwordHash())).isTrue();
    }

    @Test
    void resetRejectsANewPasswordOverSeventyTwoBytesAndKeepsTheToken() throws Exception {
        String email = verifiedUser();
        assertThat(post("/api/v1/auth/forgot-password", null, Map.of("email", email)).getStatus()).isEqualTo(200);
        // The reset link is sent after the response, so the token exists only once the dispatch ran.
        String token = awaitSent(resetToken);
        assertThat(token).isNotBlank();

        MockHttpServletResponse tooLong = post("/api/v1/auth/reset-password", null,
                Map.of("token", token, "newPassword", TURKISH_73_BYTES));
        assertThat(tooLong.getStatus()).isEqualTo(400);
        assertThat(code(tooLong)).isEqualTo("PASSWORD_TOO_LONG");

        MockHttpServletResponse valid = post("/api/v1/auth/reset-password", null,
                Map.of("token", token, "newPassword", TURKISH_72_BYTES));
        assertThat(valid.getStatus()).isBetween(200, 204);
    }

    @Test
    void changeRejectsANewPasswordOverSeventyTwoBytes() throws Exception {
        String email = verifiedUser();
        AuthUser user = authUsers.findByEmail(email).orElseThrow();
        String accessToken = jwtService.issue(user).token();

        MockHttpServletResponse response = post("/api/v1/auth/change-password", accessToken,
                Map.of("currentPassword", "StrongerPass123", "newPassword", ASCII_73_BYTES));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(code(response)).isEqualTo("PASSWORD_TOO_LONG");
    }

    /**
     * Waits for the mocked sender to capture a token. An executor that looks idle is not enough here:
     * a task handed to a newly started worker is neither queued nor counted active until it runs.
     */
    private static String awaitSent(AtomicReference<String> token) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (token.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return token.get();
    }

    /** Waits until the recovery executor is idle, as PublicEmailDeliveryEnumerationHttpTest does. */
    private void awaitRecoveryDispatch() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while ((recoveryDispatch.getActiveCount() > 0 || recoveryDispatch.getQueueSize() > 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    /** Registration sends the verification link in the request itself, so its token is set on return. */
    private String verifiedUser() throws Exception {
        String email = email();
        assertThat(post("/api/v1/auth/register", null,
                Map.of("email", email, "password", "StrongerPass123", "locale", "en")).getStatus()).isEqualTo(201);
        assertThat(post("/api/v1/auth/verify-email", null, Map.of("token", verificationToken.get())).getStatus())
                .isEqualTo(200);
        return email;
    }

    private MockHttpServletResponse post(String path, String accessToken, Map<String, String> body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asText();
    }

    private static String email() {
        return "password-bytes-" + UUID.randomUUID() + "@example.com";
    }
}
