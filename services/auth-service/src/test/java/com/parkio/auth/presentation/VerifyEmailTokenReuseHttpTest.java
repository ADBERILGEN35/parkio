package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.EmailLocale;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * CL-F35: a verification link works once. The first use answers as before (200 with the
 * account), and afterwards the link answers like any invalid link, without account data,
 * also for accounts verified before tokens were cleared on use (they still store the hash).
 */
@SpringBootTest
@AutoConfigureMockMvc
class VerifyEmailTokenReuseHttpTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoleJpaRepository roles;

    @Autowired
    private AuthUserRepository authUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailVerificationSender emailVerificationSender;

    private final AtomicReference<String> verificationToken = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
        doAnswer(invocation -> {
            verificationToken.set(invocation.getArgument(1));
            return null;
        }).when(emailVerificationSender).sendVerificationLink(anyString(), anyString(), any(EmailLocale.class));
    }

    @Test
    void aVerificationLinkVerifiesOnceAndThenReturnsNoAccountData() throws Exception {
        String email = registeredEmail();
        String token = verificationToken.get();

        MockHttpServletResponse first = verify(token);

        assertThat(first.getStatus()).isEqualTo(200);
        JsonNode account = json(first);
        assertThat(account.path("email").asText()).isEqualTo(email);
        assertThat(account.path("status").asText()).isEqualTo("ACTIVE");
        AuthUser user = authUsers.findByEmail(email).orElseThrow();
        assertThat(user.emailVerificationTokenHash()).as("token hash after use").isNull();
        assertThat(user.emailVerificationExpiresAt()).as("token expiry after use").isNull();

        assertSpentLinkAnswer(verify(token), user);
    }

    @Test
    void aLinkStillStoredForAnAlreadyVerifiedAccountReturnsNoAccountData() throws Exception {
        String email = registeredEmail();
        String token = verificationToken.get();
        AuthUser user = markVerifiedKeepingTheToken(email, null);

        assertSpentLinkAnswer(verify(token), user);
    }

    @Test
    void anExpiredLinkOfAnAlreadyVerifiedAccountIsRejectedLikeAnyExpiredLink() throws Exception {
        String email = registeredEmail();
        String token = verificationToken.get();
        AuthUser user = markVerifiedKeepingTheToken(email, Instant.parse("2020-01-01T00:00:00Z"));

        assertSpentLinkAnswer(verify(token), user);
    }

    private void assertSpentLinkAnswer(MockHttpServletResponse response, AuthUser user) throws Exception {
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(json(response).path("code").asText()).isEqualTo("INVALID_VERIFICATION_TOKEN");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(user.email())
                .doesNotContain(user.id().toString());
    }

    /** The state of an account verified before verification cleared the token. */
    private AuthUser markVerifiedKeepingTheToken(String email, Instant tokenExpiresAt) {
        UUID id = authUsers.findByEmail(email).orElseThrow().id();
        jdbc.update("""
                UPDATE auth_users
                SET email_verified = TRUE, email_verified_at = CURRENT_TIMESTAMP, status = 'ACTIVE'
                WHERE id = ?
                """, id);
        if (tokenExpiresAt != null) {
            jdbc.update("UPDATE auth_users SET email_verification_expires_at = ? WHERE id = ?",
                    Timestamp.from(tokenExpiresAt), id);
        }
        AuthUser user = authUsers.findByEmail(email).orElseThrow();
        assertThat(user.emailVerified()).isTrue();
        assertThat(user.emailVerificationTokenHash()).isNotNull();
        return user;
    }

    private String registeredEmail() throws Exception {
        String email = "verify-reuse-" + UUID.randomUUID() + "@example.com";
        MockHttpServletResponse response = post("/api/v1/auth/register",
                Map.of("email", email, "password", "StrongerPass123", "locale", "en"));
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(verificationToken.get()).isNotBlank();
        return email;
    }

    private MockHttpServletResponse verify(String token) throws Exception {
        return post("/api/v1/auth/verify-email", Map.of("token", token));
    }

    private MockHttpServletResponse post(String path, Map<String, String> body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
}
