package com.parkio.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.RegistrationInviteService;
import com.parkio.auth.application.port.EmailVerificationSender;
import com.parkio.auth.domain.RoleName;
import com.parkio.auth.infrastructure.persistence.entity.RoleEntity;
import com.parkio.auth.infrastructure.persistence.jpa.RoleJpaRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CL-F14.1: in INVITE mode a registration must not reveal whether an e-mail is
 * registered unless the caller holds a valid invite. The invite is checked before
 * the e-mail existence check, and an invite is not used up by a registration that
 * is rejected afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "parkio.registration.mode=invite")
class InviteRegistrationEnumerationHttpTest {

    private static final String GATEWAY_SECRET =
            "test-only-parkio-gateway-internal-secret-0123456789";
    private static final String PASSWORD = "StrongerPass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RegistrationInviteService invites;

    @Autowired
    private RoleJpaRepository roles;

    @MockitoBean
    private EmailVerificationSender emailVerificationSender;

    @BeforeEach
    void seedUserRole() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
    }

    @Test
    void invalidInviteGetsTheSameAnswerForRegisteredAndUnknownEmails() throws Exception {
        String registered = email("registered");
        assertThat(register(registered, invites.createInvite("test")).getStatus()).isEqualTo(201);

        for (String invalidInvite : new String[] {"not-a-real-invite-" + UUID.randomUUID(), " "}) {
            MockHttpServletResponse forRegistered = register(registered, invalidInvite);
            MockHttpServletResponse forUnknown = register(email("unknown"), invalidInvite);

            assertThat(forRegistered.getStatus()).isEqualTo(forUnknown.getStatus());
            assertThat(code(forRegistered)).isEqualTo(code(forUnknown));
            assertThat(code(forRegistered)).isIn("REGISTRATION_INVITE_INVALID", "REGISTRATION_INVITE_REQUIRED");
            assertThat(forRegistered.getStatus()).isEqualTo(403);
        }
    }

    @Test
    void validInviteRejectedForARegisteredEmailStaysUsable() throws Exception {
        String registered = email("registered");
        assertThat(register(registered, invites.createInvite("test")).getStatus()).isEqualTo(201);
        String invite = invites.createInvite("test");

        MockHttpServletResponse duplicate = register(registered, invite);
        assertThat(duplicate.getStatus()).isEqualTo(409);
        assertThat(code(duplicate)).isEqualTo("EMAIL_ALREADY_EXISTS");

        // The rejected registration did not use the invite up ...
        assertThat(register(email("first-use"), invite).getStatus()).isEqualTo(201);
        // ... and it is still single-use.
        MockHttpServletResponse reuse = register(email("second-use"), invite);
        assertThat(reuse.getStatus()).isEqualTo(403);
        assertThat(code(reuse)).isEqualTo("REGISTRATION_INVITE_INVALID");
    }

    private MockHttpServletResponse register(String email, String inviteToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", PASSWORD,
                                "locale", "en",
                                "inviteToken", inviteToken))))
                .andReturn()
                .getResponse();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString()).path("code").asText();
    }

    private static String email(String label) {
        return "invite-" + label + "-" + UUID.randomUUID() + "@example.com";
    }
}
