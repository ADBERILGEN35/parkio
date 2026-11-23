package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "parkio.privacy.account-erasure.enabled=true")
class AccountErasureHttpIntegrationTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String PASSWORD = "StrongerPass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthApplicationService authApplicationService;

    @Autowired
    private AccountErasureApplicationService erasure;

    @Autowired
    private AuthUserRepository users;

    @Autowired
    private RoleJpaRepository roles;

    @MockitoBean
    private LoginFailureTracker loginFailureTracker;

    @MockitoBean
    private EmailVerificationSender emailVerificationSender;

    @BeforeEach
    void seedUserRole() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
    }

    @Test
    void anonymousDeleteIsDenied() throws Exception {
        mockMvc.perform(delete("/api/v1/account")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType("application/json")
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalAckWithoutGatewaySecretIsDenied() throws Exception {
        mockMvc.perform(post("/internal/erasure/acks")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgedUserHeaderCannotEraseAnotherAccount() throws Exception {
        String victimEmail = registerAndVerify("victim-" + UUID.randomUUID() + "@example.com");
        String attackerEmail = registerAndVerify("attacker-" + UUID.randomUUID() + "@example.com");
        String attackerToken = accessToken(attackerEmail);

        mockMvc.perform(delete("/api/v1/account")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Authorization", "Bearer " + attackerToken)
                        .header("X-User-Id", "00000000-0000-4000-8000-000000000099")
                        .contentType("application/json")
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(credentials(victimEmail)))
                .andExpect(status().isOk());
    }

    /** CL-F15 v2: the erasure request removes the account's login-throttle state (the Spring-wired hook). */
    @Test
    void deletionForgetsTheLoginThrottleStateOfTheAccount() throws Exception {
        String email = registerAndVerify("erase-throttle-" + UUID.randomUUID() + "@example.com");
        String access = accessToken(email);

        mockMvc.perform(delete("/api/v1/account")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Authorization", "Bearer " + access)
                        .contentType("application/json")
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(loginFailureTracker).forgetAccount(email);
    }

    /** Best effort: a failing throttle store does not fail or change the erasure request. */
    @Test
    void aFailingThrottleStoreDoesNotFailTheDeletion() throws Exception {
        String email = registerAndVerify("erase-throttle-down-" + UUID.randomUUID() + "@example.com");
        String access = accessToken(email);
        org.mockito.Mockito.doThrow(new IllegalStateException("throttle store unavailable"))
                .when(loginFailureTracker).forgetAccount(org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(delete("/api/v1/account")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Authorization", "Bearer " + access)
                        .contentType("application/json")
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    /** #563: the deletion request checks the password on the throttle of the gateway-resolved client. */
    @Test
    void deletionAdmitsTheGatewayResolvedClient() throws Exception {
        String email = registerAndVerify("erase-client-" + UUID.randomUUID() + "@example.com");
        String access = accessToken(email);
        clearInvocations(loginFailureTracker);

        MockHttpServletResponse response = deleteAccount(access, "2001:DB8:5:6::7", PASSWORD);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(loginFailureTracker).admit(eq(email), eq("2001:db8:5:6:0:0:0:0/64"), any(Instant.class));
        verify(loginFailureTracker, never()).recordFailure(anyString(), anyString(), any(Instant.class));
        verify(loginFailureTracker, never()).clearAfterSuccess(anyString(), anyString());
        verify(loginFailureTracker).forgetAccount(email);
    }

    /**
     * #563: a deletion request that the throttle refuses is answered exactly like a wrong password, as on login:
     * same status and body, no Retry-After; the refused attempt is not counted and starts no erasure.
     */
    @Test
    void aThrottledDeletionIsAnsweredLikeAWrongPassword() throws Exception {
        String email = registerAndVerify("erase-throttled-" + UUID.randomUUID() + "@example.com");
        String access = accessToken(email);
        when(loginFailureTracker.recordFailure(eq(email), eq("198.51.100.23"), any(Instant.class)))
                .thenReturn(new LoginFailureTracker.LoginFailureOutcome(1, 1, Duration.ZERO, null));
        when(loginFailureTracker.admit(eq(email), eq("198.51.100.24"), any(Instant.class)))
                .thenReturn(Duration.ofSeconds(30));

        MockHttpServletResponse wrong = deleteAccount(access, "198.51.100.23", "wrong-password");
        MockHttpServletResponse throttled = deleteAccount(access, "198.51.100.24", PASSWORD);

        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(throttled.getStatus()).isEqualTo(401);
        assertThat(wrong.getHeader("Retry-After")).isNull();
        assertThat(throttled.getHeader("Retry-After")).isNull();
        JsonNode wrongBody = errorWithoutRequestFields(wrong);
        assertThat(wrongBody.path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(errorWithoutRequestFields(throttled)).isEqualTo(wrongBody);
        verify(loginFailureTracker).recordFailure(eq(email), eq("198.51.100.23"), any(Instant.class));
        verify(loginFailureTracker, never()).recordFailure(eq(email), eq("198.51.100.24"), any(Instant.class));
        verify(loginFailureTracker, never()).forgetAccount(anyString());
        assertThat(users.findByEmail(email).orElseThrow().status()).isEqualTo(AuthUserStatus.ACTIVE);
    }

    @Test
    void deletionLocksLoginUntilAllParticipantsAckThenCompletes() throws Exception {
        String email = registerAndVerify("erase-" + UUID.randomUUID() + "@example.com");
        String access = accessToken(email);

        String body = mockMvc.perform(delete("/api/v1/account")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Authorization", "Bearer " + access)
                        .contentType("application/json")
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode deletion = objectMapper.readTree(body);
        UUID requestId = UUID.fromString(deletion.get("erasureRequestId").asText());

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(credentials(email)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_NOT_ACTIVE"));

        assertThat(erasure.status(users.findByEmail(email).orElseThrow().id()).status())
                .isEqualTo("IN_PROGRESS");
    }

    private String accessToken(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Origin", "http://localhost:5173")
                        .contentType("application/json")
                        .content(credentials(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private MockHttpServletResponse deleteAccount(String accessToken, String clientIp, String password)
            throws Exception {
        return mockMvc.perform(delete("/api/v1/account")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Parkio-Client-Ip", clientIp)
                        .contentType("application/json")
                        .content("{\"password\":\"" + password + "\"}"))
                .andReturn()
                .getResponse();
    }

    /** The error body without its per-request fields (trace id, timestamp). */
    private JsonNode errorWithoutRequestFields(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getContentAsString());
        body.remove("traceId");
        body.remove("timestamp");
        return body;
    }

    private String registerAndVerify(String email) {
        clearInvocations(emailVerificationSender);
        authApplicationService.register(new RegisterCommand(email, PASSWORD));
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailVerificationSender, atLeastOnce()).sendVerificationLink(eq(email), tokenCaptor.capture(), any());
        authApplicationService.verifyEmail(new VerifyEmailCommand(tokenCaptor.getValue()));
        return email;
    }

    private String credentials(String email) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
    }
}
