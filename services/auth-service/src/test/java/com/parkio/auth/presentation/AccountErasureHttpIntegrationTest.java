package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AccountErasureApplicationService;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.LoginFailureTracker;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.command.VerifyEmailCommand;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
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
