package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.command.RegisterCommand;
import com.parkio.auth.application.port.AuthUserRepository;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.domain.AuthUser;
import com.parkio.auth.domain.AuthUserStatus;
import com.parkio.auth.domain.Role;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import com.parkio.auth.infrastructure.security.JwtService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CL-F16: auth-service admin endpoints take the caller's identity and roles from the
 * verified access token, not from the {@code X-User-Id}/{@code X-User-Roles} headers.
 * Every internal service holds the shared gateway secret, so with any valid user token
 * it could otherwise claim an admin role or impersonate another actor in the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminJwtAuthorityHttpTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String PASSWORD = "StrongerPass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuthApplicationService authService;

    @Autowired
    private AuthUserRepository authUsers;

    @Autowired
    private RoleJpaRepository roles;

    @MockitoBean
    private EmailVerificationSender emailVerificationSender;

    @BeforeEach
    void seedRoles() {
        for (RoleName name : RoleName.values()) {
            if (roles.findByName(name).isEmpty()) {
                roles.save(new RoleEntity(UUID.randomUUID(), name));
            }
        }
    }

    @Test
    void userTokenWithForgedSuperAdminHeaderCannotGrantItselfAdmin() throws Exception {
        UUID attacker = registeredUser();
        String userToken = token(attacker, RoleName.USER);

        MockHttpServletResponse response = mockMvc.perform(withHeaders(post("/api/v1/admin/users/{id}/roles", attacker),
                                userToken, attacker, "SUPER_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"action\":\"GRANT\",\"reason\":\"forged header\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("FORBIDDEN");
        assertThat(authUsers.findById(attacker).orElseThrow().roles())
                .extracting(Role::name)
                .containsExactly(RoleName.USER);
    }

    @Test
    void userTokenWithForgedAdminHeaderCannotReadAdminData() throws Exception {
        UUID user = UUID.randomUUID();

        MockHttpServletResponse response = mockMvc.perform(withHeaders(get("/api/v1/admin/dashboard"),
                        token(user, RoleName.USER), user, "ADMIN"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("FORBIDDEN");
    }

    @Test
    void adminTokenIsAllowedWithOrWithoutTheGatewayIdentityHeaders() throws Exception {
        UUID admin = UUID.randomUUID();
        String adminToken = token(admin, RoleName.ADMIN);

        assertThat(mockMvc.perform(withHeaders(get("/api/v1/admin/dashboard"), adminToken, admin, "ADMIN"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mockMvc.perform(get("/api/v1/admin/dashboard")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void auditRecordsTheTokenSubjectNotAForgedActorHeader() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID impersonated = UUID.randomUUID();
        UUID target = registeredUser();

        MockHttpServletResponse revoke = mockMvc.perform(withHeaders(
                                post("/api/v1/admin/users/{id}/revoke-sessions", target),
                                token(admin, RoleName.ADMIN), impersonated, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"audit actor check\"}"))
                .andReturn().getResponse();
        assertThat(revoke.getStatus()).isEqualTo(204);

        MockHttpServletResponse audit = mockMvc.perform(withHeaders(
                        get("/api/v1/admin/audit-events").param("targetResourceId", target.toString()),
                        token(admin, RoleName.ADMIN), admin, "ADMIN"))
                .andReturn().getResponse();
        JsonNode events = objectMapper.readTree(audit.getContentAsString()).path("content");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("actionType").asText()).isEqualTo("ADMIN_USER_SESSIONS_REVOKED");
        assertThat(events.get(0).path("actorUserId").asText()).isEqualTo(admin.toString());
    }

    @Test
    void forgedHeadersWithoutAnyTokenAreStillUnauthenticated() throws Exception {
        UUID someone = UUID.randomUUID();

        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/admin/dashboard")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .header("X-User-Id", someone.toString())
                        .header("X-User-Roles", "SUPER_ADMIN"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder request,
                                                      String accessToken, UUID headerUserId, String headerRoles) {
        return request
                .header("X-Gateway-Auth", GATEWAY_SECRET)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-User-Id", headerUserId.toString())
                .header("X-User-Roles", headerRoles);
    }

    private UUID registeredUser() {
        String email = "admin-jwt-" + UUID.randomUUID() + "@example.com";
        return authService.register(new RegisterCommand(email, PASSWORD)).user().id();
    }

    private String token(UUID userId, RoleName role) {
        Instant now = Instant.now();
        AuthUser user = new AuthUser(userId, "synthetic-" + userId + "@example.com", "synthetic-hash",
                AuthUserStatus.ACTIVE, null, Set.of(new Role(UUID.randomUUID(), role)), now, null);
        return jwtService.issue(user).token();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString()).path("code").asText();
    }
}
