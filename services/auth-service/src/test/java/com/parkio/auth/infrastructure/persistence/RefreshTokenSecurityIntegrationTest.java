package com.parkio.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.AuthApplicationService;
import com.parkio.auth.application.command.RefreshTokenCommand;
import com.parkio.auth.application.command.RegisterCommand;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

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

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedUserRole() {
        if (roles.findByName(RoleName.USER).isEmpty()) {
            roles.save(new RoleEntity(UUID.randomUUID(), RoleName.USER));
        }
    }

    @Test
    void reusedRotatedTokenCommitsFamilyRevocationAndReturnsGenericError() throws Exception {
        AuthResult initial = authService.register(
                new RegisterCommand("reuse-" + UUID.randomUUID() + "@example.com", "password1"));
        AuthResult refreshed = authService.refresh(new RefreshTokenCommand(initial.refreshToken()));
        String initialHash = refreshTokenHasher.hash(initial.refreshToken());
        String childHash = refreshTokenHasher.hash(refreshed.refreshToken());

        String response = mockMvc.perform(post("/api/v1/auth/refresh-token")
                        .header("X-Gateway-Auth", GATEWAY_SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(Map.of("refreshToken", initial.refreshToken()))))
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
}
